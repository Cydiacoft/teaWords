package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.teameow.teawords.ui.LookupViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LookupNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editingAutomaticallySwitchesModesAndSharesDraft() {
        lateinit var model: LookupViewModel
        var shared = "school"
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LookupViewModel::class.java] }
        compose.setContent {
            MaterialTheme { LookupScreen("school", external = true, onBack = {}, onInputChange = { shared = it }, model = model) }
        }
        compose.waitUntil(30_000) { !model.state.value.busy && model.state.value.selected?.word == "school" }
        val paragraph = "This is a long paragraph for translation.\nIt should keep its line breaks and switch automatically."
        compose.onNode(hasSetTextAction()).performTextReplacement(paragraph)
        compose.runOnIdle {
            assertEquals(false, model.state.value.dictionaryMode)
            assertEquals(paragraph, model.state.value.input)
            assertEquals(paragraph, shared)
        }
        compose.onNodeWithText("查词与翻译").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("apple")
        compose.runOnIdle { assertEquals(true, model.state.value.dictionaryMode); assertEquals("apple", shared) }
        compose.onNodeWithContentDescription("查询").assertIsDisplayed()
    }

    @Test fun restoredPageConsumesNewRequestsIncludingRepeatedHomeWord() {
        lateinit var model: LookupViewModel
        var visible by mutableStateOf(true)
        var query by mutableStateOf("link")
        var request by mutableStateOf(1L)
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[LookupViewModel::class.java]
        }
        compose.setContent {
            val pages = rememberSaveableStateHolder()
            MaterialTheme {
                if (visible) pages.SaveableStateProvider("result") {
                    LookupScreen(query, external = true, onBack = { visible = false }, requestId = request, model = model)
                }
            }
        }
        fun awaitWord(word: String) {
            compose.waitUntil(30_000) { !model.state.value.busy && model.state.value.selected?.word == word }
            compose.onNode(hasSetTextAction()).assertTextContains(word)
            compose.runOnIdle { assertEquals(word, model.state.value.input) }
        }
        awaitWord("link")
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { query = "apple"; request++; visible = true }
        awaitWord("apple")
        // A manual edit on the result page must not win over a fresh home request,
        // even if the home searches for the same word as last time.
        compose.runOnIdle { model.open("banana", external = true) }
        awaitWord("banana")
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { request++; visible = true }
        awaitWord("apple")

        // Scope choices stay hidden until opened; selecting a book re-runs lookup.
        compose.onNodeWithText("查询范围").assertDoesNotExist()
        compose.onNodeWithContentDescription("词典选项").performClick()
        compose.onNodeWithText("查询范围").performClick()
        val book = model.state.value.books.first { it.id.contains("cet4") }
        compose.onNodeWithText(book.name).performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.scope == book.id }
        compose.onNodeWithText(book.name).assertDoesNotExist()
        compose.onNodeWithContentDescription("词典选项").performClick()
        compose.onNodeWithText("查询范围").assertIsDisplayed()
        compose.onNodeWithText(book.name).assertIsDisplayed()
    }
}
