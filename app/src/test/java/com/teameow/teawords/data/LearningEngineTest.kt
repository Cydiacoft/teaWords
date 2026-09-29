package com.teameow.teawords.data

import org.junit.Assert.*
import org.junit.Test

class LearningEngineTest {
    private val now = 1_800_000_000_000L
    @Test fun newWordBudgetDoesNotHideDueReviews() {
        val rows = listOf(Knowledge("due", attempts = 2, due = 0), Knowledge("fresh", SelfReport.UNKNOWN))
        assertEquals(listOf("due"), LearningEngine.queue(rows, 1000, 10, newLimit = 0).map { it.word })
        assertEquals(listOf("due", "fresh"), LearningEngine.queue(rows, 1000, 10, newLimit = 1).map { it.word })
    }

    @Test fun knownMajorityIsNotForcedIntoNewLearning() {
        val states = (1..1000).map { Knowledge("word$it", if (it <= 900) SelfReport.KNOWN else SelfReport.UNKNOWN, updated = now) }
        val queue = LearningEngine.queue(states, now)
        assertEquals(20, queue.size)
        assertTrue(queue.all { it.report == SelfReport.UNKNOWN })
    }
    @Test fun sampleKnownWordsOnlyAfterSevenDaysAndAtMostOne() {
        val states = (1..100).map { Knowledge("word$it", SelfReport.KNOWN, updated = now) }
        assertTrue(LearningEngine.queue(states, now).isEmpty())
        assertEquals(1, LearningEngine.queue(states, now + 8 * 86400000L).size)
    }
    @Test fun failuresResetEvidenceAndShortenInterval() {
        var state = Knowledge("issue", SelfReport.FUZZY)
        repeat(3) { state = LearningEngine.answer(state, true, state.due.coerceAtLeast(now)) }
        assertTrue(state.verified)
        val failed = LearningEngine.answer(state, false, state.due)
        assertFalse(failed.verified)
        assertTrue(failed.intervalHours < state.intervalHours)
        assertEquals(state.due + 600000, failed.due)
    }
    @Test fun futureReviewsAndUnscreenedWordsAreExcluded() {
        val future = LearningEngine.answer(Knowledge("issue", SelfReport.UNKNOWN), true, now)
        assertTrue(LearningEngine.queue(listOf(future, Knowledge("other")), now).isEmpty())
        assertEquals("issue", LearningEngine.queue(listOf(future), future.due).single().word)
    }
    @Test fun fuzzyWordsHavePriorityAndSingleSuccessIsNotMastery() {
        val queue = LearningEngine.queue(listOf(Knowledge("a", SelfReport.UNKNOWN), Knowledge("z", SelfReport.FUZZY)), now)
        assertEquals("z", queue.first().word)
        assertFalse(LearningEngine.answer(queue.first(), true, now).verified)
    }
    @Test fun csvAndJsonNormalizeAndDeduplicate() {
        assertEquals(listOf(ImportedWord("issue", "问题,发行")), WordImport.parse("word,definition\nIssue,\"问题,发行\"\nissue,问题"))
        assertEquals("ability", WordImport.parse("[{\"word\":\"Ability\",\"definition\":\"能力\"}]").single().word)
        assertEquals("能力", WordImport.parse("ability 能力").single().definition)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidImportIsRejected() { WordImport.parse("nothing") }
}
