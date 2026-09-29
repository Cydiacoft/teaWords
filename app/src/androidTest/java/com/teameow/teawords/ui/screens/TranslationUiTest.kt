package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.teameow.teawords.ui.LookupViewModel
import com.teameow.teawords.data.TranslationEngine
import com.teameow.teawords.data.TranslationPreferences
import androidx.compose.runtime.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TranslationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun onlineTranslationIsUsableWithoutAModelAndExternalInputWaitsForSubmit() {
        val preferences = TranslationPreferences(compose.activity)
        val original = preferences.load()
        preferences.save(TranslationEngine.MYMEMORY, "")
        lateinit var model: LookupViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LookupViewModel::class.java] }
        try {
            val paragraph = "This paragraph should be ready to translate without downloading a language model.\n\nThe original line breaks stay in the input."
            compose.setContent { MaterialTheme { LookupScreen(paragraph, external = true, onBack = {}, model = model) } }
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.input == paragraph }
            compose.onNodeWithContentDescription("翻译").assertIsEnabled()
            compose.onNodeWithText("通过 Wi-Fi 下载离线模型 · 约 30 MB").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(model.state.value.available)
                assertFalse(model.state.value.dictionaryMode)
                assertEquals("", model.state.value.translation)
            }
            compose.onNodeWithContentDescription("词典选项").performClick()
            compose.onNodeWithText("翻译设置").assertDoesNotExist()
        } finally { preferences.save(original.engine, original.email) }
    }

    @Test fun preferencePageValidatesKeyAndLookupReloadsSavedPreferences() {
        val preferences = TranslationPreferences(compose.activity)
        val original = preferences.load()
        preferences.save(TranslationEngine.MYMEMORY, "")
        lateinit var model: LookupViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LookupViewModel::class.java] }
        var settings by mutableStateOf(true)
        try {
            compose.setContent { MaterialTheme {
                if (settings) TranslationSettingsPage { settings = false }
                else LookupScreen("This is a sentence.", external = true, onBack = {}, model = model)
            } }
            compose.onNodeWithText("DeepSeek · 长文翻译").performClick()
            if (!original.hasKey) {
                compose.onNodeWithText("DeepSeek API 密钥").assertExists()
                compose.onNodeWithText("保存设置").performScrollTo().performClick()
                compose.onNodeWithText("请先填写自己的 API 密钥。").assertIsDisplayed()
            }
            compose.onNodeWithText("MyMemory · 免配置").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("person@example.com")
            compose.onNodeWithText("保存设置").performScrollTo().performClick()
            compose.waitUntil(10_000) { preferences.load().email == "person@example.com" }
            compose.onNodeWithText("翻译设置已保存").assertExists()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(10_000) { model.state.value.input == "This is a sentence." }
            compose.runOnIdle { assertEquals("person@example.com", model.state.value.translationEmail) }
        } finally { preferences.save(original.engine, original.email) }
    }

    @Test fun rewrittenAboutShowsCurrentSourcesWithoutUpdateAction() {
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                AboutIdentity()
                AboutCredits()
            } }
        }
        compose.onNodeWithText("检查更新").assertDoesNotExist()
        compose.onNodeWithText("ECDICT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Free Dictionary API").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("反馈问题").performScrollTo().assertIsDisplayed()
    }
}
