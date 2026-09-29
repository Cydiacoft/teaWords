package com.teameow.teawords.data.pronunciation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

enum class PronunciationRegion { UK, US, UNSPECIFIED }

/** Attribution belongs to each asset: the IPA and recording can have different licenses. */
data class PronunciationAttribution(
    val sourceId: String,
    val sourceUrl: String,
    val licenseId: String,
    val licenseUrl: String,
    val attribution: String,
    val retrievedAt: Long
)

data class RecordedPronunciation(
    val url: String,
    val attribution: PronunciationAttribution,
    val sha256: String,
    val playbackPermitted: Boolean = false,
    val cachingPermitted: Boolean = false
)

/**
 * Independent from lex_words and learning_knowledge. One word can have multiple sources/variants
 * per accent without creating any knowledge records. No inferred or converted accent is stored.
 */
data class WordPronunciationEntity(
    val word: String,
    val region: PronunciationRegion,
    val sourceRecordId: String,
    val ipa: String? = null,
    val ipaAttribution: PronunciationAttribution? = null,
    val recording: RecordedPronunciation? = null
) {
    fun validate() {
        require(word.isNotBlank() && sourceRecordId.isNotBlank())
        require(!ipa.isNullOrBlank() || recording != null)
        if (!ipa.isNullOrBlank()) requireNotNull(ipaAttribution).validate()
        recording?.let {
            it.attribution.validate()
            require(it.url.startsWith("https://"))
            require(it.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        }
    }
}

private fun PronunciationAttribution.validate() {
    require(sourceId.isNotBlank() && sourceUrl.isNotBlank() && licenseId.isNotBlank() && attribution.isNotBlank())
}

/** Providers must supply an explicit region and independently verified licensing, never URL guesses. */
interface WordPronunciationSource {
    suspend fun lookup(word: String): List<WordPronunciationEntity>
}

class WordPronunciationRepository(context: Context) : WordPronunciationSource {
    private val app = context.applicationContext
    private val gson = Gson()
    // Caller performs disk operations on Dispatchers.IO. Each operation owns its connection.
    override suspend fun lookup(word: String): List<WordPronunciationEntity> = PronunciationDatabase(app).use { helper ->
        helper.readableDatabase.rawQuery(
            "SELECT payload FROM word_pronunciations WHERE word=? ORDER BY region, source_record_id",
            arrayOf(normalizePronunciationWord(word))
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(gson.fromJson(cursor.getString(0), WordPronunciationEntity::class.java))
        } }
    }
    fun save(entry: WordPronunciationEntity) {
        entry.validate()
        val normalized = entry.copy(word = normalizePronunciationWord(entry.word))
        PronunciationDatabase(app).use { helper ->
            helper.writableDatabase.insertWithOnConflict("word_pronunciations", null, ContentValues().apply {
                put("word", normalized.word)
                put("region", normalized.region.name)
                // Provider-local IDs can overlap. Namespace the storage key by both asset
                // sources, while retaining the original source record ID in the payload.
                put("source_record_id", gson.toJson(listOf(normalized.ipaAttribution?.sourceId,
                    normalized.recording?.attribution?.sourceId, normalized.sourceRecordId)))
                put("payload", gson.toJson(normalized))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
    }
}

fun normalizePronunciationWord(word: String): String = word.trim().lowercase(Locale.ROOT)

private class PronunciationDatabase(context: Context) : SQLiteOpenHelper(context, "pronunciations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE word_pronunciations (word TEXT NOT NULL, region TEXT NOT NULL, source_record_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(word, region, source_record_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** Future licensed recording adapters can populate this cache; TTS never downloads recordings. */
class LicensedPronunciationCache(private val directory: File) {
    private fun allowed(asset: RecordedPronunciation): Boolean = asset.playbackPermitted && asset.cachingPermitted &&
        asset.attribution.licenseId.isNotBlank() && asset.sha256.matches(Regex("[a-fA-F0-9]{64}"))

    fun find(asset: RecordedPronunciation): File? {
        if (!allowed(asset)) return null
        val file = File(directory, asset.sha256.lowercase(Locale.ROOT))
        return file.takeIf { it.isFile && digest(it).equals(asset.sha256, ignoreCase = true) }
    }

    /** Accept only an adapter-provided authorized stream; URLs are never fetched implicitly. */
    fun store(asset: RecordedPronunciation, input: InputStream): File {
        input.use { source ->
            require(allowed(asset)) { "该音频未获得播放与缓存许可" }
            check(directory.isDirectory || directory.mkdirs())
            val temp = File.createTempFile("voice-", ".part", directory)
            try {
                temp.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val size = source.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= 5 * 1024 * 1024) { "单词录音超过 5 MB" }
                        out.write(buffer, 0, size)
                    }
                }
                require(digest(temp).equals(asset.sha256, ignoreCase = true)) { "音频校验失败" }
                val target = File(directory, asset.sha256.lowercase(Locale.ROOT))
                if (!temp.renameTo(target)) {
                    check(find(asset) != null) { "无法保存发音缓存" }
                }
                return target
            } finally { temp.delete() }
        }
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
