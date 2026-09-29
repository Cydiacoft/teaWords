package com.teameow.teawords.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Installs the pre-built dictionary that ships in `assets/dictionary.db`.
 *
 * Why a pre-built file instead of parsing a CSV at runtime: the dataset has 770,611 source rows and
 * the shipped subset has 57,841 entries with 197,766 senses and 465,982 reverse-lookup tokens.
 * Building those indexes on a phone would take minutes and would have to be redone whenever the data
 * changed. The build tool produces the file once; the app only copies it.
 *
 * It is deliberately a **separate database file** from `teawords.db`, which holds the user's learning
 * records. Updating the dictionary therefore cannot touch knowledge state: the installer replaces the
 * dictionary file alone and the user database keeps its own `word_id` values, which are stable because
 * the build tool assigns ids from a deterministic scan of the source.
 *
 * The copy is streamed with a progress callback: 61 MB must not be read into memory, and the UI needs
 * to show that something is happening on first launch.
 */
class BundledDictionaryInstaller(private val context: Context) {

    companion object {
        const val ASSET_NAME = "dictionary.db"
        const val DB_NAME = "dictionary.db"
        /**
         * Must equal `dictionary_meta.schema_version` inside the shipped asset.
         *
         * Bumped to 2 when the reverse-lookup token statistics (`lex_token_stats`) were added. The bump is
         * what makes an already-installed v1 copy get replaced: [isCurrent] compares this constant against
         * the value stored *in the installed file*, so leaving it at 1 would have left every existing
         * device on the old copy — and the related-meaning search would have kept failing there forever.
         */
        const val SCHEMA_VERSION = 2
        private const val BUFFER = 256 * 1024
    }

    data class Result(
        val installed: Boolean,
        val bytes: Long,
        val target: File
    )

    fun targetFile(): File = context.getDatabasePath(DB_NAME)

    /** True when a usable copy is already in place for the current asset version. */
    fun isCurrent(): Boolean {
        val file = targetFile()
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM dictionary_meta WHERE key='schema_version'", null)
                    .use { c -> c.moveToFirst() && c.getString(0) == SCHEMA_VERSION.toString() }
            }
        }.getOrDefault(false)
    }

    /**
     * Copies the asset when needed and reports progress in bytes.
     *
     * @param force reinstall even when the current copy looks valid, used for a dictionary update.
     */
    fun install(force: Boolean = false, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result {
        val target = targetFile()
        if (!force && isCurrent()) return Result(installed = false, bytes = target.length(), target = target)

        target.parentFile?.mkdirs()
        // A half-written file must never look valid, so write to a temporary name and rename last.
        val temp = File(target.parentFile, "$DB_NAME.tmp")
        if (temp.exists()) temp.delete()

        var copied = 0L
        context.assets.open(ASSET_NAME).use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(BUFFER)
                val total = runCatching { input.available().toLong() }.getOrDefault(0L)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, if (total > 0) total else copied)
                }
                output.flush()
            }
        }
        // Stale write-ahead log files from a previous copy would corrupt the fresh database.
        File(target.path + "-wal").takeIf { it.exists() }?.delete()
        File(target.path + "-shm").takeIf { it.exists() }?.delete()
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            // renameTo can fail across some filesystems; fall back to a copy so the install still works.
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return Result(installed = true, bytes = copied, target = target)
    }
}
