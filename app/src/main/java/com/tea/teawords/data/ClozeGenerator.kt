package com.tea.teawords.data

import android.util.Log
import java.util.*
import kotlin.math.min

class ClozeGenerator(private val dictionaryApi: DictionaryApi) {

    fun generateClozeProblem(
        word: String,
        definition: String,
        callback: (ClozeProblem?) -> Unit
    ) {
        // 首先尝试从离线题库获取
        val offlineBank = OfflineQuestionBank()
        val offlineQ = offlineBank.getQuestionByWord(word)
        if (offlineQ != null) {
            callback(ClozeProblem(
                id = offlineQ.id,
                originalWord = offlineQ.word,
                sentence = offlineQ.sentence,
                clozeType = ClozeType.WORD_CLOZE,
                blankedSentence = offlineQ.blankedSentence,
                clozePosition = offlineQ.sentence.lowercase().indexOf(offlineQ.word.lowercase()),
                clozeWord = offlineQ.correctAnswer,
                options = offlineQ.options.shuffled(),
                difficulty = offlineQ.difficulty
            ))
            return
        }

        // 离线题库没有，则从字典 API 获取例句
        dictionaryApi.lookupWord(word) { wordResponse ->
            if (wordResponse != null && wordResponse.meanings != null) {
                val example = wordResponse.meanings
                    .flatMap { it.definitions ?: emptyList() }
                    .firstOrNull { !it.example.isNullOrEmpty() }
                    ?.example

                if (!example.isNullOrEmpty()) {
                    val problem = createClozeProblem(word, example, definition)
                    callback(problem)
                } else {
                    // 如果没有例句，生成简单的语义填空题
                    callback(createSemanticCloze(word, definition))
                }
            } else {
                // 字典查询失败，生成语义填空题
                callback(createSemanticCloze(word, definition))
            }
        }
    }

    private fun createClozeProblem(
        word: String,
        sentence: String,
        definition: String
    ): ClozeProblem? {
        // 清理句子（移除多余引号）
        val cleanSentence = sentence
            .trim()
            .replace(Regex("^[\"']|[\"']$"), "")

        // 在句子中查找单词位置
        val lowerSentence = cleanSentence.lowercase()
        val lowerWord = word.lowercase()
        val position = lowerSentence.indexOf(lowerWord)

        if (position < 0) {
            Log.w("ClozeGenerator", "Word '$word' not found in sentence: $cleanSentence")
            return null
        }

        // 创建挖空句子
        val blankedSentence = cleanSentence.replaceRange(
            position,
            position + word.length,
            "___"
        )

        // 生成干扰项（错误的单词）
        val options = generateOptions(word, definition)

        return ClozeProblem(
            id = "${word}_${System.currentTimeMillis()}",
            originalWord = word,
            sentence = cleanSentence,
            clozeType = ClozeType.WORD_CLOZE,
            blankedSentence = blankedSentence,
            clozePosition = position,
            clozeWord = word,
            options = options,
            difficulty = 2
        )
    }

    private fun createSemanticCloze(
        word: String,
        definition: String
    ): ClozeProblem {
        // 生成语义填空题（简单的定义选择）
        val options = listOf(
            definition,
            "与 '$word' 意思完全相反的词",
            "与 '$word' 无直接关系的词",
            "与 '$word' 拼写相似的词"
        ).shuffled()

        return ClozeProblem(
            id = "${word}_semantic_${System.currentTimeMillis()}",
            originalWord = word,
            sentence = "请选择 '$word' 的含义：",
            clozeType = ClozeType.SEMANTIC_CLOZE,
            blankedSentence = "___",
            clozePosition = 0,
            clozeWord = word,
            options = options,
            difficulty = 1
        )
    }

    private fun generateOptions(
        correctWord: String,
        definition: String
    ): List<String> {
        // 生成选项（包含正确答案和干扰项）
        val distractors = listOf(
            // 同长度的常见单词
            getWordByLength(correctWord.length, correctWord),
            // 相似首字母的单词
            getWordByFirstLetter(correctWord[0], correctWord),
            // 随机选择
            getRandomWord(correctWord)
        ).filterNotNull()

        return (listOf(correctWord) + distractors)
            .distinct()
            .take(4)
            .shuffled()
    }

    private fun getWordByLength(length: Int, excludeWord: String): String? {
        val commonWords = mapOf(
            3 to listOf("the", "and", "cat", "dog", "run"),
            4 to listOf("that", "with", "have", "this", "been"),
            5 to listOf("which", "their", "about", "would", "could"),
            6 to listOf("should", "before", "people", "system", "number"),
            7 to listOf("between", "through", "another", "example", "because"),
            8 to listOf("question", "children", "interest", "possible", "company")
        )

        val words = commonWords[length]?.filter { it != excludeWord.lowercase() }
        return words?.randomOrNull()
    }

    private fun getWordByFirstLetter(letter: Char, excludeWord: String): String? {
        val wordsByLetter = mapOf(
            'a' to listOf("about", "above", "after", "again", "animal"),
            'b' to listOf("because", "before", "between", "big", "book"),
            'c' to listOf("can", "could", "come", "city", "change"),
            'd' to listOf("day", "different", "during", "did", "done"),
            'e' to listOf("each", "every", "even", "example", "end"),
            'f' to listOf("find", "first", "for", "from", "four"),
            'g' to listOf("get", "give", "go", "good", "great"),
            'h' to listOf("have", "he", "her", "here", "his"),
            'i' to listOf("if", "in", "into", "is", "it"),
            'j' to listOf("just", "judge", "jump", "January", "job"),
            'k' to listOf("keep", "kind", "know", "key", "kingdom"),
            'l' to listOf("language", "last", "left", "life", "like"),
            'm' to listOf("make", "many", "may", "me", "more"),
            'n' to listOf("name", "never", "new", "next", "not"),
            'o' to listOf("of", "often", "on", "one", "only"),
            'p' to listOf("people", "place", "play", "possible", "put"),
            'q' to listOf("question", "quite", "quick", "quality", "quarter"),
            'r' to listOf("really", "right", "run", "right", "result"),
            's' to listOf("same", "see", "should", "so", "some"),
            't' to listOf("take", "that", "the", "their", "than"),
            'u' to listOf("use", "under", "until", "usually", "upon"),
            'v' to listOf("very", "visit", "voice", "value", "various"),
            'w' to listOf("want", "water", "way", "we", "who"),
            'x' to listOf("x-ray"),
            'y' to listOf("year", "yes", "you", "young", "your"),
            'z' to listOf("zero", "zone", "zoo", "zealous")
        )

        val words = wordsByLetter[letter.lowercaseChar()]?.filter { it != excludeWord.lowercase() }
        return words?.randomOrNull()
    }

    private fun getRandomWord(excludeWord: String): String {
        val commonWords = listOf(
            "think", "know", "try", "need", "feel", "become",
            "leave", "put", "mean", "keep", "let", "begin",
            "seem", "help", "talk", "turn", "start", "show",
            "hear", "play", "run", "move", "like", "live",
            "believe", "hold", "bring", "happen", "write", "provide",
            "sit", "stand", "lose", "pay", "meet", "include"
        )

        return commonWords
            .filter { it != excludeWord.lowercase() }
            .randomOrNull() ?: "word"
    }

    fun generateSessionProblems(
        vocabItems: List<VocabularyItem>,
        callback: (List<ClozeProblem>) -> Unit
    ) {
        val problems = mutableListOf<ClozeProblem>()
        var completed = 0

        if (vocabItems.isEmpty()) {
            callback(emptyList())
            return
        }

        // 随机选择最多 5 个单词进行复习
        val selectedItems = vocabItems.shuffled().take(5)

        selectedItems.forEach { vocab ->
            generateClozeProblem(vocab.word, vocab.definition) { problem ->
                if (problem != null) {
                    problems.add(problem)
                }
                completed++
                if (completed == selectedItems.size) {
                    callback(problems.shuffled())
                }
            }
        }
    }

    fun generateLocalMixedSessionProblems(
        vocabItems: List<VocabularyItem>,
        count: Int = 10,
        callback: (List<ClozeProblem>) -> Unit
    ) {
        if (vocabItems.isEmpty()) {
            callback(emptyList())
            return
        }

        val sourceItems = vocabItems
            .filter { it.word.isNotBlank() && it.definition.isNotBlank() }
            .distinctBy { it.word.lowercase() }

        if (sourceItems.isEmpty()) {
            callback(emptyList())
            return
        }

        val selectedItems = sourceItems.shuffled().take(count.coerceAtLeast(1))
        val problems = selectedItems.mapIndexed { index, item ->
            if (index % 2 == 0) {
                createWordChoiceProblem(item, sourceItems)
            } else {
                createMeaningChoiceProblem(item, sourceItems)
            }
        }

        callback(problems.shuffled())
    }

    private fun createWordChoiceProblem(
        item: VocabularyItem,
        allItems: List<VocabularyItem>
    ): ClozeProblem {
        val options = buildWordOptions(item, allItems)
        return ClozeProblem(
            id = "local_word_${item.word}_${System.currentTimeMillis()}",
            originalWord = item.word,
            sentence = item.definition,
            clozeType = ClozeType.SEMANTIC_CLOZE,
            blankedSentence = "请选择与释义匹配的单词：\n${item.definition}",
            clozePosition = 0,
            clozeWord = item.word,
            options = options,
            difficulty = estimateDifficulty(item.word)
        )
    }

    private fun createMeaningChoiceProblem(
        item: VocabularyItem,
        allItems: List<VocabularyItem>
    ): ClozeProblem {
        val options = buildDefinitionOptions(item, allItems)
        return ClozeProblem(
            id = "local_meaning_${item.word}_${System.currentTimeMillis()}",
            originalWord = item.word,
            sentence = "${item.word}: ${item.definition}",
            clozeType = ClozeType.SEMANTIC_CLOZE,
            blankedSentence = "请选择 “${item.word}” 的正确释义：",
            clozePosition = 0,
            clozeWord = item.definition,
            options = options,
            difficulty = estimateDifficulty(item.word)
        )
    }

    private fun buildWordOptions(
        item: VocabularyItem,
        allItems: List<VocabularyItem>
    ): List<String> {
        return (listOf(item.word) + allItems
            .filterNot { it.word.equals(item.word, ignoreCase = true) }
            .shuffled()
            .take(3)
            .map { it.word })
            .distinct()
            .shuffled()
    }

    private fun buildDefinitionOptions(
        item: VocabularyItem,
        allItems: List<VocabularyItem>
    ): List<String> {
        return (listOf(item.definition) + allItems
            .filterNot { it.word.equals(item.word, ignoreCase = true) }
            .filter { it.definition.isNotBlank() }
            .shuffled()
            .take(3)
            .map { it.definition })
            .distinct()
            .shuffled()
    }

    private fun estimateDifficulty(word: String): Int {
        return when {
            word.length <= 5 -> 1
            word.length <= 8 -> 2
            word.length <= 11 -> 3
            word.length <= 14 -> 4
            else -> 5
        }
    }
}
