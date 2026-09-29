package com.teameow.teawords.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Rebinds the user's existing progress from the old in-app word ids to the shipped dictionary's ids.
 *
 * ## Why this is needed
 *
 * Progress has always been stored against a numeric `word_id`, but the number came from the *old* inline
 * index built from `cet4.txt`/`cet6.txt`. The shipped dictionary assigns ids from a deterministic scan of
 * ECDICT, so the same word has a different number in each: `abandon` was 1 and is now 16. Anything that
 * keeps the old numbers while the app starts looking words up in the new dictionary would attach the
 * user's progress to the wrong words, which is worse than losing it because it looks correct.
 *
 * Measured on a real device database: of the 20 knowledge rows, **20/20** rebind by word text; 5,706 of
 * the old index's 5,707 words exist in the shipped dictionary (only `air-condition` does not).
 *
 * ## The id-space invariant
 *
 * After this migration:
 *
 *  * `word_id > 0` — the global dictionary id, stable across books and devices, so knowledge is reused
 *    wherever the word appears.
 *  * `word_id < 0` — a user-only word that the reference dictionary does not contain. The old id is kept
 *    as its negative, which keeps the two spaces disjoint: a user-only word can never be mistaken for a
 *    dictionary word, and nothing has to be deleted to achieve that.
 *
 * ## What is deliberately *not* migrated
 *
 * Sense *identity* stays owned by `teawords.db`. Only 21% of the derived `sense_units` texts still appear
 * verbatim in the new dictionary (2,720 / 12,923), so rewriting sense rows from the new glosses would
 * silently discard three quarters of the derived model for no gain. The dictionary file is a read-only
 * reference; the user's sense model, and the progress attached to it, stay where they are.
 *
 * The whole thing runs inside one transaction and records the dictionary build it bound to, so it applies
 * once per dictionary build rather than on every launch.
 */
class WordIdMigration(private val helper: DatabaseHelper) {

    companion object {
        /** `lex_meta` key holding the `built_at` of the dictionary this database is bound to. */
        const val BINDING_KEY = "dictionary-binding"
    }

    /**
     * @param applied false when this database is already bound to [dictionaryBuiltAt].
     * @param unmatched words the reference dictionary does not contain; their ids stay negative.
     */
    data class Report(
        val applied: Boolean,
        val dictionaryBuiltAt: String,
        val knowledge: Int,
        val senses: Int,
        val testRecords: Int,
        val difficulty: Int,
        val unmatchedSenses: Int,
        val unmatchedRecords: Int,
        val userOnlyWords: Int
    ) {
        val changed: Int get() = knowledge + senses + testRecords + difficulty
        fun describe(): String =
            if (!applied) "词典绑定已是最新，未改动任何学习记录"
            else "已按全局词条 ID 重新绑定：知识 $knowledge 行、义项 $senses 行、测试记录 $testRecords 行、" +
                "题目难度 $difficulty 行；词典中不存在的自建词 $userOnlyWords 个（ID 记为负数）"
    }

    private fun db(): SQLiteDatabase = helper.writableDatabase

    /** True when this database has not yet been bound to the given dictionary build. */
    fun needsBinding(dictionaryBuiltAt: String): Boolean = db().rawQuery(
        "SELECT value FROM lex_meta WHERE key=?", arrayOf(BINDING_KEY)
    ).use { if (it.moveToFirst()) it.getString(0) != dictionaryBuiltAt else true }

    /**
     * Rebinds every table that carries a `word_id` to the shipped dictionary.
     *
     * Must run after the dictionary file is installed and readable, and before anything reads progress by
     * `word_id`. Safe to call on every launch: it returns immediately once the binding is current.
     */
    fun apply(dictionaryFile: File, dictionaryBuiltAt: String): Report {
        if (!needsBinding(dictionaryBuiltAt)) {
            return Report(false, dictionaryBuiltAt, 0, 0, 0, 0, 0, 0, 0)
        }
        val database = db()
        LocalDictionary.create(database)
        acceptWordIdColumn(database)
        // ATTACH cannot be a prepared statement on every supported SQLite build, and the path is ours
        // (from getDatabasePath), so it is escaped and inlined rather than bound.
        val path = dictionaryFile.absolutePath.replace("'", "''")
        database.execSQL("ATTACH DATABASE '$path' AS dict")
        try {
            if (!hasAttachedDictionary(database)) return Report(false, dictionaryBuiltAt, 0, 0, 0, 0, 0, 0, 0)
            database.beginTransaction()
            try {
                val report = rebind(database, dictionaryBuiltAt)
                database.setTransactionSuccessful()
                return report
            } finally {
                database.endTransaction()
            }
        } finally {
            runCatching { database.execSQL("DETACH DATABASE dict") }
        }
    }

    private fun hasAttachedDictionary(database: SQLiteDatabase): Boolean =
        runCatching {
            database.rawQuery("SELECT 1 FROM dict.lex_words LIMIT 1", null).use { true }
        }.getOrDefault(false)

    private fun rebind(database: SQLiteDatabase, dictionaryBuiltAt: String): Report {
        // `learning_knowledge` stores the normalised word, so it rebinds by text and cannot be misled by a
        // stale number. The tables without text go through the old inline index to find that text first.
        val knowledge = update(database,
            "UPDATE learning_knowledge SET word_id = " +
                "(SELECT d.id FROM dict.lex_words d WHERE d.normalized = learning_knowledge.word) " +
                "WHERE EXISTS (SELECT 1 FROM dict.lex_words d WHERE d.normalized = learning_knowledge.word)"
        )
        val userOnly = update(database,
            "UPDATE learning_knowledge SET word_id = -abs(word_id) " +
                "WHERE word_id IS NOT NULL AND word_id > 0 " +
                "AND NOT EXISTS (SELECT 1 FROM dict.lex_words d WHERE d.normalized = learning_knowledge.word)"
        )
        // Sense units, test records and item difficulty only carry the number, so the old index supplies
        // the text. A word the dictionary lacks keeps its old id, negated.
        val senses = rebindByLegacyId(database, "sense_units")
        val records = rebindByLegacyId(database, "test_records")
        val difficulty = rebindByLegacyId(database, "item_difficulty")
        database.execSQL(
            "INSERT OR REPLACE INTO lex_meta(key,value) VALUES(?,?)",
            arrayOf(BINDING_KEY, dictionaryBuiltAt)
        )
        val unmatchedSenses = count(database, "SELECT COUNT(*) FROM sense_units WHERE word_id < 0")
        val unmatchedRecords = count(database, "SELECT COUNT(*) FROM test_records WHERE word_id < 0")
        return Report(
            applied = true,
            dictionaryBuiltAt = dictionaryBuiltAt,
            knowledge = knowledge,
            senses = senses,
            testRecords = records,
            difficulty = difficulty,
            unmatchedSenses = unmatchedSenses,
            unmatchedRecords = unmatchedRecords,
            userOnlyWords = userOnly
        )
    }

    /**
     * Rebinds a table that only has `word_id`, by resolving the old id to its normalised word and then to
     * the dictionary id.
     *
     * Order matters and is the subtle part: rows the dictionary cannot resolve are negated **first**, while
     * every row still holds its original id. Mapping first would overwrite an id with a dictionary id, and
     * the follow-up "unmatched" check would then compare a *new* id against the *old* index — which matches
     * a different word for any dictionary id below the old index's maximum, silently negating rows that had
     * just been mapped correctly.
     */
    private fun rebindByLegacyId(database: SQLiteDatabase, table: String): Int {
        val negated = update(database,
            "UPDATE $table SET word_id = -abs(word_id) WHERE word_id > 0 " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM dict.lex_words d JOIN main.lex_words o ON o.normalized = d.normalized " +
                "WHERE o.id = $table.word_id)"
        )
        // Only rows still holding a positive (original) id are mapped, so the negated ones are untouched.
        val matched = update(database,
            "UPDATE $table SET word_id = (" +
                "SELECT d.id FROM dict.lex_words d JOIN main.lex_words o ON o.normalized = d.normalized " +
                "WHERE o.id = $table.word_id) " +
                "WHERE word_id > 0 AND EXISTS (" +
                "SELECT 1 FROM dict.lex_words d JOIN main.lex_words o ON o.normalized = d.normalized " +
                "WHERE o.id = $table.word_id)"
        )
        return matched + negated
    }

    private fun update(database: SQLiteDatabase, sql: String): Int {
        database.execSQL(sql)
        return database.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun count(database: SQLiteDatabase, sql: String): Int =
        database.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }

    /**
     * Adds `learning_knowledge.word_id` if an old install predates it.
     *
     * The migration must not depend on the app having run its newer schema pass first, because the
     * ProcessText entry point can open this database directly.
     */
    private fun acceptWordIdColumn(database: SQLiteDatabase) {
        val columns = database.rawQuery("PRAGMA table_info(learning_knowledge)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        if (columns.isNotEmpty() && "word_id" !in columns) {
            database.execSQL("ALTER TABLE learning_knowledge ADD COLUMN word_id INTEGER")
        }
    }
}
