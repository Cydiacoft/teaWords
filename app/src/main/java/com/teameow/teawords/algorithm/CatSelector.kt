package com.teameow.teawords.algorithm

import kotlin.math.abs

/** A word that can be used as a diagnostic item. */
data class DiagnosticItem(
    val wordId: Long,
    val word: String,
    /** Prior difficulty on the logit scale (see [DifficultyPrior]). */
    val difficulty: Double,
    /** How much is already known about this item; high values make it a poor diagnostic choice. */
    val existingEvidence: Double = 0.0
)

/** Why the diagnostic test stopped. Shown to the user so the estimate is never unexplained. */
enum class CatStopReason {
    /** The standard error target was reached. */
    PRECISION_REACHED,
    /** The configured maximum number of items was answered. */
    MAX_ITEMS_REACHED,
    /** No candidate could add information (everything left is already well known). */
    NO_INFORMATIVE_ITEM,
    /** Every available word has been asked: the pool itself is the limit. */
    EXHAUSTED,
    /** The user chose to stop; screening continues to refine knowledge afterwards. */
    USER_STOPPED
}

data class CatState(
    val ability: AbilityEstimate = AbilityEstimator.initial,
    val answered: List<AnsweredItem> = emptyList(),
    val stopReason: CatStopReason? = null
) {
    data class AnsweredItem(val item: DiagnosticItem, val correct: Boolean, val elapsedMillis: Long)

    val isFinished: Boolean get() = stopReason != null
    val answeredCount: Int get() = answered.size
    val correctCount: Int get() = answered.count { it.correct }
}

/**
 * Computerised adaptive testing on the Rasch model.
 *
 * Item selection maximises Fisher information `p(1-p)` at the current ability estimate, i.e. it
 * prefers words whose difficulty sits closest to what the user can currently do — which is what
 * makes a short test informative. Items already well known are penalised so the test does not waste
 * questions on words the user has already demonstrated.
 *
 * Honest limitations, enforced in code rather than in a comment only:
 * * Difficulty comes from the word book prior, so item selection is only as good as that ordering.
 * * Stopping is driven by the standard error of the ability estimate, which is reported to the UI
 *   together with the number of answers it is based on.
 * * Tests may stop early at any time; screening and normal practice keep refining the picture.
 */
object CatSelector {
    /** Items answered between 8 and 20 keeps a diagnostic session short. */
    const val DEFAULT_MAX_ITEMS = 20
    const val DEFAULT_MIN_ITEMS = 6

    /** Returns the next item, or null when no candidate carries information. */
    fun selectNext(state: CatState, candidates: List<DiagnosticItem>): DiagnosticItem? {
        val answeredIds = state.answered.mapTo(HashSet()) { it.item.wordId }
        var best: DiagnosticItem? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (item in candidates) {
            if (item.wordId in answeredIds) continue
            val info = AbilityEstimator.information(state.ability.theta, item.difficulty)
            // Penalise items with existing evidence: answering them again teaches us little.
            val score = info * (1.0 - item.existingEvidence.coerceIn(0.0, 0.95))
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        // A score under this bound means no candidate can move the estimate meaningfully.
        return if (bestScore < MIN_USEFUL_INFORMATION) null else best
    }

    /** Smallest information worth asking a question for. */
    const val MIN_USEFUL_INFORMATION = 1e-3

    fun answer(state: CatState, item: DiagnosticItem, correct: Boolean, elapsedMillis: Long): CatState {
        val updated = AbilityEstimator.update(
            state.ability,
            ResponseObservation(item.difficulty, correct, elapsedMillis)
        )
        return state.copy(
            ability = updated,
            answered = state.answered + CatState.AnsweredItem(item, correct, elapsedMillis)
        )
    }

    /**
     * Advancing with [candidates] keeps the session honest about its own limits: it stops when the
     * pool is empty, when precision is reached, or at the item cap. Nothing to measure is *not* a
     * reason to stop a diagnostic run early — a user who answers everything correctly still needs
     * enough items for the estimate to become precise.
     */
    fun answer(
        state: CatState,
        item: DiagnosticItem,
        correct: Boolean,
        elapsedMillis: Long,
        candidates: List<DiagnosticItem>
    ): CatState {
        val answeredState = answer(state, item, correct, elapsedMillis)
        if (answeredState.stopReason != null) return answeredState
        val remaining = candidates.filterNot { candidate ->
            answeredState.answered.any { it.item.wordId == candidate.wordId }
        }
        if (remaining.isEmpty()) return answeredState.copy(stopReason = CatStopReason.EXHAUSTED)
        return answeredState.copy(stopReason = stopReason(answeredState, remaining))
    }

    /**
     * @param remainingCandidates supplies the pool so the session can stop when the pool is empty.
     *   A pool that still has items never stops the run for "nothing informative": a correct answer
     *   streak needs more items before the estimate is allowed to call itself precise.
     */
    fun stopReason(state: CatState, remainingCandidates: List<DiagnosticItem>?): CatStopReason? {
        if (state.answeredCount >= DEFAULT_MAX_ITEMS) return CatStopReason.MAX_ITEMS_REACHED
        if (state.ability.isPreciseEnough && state.answeredCount >= DEFAULT_MIN_ITEMS) {
            return CatStopReason.PRECISION_REACHED
        }
        if (remainingCandidates != null && remainingCandidates.isEmpty()) {
            return CatStopReason.EXHAUSTED
        }
        return null
    }

    fun stop(state: CatState): CatState = state.copy(stopReason = CatStopReason.USER_STOPPED)

    /** Human-readable label for the estimate, used in the UI next to the number. */
    fun describe(estimate: AbilityEstimate): String = when {
        estimate.itemsAnswered == 0 -> "尚未诊断：以下均为先验，不含任何测试证据"
        !estimate.isPreciseEnough -> "初步估计（${estimate.itemsAnswered} 题，误差 ±${"%.2f".format(1.96 * estimate.standardError)}）"
        else -> "已可定位练习难度（${estimate.itemsAnswered} 题，误差 ±${"%.2f".format(1.96 * estimate.standardError)}）"
    }

    /** Rough CEFR-ish band for displaying ability, explicitly approximate. */
    fun approximateBand(theta: Double): String = when {
        theta < -1.5 -> "初中 / 基础"
        theta < -0.5 -> "高中 / CET-4 以下"
        theta < 0.5 -> "CET-4 附近"
        theta < 1.5 -> "CET-6 附近"
        else -> "六级以上"
    }

    fun difficultyGap(item: DiagnosticItem, ability: AbilityEstimate): Double =
        abs(item.difficulty - ability.theta)
}
