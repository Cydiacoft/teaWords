package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.gson.Gson
import com.teameow.teawords.data.PronunciationDialect
import com.teameow.teawords.data.pronunciation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real device audit: records synthesis completion separately from an explicit unavailable result. */
class PronunciationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun realEngineReportsExactRegionalVoiceAndPlaybackOutcome() {
        lateinit var manager: PronunciationManager
        compose.runOnUiThread { manager = PronunciationManager(compose.activity) }
        val audit = mutableListOf<Map<String, Any?>>()
        try {
            compose.waitUntil(20_000) { manager.state.value.phase != SpeechPhase.INITIALIZING }
            for (dialect in listOf(PronunciationDialect.UK, PronunciationDialect.US)) {
                compose.runOnUiThread { manager.play("tomato", dialect) }
                compose.waitUntil(35_000) { manager.state.value.phase in listOf(SpeechPhase.READY, SpeechPhase.ERROR) }
                val state = manager.state.value
                val voice = state.voices[dialect]
                if (state.phase == SpeechPhase.READY) {
                    assertNotNull(voice)
                    assertEquals(dialect.locale.country, voice!!.locale.country)
                    assertEquals("en", voice.locale.language)
                } else assertFalse(state.message.isNullOrBlank())
                audit += mapOf("dialect" to dialect.tag, "phase" to state.phase.name,
                    "voice" to voice?.name, "locale" to voice?.locale?.toLanguageTag(),
                    "networkRequired" to voice?.networkRequired, "message" to state.message)
            }
        } finally {
            File(compose.activity.filesDir, "pronunciation-device-audit.json").writeText(Gson().toJson(audit))
            compose.runOnUiThread { manager.close() }
        }
    }
}
