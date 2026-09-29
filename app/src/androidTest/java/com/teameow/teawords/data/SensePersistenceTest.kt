package com.teameow.teawords.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the schema v6 write path on a real device database.
 *
 * This test exists because the same failures were invisible from the outside: a missing column made
 * every insert fail while the UI still looked healthy, and the fix (`ensureSchema`) has to run for an
 * existing database whose version number already matches.
 */
@RunWith(AndroidJUnit4::class)
class SensePersistenceTest {

    private lateinit var helper: DatabaseHelper
    private lateinit var senses: SenseRepository
    private lateinit var dbFile: File

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        dbFile = context.getDatabasePath("v6test.db")
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        helper = DatabaseHelper(context, "v6test.db")
        helper.ensureSchema()
        senses = SenseRepository(helper)
    }

    @After
    fun tearDown() {
        helper.close()
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    private fun seedWord(word: String, zh: String): Long {
        val dictionary = LocalDictionary(helper)
        dictionary.upsert(word, zh, source = "测试")
        return dictionary.exact(word)!!.id
    }

    @Test
    fun everyColumnTheWritePathNeedsExistsAfterEnsureSchema() {
        val db = helper.writableDatabase
        val eventColumns = db.rawQuery("PRAGMA table_info(learning_events)", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        assertTrue("learning_events must carry the evidence columns",
            eventColumns.containsAll(listOf("sense_id", "mode", "grade", "revealed")))

        val recordColumns = db.rawQuery("PRAGMA table_info(test_records)", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        assertTrue("test_records must carry grade: the insert names it",
            recordColumns.contains("grade"))
        assertTrue(recordColumns.containsAll(listOf("ability_before", "ability_after", "mastery_after")))
    }

    @Test
    fun ensureSchemaIsIdempotent() {
        // Called on every launch, so running it repeatedly must be harmless.
        val wordId = seedWord("able", "能够的")
        senses.deriveMissingSenseUnits()
        val before = senses.sensesFor(wordId)
        repeat(3) { helper.ensureSchema() }
        assertEquals(before, senses.sensesFor(wordId))
        assertTrue(before.isNotEmpty())
    }

    @Test fun diagnosticAnswersRecordEvidenceWithoutChangingRecallKnowledge() {
        val wordId = seedWord("issue", "问题")
        senses.deriveMissingSenseUnits()
        val sense = senses.sensesFor(wordId).first()
        val candidate = SenseRepository.Candidate(wordId, "issue", sense.id, sense.text, sense.confidence, "", 0)
        repeat(3) { senses.recordDiagnostic(candidate, true, 1000, 2000L + it, 0.0, 0.1, 0.0) }
        assertNull(senses.knowledgeFor(listOf(sense.id))[sense.id])
        assertEquals(3, senses.evidenceByMode()[SenseRepository.MODE_DIAGNOSTIC])
        assertEquals(3, LearningRepository(helper).totals().tests)
        assertEquals(0, LearningRepository(helper).newWordsSince(0))
    }

    @Test
    fun senseUnitsAreDerivedFromTheGloss() {
        val wordId = seedWord("issue", "问题；发行")
        val added = senses.deriveMissingSenseUnits()
        assertTrue("at least this word must produce units", added >= 2)
        val units = senses.sensesFor(wordId)
        assertEquals(2, units.size)
        assertEquals("问题", units[0].text)
        assertEquals("发行", units[1].text)
        assertTrue("split senses carry the higher confidence", units.all { it.confidence >= 0.6 })
        // Idempotent: a second pass adds nothing.
        senses.deriveMissingSenseUnits()
        assertEquals(2, senses.sensesFor(wordId).size)
    }

    @Test
    fun aReviewWritesBothTheMemoryStateAndTheEvidence() {
        val wordId = seedWord("abandon", "放弃")
        senses.deriveMissingSenseUnits()
        val senseId = senses.sensesFor(wordId).first().id
        val now = System.currentTimeMillis()

        senses.recordReview(
            wordId = wordId, senseId = senseId, word = "abandon", mode = "RECALL",
            correct = true, revealed = false, elapsedMillis = 6_000, now = now,
            grade = 2, difficulty = 5.1, stability = 3.7, dueAt = now + 86_400_000L,
            intervalDays = 3.7, reps = 1, lapses = 0,
            masteryBefore = 0.3, masteryAfter = 0.62, abilityBefore = 0.0, abilityAfter = 0.35
        )

        val knowledge = senses.knowledgeFor(listOf(senseId))[senseId]
        assertNotNull("the sense must have knowledge after a review", knowledge)
        assertEquals(1, knowledge!!.attempts)
        assertEquals(1, knowledge.correctStreak)

        val memory = senses.memoryFor(senseId)
        assertNotNull("the FSRS memory state must be stored", memory)
        assertEquals(5.1, memory!!.difficulty, 1e-6)
        assertEquals(3.7, memory.stability, 1e-6)
        assertEquals(1, memory.reps)
        assertTrue("a due time must be scheduled", memory.dueAt > now)

        assertEquals("the answer must be replayable", 1, senses.replayableRecordCount())
        assertEquals("it must carry a grade", 1, senses.gradeHistogram().size)
        assertEquals(1, senses.evidenceByMode()["RECALL"])
    }

    @Test
    fun aRevealedAnswerIsRecordedButDoesNotVerify() {
        val wordId = seedWord("vague", "模糊的")
        senses.deriveMissingSenseUnits()
        val senseId = senses.sensesFor(wordId).first().id
        val now = System.currentTimeMillis()

        senses.recordReview(
            wordId = wordId, senseId = senseId, word = "vague", mode = "RECALL",
            correct = false, revealed = true, elapsedMillis = 9_000, now = now,
            grade = 0, difficulty = 5.0, stability = 0.5, dueAt = now + 3_600_000L,
            intervalDays = 0.04, reps = 1, lapses = 1,
            masteryBefore = 0.3, masteryAfter = 0.3, abilityBefore = 0.0, abilityAfter = 0.0
        )

        val knowledge = senses.knowledgeFor(listOf(senseId))[senseId]!!
        assertEquals(1, knowledge.attempts)
        assertEquals("looking at the answer must not build a streak", 0, knowledge.correctStreak)
        assertFalse("a revealed answer must not verify the sense", knowledge.verified)
        assertEquals(1, senses.evidenceByMode()["RECALL"])
    }

    @Test
    fun knowledgeSurvivesReopeningTheDatabase() {
        val wordId = seedWord("persist", "坚持")
        senses.deriveMissingSenseUnits()
        val senseId = senses.sensesFor(wordId).first().id
        val now = System.currentTimeMillis()
        senses.recordReview(
            wordId = wordId, senseId = senseId, word = "persist", mode = "RECALL",
            correct = true, revealed = false, elapsedMillis = 5_000, now = now,
            grade = 2, difficulty = 5.0, stability = 2.0, dueAt = now + 172_800_000L,
            intervalDays = 2.0, reps = 1, lapses = 0,
            masteryBefore = 0.3, masteryAfter = 0.6, abilityBefore = 0.0, abilityAfter = 0.3
        )
        helper.close()

        // A fresh helper over the same file: this is what "restart the app" does.
        helper = DatabaseHelper(context, "v6test.db")
        helper.ensureSchema()
        val reopened = SenseRepository(helper)
        val knowledge = reopened.knowledgeFor(listOf(senseId))[senseId]
        assertNotNull("knowledge must survive a restart", knowledge)
        assertEquals(1, knowledge!!.attempts)
        assertEquals(2.0, reopened.memoryFor(senseId)!!.stability, 1e-6)
        assertTrue(reopened.dueSenses(now + 200_000_000L).any { it.senseId == senseId })
    }
}
