package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.Rule
import org.junit.Test

class PullDownSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 1f

    private fun showHome() {
        compose.setContent {
            density = LocalDensity.current.density
            var focused by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            TeaWordsTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    HomeView(query, { query = it }, {}, emptyList(), focused, { focused = it }, emptyList(),
                        "茶词", "专注翻译", {}, {}, {}, {})
                }
            }
        }
    }

    private fun pull(distanceDp: Float) {
        compose.onNode(hasScrollAction()).performTouchInput {
            swipe(
                start = Offset(20f * density, 40f * density),
                end = Offset(20f * density, (40f + distanceDp) * density),
                durationMillis = 500
            )
        }
    }

    @Test fun pullingDownEntersSearchOnRelease() {
        showHome()
        pull(130f)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("茶词").assertDoesNotExist()
    }

    @Test fun shortPullReturnsHomeWithoutFocusingSearch() {
        showHome()
        pull(36f)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("茶词").assertIsDisplayed()
        compose.onNodeWithText("下拉进入搜索").assertDoesNotExist()
    }

    @Test fun clearButtonKeepsSearchFocused() {
        showHome()
        compose.onNodeWithContentDescription("打开下拉搜索").performClick()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("hello")
        compose.onNodeWithContentDescription("清空输入").performClick()
        compose.onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assertIsFocused()
        compose.onNodeWithContentDescription("退出搜索").assertIsDisplayed()
    }

    @Test fun backArrowExitsSearchWithoutClearingQuery() {
        showHome()
        compose.onNodeWithContentDescription("打开下拉搜索").performClick()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("hello")
        compose.onNodeWithContentDescription("退出搜索").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("茶词").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开下拉搜索").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("hello").assertIsFocused()
    }
}
