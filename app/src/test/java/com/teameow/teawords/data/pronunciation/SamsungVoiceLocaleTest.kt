package com.teameow.teawords.data.pronunciation

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SamsungVoiceLocaleTest {
    @Test fun concreteSamsungPackageAndVoiceTogetherIdentifyUsVoice() {
        val locale = reportedVoiceLocale("en-US-SMTf00", Locale("eng", "", "f00"),
            setOf("packageName=com.samsung.SMT.lang_en_us_f00"))
        assertEquals(Locale.US, locale)
        assertEquals(1, matchingVoices(listOf(SpeechVoice("samsung", locale, false)),
            com.teameow.teawords.data.PronunciationDialect.US).size)
        assertTrue(matchingVoices(listOf(SpeechVoice("samsung", locale, false)),
            com.teameow.teawords.data.PronunciationDialect.UK).isEmpty())
    }
    @Test fun defaultNameOrDisagreeingPackageCannotEstablishAccent() {
        val raw = Locale("eng")
        assertEquals(raw, reportedVoiceLocale("en-US-default", raw, emptySet()))
        assertEquals(raw, reportedVoiceLocale("en-US-SMTf00", raw,
            setOf("packageName=com.samsung.SMT.lang_en_gb_f00")))
        assertEquals(Locale.UK, reportedVoiceLocale("en-US-SMTf00", Locale.UK,
            setOf("packageName=com.samsung.SMT.lang_en_us_f00")))
    }
}
