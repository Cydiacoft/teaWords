package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.teameow.teawords.data.pronunciation.*
import com.teameow.teawords.ui.theme.TeaWordsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class DualPronunciationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun twoButtonsDoNotInventIpaOrFallbackAndWordChangeStopsPlayback() {
        var played = 0
        val backend = object : SpeechBackend {
            override fun initialize(onReady: (Boolean) -> Unit, onEvent: (String, SpeechPhase, String?) -> Unit) = onReady(true)
            override fun voices() = listOf(SpeechVoice("us-local", Locale.US, false))
            override fun select(voice: SpeechVoice) = voice.locale == Locale.US
            override fun speak(word: String, id: String): Boolean { played++; return true }
            override fun stop() = Unit
            override fun close() = Unit
        }
        lateinit var services: PronunciationServices
        var word by mutableStateOf("tomato")
        compose.runOnUiThread { services = PronunciationServices(compose.activity, PronunciationManager(backend)) }
        try {
            compose.setContent {
                TeaWordsTheme {
                    CompositionLocalProvider(LocalPronunciationServices provides services) {
                        DualPronunciation(word, "/unlabelled-fixture/", "test fixture")
                    }
                }
            }
            compose.onNodeWithText("UK 发音").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(0, played); assertEquals(SpeechPhase.ERROR, services.manager.state.value.phase) }
            compose.onNodeWithText("US 发音").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, played) }
            compose.onAllNodes(hasText("/unlabelled-fixture/", substring = true)).assertCountEquals(1)
            compose.onNodeWithText("暂无独立英式音标").assertExists()
            compose.onNodeWithText("暂无独立美式音标").assertExists()
            compose.runOnIdle { word = "apple" }
            compose.runOnIdle { assertEquals(SpeechPhase.READY, services.manager.state.value.phase) }
        } finally { compose.runOnUiThread { services.close() } }
    }
}
