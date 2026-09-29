package com.teameow.teawords.algorithm

/**
 * The result of grading one answer, before anything is persisted.
 *
 * This is the seam between the algorithm and the database: the algorithms are pure Kotlin and know
 * nothing about SQLite, and the repository stores exactly what a verdict contains. Keeping them apart
 * means the models can be replaced or fitted later without touching storage.
 */
data class ReviewVerdict(
    val grade: ReviewGrade,
    val knowledge: KnowledgeBelief,
    val memory: MemoryState,
    val ability: AbilityEstimate,
    /** Days until the next review, or null when the scheduler has no state to schedule from yet. */
    val intervalDays: Double?,
    /** Absolute time of the next review, or null when there is nothing to schedule. */
    val dueAt: Long?
) {
    val isFirstReview: Boolean get() = memory.reps <= 1
}

/**
 * Grades one answer with every model at once, in a fixed order.
 *
 * Order matters and is deliberate:
 * 1. [ReviewGrade] is derived from what was observed (correct, revealed, how long it took).
 * 2. [BayesianKnowledgeTracing] updates the mastery belief from that answer.
 * 3. [FsrsScheduler] updates the memory state and derives the next review time from it.
 * 4. [AbilityEstimator] folds the same answer into the global ability estimate.
 *
 * The three models are kept separate on purpose: mastery, memory stability and ability are different
 * quantities and are never collapsed into one score. Callers persist the whole verdict, so a later
 * parameter fit has the grade, the mode, the timings and the before/after values it needs.
 */
class ReviewCoordinator(
    private val tracer: KnowledgeTracer = BayesianKnowledgeTracing,
    private val scheduler: SpacedRepetitionScheduler = FsrsScheduler
) {
    /**
     * @param targetRetention from the user's learning strategy; higher means shorter intervals.
     */
    fun grade(
        belief: KnowledgeBelief,
        memory: MemoryState,
        ability: AbilityEstimate,
        difficulty: Double,
        correct: Boolean,
        revealed: Boolean,
        elapsedMillis: Long,
        now: Long,
        targetRetention: Double = FsrsScheduler.DEFAULT_TARGET_RETENTION
    ): ReviewVerdict {
        val grade = ReviewGrade.fromAnswer(correct, revealed, elapsedMillis)
        // "Revealed" means the user gave up and looked: that is not evidence of knowledge, so mastery
        // is left untouched while the memory model still records that the item was seen.
        val context = when {
            revealed -> ResponseContext.REVEALED
            else -> ResponseContext.NORMAL
        }
        val knowledge = tracer.observe(belief, correct = correct && !revealed, context = context, now = now)
        val nextMemory = scheduler.review(memory, grade, now)
        val interval = scheduler.nextIntervalDays(nextMemory, targetRetention)
        val nextAbility = if (revealed) ability else AbilityEstimator.update(ability, ResponseObservation(difficulty, correct, elapsedMillis))
        return ReviewVerdict(
            grade = grade,
            knowledge = knowledge,
            memory = nextMemory,
            ability = nextAbility,
            intervalDays = interval,
            dueAt = interval?.let { now + (it * MemoryState.MILLIS_PER_DAY).toLong() }
        )
    }

    /** True when this sense should be shown again; null state means it was never reviewed. */
    fun isDue(memory: MemoryState, now: Long): Boolean = memory.reps > 0 && memory.dueAtOrZero(now)

    private fun MemoryState.dueAtOrZero(now: Long): Boolean =
        retrievability(now)?.let { it <= FsrsScheduler.DEFAULT_TARGET_RETENTION } ?: false

    /**
     * Priority for the review queue: how likely the item is to have been forgotten, weighted by how
     * important the word is. Higher comes first.
     */
    fun reviewUrgency(memory: MemoryState, now: Long, importance: Double = 1.0): Double {
        val retrievability = memory.retrievability(now) ?: return 0.0
        return (1.0 - retrievability).coerceIn(0.0, 1.0) * importance
    }

    /** Human-readable reason to show next to a review item. */
    fun reviewReason(memory: MemoryState, now: Long): String {
        val retrievability = memory.retrievability(now)
            ?: return "尚未复习过：这次会建立第一个记忆状态"
        return "预计提取概率 ${(retrievability * 100).toInt()}% · 稳定度 ${"%.1f".format(memory.stability)} 天"
    }
}
