package com.teameow.teawords.data

/** A lookup is eligible only when it identifies an English word, never a sentence or translation. */
object LookupReviewPolicy {
    fun isWord(text: String): Boolean = text.trim().matches(Regex("[A-Za-z]+(?:['’.-][A-Za-z]+)*"))

    fun headwords(records: List<QueryRecord>): List<String> = records.asSequence()
        .filter { it.kind == "词典" }
        .map { it.headword ?: it.text }
        .filter(::isWord).map(LexicalText::normalize).distinct().toList()

    fun queue(states: List<Knowledge>, now: Long, limit: Int): List<Knowledge> = states
        .distinctBy { it.word }
        .filter { !it.verified || it.due <= now }
        .sortedWith(compareBy<Knowledge> {
            when { it.attempts > 0 && it.due <= now -> 0; it.attempts == 0 -> 1; else -> 2 }
        }.thenBy { if (it.attempts > 0 && it.due <= now) it.due else Long.MAX_VALUE })
        .take(limit.coerceAtLeast(0))
}

/** The recall tab borrows lookup history; it never draws words from a selected book. */
class LookupReviewRepository(
    private val helper: DatabaseHelper,
    private val dictionary: LocalDictionary = LocalDictionary(helper)
) {
    fun entries(): List<LocalEntry> {
        val recent = dictionary.reviewHeadwords()
        val legacy = helper.getHistory().map { it.word }.filter(LookupReviewPolicy::isWord).map(LexicalText::normalize)
        return (recent + legacy).distinct().mapNotNull { word ->
            val entry = dictionary.exact(word) ?: dictionary.resolve(word).firstOrNull()
            entry?.takeIf { LookupReviewPolicy.isWord(it.word) && (it.zh.isNotBlank() || it.en.isNotBlank()) }
        }.distinctBy { LexicalText.normalize(it.word) }
    }

    fun queue(entries: List<LocalEntry>, now: Long, limit: Int): List<Knowledge> =
        LookupReviewPolicy.queue(entries.map { dictionary.knowledge(it.word) }, now, limit)
}
