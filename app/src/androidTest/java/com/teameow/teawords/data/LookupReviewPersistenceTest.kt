package com.teameow.teawords.data

import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.algorithm.*
import com.teameow.teawords.data.search.ShippedDictionarySource
import org.junit.Assert.*
import org.junit.Test

class LookupReviewPersistenceTest {
    private fun database(test: (DatabaseHelper, LocalDictionary) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lookup-review-${System.nanoTime()}.db"
        val helper = DatabaseHelper(context, name)
        try { helper.ensureSchema(); test(helper, LocalDictionary(helper, ShippedDictionarySource.localOnly)) }
        finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun reviewUsesQueriedWordsOnlyIncludingCanonicalChineseAndLegacyRecords() = database { helper, dictionary ->
        dictionary.upsert("apple", "苹果")
        dictionary.upsert("banana", "香蕉")
        dictionary.upsert("unqueried", "没查过的词")
        dictionary.record("苹果", "词典", "zh→en", headword = "apple")
        dictionary.record(" Apple ", "词典", "en↔zh")
        dictionary.record("I like apples.", "词典", "en↔zh")
        dictionary.record("unqueried", "翻译", "en→zh")
        helper.addHistory("banana", "香蕉")
        helper.addVocabulary("unqueried", "", "没查过的词")
        val repo = LookupReviewRepository(helper, dictionary)
        assertEquals(setOf("apple", "banana"), repo.entries().map { it.word }.toSet())
        assertEquals(2, repo.queue(repo.entries(), 100, 20).size)
        assertEquals(0, LearningRepository(helper).totals().tests)
        assertEquals(0, dictionary.knowledge("apple").attempts)
    }

    @Test fun olderWordsSurviveRecentDisplayLimit() = database { helper, dictionary ->
        dictionary.upsert("apple", "苹果")
        dictionary.record("apple", "词典", "en↔zh")
        repeat(205) { dictionary.record("Sentence number $it.", "翻译", "en→zh") }
        assertEquals(100, dictionary.history().size)
        assertEquals(listOf("apple"), LookupReviewRepository(helper, dictionary).entries().map { it.word })
    }

    @Test fun additiveHistoryMigrationPreservesExistingQueries() = database { helper, dictionary ->
        helper.writableDatabase.apply {
            execSQL("DROP TABLE query_history")
            execSQL("CREATE TABLE query_history(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, kind TEXT NOT NULL, direction TEXT NOT NULL, word_id INTEGER, timestamp INTEGER NOT NULL)")
            execSQL("INSERT INTO query_history(text,kind,direction,timestamp) VALUES('apple','词典','en↔zh',1000)")
        }
        helper.ensureSchema()
        helper.ensureSchema()
        val record = dictionary.history().single()
        assertEquals("apple", record.text)
        assertNull(record.headword)
        assertEquals(listOf("apple"), dictionary.reviewHeadwords())
    }

    private fun save(helper: DatabaseHelper, dictionary: LocalDictionary): Knowledge {
        dictionary.upsert("teatestword", "测试词")
        val (wordId, senseId) = SenseRepository(helper).ensurePrimarySense("teatestword")
        val state = dictionary.knowledge("teatestword")
        val verdict = ReviewCoordinator().grade(BeliefMapper.from(state.report.name, 0, 0, 0, 1000),
            MemoryState.unseen, AbilityEstimator.initial, 0.5, true, false, 2300, 1000)
        return LearningRepository(helper).saveReview(state, verdict, wordId, senseId, true, false, 2300, 1000, 0.0)
    }

    @Test fun recallWritesOneEvidenceEventAndSharedKnowledgeWithoutFavoriting() = database { helper, dictionary ->
        val saved = save(helper, dictionary)
        assertEquals(saved, dictionary.knowledge("teatestword"))
        assertEquals(1, saved.attempts)
        assertEquals(1, saved.streak)
        assertEquals(1, LearningRepository(helper).totals().tests)
        val sense = SenseRepository(helper).ensurePrimarySense("teatestword").second
        assertEquals(1, SenseRepository(helper).knowledgeFor(listOf(sense)).getValue(sense).attempts)
        assertNotNull(SenseRepository(helper).memoryFor(sense))
        assertFalse(helper.isInVocabulary("teatestword"))
    }

    @Test fun failedAnswerRollsBackEvidenceKnowledgeAndMemory() = database { helper, dictionary ->
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_event BEFORE INSERT ON learning_events BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
        try { save(helper, dictionary); fail("The answer must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        val sense = SenseRepository(helper).ensurePrimarySense("teatestword").second
        assertEquals(0, dictionary.knowledge("teatestword").attempts)
        assertTrue(SenseRepository(helper).knowledgeFor(listOf(sense)).isEmpty())
        assertNull(SenseRepository(helper).memoryFor(sense))
        assertEquals(0, LearningRepository(helper).totals().tests)
    }
}
