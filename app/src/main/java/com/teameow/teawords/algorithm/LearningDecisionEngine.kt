package com.teameow.teawords.algorithm

import kotlin.math.abs

/** What the system decided to do with one knowledge unit, and why. */
enum class StudyAction {
    /** Ask the user to judge a word quickly so unknown words can be found fast. */
    SCREEN,
    /** A short test that mainly improves the ability estimate / resolves uncertainty. */
    DIAGNOSE,
    /** Teach a word that is probably not yet known. */
    LEARN,
    /** Review something learned before, scheduled by the memory model. */
    REVIEW,
    /** Spot-check something the user claimed to know, to keep self-reports honest. */
    VALIDATE
}

/** A knowledge unit the engine can act on: one word, one sense, one practice mode. */
data class StudyUnit(
    val wordId: Long,
    val word: String,
    val senseId: Long,
    val senseLabel: String,
    val mode: PracticeMode,
    val difficulty: Double,
    val importance: Double,
    val belief: KnowledgeBelief,
    val memory: MemoryState,
    /** Probability the unit is already known, from the model (never a test result). */
    val predictedMastery: Double,
    val selfReportedKnown: Boolean,
    val dueAtMillis: Long
)

/** How aggressively to trade coverage against time. */
enum class LearningStrategy(val label: String) {
    /** Fewer tests and reviews; skip anything reasonably likely to be known. */
    EFFICIENCY("效率优先"),
    BALANCED("均衡"),
    /** More verification and review so fewer gaps are missed. */
    COVERAGE("覆盖优先");

    val knownSkipThreshold: Double
        get() = when (this) {
            EFFICIENCY -> 0.75
            BALANCED -> 0.85
            COVERAGE -> 0.93
        }

    val uncertainLower: Double
        get() = when (this) {
            EFFICIENCY -> 0.35
            BALANCED -> 0.30
            COVERAGE -> 0.25
        }

    val targetRetention: Double
        get() = when (this) {
            EFFICIENCY -> 0.85
            BALANCED -> 0.90
            COVERAGE -> 0.94
        }

    /** Daily caps; 0 means no cap. */
    val dailyReviewCap: Int
        get() = when (this) {
            EFFICIENCY -> 40
            BALANCED -> 80
            COVERAGE -> 150
        }

    val dailyNewCap: Int
        get() = when (this) {
            EFFICIENCY -> 10
            BALANCED -> 20
            COVERAGE -> 40
        }
}

/** One recommended action with the reason that must be shown to the user. */
data class StudyDecision(
    val unit: StudyUnit,
    val action: StudyAction,
    val score: Double,
    val reason: String
)

data class DecisionInput(
    val units: List<StudyUnit>,
    val strategy: LearningStrategy,
    val now: Long,
    /** Reviews already done today, for the daily caps. */
    val reviewsDoneToday: Int = 0,
    val newLearnedToday: Int = 0,
    val limit: Int = 20
)

/**
 * The single place that decides what to do next.
 *
 * It replaces "put everything in one time-ordered queue". Each unit is routed by the *kind* of
 * uncertainty it carries:
 *
 * * never judged  -> SCREEN (cheapest way to find gaps)
 * * judged unknown/fuzzy -> LEARN, or DIAGNOSE when the model is still unsure
 * * learned and due -> REVIEW, scheduled by [MemoryState]
 * * self-reported known -> skip when predicted mastery is high; otherwise VALIDATE occasionally
 *
 * The two value scales are kept apart on purpose, as the spec requires: diagnostic value decides
 * what is worth *measuring*, learning value decides what is worth *teaching*.
 */
object LearningDecisionEngine {
    /** Value of teaching a unit: how much is missing times how much the word matters. */
    fun learningValue(unit: StudyUnit): Double {
        val gap = (1.0 - unit.predictedMastery).coerceIn(0.0, 1.0)
        return gap * unit.importance
    }

    /** Value of testing a unit: uncertainty times information, damped by already-verified units. */
    fun diagnosticValue(unit: StudyUnit): Double {
        val uncertainty = 1.0 - unit.belief.confidence
        val info = AbilityEstimator.information(0.0, unit.difficulty)
        val verifiedPenalty = if (unit.belief.verifiedByTest) 0.15 else 1.0
        return uncertainty * (0.5 + info) * verifiedPenalty
    }

    /** Value of reviewing now: how sure we are that it has been forgotten. */
    fun reviewValue(unit: StudyUnit, now: Long): Double {
        val retrievability = unit.memory.retrievability(now) ?: return 0.0
        val overdue = if (unit.dueAtMillis in 1..now) 1.0 else 0.0
        return ((1.0 - retrievability).coerceIn(0.0, 1.0)) * unit.importance * (0.5 + 0.5 * overdue)
    }

    fun decide(input: DecisionInput): List<StudyDecision> {
        val strategy = input.strategy
        val pending = mutableListOf<StudyDecision>()
        val reviewBudget = budget(strategy.dailyReviewCap, input.reviewsDoneToday, input.limit)
        val learnBudget = budget(strategy.dailyNewCap, input.newLearnedToday, input.limit)
        /** First confidently-known unit, used for the optional spot check when nothing else is left. */
        var sampleCandidate: StudyUnit? = null

        for (unit in input.units) {
            val belief = unit.belief
            val neverTested = belief.totalAnswers == 0
            val mastered = unit.predictedMastery >= strategy.knownSkipThreshold

            if (mastered) {
                if (unit.selfReportedKnown) {
                    // A self-report that the model also believes: the core saving. It leaves the
                    // teaching queue and only becomes a spot check when nothing else needs doing.
                    sampleCandidate = sampleCandidate ?: unit
                }
                continue
            }
            // A self-reported word the model does not believe still gets a spot check. A real test
            // miss outranks that, so this only applies while there is no test evidence.
            if (unit.selfReportedKnown && neverTested) {
                pending += StudyDecision(
                    unit, StudyAction.VALIDATE, validationScore(unit),
                    "自评认识 · 抽查确认，避免误判"
                )
                continue
            }

            when {
                // Nothing is known about this unit at all: one screening tap is the cheapest signal.
                // A unit that already carries evidence (a test run, or a screening result) is never
                // sent back to screening — that is what keeps the queue from re-asking known words.
                neverTested && belief.masterySource == MasterySource.MODEL_PRIOR ->
                    pending += StudyDecision(
                        unit, StudyAction.SCREEN, SCREEN_SCORE,
                        "尚未排查 · 先用一次点击判断是否认识"
                    )

                // A unit with no test evidence but a screening result: it is already judged, so it is
                // taught when it is believed unknown. This is the fuzzy/unknown path after screening.
                neverTested -> {
                    val value = learningValue(unit)
                    if (value >= LEARNING_VALUE_FLOOR) {
                        pending += StudyDecision(
                            unit, StudyAction.LEARN, value,
                            "已排查为${selfReportLabel(belief)} · 学习价值 ${"%.2f".format(value)}"
                        )
                    }
                }

                // Learned before and the memory model says it is due.
                unit.dueAtMillis in 1..input.now ->
                    pending += StudyDecision(
                        unit, StudyAction.REVIEW, reviewValue(unit, input.now),
                        "到期复习 · 预计提取概率 ${percent(unit.memory.retrievability(input.now))}"
                    )

                // Known to be weak: teach it, unless the model is still too unsure to pick content.
                belief.mastery < strategy.uncertainLower -> {
                    if (belief.confidence < UNCERTAIN_CONFIDENCE) {
                        pending += StudyDecision(
                            unit, StudyAction.DIAGNOSE, diagnosticValue(unit),
                            "证据不足 · 先测一次再决定教什么"
                        )
                    } else {
                        pending += StudyDecision(
                            unit, StudyAction.LEARN, learningValue(unit),
                            "未掌握 · 学习价值 ${"%.2f".format(learningValue(unit))}"
                        )
                    }
                }

                // Between the two thresholds: teach it if the word matters enough.
                learningValue(unit) >= LEARNING_VALUE_FLOOR ->
                    pending += StudyDecision(
                        unit, StudyAction.LEARN, learningValue(unit),
                        "掌握不牢 · 学习价值 ${"%.2f".format(learningValue(unit))}"
                    )
            }
        }

        // Screening first (find gaps), then the highest-value teaching/testing/review work.
        val ordered = pending.sortedWith(
            compareByDescending<StudyDecision> { it.action == StudyAction.SCREEN }
                .thenByDescending { it.score }
                .thenBy { it.unit.word }
        )

        // Nothing worth doing? One spot check keeps a self-report from standing unchallenged forever,
        // but it is explicitly low priority and only happens when there is no real work left.
        val withSample = if (ordered.isEmpty() && sampleCandidate != null) {
            listOf(
                StudyDecision(
                    sampleCandidate!!, StudyAction.VALIDATE, 0.0,
                    "今日没有更值得做的内容 · 抽查一个自评认识的词"
                )
            )
        } else {
            ordered
        }

        var reviews = 0
        var learns = 0
        return withSample.filter { decision ->
            when (decision.action) {
                StudyAction.REVIEW -> (reviews++ < reviewBudget)
                StudyAction.LEARN -> (learns++ < learnBudget)
                else -> true
            }
        }.take(input.limit)
    }

    private fun budget(cap: Int, done: Int, limit: Int): Int {
        val remaining = if (cap <= 0) limit else (cap - done).coerceAtLeast(0)
        return minOf(remaining, limit)
    }

    private fun validationScore(unit: StudyUnit): Double =
        (1.0 - unit.predictedMastery) * unit.importance * VALIDATION_WEIGHT

    /** Short label for why a screened unit is being taught, based on its stored belief. */
    private fun selfReportLabel(belief: KnowledgeBelief): String = when {
        belief.mastery >= 0.70 -> "认识（待验证）"
        belief.mastery >= 0.35 -> "模糊"
        else -> "陌生"
    }

    private fun percent(value: Double?): String =
        if (value == null) "未知" else "${(value * 100).roundToInt()}%"

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()

    /** Screening is always cheapest per unit of information, so it leads. */
    const val SCREEN_SCORE = 1000.0
    /** Below this learning value a unit is not worth teaching right now. */
    const val LEARNING_VALUE_FLOOR = 0.05
    /** Confidence under which we prefer to measure before teaching. */
    const val UNCERTAIN_CONFIDENCE = 0.35
    /** Spot checks are worth doing but never outrank real gaps. */
    const val VALIDATION_WEIGHT = 0.6

    /** Sort key helper used by the UI to explain ordering. */
    fun valueOf(decision: StudyDecision): Double = when (decision.action) {
        StudyAction.SCREEN -> SCREEN_SCORE
        StudyAction.DIAGNOSE -> diagnosticValue(decision.unit)
        StudyAction.LEARN -> learningValue(decision.unit)
        StudyAction.REVIEW -> reviewValue(decision.unit, decision.unit.dueAtMillis)
        StudyAction.VALIDATE -> validationScore(decision.unit)
    }

    /** Distance between predicted mastery and the "worth teaching" boundary, for debugging. */
    fun gapToThreshold(unit: StudyUnit, strategy: LearningStrategy): Double =
        abs(unit.predictedMastery - strategy.uncertainLower)
}
