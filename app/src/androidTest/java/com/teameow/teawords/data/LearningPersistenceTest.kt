package com.teameow.teawords.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LearningPersistenceTest {
    @Test fun undoSelfReportRestoresFuzzyWordAndKeepsEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "learning-undo-${System.nanoTime()}.db"
        val helper = DatabaseHelper(context, name)
        try {
            val repo = LearningRepository(helper)
            repo.importWords(listOf(ImportedWord("issue", "问题")), "test", "test")
            repo.save(Knowledge("issue", SelfReport.KNOWN, streak = 2, due = 9000, updated = 1000), "screen")
            repo.revertSelfReport("issue", 2000)
            val restored = repo.states().single()
            assertEquals(SelfReport.FUZZY, restored.report)
            assertEquals(0, restored.streak)
            assertEquals(0L, restored.due)
            assertEquals(2000L, restored.updated)
            assertEquals(1, repo.totals().screened)
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun failedEvidenceWriteRollsBackKnowledgeState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "learning-atomic-${System.nanoTime()}.db"
        val helper = DatabaseHelper(context, name)
        try {
            val repo = LearningRepository(helper)
            repo.importWords(listOf(ImportedWord("issue", "问题")), "test", "test")
            val old = Knowledge("issue", SelfReport.FUZZY, updated = 1000)
            repo.save(old, "screen")
            helper.writableDatabase.execSQL("CREATE TRIGGER fail_event BEFORE INSERT ON learning_events BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END")
            try {
                repo.save(LearningEngine.answer(old, true, 2000), "test", true)
                fail("The write must fail")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(old, repo.states().single())
            assertEquals(0, repo.totals().tests)
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun evidenceScheduleAndImportSurviveReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "learning-test-${System.nanoTime()}.db"
        var helper = DatabaseHelper(context, name)
        try {
            val repo = LearningRepository(helper)
            assertEquals(1, repo.importWords(listOf(ImportedWord("issue", "问题")), "test", "test"))
            val known = Knowledge("issue", SelfReport.KNOWN, updated = 1000)
            repo.save(known, "screen")
            val tested = LearningEngine.answer(known, false, 2000)
            repo.save(tested, "test", false, 2300)
            helper.close()
            helper = DatabaseHelper(context, name)
            val reopened = LearningRepository(helper)
            assertEquals(tested, reopened.states().single())
            assertEquals(1, reopened.totals().tests)
            assertEquals(2L, reopened.totals().seconds)
            assertEquals(0, reopened.importWords(listOf(ImportedWord("issue", "发行")), "other", "test"))
            assertEquals("问题", helper.getVocabulary().single().definition)
            assertEquals(tested, reopened.states().single())
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTwoMigrationPreservesVocabularyAndHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "learning-migration-${System.nanoTime()}.db"
        var helper = DatabaseHelper(context, name)
        try {
            helper.addVocabulary("retain", "", "保留")
            helper.addHistory("retain", "保留")
            helper.writableDatabase.apply {
                execSQL("DROP TABLE learning_knowledge")
                execSQL("DROP TABLE learning_events")
                execSQL("DROP TABLE learning_sources")
                version = 2
            }
            helper.close()
            helper = DatabaseHelper(context, name)
            assertEquals("retain", helper.getVocabulary().single().word)
            assertEquals("retain", helper.getHistory().single().word)
            assertEquals(SelfReport.UNSEEN, LearningRepository(helper).states().single().report)
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
