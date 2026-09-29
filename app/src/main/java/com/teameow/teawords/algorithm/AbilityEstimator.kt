package com.teameow.teawords.algorithm

import kotlin.math.abs
import kotlin.math.exp

/** How a word is being practised. Difficulty differs per mode, so knowledge is tracked per mode. */
enum class PracticeMode {
    /** English -> Chinese: recognising a word when you see it. */
    RECOGNITION,
    /** Chinese -> English: producing the word from its meaning. */
    RECALL,
    /** Producing the spelling from audio or meaning. */
    SPELLING
}

/** One observed answer, independent of storage. */
data class ResponseObservation(
    val difficulty: Double,
    val correct: Boolean,
    val elapsedMillis: Long = 0
)

/**
 * Rasch / 1PL ability estimate with an uncertainty measure.
 *
 * Model: P(correct | theta, b) = sigmoid(theta - b)
 *
 * This is the honest, minimal IRT form: one ability parameter, per-item difficulty supplied by a
 * prior (see [DifficultyPrior]). It is **not** a calibrated 2PL/3PL model: no discrimination or
 * guessing parameters are fitted, because fitting them needs many responses per item and the app
 * has no item bank with repeated exposure yet.
 *
 * theta is reported on the logit scale together with a standard error derived from the Fisher
 * information of the answers actually given, so a flat "ability = 2.3" can never be mistaken for a
 * precise measurement.
 */
data class AbilityEstimate(
    val theta: Double,
    val standardError: Double,
    val itemsAnswered: Int,
    val totalInformation: Double = 0.0
) {
    /** 95% confidence interval on the logit scale. */
    val interval: ClosedFloatingPointRange<Double>
        get() = (theta - 1.96 * standardError)..(theta + 1.96 * standardError)

    /** True once the standard error is small enough to aim practice at. */
    val isPreciseEnough: Boolean
        get() = itemsAnswered > 0 && standardError <= AbilityEstimator.DEFAULT_TARGET_SE
}

object AbilityEstimator {
    /** Ability is bounded to keep Newton steps from running away on short answer sequences. */
    const val MIN_THETA = -4.0
    const val MAX_THETA = 4.0
    /** Prior spread of ability before any answer: N(0, PRIOR_SD). */
    const val PRIOR_SD = 1.5
    private const val DAMPING = 0.8

    fun probabilityCorrect(theta: Double, difficulty: Double): Double =
        1.0 / (1.0 + exp(-(theta - difficulty)))

    /** Fisher information of one item at the current ability. */
    fun information(theta: Double, difficulty: Double): Double {
        val p = probabilityCorrect(theta, difficulty)
        return p * (1.0 - p)
    }

    /**
     * Updates the estimate with one more answer.
     *
     * A Bayesian step: the prior is N(0, PRIOR_SD) and the likelihood is the Rasch model, so a
     * single unusual answer moves the estimate only a little; repeated consistent answers move it
     * further. The standard error is recomputed from all accumulated information.
     */
    fun update(previous: AbilityEstimate, observation: ResponseObservation): AbilityEstimate {
        val theta0 = previous.theta
        val priorPrecision = 1.0 / (PRIOR_SD * PRIOR_SD)
        val priorMean = 0.0

        // Newton-Raphson on the penalised log-likelihood. The prior enters as one pseudo-observation
        // at theta = 0 with the prior precision, which keeps the first few answers stable.
        val p = probabilityCorrect(theta0, observation.difficulty)
        val residual = (if (observation.correct) 1.0 else 0.0) - p
        val gradient = residual + (priorMean - theta0) * priorPrecision
        val curvature = p * (1.0 - p) + priorPrecision
        val step = if (curvature <= 1e-9) 0.0 else DAMPING * gradient / curvature
        val theta1 = (theta0 + step).coerceIn(MIN_THETA, MAX_THETA)

        // Standard error from the observed information at the new estimate, including the prior.
        val p1 = probabilityCorrect(theta1, observation.difficulty)
        val totalInformation = previous.totalInformation + p1 * (1.0 - p1) + priorPrecision
        val se = 1.0 / kotlin.math.sqrt(totalInformation)

        return AbilityEstimate(
            theta = theta1,
            standardError = se,
            itemsAnswered = previous.itemsAnswered + 1,
            totalInformation = totalInformation
        ).also {
            require(abs(it.theta) <= MAX_THETA + 1e-9) { "theta escaped bounds" }
        }
    }

    val initial: AbilityEstimate get() = AbilityEstimate(0.0, PRIOR_SD, 0, 0.0)

    /** Roughly a third of a logit of uncertainty: good enough to aim practice at. */
    const val DEFAULT_TARGET_SE = 0.35
}
