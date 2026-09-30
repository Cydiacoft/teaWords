package com.teameow.teawords.data

import org.junit.Assert.*
import org.junit.Test

class PocketPlannerLimitTest {
    private val words = (1..15).associate { index ->
        val word = "sample${('a'.code + index).toChar()}"
        word to VocabularyItem(word = word, definition = "释义 $index", phonetic = null, timestamp = 0)
    }
    private val fresh = words.keys.map { Knowledge(it, SelfReport.UNSEEN) }
    @Test fun chosenWordCountCanBeSmallerOrLargerThanFive() {
        for (limit in listOf(1, 3, 8, 12)) {
            assertEquals(limit, PocketPlanner.build(fresh, words, 15, 100, wordLimit = limit)!!.wordCount)
        }
    }
    @Test fun dueReviewsComeFirstAndFreshWordsRespectDailyBudget() {
        val states = fresh.mapIndexed { index, state -> if (index < 2) state.copy(attempts = 1, due = 1) else state }
        val round = PocketPlanner.build(states, words, 3, 100, wordLimit = 12)!!
        assertEquals(5, round.wordCount)
        assertTrue(round.tasks.map { it.word }.containsAll(states.take(2).map { it.word }))
        assertEquals(1, PocketPlanner.build(states, words, 3, 100, wordLimit = 1)!!.wordCount)
    }
}
