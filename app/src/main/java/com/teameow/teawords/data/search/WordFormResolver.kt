package com.teameow.teawords.data.search

import com.teameow.teawords.data.LexicalText

/**
 * Resolves an inflected form back to its headword using the stored morphological data.
 *
 * ECDICT ships an `exchange` field per word (`d:perceived/p:perceived/3:perceives/i:perceiving`), and
 * the build tool turns that into a `lex_forms` table. So `went -> go` and `better -> good` are answered
 * from data, not from stripping `-ed`, `-ing` or `-s`: suffix stripping gets irregular verbs, comparatives
 * and consonant doubling wrong, and would invent headwords that do not exist.
 *
 * If a form is not in the table, the query is left alone. An honest miss beats a wrong guess.
 */
interface WordFormResolver {
    /** Headwords for a surface form, strongest first; empty when the form is unknown. */
    fun resolve(form: String): List<Long>
}

object LexicalFormKeys {
    /** Longest surface form accepted from the table, to keep lookups bounded. */
    const val MAX_FORM_LENGTH = 64

    fun isResolvable(form: String): Boolean {
        val text = form.trim()
        return text.isNotEmpty() && text.length <= MAX_FORM_LENGTH && !LexicalText.isChinese(text)
    }
}

/**
 * Pure Kotlin helper that describes which query variants are worth trying for spelling correction and
 * how to key them. Kept separate from the SQLite access so it can be unit tested directly.
 */
object WordFormKeys {
    /** ECDICT exchange kinds, documented in the build tool. */
    val KIND_LABELS: Map<String, String> = mapOf(
        "p" to "过去式",
        "d" to "过去分词",
        "i" to "现在分词",
        "3" to "第三人称单数",
        "s" to "复数",
        "r" to "比较级",
        "t" to "最高级",
        "0" to "原形",
        "1" to "原形变换"
    )

    fun label(kind: String): String = KIND_LABELS[kind] ?: kind
}
