package com.teameow.teawords.data

import com.google.gson.JsonParser
import java.util.Locale

data class ImportedWord(val word: String, val definition: String)

object WordImport {
    fun parse(text: String): List<ImportedWord> {
        require(text.length <= 2_000_000) { "文件过大，请分批导入（最多 2 MB 文本）" }
        val rows = if (text.trimStart().startsWith("[")) {
            JsonParser.parseString(text).asJsonArray.map { entry ->
                val obj = entry.asJsonObject
                ImportedWord(obj.get("word").asString, obj.get("definition").asString)
            }
        } else text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = if (line.contains(',') || line.contains('\t')) csv(line) else line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) null else ImportedWord(parts[0], cleanDefinition(parts.drop(1).joinToString("; ")))
        }.toList()
        val words = rows.map { ImportedWord(it.word.trim().lowercase(Locale.ROOT), it.definition.trim()) }
            // Single letters are section headings in syllabus wordbooks ("A", "B"), not entries.
            .filter { it.word.matches(Regex("[a-z]{2,}(?:[-'][a-z]+)*")) && it.definition.isNotBlank() && !(it.word == "word" && it.definition == "definition") }
            .distinctBy { it.word }
        require(words.isNotEmpty()) { "没有有效词条。请提供 word,definition，或每行“单词 释义”。" }
        return words
    }

    /**
     * Syllabus wordbooks look like "brute [bruːt] n.禽兽" or "sick a.有病的". The bracket, the
     * phonetic and the part-of-speech shorthand are not the meaning, so strip them.
     * Shared with the bundled asset path so both ingestion routes store the same thing.
     */
    fun cleanDefinition(raw: String): String {
        // The bracket is optional: entries may appear as "able [ˈeibl] a.能够的" or "able a.能够的".
        var text = raw.trim()
        Regex("^\\[[^\\]]*\\]\\s*").find(text)?.let { text = text.substring(it.value.length) }
        return text.replaceFirst(
            Regex("^(?:pron|pl|sing|pt|pp|adv|adj|prep|conj|num|int|art|aux|abbr|n|v|vi|vt|a|ad)\\.\\s*"),
            ""
        ).trim()
    }

    private fun csv(line: String): List<String> {
        val fields = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                (c == ',' || c == '\t') && !quoted -> { fields.add(field.toString()); field.clear() }
                else -> field.append(c)
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合；请使用每行一条词汇的文件。" }
        fields.add(field.toString()); return fields
    }
}
