package com.teameow.teawords.algorithm

import org.junit.Assert.*
import org.junit.Test

/**
 * The graded review pipeline: one answer goes through grading, BKT, FSRS and ability estimation, and
 * the verdict is what the database stores.
 *
 * The point of these tests is that the schedule is derived from the memory model rather than a fixed
 * table, and that "I gave up and looked" is never counted as knowledge.
 */
class ReviewCoordinatorTest {

    private val now = 1_800_000_000_000L
    private val coordinator = ReviewCoordinator()

    private fun grade(
        correct: Boolean,
        revealed: Boolean = false,
        elapsed: Long = 8_000,
        belief: KnowledgeBelief = KnowledgeBelief.unseen(now),
        memory: MemoryState = MemoryState.unseen,
        ability: AbilityEstimate = AbilityEstimator.initial,
        difficulty: Double = 0.0,
        at: Long = now
    ) = coordinator.grade(
        belief = belief, memory = memory, ability = ability, difficulty = difficulty,
        correct = correct, revealed = revealed, elapsedMillis = elapsed, now = at
    )

    @Test
    fun `an answer produces every model update at once`() {
        val verdict = grade(correct = true, elapsed = 8_000)
        assertEquals(ReviewGrade.GOOD, verdict.grade)
        assertTrue("the first review must create a memory state", FsrsScheduler.isReady(verdict.memory))
        assertNotNull("a first review must schedule the next one", verdict.dueAt)
        assertTrue("mastery must move on test evidence", verdict.knowledge.mastery > 0.0)
        assertEquals(MasterySource.TEST_EVIDENCE, verdict.knowledge.masterySource)
        assertTrue("the ability estimate must absorb the answer", verdict.ability.itemsAnswered == 1)
    }

    @Test
    fun `giving up and looking is practice, not evidence`() {
        val before = KnowledgeBelief.unseen(now)
        val verdict = grade(correct = false, revealed = true, belief = before)
        assertEquals("a revealed answer grades as a lapse", ReviewGrade.AGAIN, verdict.grade)
        assertEquals("mastery must be untouched by a revealed answer",
            before.mastery, verdict.knowledge.mastery, 1e-9)
        assertEquals("a revealed answer is not a test answer",
            before.totalAnswers, verdict.knowledge.totalAnswers)
        assertFalse("a revealed answer must never verify a word", verdict.knowledge.verifiedByTest)
        assertEquals("guided practice must not lower diagnostic ability", AbilityEstimator.initial, verdict.ability)
        assertNotNull("the memory model still records that the item was seen", verdict.dueAt)
    }

    @Test
    fun `how long the answer took changes the grade`() {
        assertEquals(ReviewGrade.EASY, grade(correct = true, elapsed = 1_500).grade)
        assertEquals(ReviewGrade.GOOD, grade(correct = true, elapsed = 8_000).grade)
        assertEquals(ReviewGrade.HARD, grade(correct = true, elapsed = 40_000).grade)
        assertEquals(ReviewGrade.AGAIN, grade(correct = false).grade)
    }

    @Test
    fun `a fast answer grades easier but is not treated as knowledge on its own`() {
        val fast = grade(correct = true, elapsed = 800)
        val normal = grade(correct = true, elapsed = 8_000)
        assertEquals(ReviewGrade.EASY, fast.grade)
        // FSRS does award more stability for EASY, which is correct: the answer really was effortless.
        assertTrue("EASY must schedule at least as far out as GOOD",
            fast.intervalDays!! >= normal.intervalDays!!)
        assertFalse("a single easy answer must not verify the word", fast.knowledge.verifiedByTest)
    }

    @Test
    fun `the next review time comes from the memory model, not a fixed table`() {
        // Same word, two different histories: the intervals must differ.
        val fresh = grade(correct = true, elapsed = 8_000)
        val wellKnown = grade(
            correct = true, elapsed = 8_000,
            memory = MemoryState(difficulty = 5.0, stability = 40.0, lastReview = now - 20L * 86_400_000L, reps = 6, lapses = 0)
        )
        assertTrue(
            "a well-established word must be scheduled further out (${fresh.intervalDays} vs ${wellKnown.intervalDays})",
            wellKnown.intervalDays!! > fresh.intervalDays!!
        )
        // And the interval must actually be reflected in the due date.
        val expectedDue = now + (wellKnown.intervalDays!! * MemoryState.MILLIS_PER_DAY).toLong()
        assertEquals(expectedDue, wellKnown.dueAt)
    }

    @Test
    fun `a lapse shortens the interval and is counted`() {
        val established = MemoryState(difficulty = 5.0, stability = 30.0, lastReview = now - 40L * 86_400_000L, reps = 5, lapses = 0)
        val failed = grade(correct = false, memory = established, elapsed = 12_000)
        assertEquals(ReviewGrade.AGAIN, failed.grade)
        assertEquals("a lapse must be counted", 1, failed.memory.lapses)
        assertTrue("stability must drop after a lapse", failed.memory.stability < established.stability)
    }

    @Test
    fun `target retention from the strategy changes the schedule`() {
        val efficient = coordinator.grade(
            belief = KnowledgeBelief.unseen(now), memory = MemoryState.unseen,
            ability = AbilityEstimator.initial, difficulty = 0.0, correct = true, revealed = false,
            elapsedMillis = 8_000, now = now, targetRetention = 0.85
        )
        val coverage = coordinator.grade(
            belief = KnowledgeBelief.unseen(now), memory = MemoryState.unseen,
            ability = AbilityEstimator.initial, difficulty = 0.0, correct = true, revealed = false,
            elapsedMillis = 8_000, now = now, targetRetention = 0.94
        )
        assertTrue("coverage mode must review sooner (${coverage.intervalDays} vs ${efficient.intervalDays})",
            coverage.intervalDays!! < efficient.intervalDays!!)
    }

    @Test
    fun `review priority follows predicted forgetting`() {
        val fresh = MemoryState(difficulty = 5.0, stability = 30.0, lastReview = now, reps = 3, lapses = 0)
        val stale = MemoryState(difficulty = 5.0, stability = 3.0, lastReview = now - 30L * 86_400_000L, reps = 3, lapses = 0)
        assertTrue("a stale item must outrank a fresh one",
            coordinator.reviewUrgency(stale, now) > coordinator.reviewUrgency(fresh, now))
        assertEquals("a never-reviewed item has no urgency", 0.0,
            coordinator.reviewUrgency(MemoryState.unseen, now), 1e-9)
    }

    @Test
    fun `the review reason states real retrievability`() {
        val memory = MemoryState(difficulty = 5.0, stability = 10.0, lastReview = now, reps = 3, lapses = 0)
        val reason = coordinator.reviewReason(memory, now + 10L * 86_400_000L)
        assertTrue("the reason must quote a probability: $reason", reason.contains("%"))
        assertTrue("the reason must quote stability: $reason", reason.contains("稳定度"))
        val unseenReason = coordinator.reviewReason(MemoryState.unseen, now)
        assertTrue("an unreviewed item must say so", unseenReason.contains("尚未复习"))
    }

    @Test
    fun `stored knowledge is mapped to a belief without inflating it`() {
        // A self-report is not evidence.
        val selfReported = BeliefMapper.from("KNOWN", attempts = 0, streak = 0, updatedAt = now, now = now)
        assertFalse(selfReported.verifiedByTest)
        assertEquals(MasterySource.SELF_REPORT, selfReported.masterySource)

        // A real streak is evidence, and only a full streak verifies.
        val twoCorrect = BeliefMapper.from("UNKNOWN", attempts = 2, streak = 2, updatedAt = now, now = now)
        val threeCorrect = BeliefMapper.from("UNKNOWN", attempts = 3, streak = 3, updatedAt = now, now = now)
        assertFalse(twoCorrect.verifiedByTest)
        assertTrue(threeCorrect.verifiedByTest)
        assertEquals(MasterySource.TEST_EVIDENCE, threeCorrect.masterySource)
        assertTrue(threeCorrect.mastery > twoCorrect.mastery)

        // Untouched rows stay unseen rather than being given a probability.
        val untouched = BeliefMapper.from("UNSEEN", attempts = 0, streak = 0, updatedAt = now, now = now)
        assertEquals(MasterySource.MODEL_PRIOR, untouched.masterySource)
        assertFalse(untouched.verifiedByTest)
    }
}
