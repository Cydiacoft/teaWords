package com.teameow.teawords.algorithm

import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Grading of a review, as FSRS expects it. Derived from what the app can actually observe:
 * a free-recall answer plus whether the user revealed the answer.
 */
enum class ReviewGrade(val label: String) {
    /** Could not produce it at all, even after thinking. */
    AGAIN("完全想不起来"),
    /** Wrong, or only vaguely familiar. */
    HARD("答错 / 很吃力"),
    /** Correct with some effort. */
    GOOD("答对，正常"),
    /** Correct immediately and effortlessly. */
    EASY("答对，很轻松");

    companion object {
        fun fromAnswer(correct: Boolean, revealed: Boolean, elapsedMillis: Long): ReviewGrade = when {
            revealed || !correct -> AGAIN
            elapsedMillis > 0 && elapsedMillis > SLOW_MILLIS -> HARD
            elapsedMillis in 1..FAST_MILLIS -> EASY
            else -> GOOD
        }

        const val FAST_MILLIS = 4_000L
        const val SLOW_MILLIS = 20_000L
    }
}

/**
 * Memory state for one knowledge unit.
 *
 * [difficulty]  FSRS D, 1..10: how hard this item is for this user.
 * [stability]   FSRS S in days: interval at which retrievability falls to the target level.
 * [lastReview]  epoch millis of the last real review.
 * [reps]/[lapses] counters used by the update rules.
 */
data class MemoryState(
    val difficulty: Double,
    val stability: Double,
    val lastReview: Long,
    val reps: Int,
    val lapses: Int
) {
    /**
     * FSRS R(t): probability of recall now. Returns null when no real review has happened yet —
     * a word that was never practised has no personal memory state, and inventing one would be a
     * fabricated measurement.
     */
    fun retrievability(now: Long): Double? {
        if (reps == 0 || stability <= 0.0 || lastReview <= 0L) return null
        val elapsedDays = (now - lastReview).coerceAtLeast(0L) / MILLIS_PER_DAY
        return FsrsScheduler.retrievabilityAt(stability, elapsedDays)
    }

    companion object {
        const val MILLIS_PER_DAY = 86_400_000.0

        /**
         * FSRS forgetting-curve constants.
         *
         * `DECAY = -0.5` is the exponent of the power curve, and `FACTOR = 19/81` is the scaling
         * constant that makes stability mean "days until retrievability drops to 0.9". Using
         * `-DECAY` directly as the scaling factor — an easy and common mistake — makes the curve decay
         * roughly eight times too slowly and drives retrievability above 1.
         */
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0

        val unseen: MemoryState get() = MemoryState(0.0, 0.0, 0L, 0, 0)
    }
}

/** Scheduler port: FSRS is the implementation, a future model can replace it. */
interface SpacedRepetitionScheduler {
    fun review(state: MemoryState, grade: ReviewGrade, now: Long): MemoryState
    fun nextIntervalDays(state: MemoryState, targetRetention: Double): Double?
    fun isReady(state: MemoryState): Boolean
}

/**
 * FSRS-style scheduler (FSRS-4.5 update rules).
 *
 * Weights are the published FSRS-4.5 defaults. They are **not** optimised on this user's data —
 * [SchedulerReadiness] reports that plainly so the UI never claims a personalised model. The port
 * [SpacedRepetitionScheduler] exists so a parameter-fitting step can replace the weights later
 * without touching callers.
 *
 * Deliberate design decision required by the product spec: a knowledge unit that has never been
 * reviewed has **no** stability. Callers must not show a next-review date derived from a default
 * before the first real review.
 */
object FsrsScheduler : SpacedRepetitionScheduler {
    /**
     * FSRS-4.5 default parameters (w0..w16), matching the reference implementation in
     * open-spaced-repetition/py-fsrs. Written out by index so a wrong slot is visible:
     * w0..w3 initial stability per grade, w4..w5 initial difficulty, w6..w7 difficulty update,
     * w8..w10 successful-recall stability, w11..w14 lapse stability, w15 hard penalty, w16 easy bonus.
     */
    val DEFAULT_WEIGHTS = doubleArrayOf(
        /* w0  */ 0.4872,  // initial stability, AGAIN
        /* w1  */ 1.4003,  // initial stability, HARD
        /* w2  */ 3.7145,  // initial stability, GOOD
        /* w3  */ 13.8206, // initial stability, EASY
        /* w4  */ 5.1618,  // initial difficulty baseline
        /* w5  */ 1.2298,  // initial difficulty slope per grade step
        /* w6  */ 0.8975,  // difficulty change per grade step
        /* w7  */ 0.031,   // difficulty mean reversion towards the easy anchor
        /* w8  */ 1.6474,  // successful recall: growth scale
        /* w9  */ 0.1367,  // successful recall: stability exponent (S^-w9)
        /* w10 */ 1.0461,  // successful recall: retrievability exponent
        /* w11 */ 2.1072,  // lapse: scale
        /* w12 */ 0.0793,  // lapse: difficulty exponent
        /* w13 */ 0.3246,  // lapse: (S+1)^w13 - 1
        /* w14 */ 1.587,   // lapse: retrievability exponent
        /* w15 */ 0.2272,  // HARD penalty
        /* w16 */ 2.8755   // EASY bonus
    )

    /** Target retention when turning stability into an interval. */
    const val DEFAULT_TARGET_RETENTION = 0.9
    /** Shortest interval FSRS will propose, in days (a few minutes keeps same-session steps sane). */
    const val MIN_INTERVAL_DAYS = 0.01
    /** FSRS keeps intervals within a sane range; we cap at four years. */
    const val MAX_INTERVAL_DAYS = 365.0 * 4

    override fun isReady(state: MemoryState): Boolean = state.reps > 0 && state.stability > 0

    private fun initStability(grade: ReviewGrade): Double =
        DEFAULT_WEIGHTS[grade.ordinal].coerceAtLeast(0.1)

    private fun initDifficulty(grade: ReviewGrade): Double =
        (DEFAULT_WEIGHTS[4] - (grade.ordinal - 2) * DEFAULT_WEIGHTS[5]).coerceIn(1.0, 10.0)

    private fun nextDifficulty(difficulty: Double, grade: ReviewGrade): Double {
        val delta = -DEFAULT_WEIGHTS[6] * (grade.ordinal - 2)
        // Mean reversion towards the "easy" anchor keeps D from drifting forever.
        val reverted = DEFAULT_WEIGHTS[7] * initDifficulty(ReviewGrade.EASY) +
            (1 - DEFAULT_WEIGHTS[7]) * (difficulty + delta)
        return reverted.coerceIn(1.0, 10.0)
    }

    /**
     * FSRS retrievability R(t) = (1 + FACTOR * t / S)^DECAY, clamped to [0,1].
     *
     * With these constants R(S) = 0.9 exactly, which is what makes stability mean "days until the
     * recall probability reaches the 0.9 anchor". A non-positive base is guarded so a hostile clock
     * or a corrupted row cannot yield NaN, and the clamp keeps the value a probability.
     */
    internal fun retrievabilityAt(stability: Double, elapsedDays: Double): Double {
        if (stability <= 0.0 || !stability.isFinite() || !elapsedDays.isFinite()) return 0.0
        val base = 1.0 + MemoryState.FACTOR * elapsedDays / stability
        if (base <= 0.0) return 0.0
        val raw = base.pow(MemoryState.DECAY)
        return if (raw.isFinite()) raw.coerceIn(0.0, 1.0) else 0.0
    }

    private fun retrievability(stability: Double, elapsedDays: Double): Double =
        retrievabilityAt(stability, elapsedDays)

    override fun review(state: MemoryState, grade: ReviewGrade, now: Long): MemoryState {
        // First real review: FSRS initialises D and S from the grade (w0..w3 / w4..w5).
        if (!isReady(state)) {
            return MemoryState(
                difficulty = initDifficulty(grade),
                stability = initStability(grade),
                lastReview = now,
                reps = state.reps + 1,
                lapses = if (grade == ReviewGrade.AGAIN) state.lapses + 1 else state.lapses
            )
        }
        val elapsedDays = ((now - state.lastReview).coerceAtLeast(0L)) / MemoryState.MILLIS_PER_DAY
        val r = retrievability(state.stability, elapsedDays)
        val difficulty = nextDifficulty(state.difficulty, grade)

        val stability = if (grade == ReviewGrade.AGAIN) {
            // Forgetting: stability drops, scaled by how hard the item is and how well it was known.
            val s = DEFAULT_WEIGHTS[11] * difficulty.pow(-DEFAULT_WEIGHTS[12]) *
                ((state.stability + 1).pow(DEFAULT_WEIGHTS[13]) - 1) *
                expm1((1 - r) * DEFAULT_WEIGHTS[14])
            s.coerceAtLeast(0.05)
        } else {
            val hardPenalty = DEFAULT_WEIGHTS[15]
            val easyBonus = DEFAULT_WEIGHTS[16]
            val gradeFactor = when (grade) {
                ReviewGrade.HARD -> hardPenalty
                ReviewGrade.GOOD -> 1.0
                ReviewGrade.EASY -> easyBonus
                ReviewGrade.AGAIN -> 1.0
            }
            // FSRS-4.5 reference form: the decays use `expm1((1 - R) * w)` and the stability term is
            // a multiplier (S^-w9), not an exponent. Getting either wrong makes intervals collapse.
            val retrievabilityDecay = expm1((1 - r) * DEFAULT_WEIGHTS[10])
            val s = state.stability * (
                1 + exp(DEFAULT_WEIGHTS[8]) *
                    (11 - difficulty) *
                    state.stability.pow(-DEFAULT_WEIGHTS[9]) *
                    retrievabilityDecay *
                    gradeFactor
                )
            s.coerceAtLeast(0.05)
        }

        return MemoryState(
            difficulty = difficulty,
            stability = stability.coerceAtMost(MAX_INTERVAL_DAYS),
            lastReview = now,
            reps = state.reps + 1,
            lapses = if (grade == ReviewGrade.AGAIN) state.lapses + 1 else state.lapses
        )
    }

    /**
     * Interval in days for the requested retention, or null when there is no memory state yet
     * (never reviewed) — callers must treat null as "no schedule yet", not as "review now".
     */
    override fun nextIntervalDays(state: MemoryState, targetRetention: Double): Double? {
        if (!isReady(state)) return null
        val retention = targetRetention.coerceIn(0.70, 0.98)
        // FSRS: I = (S / FACTOR) * (r^(1/DECAY) - 1). Higher requested retention *shortens* the
        // interval, which is how the three learning strategies change review load.
        val interval = state.stability / MemoryState.FACTOR *
            (retention.pow(1.0 / MemoryState.DECAY) - 1)
        if (!interval.isFinite()) return null
        return interval.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
    }

    /** Interval in whole days, never below one day, for display and persistence. */
    fun nextIntervalWholeDays(state: MemoryState, targetRetention: Double): Int? =
        nextIntervalDays(state, targetRetention)?.let { days ->
            // Under a day still means "next session": report 1 rather than 0.
            if (days < 1.0) 1 else days.roundToInt()
        }
}

/** How much can honestly be claimed about the scheduler for a given user. */
data class SchedulerReadiness(
    val personalised: Boolean,
    val reviewsRecorded: Int,
    val note: String
) {
    companion object {
        /** Reviews needed before parameter fitting would be meaningful (not yet implemented). */
        const val REVIEWS_REQUIRED_FOR_FITTING = 400

        fun of(reviewsRecorded: Int): SchedulerReadiness = SchedulerReadiness(
            personalised = false,
            reviewsRecorded = reviewsRecorded,
            note = if (reviewsRecorded >= REVIEWS_REQUIRED_FOR_FITTING) {
                "已积累 $reviewsRecorded 次复习；参数仍使用 FSRS-4.5 默认值，个人参数拟合尚未实现。"
            } else {
                "使用 FSRS-4.5 默认参数（$reviewsRecorded/$REVIEWS_REQUIRED_FOR_FITTING 次复习，尚不足以拟合个人参数）。"
            }
        )
    }
}
