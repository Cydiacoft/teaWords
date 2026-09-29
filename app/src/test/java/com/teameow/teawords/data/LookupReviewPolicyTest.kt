package com.teameow.teawords.data

import org.junit.Assert.*
import org.junit.Test

class LookupReviewPolicyTest {
    private fun record(text: String, kind: String = "词典", headword: String? = null) =
        QueryRecord(1, text, kind, "en↔zh", 0, headword)

    @Test fun sentencesAndTranslationsAreExcluded() {
        assertEquals(listOf("apple", "ice-cream"), LookupReviewPolicy.headwords(listOf(
            record("Apple"), record("I like apples."), record("banana", "翻译"),
            record("苹果"), record("ice-cream"), record("123"), record("")
        )))
    }

    @Test fun canonicalWordSupportsChineseAndInflectedLookupWithoutDuplicates() {
        assertEquals(listOf("apple", "run"), LookupReviewPolicy.headwords(listOf(
            record("苹果", headword = "apple"), record(" Apple "), record("running", headword = "run"),
            record("run"), record("一句话", "翻译", "sentence")
        )))
    }

    @Test fun dueReviewsPrecedeNewAndUnverifiedWords() {
        val rows = listOf(Knowledge("fresh"), Knowledge("practice", attempts = 1, due = 200),
            Knowledge("due", attempts = 4, streak = 3, due = 90),
            Knowledge("earlier", attempts = 2, due = 80), Knowledge("mastered", attempts = 3, streak = 3, due = 200))
        assertEquals(listOf("earlier", "due", "fresh", "practice"), LookupReviewPolicy.queue(rows, 100, 20).map { it.word })
    }

    @Test fun limitsAndRepeatedWordsDoNotDuplicatePractice() {
        val rows = listOf(Knowledge("apple"), Knowledge("apple"), Knowledge("banana"))
        assertEquals(listOf("apple"), LookupReviewPolicy.queue(rows, 100, 1).map { it.word })
        assertEquals(2, LookupReviewPolicy.queue(rows, 100, 20).size)
        assertTrue(LookupReviewPolicy.queue(rows, 100, -1).isEmpty())
    }
}
