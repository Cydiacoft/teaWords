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

/** Samsung SMT reports ISO-639-3 language and no usable country for its installed packs.
 * Require agreement between both engine-provided pack metadata and the concrete voice ID;
 * generic English and a default voice ID alone cannot establish a regional accent. */
internal fun reportedVoiceLocale(name: String, locale: Locale, features: Set<String>): Locale {
    if (locale.language !in setOf("en", "eng")) return locale
    val country = when (locale.country.uppercase(Locale.ROOT)) {
        "USA" -> "US"
        "GBR" -> "GB"
        else -> locale.country.uppercase(Locale.ROOT)
    }
    if (country in Locale.getISOCountries()) return Locale("en", country)
    val packageName = features.firstOrNull { it.startsWith("packageName=") }?.substringAfter('=') ?: return locale
    return when {
        name.matches(Regex("en-US-SMT[a-zA-Z0-9]+")) && packageName.matches(Regex("com\\.samsung\\.SMT\\.lang_en_us_[a-zA-Z0-9]+")) -> Locale.US
        name.matches(Regex("en-GB-SMT[a-zA-Z0-9]+")) && packageName.matches(Regex("com\\.samsung\\.SMT\\.lang_en_gb_[a-zA-Z0-9]+")) -> Locale.UK
        else -> locale
    }
}

/** A generic English, Australian or opposite-region voice is never a match. */
fun matchingVoices(voices: Collection<SpeechVoice>, dialect: PronunciationDialect): List<SpeechVoice> =
    voices.filter {
        it.installed && it.locale.language == dialect.locale.language &&
            it.locale.country == dialect.locale.country
    }.sortedWith(compareBy<SpeechVoice> { it.networkRequired }
        .thenByDescending { it.quality }.thenBy { it.latency }.thenBy { it.name })
