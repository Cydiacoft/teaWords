package com.teameow.teawords.data

import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.data.search.DictionaryGateway
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class LearningBooksTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: DatabaseHelper
    @Before fun setup() {
        context.deleteDatabase("learning-books-test.db")
        helper = DatabaseHelper(context, "learning-books-test.db")
        helper.ensureSchema()
        assertTrue(DictionaryGateway.open(context))
    }
    @After fun close() { helper.close(); context.deleteDatabase("learning-books-test.db") }

    @Test fun allBooksAndSwitchingPreserveSharedProgress() {
        val source = DictionaryGateway.sourceOrNull()!!
        val catalog = LearningCatalog(helper, source)
        assertEquals(8, source.books().size)
        source.books().forEach { book ->
            val snapshot = catalog.load(setOf(book.id))
            assertEquals(book.name, book.wordCount, snapshot.states.size)
            assertTrue(snapshot.words.values.all { it.definition.isNotBlank() })
        }
        assertTrue(helper.getVocabulary().isEmpty())
        val ielts = source.learningWords(setOf("ielts"))
        val toefl = source.learningWords(setOf("toefl"))
        val shared = ielts.first { a -> toefl.any { it.wordId == a.wordId } }
        val known = Knowledge(shared.word, SelfReport.FUZZY, 2, 1, 36.0, 123456L, 42L)
        LearningRepository(helper).save(known, "screen")
        val both = catalog.load(setOf("ielts", "toefl"))
        assertEquals((ielts + toefl).map { it.wordId }.toSet().size, both.states.size)
        assertEquals(known, both.states.single { it.word == shared.word })
        assertEquals(known, catalog.load(setOf("toefl")).states.single { it.word == shared.word })
        catalog.load(emptySet())
        assertEquals(known, catalog.load(setOf("ielts")).states.single { it.word == shared.word })
    }

    @Test fun referenceSenseAndSingleEventUseCorrectWord() {
        val source = DictionaryGateway.sourceOrNull()!!
        val entry = source.learningWords(setOf("toefl")).last { it.zh.isNotBlank() }
        val senses = SenseRepository(helper)
        val (wordId, senseId) = senses.ensurePrimarySense(entry.word)
        assertEquals(entry.wordId, wordId)
        assertTrue(senseId > 0)
        assertEquals(wordId to senseId, senses.ensurePrimarySense(entry.word))
        senses.recordReview(wordId, senseId, entry.word, "RECALL", true, true, 5000, 10000,
            0, 5.0, 1.0, 20000, 0.1, 1, 0, null, null, 0.0, 0.1)
        LearningRepository(helper).save(Knowledge(entry.word, attempts = 1, due = 20000), "test", false, recordEvidence = false)
        assertEquals(0, senses.knowledgeFor(listOf(senseId))[senseId]!!.correctStreak)
        assertEquals(20000L, senses.memoryFor(senseId)!!.dueAt)
        helper.readableDatabase.rawQuery("SELECT word, sense_id FROM learning_events", null).use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals(entry.word, it.getString(0)); assertEquals(senseId, it.getLong(1))
        }
        assertEquals(1, LearningRepository(helper).newWordsSince(0))
        assertEquals(0, LearningRepository(helper).newWordsSince(10001))
        val pool = senses.diagnosticCandidates(listOf(entry.word), mapOf(entry.word to entry.tags))
        assertEquals(entry.wordId, pool.single().wordId)
        assertEquals(entry.word, pool.single().word)
    }
}
