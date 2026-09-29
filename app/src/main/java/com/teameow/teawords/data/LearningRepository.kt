package com.teameow.teawords.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class LearningRepository(private val helper: DatabaseHelper) {
    companion object {
        /**
         * Consecutive correct answers required before a word counts as verified by test.
         * Kept here as data-layer truth so profile queries do not depend on the algorithm package;
         * it must stay equal to `Knowledge.verified`'s rule and to BKT's verification streak.
         */
        const val VERIFY_STREAK = 3

        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS learning_knowledge (word TEXT PRIMARY KEY, report TEXT NOT NULL, attempts INTEGER NOT NULL, streak INTEGER NOT NULL, interval_hours REAL NOT NULL, due INTEGER NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS learning_events (id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL, kind TEXT NOT NULL, correct INTEGER, elapsed INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS learning_events_time ON learning_events(timestamp)")
            db.execSQL("CREATE TABLE IF NOT EXISTS learning_sources (word TEXT NOT NULL, source TEXT NOT NULL, license TEXT NOT NULL, PRIMARY KEY(word, source))")
            val columns = db.rawQuery("PRAGMA table_info(learning_knowledge)", null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            if ("word_id" !in columns) db.execSQL("ALTER TABLE learning_knowledge ADD COLUMN word_id INTEGER REFERENCES lex_words(id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS learning_knowledge_word_id ON learning_knowledge(word_id)")
        }
    }

    fun importWords(words: List<ImportedWord>, source: String, license: String): Int {
        val db = helper.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            words.forEach { word ->
                LocalDictionary(helper).upsert(word.word, word.definition, source = source)
                if (!helper.isInVocabulary(word.word)) {
                    helper.addVocabulary(word.word, "", word.definition)
                    added++
                }
                db.insertWithOnConflict("learning_sources", null, ContentValues().apply {
                    put("word", word.word); put("source", source); put("license", license)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

    fun states(additionalWords: Collection<String> = emptyList()): List<Knowledge> {
        val stored = mutableMapOf<String, Knowledge>()
        helper.readableDatabase.rawQuery("SELECT * FROM learning_knowledge", null).use { c ->
            while (c.moveToNext()) stored[c.getString(0)] = Knowledge(c.getString(0), SelfReport.valueOf(c.getString(1)), c.getInt(2), c.getInt(3), c.getDouble(4), c.getLong(5), c.getLong(6))
        }
        return (helper.getVocabulary().map { it.word } + additionalWords).distinct().map { stored[it] ?: Knowledge(it) }
    }

    fun save(state: Knowledge, kind: String, correct: Boolean? = null, elapsed: Long = 0, recordEvidence: Boolean = true) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val dictionary = LocalDictionary(helper)
            val wordId = dictionary.exact(state.word)?.id ?: dictionary.upsert(state.word, "")
            db.insertWithOnConflict("learning_knowledge", null, ContentValues().apply {
                put("word_id", wordId)
                put("word", state.word); put("report", state.report.name); put("attempts", state.attempts)
                put("streak", state.streak); put("interval_hours", state.intervalHours); put("due", state.due); put("updated", state.updated)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "学习状态保存失败" } }
            // State and evidence commit together, including when this is a nested transaction.
            if (recordEvidence) recordEvent(state.word, kind, correct, elapsed, state.updated)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Writes one answer to the shared evidence log.
     *
     * This is the single path every answer goes through — screening taps, recall practice and
     * diagnostics — so the log always carries the practice mode, grade and the sense it belonged to.
     * Without those columns a later model fit could never replay the history.
     */
    fun recordEvent(
        word: String,
        kind: String,
        correct: Boolean?,
        elapsedMillis: Long,
        timestamp: Long = System.currentTimeMillis(),
        senseId: Long? = null,
        mode: String? = null,
        grade: Int? = null,
        revealed: Boolean? = null
    ) {
        helper.writableDatabase.insertOrThrow(
            "learning_events", null,
            android.content.ContentValues().apply {
                put("word", word)
                put("kind", kind)
                if (correct == null) putNull("correct") else put("correct", if (correct) 1 else 0)
                put("elapsed", elapsedMillis.coerceIn(0, 300_000))
                put("timestamp", timestamp)
                if (senseId == null) putNull("sense_id") else put("sense_id", senseId)
                if (mode == null) putNull("mode") else put("mode", mode)
                if (grade == null) putNull("grade") else put("grade", grade)
                if (revealed == null) putNull("revealed") else put("revealed", if (revealed) 1 else 0)
            }
        )
    }

    data class Totals(val tests: Int, val correct: Int, val seconds: Long, val screened: Int)

    /** Shared persistence for book learning and lookup-word review. */
    fun saveReview(
        state: Knowledge, verdict: com.teameow.teawords.algorithm.ReviewVerdict,
        wordId: Long, senseId: Long, correct: Boolean, revealed: Boolean,
        elapsed: Long, now: Long, abilityBefore: Double
    ): Knowledge {
        val updated = LearningEngine.answer(state, correct && !revealed, now).copy(
            intervalHours = verdict.intervalDays?.times(24.0) ?: state.intervalHours,
            due = verdict.dueAt ?: state.due
        )
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            SenseRepository(helper).recordReview(
                wordId = wordId, senseId = senseId, word = state.word, mode = "RECALL",
                correct = correct, revealed = revealed, elapsedMillis = elapsed, now = now,
                grade = verdict.grade.ordinal, difficulty = verdict.memory.difficulty,
                stability = verdict.memory.stability, dueAt = verdict.dueAt,
                intervalDays = verdict.intervalDays, reps = verdict.memory.reps, lapses = verdict.memory.lapses,
                masteryBefore = null, masteryAfter = verdict.knowledge.mastery,
                abilityBefore = abilityBefore, abilityAfter = verdict.ability.theta
            )
            save(updated, "test", correct && !revealed, elapsed, recordEvidence = false)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return updated
    }
    fun totals(since: Long = 0): Totals = helper.readableDatabase.rawQuery(
        "SELECT COUNT(correct), COALESCE(SUM(correct),0), COALESCE(SUM(elapsed),0)/1000, COALESCE(SUM(CASE WHEN kind='screen' THEN 1 ELSE 0 END),0) FROM learning_events WHERE timestamp>=?", arrayOf(since.toString())
    ).use { it.moveToFirst(); Totals(it.getInt(0), it.getInt(1), it.getLong(2), it.getInt(3)) }

    /** Count first study days across all books; switching books cannot reset the new-word budget. */
    fun newWordsSince(since: Long): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM (SELECT word FROM learning_events WHERE kind='test' " +
            "AND (mode IS NULL OR mode <> 'DIAGNOSTIC') GROUP BY word HAVING MIN(timestamp)>=CAST(? AS INTEGER))",
        arrayOf(since.toString())
    ).use { it.moveToFirst(); it.getInt(0) }

    /** Per-day counts for the recent trend; only days with persisted answers appear. */
    data class Daily(val day: String, val tests: Int, val correct: Int, val seconds: Long)

    fun daily(since: Long): List<Daily> = helper.readableDatabase.rawQuery(
        "SELECT date(timestamp/1000,'unixepoch','localtime') d, COUNT(correct), COALESCE(SUM(correct),0), COALESCE(SUM(elapsed),0)/1000 FROM learning_events WHERE timestamp>=? AND kind='test' GROUP BY d ORDER BY d", arrayOf(since.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(Daily(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3))) } }

    /** Distinct days with at least one persisted answer, newest first. */
    fun activeDays(limit: Int = 400): List<String> = helper.readableDatabase.rawQuery(
        "SELECT DISTINCT date(timestamp/1000,'unixepoch','localtime') d FROM learning_events WHERE kind='test' ORDER BY d DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /**
     * Reverts a self-reported "I know it" back to "fuzzy".
     *
     * The user correcting the app's judgement must be a first-class action: without it, a wrong tap
     * during screening would keep a word out of the queue with no way back.
     */
    fun revertSelfReport(word: String, now: Long = System.currentTimeMillis()) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE learning_knowledge SET report='FUZZY', streak=0, interval_hours=0, due=0, updated=? WHERE word=?",
                arrayOf(now.toString(), word)
            )
            db.execSQL(
                "INSERT INTO learning_events(word, kind, correct, elapsed, timestamp) VALUES(?, 'undo', NULL, 0, ?)",
                arrayOf(word, now.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- Knowledge profile ---------------------------------------------------------------

    /**
     * One row per word the user has knowledge for, with the evidence that backs it.
     *
     * [verified] is real evidence: consecutive correct answers reached the verification streak.
     * [predictedKnown] is a *model prediction* for a word that was never tested, taken from the
     * stored belief. The UI must keep the two apart; they are returned as separate fields so they
     * cannot be accidentally summed into one "mastered" number.
     */
    data class WordRow(
        val word: String,
        val report: String,
        val attempts: Int,
        val streak: Int,
        val intervalHours: Double,
        val due: Long,
        val updated: Long,
        val tags: String
    ) {
        val verified: Boolean get() = streak >= VERIFY_STREAK
        val selfReportedKnown: Boolean get() = report == "KNOWN" && attempts == 0
    }

    fun wordRows(): List<WordRow> = helper.readableDatabase.rawQuery(
        "SELECT k.word, k.report, k.attempts, k.streak, k.interval_hours, k.due, k.updated, " +
            "COALESCE(w.tags, '') FROM learning_knowledge k " +
            "LEFT JOIN lex_words w ON w.id = k.word_id OR (k.word_id IS NULL AND w.normalized = lower(trim(k.word)))",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    WordRow(
                        word = c.getString(0), report = c.getString(1), attempts = c.getInt(2),
                        streak = c.getInt(3), intervalHours = c.getDouble(4), due = c.getLong(5),
                        updated = c.getLong(6), tags = c.getString(7).orEmpty()
                    )
                )
            }
        }
    }

    /** Dictionary size, used as the denominator for any coverage figure. */
    fun dictionarySize(): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM lex_words", null
    ).use { it.moveToFirst(); it.getInt(0) }

    /** Word plus the word book tags it belongs to, for ability-based screening decisions. */
    data class TaggedWord(val word: String, val tags: String)

    fun taggedWordsForVocabulary(): List<TaggedWord> = helper.readableDatabase.rawQuery(
        "SELECT v.word, COALESCE(w.tags, '') FROM vocabulary v " +
            "LEFT JOIN lex_words w ON w.normalized = lower(trim(v.word))",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(TaggedWord(c.getString(0), c.getString(1).orEmpty())) } }

    /** Counts per vocabulary band, derived from word book tags. No frequency data is invented. */
    data class BandCounts(val cet4: Int, val cet6: Int, val outside: Int)

    /** Words that crossed the verification threshold on each of the last [days] days. */
    fun newlyVerifiedByDay(since: Long): Map<String, Int> {
        // A word counts as newly verified on the day its streak reached the threshold. The streak is
        // not stored per event, so this uses the day of the answer that produced the current streak.
        val threshold = VERIFY_STREAK
        return helper.readableDatabase.rawQuery(
            "SELECT date(timestamp/1000,'unixepoch','localtime') d, COUNT(*) FROM (" +
                "SELECT k.word, MAX(e.timestamp) timestamp FROM learning_knowledge k " +
                "JOIN learning_events e ON e.word = k.word AND e.kind='test' AND e.correct=1 " +
                "WHERE k.streak >= ? GROUP BY k.word) WHERE timestamp >= ? GROUP BY d",
            arrayOf(threshold.toString(), since.toString())
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }
    }
}
