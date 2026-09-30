package com.teameow.teawords.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.teameow.teawords.algorithm.*
import org.junit.Assert.*
import org.junit.Test

class PocketLearningTest {
    private val ability = AbilityEstimate(0.0, 1.0, 0)
    private fun withDb(test: (DatabaseHelper) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pocket-${System.nanoTime()}.db"
        val helper = DatabaseHelper(context, name)
        try {
            helper.ensureSchema()
            LearningRepository(helper).importWords(listOf(ImportedWord("apple", "苹果"), ImportedWord("book", "书")), "test", "test")
            test(helper)
        } finally { helper.close(); context.deleteDatabase(name) }
    }
    private fun round() = PocketRound("round", listOf(
        PocketTask("apple", "苹果", PocketKind.MEANING, listOf("苹果", "书")),
        PocketTask("apple", "苹果", PocketKind.LISTEN, listOf("apple", "book")),
        PocketTask("apple", "苹果", PocketKind.MEANING, listOf("苹果", "书"))
    ))

    @Test fun checkpointAndChoicesSurviveReopeningRepository() = withDb { helper ->
        val repo = PocketLearningRepository(helper)
        repo.start(round())
        val saved = repo.answer("round", 0, true, false, 500, ability, .9)
        assertEquals(saved, PocketLearningRepository(helper).load())
        assertEquals(1, saved.position)
        assertEquals(listOf("apple", "book"), saved.tasks[1].options)
    }

    @Test fun choiceAnswersDoNotVerifyRecallOrCountAsThreeSpacedReviews() = withDb { helper ->
        val repo = PocketLearningRepository(helper)
        repo.start(round())
        repeat(3) { repo.answer("round", it, true, false, 500, ability, .9) }
        val knowledge = LearningRepository(helper).states().first { it.word == "apple" }
        assertEquals(3, knowledge.attempts)
        assertEquals(0, knowledge.streak)
        assertFalse(knowledge.verified)
        val senses = SenseRepository(helper)
        val ids = senses.ensurePrimarySense("apple")
        assertEquals(1, senses.memoryFor(ids.second)!!.reps)
        assertEquals(0, senses.verifiedSenseCount())
        assertTrue(knowledge.due > System.currentTimeMillis())
        assertEquals(knowledge.due, senses.memoryFor(ids.second)!!.dueAt)
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM learning_events WHERE mode LIKE 'POCKET_%'", null).use {
            it.moveToFirst(); assertEquals(3, it.getInt(0))
        }
    }

    @Test fun failingCheckpointRollsBackAnswerEvidenceAndKnowledge() = withDb { helper ->
        val repo = PocketLearningRepository(helper)
        repo.start(round())
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_checkpoint BEFORE INSERT ON pocket_round BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
        try { repo.answer("round", 0, true, false, 500, ability, .9); fail("Expected rollback") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(0, repo.load()!!.position)
        assertEquals(0, LearningRepository(helper).states().first { it.word == "apple" }.attempts)
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM learning_events WHERE mode LIKE 'POCKET_%'", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun duplicateTapCannotAdvanceOrRecordTwice() = withDb { helper ->
        val repo = PocketLearningRepository(helper)
        repo.start(round())
        val saved = repo.answer("round", 0, true, false, 500, ability, .9)
        try { repo.answer("round", 0, true, false, 500, ability, .9); fail("Expected duplicate rejection") }
        catch (_: IllegalStateException) { }
        assertEquals(saved, repo.load())
        assertEquals(1, LearningRepository(helper).states().first { it.word == "apple" }.attempts)
    }

    @Test fun intermediateActivityPreservesPreviousReviewClock() = withDb { helper ->
        val learning = LearningRepository(helper)
        val state = learning.states().first { it.word == "apple" }
        val senses = SenseRepository(helper)
        val ids = senses.ensurePrimarySense("apple")
        val previous = System.currentTimeMillis() - 3 * 86_400_000L
        val verdict = ReviewCoordinator().grade(KnowledgeBelief.unseen(previous), MemoryState.unseen,
            ability, 0.0, true, false, 5000, previous)
        learning.saveReview(state, verdict, ids.first, ids.second, true, false, 5000, previous, ability.theta)
        val repo = PocketLearningRepository(helper)
        repo.start(round())
        repo.answer("round", 0, true, false, 500, ability, .9)
        assertEquals(previous, senses.memoryFor(ids.second)!!.lastReview)
        assertEquals(1, senses.memoryFor(ids.second)!!.reps)
    }

    @Test fun studyingCountsTowardNewWordBudgetWithoutBecomingAWrongAnswer() = withDb { helper ->
        val repo = PocketLearningRepository(helper)
        repo.start(PocketRound("study", listOf(PocketTask("apple", "苹果", PocketKind.STUDY, taught = true))))
        repo.answer("study", 0, false, false, 1000, ability, .9)
        val learning = LearningRepository(helper)
        assertEquals(0, learning.totals().tests)
        assertEquals(1, learning.newWordsSince(0))
        assertEquals(1, learning.activeDays().size)
    }

    @Test fun readingDoesNotErasePreviouslyVerifiedRecall() = withDb { helper ->
        val learning = LearningRepository(helper)
        val senses = SenseRepository(helper)
        val ids = senses.ensurePrimarySense("apple")
        repeat(3) {
            val state = learning.states().first { it.word == "apple" }
            val now = System.currentTimeMillis()
            val verdict = ReviewCoordinator().grade(KnowledgeBelief.unseen(now), MemoryState.unseen,
                ability, 0.0, true, false, 5000, now)
            learning.saveReview(state, verdict, ids.first, ids.second, true, false, 5000, now, ability.theta)
        }
        val repo = PocketLearningRepository(helper)
        repo.start(PocketRound("read", listOf(PocketTask("apple", "苹果", PocketKind.STUDY, taught = true))))
        repo.answer("read", 0, false, false, 1000, ability, .9)
        assertEquals(3, learning.states().first { it.word == "apple" }.streak)
        assertEquals(1, senses.verifiedSenseCount())
    }

    @Test fun plannerRespectsBudgetAndUsesRealExamplesAndDistinctOptions() {
        val definitions = linkedMapOf("apple" to "苹果", "book" to "书", "friend" to "朋友", "house" to "房子", "happy" to "快乐", "issue" to "问题")
        val words = definitions.mapValues { VocabularyItem(word = it.key, definition = it.value, phonetic = null, timestamp = 0) }
        val states = words.keys.map { Knowledge(it, SelfReport.UNSEEN) }
        assertNull(PocketPlanner.build(states, words, 0, 100))
        val round = PocketPlanner.build(states, words, 5, 100)!!
        assertEquals(5, round.wordCount)
        assertTrue(round.tasks.any { it.kind == PocketKind.LISTEN })
        assertTrue(round.tasks.any { it.kind == PocketKind.MATCH })
        assertTrue(round.tasks.any { it.kind == PocketKind.SPELLING })
        val cloze = round.tasks.first { it.kind == PocketKind.CLOZE }
        assertEquals(OfflineQuestionBank().getQuestionByWord(cloze.word)!!.blankedSentence, cloze.prompt)
        round.tasks.filter { it.options.isNotEmpty() }.forEach {
            assertEquals(1, it.options.count { option -> option == it.answer })
            assertEquals(it.options.size, it.options.distinct().size)
        }
        val due = states.mapIndexed { i, state -> state.copy(attempts = 1, due = if (i == 0) 1 else 1000) }
        assertEquals(listOf("apple"), PocketPlanner.build(due, words, 0, 100)!!.tasks.map { it.word }.distinct())
    }

    @Test fun randomSpellingGapsStayStableAfterSavingRound() = withDb { helper ->
        val word = "apple"
        val prompt = PocketPlanner.spellingPrompt(word)
        val task = PocketTask(word, "苹果", PocketKind.SPELLING, prompt = prompt)
        assertTrue(task.answer.isNotEmpty())
        assertTrue(prompt.any { it == '_' })
        assertEquals(word, prompt.mapIndexed { index, char -> if (char == '_') word[index] else char }.joinToString(""))
        val repo = PocketLearningRepository(helper)
        repo.start(PocketRound("spell", listOf(task)))
        assertEquals(task, repo.load()!!.tasks.first())
    }

    @Test fun partiallyConnectedWrongPairSurvivesResume() = withDb { helper ->
        val repo = PocketLearningRepository(helper)
        repo.start(PocketRound("pairs", listOf(
            PocketTask("apple", "苹果", PocketKind.MATCH, group = 1),
            PocketTask("book", "书", PocketKind.MATCH, group = 1))))
        repo.answer("pairs", 0, false, false, 1000, ability, .9, pairedTo = 1)
        val loaded = PocketLearningRepository(helper).load()!!
        assertEquals(mapOf(0 to 1), loaded.pairings)
        assertEquals(setOf(0), loaded.missed)
        assertEquals(1, loaded.position)
        repo.answer("pairs", 1, false, false, 1000, ability, .9, pairedTo = 0)
        assertTrue(repo.load()!!.complete)
        assertEquals(setOf(0, 1), repo.load()!!.missed)
    }
}
