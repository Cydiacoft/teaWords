package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PocketRoundSetupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var started = 0
    private fun show() {
        compose.setContent { TeaWordsTheme { PocketRoundSetupScreen(8, false, null, {}, { started = it }) } }
    }
    @Test fun presetStartsWithSelectedCount() {
        show()
        compose.onNodeWithText("3 个词").performClick()
        compose.onNodeWithText("开始这一轮").performClick()
        assertEquals(3, started)
    }
    @Test fun customCountIsPassedToTheRoundAndInvalidCountCannotStart() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        compose.onNodeWithText("开始这一轮").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("12")
        compose.onNodeWithText("开始这一轮").performClick()
        assertEquals(12, started)
    }
}
