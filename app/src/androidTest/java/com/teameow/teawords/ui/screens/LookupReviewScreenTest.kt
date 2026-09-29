package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.teameow.teawords.data.*
import com.teameow.teawords.data.search.ShippedDictionarySource
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LookupReviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun round(reveal: Boolean) {
        val context = compose.activity
        val name = "lookup-screen-${System.nanoTime()}.db"
        val helper = DatabaseHelper(context, name)
        helper.ensureSchema()
        val dictionary = LocalDictionary(helper, ShippedDictionarySource.localOnly)
        dictionary.upsert("TeaTestWord", "测试专用释义")
        val entry = dictionary.exact("teatestword")!!
        var returned = false
        try {
            compose.setContent { TeaWordsTheme { LookupReviewScreen(helper, listOf(entry), { returned = true }) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("英文单词").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("teatestword").assertDoesNotExist()
            if (reveal) compose.onNodeWithText("想不起来，查看答案").performClick()
            else {
                compose.onNode(hasSetTextAction()).performTextInput("teatestword")
                compose.onNodeWithText("检查回答").performClick()
            }
            compose.onNodeWithText("保存并继续").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("本轮复习完成").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, dictionary.knowledge(entry.word).attempts)
            assertEquals(if (reveal) 0 else 1, dictionary.knowledge(entry.word).streak)
            assertEquals(1, LearningRepository(helper).totals().tests)
            assertFalse(helper.isInVocabulary(entry.word))
            compose.onNodeWithText("回到回望").performClick()
            compose.runOnIdle { assertTrue(returned) }
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun independentRecallPersistsAndReturnsToRecall() = round(false)
    @Test fun revealingAnswerDoesNotCountAsIndependentRecall() = round(true)
}
