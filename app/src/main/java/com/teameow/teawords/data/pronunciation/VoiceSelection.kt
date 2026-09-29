package com.teameow.teawords.data.pronunciation

import com.teameow.teawords.data.PronunciationDialect
import java.util.Locale

val PronunciationDialect.locale: Locale get() = when (this) {
    PronunciationDialect.UK -> Locale.UK
    PronunciationDialect.US -> Locale.US
}
val PronunciationDialect.tag: String get() = if (this == PronunciationDialect.UK) "UK" else "US"

data class SpeechVoice(
    val name: String, val locale: Locale, val networkRequired: Boolean,
    val installed: Boolean = true, val quality: Int = 0, val latency: Int = 0
)

/** A generic English, Australian or opposite-region voice is never a match. */
fun matchingVoices(voices: Collection<SpeechVoice>, dialect: PronunciationDialect): List<SpeechVoice> =
    voices.filter {
        it.installed && it.locale.language == dialect.locale.language &&
            it.locale.country == dialect.locale.country
    }.sortedWith(compareBy<SpeechVoice> { it.networkRequired }
        .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })
