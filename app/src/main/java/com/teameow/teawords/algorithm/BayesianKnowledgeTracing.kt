package com.teameow.teawords.algorithm

import kotlin.math.exp
import kotlin.math.ln

/**
 * How one answer was produced. This is self-reported or interaction-derived context, never a
 * fabricated measurement: it only shifts the likelihood of the observation.
 */
enum class ResponseContext {
    /** Answered normally: the answer reflects what the user knows. */
    NORMAL,
    /** The user said they did not know and revealed the answer: not evidence of knowledge. */
    REVEALED,
    /** Answered within a very short time; a correct answer may be a lucky guess. */
    FAST,
    /** The user corrected a mis-tap. */
    CORRECTED
}

/**
 * Mastery belief for one unit of knowledge (one word + one sense + one practice mode).
 *
 * Two numbers are kept deliberately separate, because the requirement is that a statistical guess
 * must never be presented as a test result:
 *
 * * [mastery]  - model probability that the unit is known. A prediction.
 * * [confidence] - how much evidence stands behind that prediction (0 = none, 1 = much).
 * * [verifiedByTest] - true only when real answers met the verification rule. Evidence.
 *
 * [masterySource] states where the number came from so the UI can label it honestly.
 */
data class KnowledgeBelief(
    val mastery: Double,
    val confidence: Double,
    val verifiedByTest: Boolean,
    val correctStreak: Int,
    val totalAnswers: Int,
    val lastUpdated: Long,
    val masterySource: MasterySource
) {
    companion object {
        fun unseen(now: Long) = KnowledgeBelief(
            mastery = BayesianKnowledgeTracing.PRIOR_MASTERY,
            confidence = 0.0,
            verifiedByTest = false,
            correctStreak = 0,
            totalAnswers = 0,
            lastUpdated = now,
            masterySource = MasterySource.MODEL_PRIOR
        )

        /** From the user's own screening tap. Not a test result, and labelled as such. */
        fun fromSelfReport(report: String, now: Long): KnowledgeBelief {
            val prior = when (report) {
                "KNOWN" -> 0.80
                "FUZZY" -> 0.45
                "UNKNOWN" -> 0.15
                else -> BayesianKnowledgeTracing.PRIOR_MASTERY
            }
            return KnowledgeBelief(
                mastery = prior,
                confidence = 0.0,
                verifiedByTest = false,
                correctStreak = 0,
                totalAnswers = 0,
                lastUpdated = now,
                masterySource = MasterySource.SELF_REPORT
            )
        }
    }
}

/** Where a mastery number came from. The UI must show this, never implying a test result. */
enum class MasterySource {
    /** No information at all. */
    MODEL_PRIOR,
    /** The user tapped "I know this" during screening. */
    SELF_REPORT,
    /** Updated by real answers. */
    TEST_EVIDENCE
}

/**
 * Bayesian Knowledge Tracing for a single knowledge unit.
 *
 * p(L) evolves as:
 *   observation:  p(L|correct) = p(L)(1-slip) / [p(L)(1-slip) + (1-p(L))guess]
 *                 p(L|wrong)   = p(L)slip     / [p(L)slip     + (1-p(L))(1-guess)]
 *   transition:   p(L) <- p(L) + (1-p(L)) * p(learn)
 *
 * Parameters are **global defaults**, not fitted per user: with a handful of answers per word a
 * per-unit fit would be pure noise. They are declared as constants with their meaning, and the
 * interface ([KnowledgeTracer]) exists so a fitted version can replace this one later.
 */
interface KnowledgeTracer {
    fun observe(
        belief: KnowledgeBelief,
        correct: Boolean,
        context: ResponseContext,
        now: Long
    ): KnowledgeBelief
}

object BayesianKnowledgeTracing : KnowledgeTracer {
    /** Chance the unit is already known before any evidence. */
    const val PRIOR_MASTERY = 0.30
    /** Chance of acquiring it during one practice even after a wrong answer (taught items only). */
    const val P_LEARN = 0.18
    /** Knowing it but answering wrong (mis-tap, hesitation). */
    const val P_SLIP = 0.10
    /** Not knowing it but answering right (guessing from options, luck). */
    const val P_GUESS = 0.20
    /** A correct answer within this window is treated as a possible lucky guess. */
    const val FAST_ANSWER_MILLIS = 1_500L
    /** Consecutive correct answers required before a unit is called verified by test. */
    const val VERIFY_STREAK = 3

    override fun observe(
        belief: KnowledgeBelief,
        correct: Boolean,
        context: ResponseContext,
        now: Long
    ): KnowledgeBelief {
        // A revealed answer is not evidence about knowledge: it only records that practice happened.
        if (context == ResponseContext.REVEALED) {
            return belief.copy(
                totalAnswers = belief.totalAnswers,
                correctStreak = 0,
                lastUpdated = now
            )
        }
        val slip = if (context == ResponseContext.CORRECTED) P_SLIP * 2 else P_SLIP
        val guess = if (context == ResponseContext.FAST) P_GUESS * 1.5 else P_GUESS

        val prior = belief.mastery.coerceIn(0.001, 0.999)
        val posterior = if (correct) {
            val numerator = prior * (1 - slip)
            numerator / (numerator + (1 - prior) * guess)
        } else {
            val numerator = prior * slip
            numerator / (numerator + (1 - prior) * (1 - guess))
        }
        // Learning transition: even a wrong answer moves the belief a little towards known.
        val afterLearning = posterior + (1 - posterior) * P_LEARN

        val answers = belief.totalAnswers + 1
        val streak = if (correct) belief.correctStreak + 1 else 0
        return KnowledgeBelief(
            mastery = afterLearning.coerceIn(0.001, 0.999),
            // Confidence counts independent answers; it saturates so five answers already count a lot
            // while one answer can never look certain.
            confidence = 1.0 - exp(-answers / 3.0),
            verifiedByTest = streak >= VERIFY_STREAK,
            correctStreak = streak,
            totalAnswers = answers,
            lastUpdated = now,
            masterySource = MasterySource.TEST_EVIDENCE
        )
    }
}

/**
 * Confidence of a *predicted* mastery for words that were never tested, derived from how much
 * evidence the ability estimate rests on. Kept here so "predicted" numbers always carry an explicit
 * uncertainty.
 */
object PredictedConfidence {
    /**
     * Answers after which the ability estimate is treated as fully usable for prediction.
     *
     * The Rasch curve on its own is optimistic: it would already place theta at the true value after
     * a handful of answers. Requiring a full diagnostic session (12 items) before trusting it is the
     * conservative choice, and it keeps the "predicted known" band from being populated by a
     * two-answer session.
     */
    const val FULL_EVIDENCE_ANSWERS = 12

    /** Maps an ability estimate onto [0,1] confidence, 1 being enough answers to rely on. */
    fun fromAbility(estimate: AbilityEstimate): Double {
        if (estimate.itemsAnswered <= 0) return 0.0
        return (estimate.itemsAnswered.toDouble() / FULL_EVIDENCE_ANSWERS).coerceIn(0.0, 1.0)
    }

    /**
     * Predicted probability that an untested word is known, given ability and item difficulty.
     *
     * Uncertainty in the ability estimate is expressed in **logit space**, not by blending towards the
     * mastery prior. Blending towards a prior of 0.30 would cap every prediction below the "known"
     * threshold, so no word could ever be skipped — which defeats the purpose of the estimate.
     * Shrinking theta towards zero instead keeps the shape of the curve and only flattens it while the
     * estimate is weak, so a two-answer diagnostic cannot declare a whole word book known.
     */
    fun predictedMastery(estimate: AbilityEstimate, difficulty: Double): Double {
        if (estimate.itemsAnswered == 0) return 0.5
        val confidence = fromAbility(estimate)
        val shrunkTheta = estimate.theta * confidence
        return AbilityEstimator.probabilityCorrect(shrunkTheta, difficulty).coerceIn(0.0, 1.0)
    }
}

/** Natural log helper kept for likelihood work; avoids importing kotlin.math.ln everywhere. */
internal fun logLikelihood(p: Double): Double = ln(p.coerceAtLeast(1e-12))
