package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LoadingIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingDoesNotMovePageContent() {
        val loading = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TeaWordsTheme {
                TeaListPage(title = "测试", loading = loading.value) {
                    item { Text("内容") }
                }
            }
        }
        val before = compose.onNodeWithText("内容").getUnclippedBoundsInRoot()
        compose.runOnIdle { loading.value = true }
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("page-loading").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("内容").getUnclippedBoundsInRoot())
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("page-loading").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithText("内容").getUnclippedBoundsInRoot())
    }

    @Test fun shortLoadingDoesNotFlashIndicator() {
        val loading = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TeaWordsTheme { TeaListPage(title = "测试", loading = loading.value) {} }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("page-loading").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("page-loading").assertDoesNotExist()
    }
}
