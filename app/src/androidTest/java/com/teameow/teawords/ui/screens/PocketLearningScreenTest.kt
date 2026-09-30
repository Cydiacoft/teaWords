package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.teameow.teawords.data.*
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PocketLearningScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var helper: DatabaseHelper? = null
    private var name = ""
    private var paused = false
    private fun show(round: PocketRound) {
        name = "pocket-ui-${System.nanoTime()}.db"
        val database = DatabaseHelper(compose.activity, name)
        helper = database
        database.ensureSchema()
        LearningRepository(database).importWords(listOf(ImportedWord("apple", "苹果"), ImportedWord("book", "书")), "test", "test")
        PocketLearningRepository(database).start(round)
        compose.setContent {
            var current by remember { mutableStateOf(round) }
            TeaWordsTheme {
                PocketLearningScreen(database, current, .9, { current = it }, { paused = true }, {})
            }
        }
    }
    @After fun clean() { helper?.close(); if (name.isNotEmpty()) compose.activity.deleteDatabase(name) }

    @Test fun answerIsSavedBeforeFeedbackAndPause() {
        show(PocketRound("choice", listOf(
            PocketTask("apple", "苹果", PocketKind.MEANING, listOf("苹果", "书")),
            PocketTask("book", "书", PocketKind.MEANING, listOf("苹果", "书")))))
        compose.onNodeWithText("苹果", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.done.size == 1 }
        compose.onNodeWithText("答对了！apple · 苹果").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("暂停，稍后继续"))
        compose.onNodeWithText("暂停，稍后继续").performClick()
        assertTrue(paused)
        assertEquals(1, PocketLearningRepository(helper!!).load()!!.position)
    }

    @Test fun matchingAcceptsAnyUnfinishedPair() {
        show(PocketRound("match", listOf(
            PocketTask("apple", "苹果", PocketKind.MATCH, group = 1),
            PocketTask("book", "书", PocketKind.MATCH, group = 1))))
        compose.onNodeWithText("book", useUnmergedTree = true).performClick()
        compose.onNodeWithText("书", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.done == setOf(1) }
        compose.onNodeWithText("继续").assertDoesNotExist()
        compose.onNodeWithText("把单词和词义连起来").assertIsDisplayed()
        compose.onNodeWithText("apple", useUnmergedTree = true).performClick()
        compose.onNodeWithText("苹果", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.complete }
        compose.onNodeWithText("看看这一轮").performClick()
        compose.onNodeWithText("这一小轮完成了").assertIsDisplayed()
    }

    @Test fun matchingShowsAllMistakesOnlyAfterEntireGroup() {
        show(PocketRound("wrong-match", listOf(
            PocketTask("apple", "苹果", PocketKind.MATCH, group = 1),
            PocketTask("book", "书", PocketKind.MATCH, group = 1))))
        compose.onNodeWithText("apple", useUnmergedTree = true).performClick()
        compose.onNodeWithText("书", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.done == setOf(0) }
        compose.onNodeWithText("继续").assertDoesNotExist()
        compose.onNodeWithText("book", useUnmergedTree = true).performClick()
        compose.onNodeWithText("苹果", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.complete }
        compose.onNode(hasText("0 / 2 组正确", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("正确对应：苹果", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("正确对应：书", substring = true)).assertIsDisplayed()
    }

    @Test fun missingAudioCanChangeToMeaningWithoutCountingAWrongAnswer() {
        show(PocketRound("no-audio", listOf(
            PocketTask("apple", "苹果", PocketKind.LISTEN, listOf("apple", "book")),
            PocketTask("book", "书", PocketKind.MEANING, listOf("书", "苹果")))))
        compose.onNodeWithText("不方便听音，改做词义题").performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.tasks[0].kind == PocketKind.MEANING }
        assertTrue(PocketLearningRepository(helper!!).load()!!.done.isEmpty())
        assertEquals(0, LearningRepository(helper!!).totals().tests)
        compose.onNodeWithText("苹果", useUnmergedTree = true).assertIsEnabled()
    }

    @Test fun spellingAcceptsOnlyTheMissingLetters() {
        show(PocketRound("spelling", listOf(PocketTask("apple", "苹果", PocketKind.SPELLING, prompt = "a_p_e"))))
        compose.onNode(hasSetTextAction()).performTextInput("PL")
        compose.onNodeWithText("检查拼写").performClick()
        compose.waitUntil(5000) { PocketLearningRepository(helper!!).load()!!.complete }
        compose.onNodeWithText("答对了！apple · 苹果").assertIsDisplayed()
        assertEquals(1, PocketLearningRepository(helper!!).load()!!.correct)
    }
}
