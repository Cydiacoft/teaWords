package com.tea.teawords.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class WordLevelProvider(private val context: Context) {

    private val cet4Set = HashSet<String>()
    private val cet6Set = HashSet<String>()
    private val tem4Set = HashSet<String>()
    private val tem8Set = HashSet<String>()
    private var isLoaded = false

    fun loadIfNeeded() {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                loadList("cet4.txt", cet4Set)
                loadList("cet6.txt", cet6Set)
                loadOptionalList("tem4.txt", tem4Set)
                loadOptionalList("tem8.txt", tem8Set)
                if (tem4Set.isEmpty()) tem4Set.addAll(defaultTem4Words)
                if (tem8Set.isEmpty()) tem8Set.addAll(defaultTem8Words)
                isLoaded = true
            } catch (e: Exception) {
                Log.e("WordLevelProvider", "Error loading word lists: ${e.message}", e)
            }
        }
    }

    private fun loadList(filename: String, targetSet: HashSet<String>) {
        context.assets.open(filename).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty()) continue
                    
                    // Skip single-character capital letter headers (like "A", "B", etc.)
                    if (trimmed.length == 1 && trimmed[0].isUpperCase()) continue
                    
                    // Split on whitespace or opening bracket to get the word
                    val parts = trimmed.split(Regex("[\\s\\[]"), 2)
                    val word = parts.firstOrNull()?.trim()?.lowercase() ?: ""
                    
                    // Basic sanity check to ensure it's a valid English word
                    if (word.isNotEmpty() && word.all { it.isLetter() || it == '-' || it == '\'' }) {
                        targetSet.add(word)
                    }
                }
            }
        }
        Log.d("WordLevelProvider", "Loaded ${targetSet.size} words from $filename")
    }

    private fun loadOptionalList(filename: String, targetSet: HashSet<String>) {
        try {
            loadList(filename, targetSet)
        } catch (e: Exception) {
            Log.w("WordLevelProvider", "Optional word list missing: $filename")
        }
    }

    fun getLevels(word: String): List<String> {
        loadIfNeeded()
        val cleanedWord = word.trim().lowercase()
        val levels = mutableListOf<String>()
        if (cet4Set.contains(cleanedWord)) {
            levels.add("四级 CET-4")
        }
        if (cet6Set.contains(cleanedWord)) {
            levels.add("六级 CET-6")
        }
        if (tem4Set.contains(cleanedWord)) {
            levels.add("专四 TEM-4")
        }
        if (tem8Set.contains(cleanedWord)) {
            levels.add("专八 TEM-8")
        }
        return levels
    }

    fun searchPrefix(prefix: String, limit: Int = 8): List<String> {
        loadIfNeeded()
        val cleanedPrefix = prefix.trim().lowercase()
        if (cleanedPrefix.isEmpty()) return emptyList()

        return sequenceOf(cet4Set, cet6Set, tem4Set, tem8Set)
            .flatMap { it.asSequence() }
            .filter { it.startsWith(cleanedPrefix) }
            .distinct()
            .sorted()
            .take(limit)
            .toList()
    }

    fun getWordbookItems(bookIds: Set<String>, limitPerBook: Int = 80): List<VocabularyItem> {
        loadIfNeeded()
        val items = mutableListOf<VocabularyItem>()
        bookIds.forEach { id ->
            when (id) {
                LearningWordbook.CET4.id -> items.addAll(loadVocabularyItems("cet4.txt", limitPerBook))
                LearningWordbook.CET6.id -> items.addAll(loadVocabularyItems("cet6.txt", limitPerBook))
                LearningWordbook.TEM4.id -> items.addAll(defaultTem4Definitions.toVocabularyItems())
                LearningWordbook.TEM8.id -> items.addAll(defaultTem8Definitions.toVocabularyItems())
            }
        }

        return items
            .distinctBy { it.word.lowercase() }
            .shuffled()
    }

    private fun loadVocabularyItems(filename: String, limit: Int): List<VocabularyItem> {
        val items = mutableListOf<VocabularyItem>()
        try {
            context.assets.open(filename).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && items.size < limit) {
                        parseVocabularyLine(line.orEmpty())?.let { items.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WordLevelProvider", "Unable to load wordbook items from $filename: ${e.message}")
        }
        return items
    }

    private fun parseVocabularyLine(line: String): VocabularyItem? {
        val trimmed = line.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null
        if (trimmed.length == 1 && trimmed[0].isUpperCase()) return null
        if (!trimmed.first().isLetter()) return null

        val word = trimmed.takeWhile { !it.isWhitespace() && it != '[' }.lowercase()
        if (word.isEmpty() || !word.all { it.isLetter() || it == '-' || it == '\'' }) return null

        val phonetic = Regex("\\[[^]]+]").find(trimmed)?.value
        val definition = trimmed
            .substringAfter(phonetic ?: word, "")
            .replace(Regex("^\\s+"), "")
            .ifBlank { "释义待补充" }

        return VocabularyItem(
            word = word,
            phonetic = phonetic,
            definition = definition,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun Map<String, String>.toVocabularyItems(): List<VocabularyItem> {
        return map { (word, definition) ->
            VocabularyItem(
                word = word,
                phonetic = null,
                definition = definition,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    companion object {
        private val defaultTem4Words = setOf(
            "abide", "abolish", "abound", "abrupt", "absurd", "acclaim", "accommodate",
            "accumulate", "adhere", "adjacent", "adolescent", "adverse", "advocate",
            "ambiguous", "analogy", "apparatus", "arbitrary", "authentic", "coherent",
            "coincide", "collaborate", "compulsory", "consecutive", "contemplate",
            "controversial", "dedicate", "deficiency", "deliberate", "diplomatic",
            "elaborate", "eligible", "equivalent", "explicit", "fluctuate", "incentive",
            "inevitable", "integrity", "legislation", "manipulate", "notorious",
            "obstacle", "preliminary", "profound", "prosperity", "radical", "restore",
            "subordinate", "substantial", "terminate", "unanimous"
        )

        private val defaultTem8Words = setOf(
            "aberration", "abstinence", "acquiesce", "alleviate", "ambivalent",
            "anachronism", "antagonism", "apprehensive", "belligerent", "benevolent",
            "capricious", "circumspect", "connoisseur", "conspicuous", "cumulative",
            "debilitate", "detrimental", "discernible", "eclectic", "emulate",
            "ephemeral", "equivocal", "exacerbate", "fastidious", "formidable",
            "gregarious", "idiosyncrasy", "impeccable", "indigenous", "inquisitive",
            "meticulous", "oblivious", "paradoxical", "peripheral", "pervasive",
            "pragmatic", "proliferate", "reconcile", "resilient", "scrutinize",
            "spontaneous", "subtle", "tantamount", "tenacious", "ubiquitous"
        )

        private val defaultTem4Definitions = mapOf(
            "abide" to "v. 遵守；忍受",
            "abolish" to "v. 废除，取消",
            "abound" to "v. 大量存在，充满",
            "abrupt" to "a. 突然的；唐突的",
            "absurd" to "a. 荒谬的",
            "acclaim" to "v./n. 称赞，赞扬",
            "accommodate" to "v. 容纳；使适应",
            "accumulate" to "v. 积累，聚积",
            "adhere" to "v. 坚持；粘附",
            "adjacent" to "a. 邻近的",
            "adverse" to "a. 不利的，有害的",
            "advocate" to "v./n. 提倡；拥护者",
            "ambiguous" to "a. 模棱两可的",
            "analogy" to "n. 类比",
            "coherent" to "a. 连贯的，一致的",
            "collaborate" to "v. 合作，协作",
            "compulsory" to "a. 强制的，必修的",
            "controversial" to "a. 有争议的",
            "diplomatic" to "a. 外交的；圆滑的",
            "eligible" to "a. 有资格的，合格的",
            "explicit" to "a. 明确的，清楚的",
            "incentive" to "n. 激励，动机",
            "integrity" to "n. 正直；完整",
            "legislation" to "n. 立法；法规",
            "manipulate" to "v. 操纵，操作",
            "preliminary" to "a. 初步的，预备的",
            "profound" to "a. 深刻的；渊博的",
            "substantial" to "a. 大量的；实质的",
            "terminate" to "v. 终止，结束",
            "unanimous" to "a. 一致同意的"
        )

        private val defaultTem8Definitions = mapOf(
            "aberration" to "n. 偏差，异常",
            "acquiesce" to "v. 默许，勉强同意",
            "alleviate" to "v. 减轻，缓和",
            "ambivalent" to "a. 矛盾的，摇摆不定的",
            "anachronism" to "n. 时代错误",
            "antagonism" to "n. 对抗，敌意",
            "apprehensive" to "a. 忧虑的；有理解力的",
            "belligerent" to "a. 好战的",
            "benevolent" to "a. 仁慈的，善意的",
            "capricious" to "a. 反复无常的",
            "circumspect" to "a. 谨慎周到的",
            "connoisseur" to "n. 鉴赏家，行家",
            "conspicuous" to "a. 显眼的，明显的",
            "debilitate" to "v. 使衰弱",
            "detrimental" to "a. 有害的",
            "eclectic" to "a. 折中的；兼收并蓄的",
            "ephemeral" to "a. 短暂的",
            "equivocal" to "a. 含糊的，可疑的",
            "exacerbate" to "v. 使恶化，加剧",
            "fastidious" to "a. 挑剔的，讲究的",
            "formidable" to "a. 强大的；令人敬畏的",
            "idiosyncrasy" to "n. 特质，习性",
            "impeccable" to "a. 无瑕疵的",
            "meticulous" to "a. 一丝不苟的",
            "oblivious" to "a. 未察觉的，健忘的",
            "paradoxical" to "a. 似矛盾而正确的",
            "pervasive" to "a. 到处弥漫的",
            "pragmatic" to "a. 务实的",
            "proliferate" to "v. 激增，扩散",
            "ubiquitous" to "a. 无处不在的"
        )
    }
}
