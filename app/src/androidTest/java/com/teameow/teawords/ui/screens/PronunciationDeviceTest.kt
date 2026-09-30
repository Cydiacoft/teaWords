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
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

/** Real device audit: records synthesis completion separately from an explicit unavailable result. */
class PronunciationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun inventoryAndLanguageSupportReportedByRealEngine() {
        var tts: TextToSpeech? = null
        val status = AtomicInteger(-999)
        try {
            compose.runOnUiThread { tts = TextToSpeech(compose.activity) { status.set(it) } }
            compose.waitUntil(20_000) { status.get() != -999 }
            compose.runOnUiThread {
                val engine = tts!!
                val audit = mapOf("status" to status.get(),
                    "voices" to engine.voices.orEmpty().map { mapOf("name" to it.name, "locale" to it.locale.toLanguageTag(), "features" to it.features) },
                    "ukSupport" to engine.isLanguageAvailable(Locale.UK), "usSupport" to engine.isLanguageAvailable(Locale.US),
                    "usSelection" to engine.setLanguage(Locale.US), "selectedVoice" to engine.voice?.locale?.toLanguageTag())
                File(compose.activity.filesDir, "speech-engine-inventory.json").writeText(Gson().toJson(audit))
            }
        } finally { compose.runOnUiThread { tts?.shutdown() } }
    }
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
