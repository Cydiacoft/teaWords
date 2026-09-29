package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.HistoryItem
import com.teameow.teawords.data.VocabularyItem
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PageLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val history = (1..30).map { HistoryItem(it, "word$it", "释义 $it", 0L) }

    @Test fun lastNotebookEntryStaysAboveDockAtLargeFont() {
        var clicked: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalReservedBottom provides 100.dp,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                TeaWordsTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        RecordsView(history.map { VocabularyItem(it.id, it.word, null, it.translation, it.timestamp) }, 0, { clicked = it }, {})
                    }
                }
            }
        }
        compose.onNodeWithText("生词本 · 30").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("word30"))
        compose.onNodeWithText("word30").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("word30", clicked) }
        assertTrue(compose.onNodeWithText("word30").getUnclippedBoundsInRoot().bottom <= 540.dp)
    }

    @Test fun homeRecentWordsRemainAccessibleInSheet() {
        compose.setContent {
            TeaWordsTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    HomeView("", {}, {}, emptyList(), false, {}, history,
                        "茶词", "专注翻译，见字如面", {}, {}, {}, {}, bottomClearance = 100.dp)
                }
            }
        }
        compose.onNodeWithContentDescription("设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("学习统计").assertIsDisplayed()
        compose.onNodeWithContentDescription("最近查阅").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("word30"))
        compose.onNodeWithText("word30").assertIsDisplayed()
    }
}
