package com.teameow.teawords.data

import com.teameow.teawords.data.search.WordSearchRepository

/** Reference books are candidates, not thousands of new personal-vocabulary records. */
class LearningCatalog(private val helper: DatabaseHelper, private val dictionary: WordSearchRepository) {
    data class Snapshot(val states: List<Knowledge>, val words: Map<String, VocabularyItem>, val tags: Map<String, String>)

    fun load(bookIds: Set<String>): Snapshot {
        val entries = dictionary.learningWords(bookIds)
        val personal = helper.getVocabulary()
        val words = personal.associateBy { it.word }.toMutableMap()
        entries.forEach { entry ->
            words[entry.word] = VocabularyItem(word = entry.word, phonetic = entry.phonetic,
                definition = entry.zh.ifBlank { entry.en }.replace("\\n", "\n"), timestamp = 0L)
        }
        val tags = LearningRepository(helper).taggedWordsForVocabulary().associate { it.word to it.tags }.toMutableMap()
        entries.forEach { tags[it.word] = it.tags }
        return Snapshot(LearningRepository(helper).states(entries.map { it.word }), words, tags)
    }
}
