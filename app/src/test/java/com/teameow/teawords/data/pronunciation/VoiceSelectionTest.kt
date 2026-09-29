package com.teameow.teawords.data.pronunciation

import com.teameow.teawords.data.PronunciationDialect
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class VoiceSelectionTest {
    private fun voice(name: String, locale: Locale, network: Boolean = false, installed: Boolean = true, quality: Int = 300) =
        SpeechVoice(name, locale, network, installed, quality)

    @Test fun accentsRequireExactRegion() {
        val voices = listOf(voice("generic", Locale.ENGLISH), voice("au", Locale("en", "AU")),
            voice("uk", Locale.UK), voice("us", Locale.US), voice("fr-us", Locale("fr", "US")))
        assertEquals(listOf("uk"), matchingVoices(voices, PronunciationDialect.UK).map { it.name })
        assertEquals(listOf("us"), matchingVoices(voices, PronunciationDialect.US).map { it.name })
    }
    @Test fun oppositeAccentIsNeverFallback() {
        assertTrue(matchingVoices(listOf(voice("us", Locale.US)), PronunciationDialect.UK).isEmpty())
    }
    @Test fun missingVoicePacksAreExcluded() {
        assertTrue(matchingVoices(listOf(voice("uk-missing", Locale.UK, installed = false)), PronunciationDialect.UK).isEmpty())
    }
    @Test fun installedOfflineVoiceWinsOverHigherQualityNetworkVoice() {
        val result = matchingVoices(listOf(voice("network", Locale.US, true, quality = 500),
            voice("offline", Locale.US, quality = 100)), PronunciationDialect.US)
        assertEquals("offline", result.first().name)
    }
    @Test fun networkVoiceIsPermittedOnlyForExactRegion() {
        assertEquals("uk-network", matchingVoices(listOf(voice("uk-network", Locale.UK, true)), PronunciationDialect.UK).single().name)
    }
    @Test fun normalizationDoesNotCreateAccentSpecificWordIdentities() {
        assertEquals(normalizePronunciationWord(" Tomato "), normalizePronunciationWord("tomato"))
    }
}
