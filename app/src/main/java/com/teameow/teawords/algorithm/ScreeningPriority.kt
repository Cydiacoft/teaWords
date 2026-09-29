package com.teameow.teawords.algorithm

import kotlin.math.abs

/** A word waiting to be judged, with the information needed to decide whether it is worth asking. */
data class ScreenCandidate(
    val word: String,
    val difficulty: Double,
    val importance: Double = 1.0
)

/**
 * Which words are worth asking the user about.
 *
 * The problem this solves: a 4500-word word book cannot be screened word by word — that is the same
 * "review everything" cost the product exists to avoid, and gating study behind it makes the app
 * unusable. An ability estimate turns that into a short list:
 *
 * * Words the model believes are **already known** are skipped outright. Not queued, not asked.
 * * Words the model believes are **clearly unknown** go straight to learning. No tap needed to learn
 *   something the user demonstrably does not know.
 * * Only the **uncertain band** around the user's ability is worth a tap, because that is where a
 *   single answer changes what the system should do next.
 *
 * The band width comes from the ability estimate's own standard error, so a vague estimate asks more
 * questions and a precise one asks fewer. Nothing here is presented as a fact about a specific word:
 * [Outcome] carries the predicted probability so the UI can label it as a prediction.
 */
object ScreeningPriority {
    /**
     * Probability above which a word is treated as known and skipped.
     *
     * This is a product decision, not a statistical one, and the two possible mistakes are not
     * symmetric:
     *
     * * Skipping a word the user does not know costs one word never taught — and the word stays in the
     *   book, so it can be found again by a later estimate.
     * * Asking about a word the user already knows costs the user's time, which is the resource this
     *   product exists to protect.
     *
     * Measured against the real priors, a 0.75 cut-off left a strong user (theta 1.0) with 90% of a
     * word book in the "uncertain" band, because average CET-4 words land near 0.73. Set at 0.70 the
     * same user skips roughly half the book while words below 0.70 still go to learning rather than
     * being silently dropped.
     */
    const val KNOWN_THRESHOLD = 0.70
    /**
     * Probability below which a word is treated as unknown and queued for learning directly.
     *
     * Calibrated against what the model actually produces. A user who answered the whole diagnostic
     * incorrectly sits near theta -0.2, which gives about 0.33 for an average CET-4 word once the
     * estimate is shrunk for uncertainty. A 0.20 cut-off therefore classified everything as
     * "uncertain" and the band carried no information; 0.35 puts a clearly weak user's words straight
     * into the learning queue, where the whole point is to avoid asking about them.
     */
    const val UNKNOWN_THRESHOLD = 0.35
    /** Width of the band around 0.5 where one answer matters most. */
    const val DECISIVE_BAND = 0.45

    enum class Band { LIKELY_KNOWN, UNCERTAIN, LIKELY_UNKNOWN }

    data class Outcome(
        val band: Band,
        val probabilityKnown: Double,
        /** Higher means a single answer would change the plan more. */
        val screeningValue: Double
    )

    /**
     * Predicted probability that the user already knows this word.
     *
     * This is the Rasch curve evaluated with the current ability estimate. It is discounted towards
     * the prior while the estimate is still weak, so a two-answer diagnostic cannot declare a whole
     * word book known.
     */
    fun probabilityKnown(ability: AbilityEstimate, difficulty: Double): Double =
        PredictedConfidence.predictedMastery(ability, difficulty)

    fun classify(ability: AbilityEstimate, candidate: ScreenCandidate, strategy: LearningStrategy? = null): Outcome {
        val probability = probabilityKnown(ability, candidate.difficulty)
        val band = when {
            probability >= (strategy?.knownSkipThreshold ?: KNOWN_THRESHOLD) -> Band.LIKELY_KNOWN
            probability <= (strategy?.uncertainLower ?: UNKNOWN_THRESHOLD) -> Band.LIKELY_UNKNOWN
            else -> Band.UNCERTAIN
        }
        return Outcome(band, probability, screeningValue(ability, candidate, probability))
    }

    /**
     * Value of one screening tap.
     *
     * Peaks when the predicted probability sits near 0.5: those are the answers that actually decide
     * whether a word should be taught. Words the model is already confident about score near zero, so
     * they never occupy the queue. Importance lets a word book priority outrank a marginal one.
     */
    fun screeningValue(ability: AbilityEstimate, candidate: ScreenCandidate, probability: Double): Double {
        // Full value for a coin-flip word, zero once the model is confident either way. Without this
        // flat region every word scores the same and the queue cannot be ordered.
        val decisiveness = (1.0 - abs(probability - 0.5) / DECISIVE_BAND).coerceIn(0.0, 1.0)
        val gapFromAbility = abs(ability.theta - candidate.difficulty)
        val closeness = 1.0 / (1.0 + gapFromAbility)
        return (decisiveness * closeness * candidate.importance).coerceIn(0.0, 1.0)
    }

    /** Words worth a tap, best first. Words outside the uncertain band are excluded entirely. */
    fun worthScreening(ability: AbilityEstimate, candidates: List<ScreenCandidate>): List<Pair<ScreenCandidate, Outcome>> =
        candidates
            .map { it to classify(ability, it) }
            .filter { (_, outcome) -> outcome.band == Band.UNCERTAIN }
            .sortedWith(compareByDescending<Pair<ScreenCandidate, Outcome>> { it.second.screeningValue }.thenBy { it.first.word })

    data class Plan(
        val skipAsKnown: List<Pair<ScreenCandidate, Outcome>>,
        val needsScreening: List<Pair<ScreenCandidate, Outcome>>,
        val learnDirectly: List<Pair<ScreenCandidate, Outcome>>
    ) {
        val total: Int get() = skipAsKnown.size + needsScreening.size + learnDirectly.size
        /** Share of the word book the user never has to judge by hand. */
        val savedFraction: Double
            get() = if (total == 0) 0.0 else (skipAsKnown.size + learnDirectly.size).toDouble() / total
    }

    fun plan(ability: AbilityEstimate, candidates: List<ScreenCandidate>, strategy: LearningStrategy? = null): Plan {
        val classified = candidates.map { it to classify(ability, it, strategy) }
        return Plan(
            skipAsKnown = classified.filter { it.second.band == Band.LIKELY_KNOWN }
                .sortedByDescending { it.second.probabilityKnown },
            needsScreening = classified.filter { it.second.band == Band.UNCERTAIN }
                .sortedByDescending { it.second.screeningValue },
            learnDirectly = classified.filter { it.second.band == Band.LIKELY_UNKNOWN }
                .sortedByDescending { PredictedConfidence.predictedMastery(ability, it.first.difficulty) * -1 }
        )
    }

    /** Human-readable summary for the screening screen header. */
    fun describe(ability: AbilityEstimate, plan: Plan): String {
        if (ability.itemsAnswered == 0) {
            return "尚未诊断：按词书顺序抽查，建议先生成能力估计以缩小范围"
        }
        val percent = (plan.savedFraction * 100).toInt()
        return "按你的能力估计，${plan.skipAsKnown.size + plan.learnDirectly.size} 个词（$percent%）无需手动判断：" +
            "其中 ${plan.skipAsKnown.size} 个预测已认识将跳过，${plan.learnDirectly.size} 个预测陌生将直接学习"
    }
}
