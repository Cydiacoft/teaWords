package com.teameow.teawords.data.pronunciation

import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.data.PronunciationDialect
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PronunciationManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun flush() = instrumentation.waitForIdleSync()
    private class Fake : SpeechBackend {
        var ready: ((Boolean) -> Unit)? = null
        lateinit var event: (String, SpeechPhase, String?) -> Unit
        var inventory = listOf(SpeechVoice("uk", Locale.UK, false), SpeechVoice("us", Locale.US, false))
        val played = mutableListOf<Pair<String, String>>()
        var selected: SpeechVoice? = null
        var rejectSelection = false
        var closes = 0
        override fun initialize(onReady: (Boolean) -> Unit, onEvent: (String, SpeechPhase, String?) -> Unit) { ready = onReady; event = onEvent }
        override fun voices() = inventory
        override fun select(voice: SpeechVoice): Boolean { if (rejectSelection) return false; selected = voice; return true }
        override fun speak(word: String, id: String): Boolean { played += word to id; return true }
        override fun stop() = Unit
        override fun close() { closes++ }
    }
    private fun withManager(block: (PronunciationManager, Fake) -> Unit) {
        val fake = Fake()
        lateinit var manager: PronunciationManager
        main { manager = PronunciationManager(fake) }
        try { main { fake.ready!!(true) }; flush(); block(manager, fake) }
        finally { main { manager.close() } }
    }
    @Test fun ukAndUsSelectSeparateExactVoices() = withManager { manager, fake ->
        main {
            manager.play("tomato", PronunciationDialect.UK)
            assertEquals(Locale.UK, fake.selected!!.locale)
            manager.play("tomato", PronunciationDialect.US)
            assertEquals(Locale.US, fake.selected!!.locale)
            assertEquals(2, fake.played.size)
        }
    }
    @Test fun missingRegionDoesNotSpeakThroughOtherVoice() = withManager { manager, fake ->
        main {
            fake.inventory = listOf(SpeechVoice("us", Locale.US, false))
            manager.play("tomato", PronunciationDialect.UK)
            assertTrue(fake.played.isEmpty())
            assertEquals(SpeechPhase.ERROR, manager.state.value.phase)
            assertTrue(manager.state.value.message!!.contains("en-GB"))
        }
    }
    @Test fun rejectedVoiceDoesNotSpeakUsingPreviousVoice() = withManager { manager, fake ->
        main { fake.rejectSelection = true; manager.play("tomato", PronunciationDialect.UK); assertTrue(fake.played.isEmpty()) }
    }
    @Test fun staleCallbacksCannotCompleteNewPlayback() = withManager { manager, fake ->
        main { manager.play("first", PronunciationDialect.UK); manager.play("second", PronunciationDialect.US) }
        main { fake.event(fake.played.first().second, SpeechPhase.READY, null) }; flush()
        assertEquals(SpeechPhase.QUEUED, manager.state.value.phase)
        assertEquals("second", manager.state.value.word)
        main { fake.event(fake.played.last().second, SpeechPhase.PLAYING, null) }; flush()
        assertEquals(SpeechPhase.PLAYING, manager.state.value.phase)
        main { fake.event(fake.played.last().second, SpeechPhase.READY, null) }; flush()
        assertEquals(SpeechPhase.READY, manager.state.value.phase)
    }
    @Test fun stopInvalidatesCallbacksAndReleaseIsIdempotent() = withManager { manager, fake ->
        main { manager.play("word", PronunciationDialect.US); manager.stop() }
        main { fake.event(fake.played.last().second, SpeechPhase.ERROR, "late error") }; flush()
        assertEquals(SpeechPhase.READY, manager.state.value.phase)
        main { manager.close(); manager.close(); manager.play("ignored", PronunciationDialect.UK) }
        assertEquals(1, fake.closes)
        assertEquals(1, fake.played.size)
        assertEquals(SpeechPhase.RELEASED, manager.state.value.phase)
    }
    @Test fun asynchronousSynthesisErrorIsShown() = withManager { manager, fake ->
        main { manager.play("word", PronunciationDialect.US) }
        main { fake.event(fake.played.last().second, SpeechPhase.ERROR, "network unavailable") }; flush()
        assertEquals(SpeechPhase.ERROR, manager.state.value.phase)
        assertEquals("network unavailable", manager.state.value.message)
    }
    @Test fun failedInitializationAndLateInitAfterReleaseDoNotPlay() {
        val fake = Fake()
        lateinit var manager: PronunciationManager
        main { manager = PronunciationManager(fake); fake.ready!!(false) }; flush()
        assertEquals(SpeechPhase.ERROR, manager.state.value.phase)
        main { manager.play("word", PronunciationDialect.UK) }
        assertTrue(fake.played.isEmpty())
        main { manager.close(); fake.ready!!(true) }; flush()
        assertEquals(SpeechPhase.RELEASED, manager.state.value.phase)
    }
}
