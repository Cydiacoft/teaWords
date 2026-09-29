package com.teameow.teawords.data

import java.io.PushbackReader
import java.io.Reader
import java.util.Locale

object LexicalText {
    fun normalize(text: String) = text.trim().replace('’', '\'').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun isChinese(text: String) = text.any { it in '\u3400'..'\u9fff' }
    fun dictionaryMode(text: String): Boolean = text.trim().let {
        if (it.matches(Regex("[A-Za-z][A-Za-z.'’-]{0,63}"))) true
        else it.length <= (if (isChinese(it)) 6 else 64) && !it.contains('\n') && !it.any { c -> c in ".!?。！？" } && it.split(Regex("\\s+")).size <= 3
    }
    fun chineseTokens(text: String): Set<String> {
        val result = mutableSetOf<String>()
        Regex("[\\u3400-\\u9fff]+").findAll(text).forEach { match ->
            match.value.forEach { result.add(it.toString()) }
            match.value.windowed(2).forEach { result.add(it) }
        }
        return result
    }
    /** At most 768 indexed exact probes; never scans the whole dictionary. */
    fun spellingCandidates(text: String): List<String> {
        if (!text.matches(Regex("[a-z]{3,24}"))) return emptyList()
        val out = linkedSetOf<String>()
        for (i in text.indices) {
            out.add(text.removeRange(i, i + 1))
            if (i + 1 < text.length) out.add(text.substring(0, i) + text[i + 1] + text[i] + text.substring(i + 2))
        }
        for (i in 0..text.length) for (c in 'a'..'z') {
            out.add(text.substring(0, i) + c + text.substring(i))
            if (i < text.length) out.add(text.substring(0, i) + c + text.substring(i + 1))
            if (out.size >= 768) return out.take(768)
        }
        return out.toList()
    }

    /** One meaning of a word, as stored in the local dictionary. */
    data class SenseUnit(val ordinal: Int, val text: String, val confidence: Double)

    /**
     * Splits a dictionary gloss into candidate meanings for sense-level tracking.
     *
     * This is a **heuristic**, not real sense disambiguation. The bundled data stores one gloss
     * string per word, so the best available boundary is the full-width semicolon. A gloss without
     * that separator, or one long enough to hold several meanings, is marked low confidence so the
     * UI never presents these as authoritative lexical senses.
     *
     * Confidence: 0.6 when split on `；` into short pieces, 0.4 for a short unsplit gloss,
     * 0.25 for a long unsplit gloss.
     */
    fun splitSenses(gloss: String): List<SenseUnit> {
        val clean = gloss.replace("\\n", "\n").replace('\n', '；').trim()
        if (clean.isEmpty()) return emptyList()
        val pieces = clean.split('；', ',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SENSES_PER_WORD)
        if (pieces.isEmpty()) return emptyList()
        val joined = pieces.joinToString("")
        val split = pieces.size > 1
        return pieces.mapIndexed { index, text ->
            val confidence = when {
                split && text.length <= SHORT_SENSE_CHARS -> 0.6
                split -> 0.45
                joined.length <= SHORT_SENSE_CHARS -> 0.4
                else -> 0.25
            }
            SenseUnit(ordinal = index, text = text, confidence = confidence)
        }
    }

    /** Cap on derived meanings per word: beyond this the gloss is a list, not senses. */
    const val MAX_SENSES_PER_WORD = 6

    /** A meaning this short is plausibly a single sense rather than a bundle. */
    const val SHORT_SENSE_CHARS = 12
}

/** Streaming RFC4180-style reader, including quoted newlines and escaped quotes. */
class CsvRows(reader: Reader) {
    private val input = PushbackReader(reader.buffered(), 1)
    fun next(): List<String>? {
        val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var seen = false
        while (true) {
            val n = input.read()
            if (n < 0) {
                require(!quoted) { "CSV 引号未闭合" }
                if (!seen && row.isEmpty() && field.isEmpty()) return null
                row.add(field.toString()); return row
            }
            seen = true
            val c = n.toChar()
            if (c == '"') {
                if (quoted) {
                    val next = input.read()
                    if (next == '"'.code) field.append('"') else { quoted = false; if (next >= 0) input.unread(next) }
                } else { require(field.isEmpty()) { "CSV 引号位置无效" }; quoted = true }
            } else if (c == ',' && !quoted) { row.add(field.toString()); field.clear() }
            else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r') { val next = input.read(); if (next != '\n'.code && next >= 0) input.unread(next) }
                row.add(field.toString()); return row
            } else field.append(c)
            require(field.length <= 100_000 && row.size < 64) { "词典字段过长或列数过多" }
        }
    }
}
