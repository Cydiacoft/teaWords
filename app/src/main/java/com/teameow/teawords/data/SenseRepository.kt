package com.teameow.teawords.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * A meaning of a word, derived from the bundled gloss.
 *
 * [confidence] records how reliable the boundary is: it is high when the gloss contained an explicit
 * separator and low when a single long gloss had to be treated as one meaning. The UI must show
 * this, because these are **derived units for progress tracking, not verified dictionary senses**.
 */
data class SenseUnitRecord(
    val id: Long,
    val wordId: Long,
    val word: String,
    val ordinal: Int,
    val text: String,
    val confidence: Double
)

/** Knowledge state for one sense of one word. Test evidence only; never a model guess. */
data class SenseKnowledge(
    val senseId: Long,
    val attempts: Int,
    val correctStreak: Int,
    val lastResult: Boolean?,
    val lastTestedAt: Long,
    val dueAt: Long,
    val intervalDays: Double
) {
    val verified: Boolean get() = correctStreak >= LearningRepository.VERIFY_STREAK
    val tested: Boolean get() = attempts > 0

    companion object {
        fun unseen(senseId: Long) = SenseKnowledge(senseId, 0, 0, null, 0L, 0L, 0.0)
    }
}

/**
 * Difficulty prior for one word and practice mode, with the origin of the number.
 *
 * [calibrated] is false for every row this build writes: the value comes from a word book tag, not
 * from fitting a model on responses. It exists so a future calibration step can update rows in place
 * and flip the flag, and so the UI can never present a heuristic as a fitted IRT parameter.
 */
data class ItemDifficultyRecord(
    val wordId: Long,
    val mode: String,
    val difficulty: Double,
    val calibrated: Boolean,
    val source: String,
    val updatedAt: Long
)

/**
 * Schema v6: sense-level knowledge, difficulty priors and a richer evidence log.
 *
 * Design rules applied here:
 * * New tables only — no existing column is dropped or rewritten, so upgrades keep user data.
 * * `learning_events` gains nullable columns, so old rows stay valid and readable.
 * * Everything is additive and idempotent, so `onCreate` and `onUpgrade` can share one path.
 */
class SenseRepository(private val helper: DatabaseHelper) {

    companion object {
        const val TABLE_SENSE_UNITS = "sense_units"
        const val TABLE_USER_SENSE_KNOWLEDGE = "user_sense_knowledge"
        const val TABLE_ITEM_DIFFICULTY = "item_difficulty"
        const val TABLE_TEST_RECORDS = "test_records"

        /** Column added to learning_events so a review can be traced to a sense and a practice mode. */
        const val EVENT_SENSE_ID = "sense_id"
        const val EVENT_MODE = "mode"
        const val EVENT_GRADE = "grade"
        const val EVENT_REVEALED = "revealed"

        fun create(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_SENSE_UNITS(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "word_id INTEGER NOT NULL REFERENCES lex_words(id)," +
                    "ordinal INTEGER NOT NULL," +
                    "text TEXT NOT NULL," +
                    "confidence REAL NOT NULL," +
                    "derived_at INTEGER NOT NULL," +
                    "UNIQUE(word_id, ordinal, text))"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS sense_units_word ON $TABLE_SENSE_UNITS(word_id)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_USER_SENSE_KNOWLEDGE(" +
                    "sense_id INTEGER PRIMARY KEY REFERENCES $TABLE_SENSE_UNITS(id)," +
                    "attempts INTEGER NOT NULL DEFAULT 0," +
                    "correct_streak INTEGER NOT NULL DEFAULT 0," +
                    "last_result INTEGER," +
                    "last_tested_at INTEGER NOT NULL DEFAULT 0," +
                    "due_at INTEGER NOT NULL DEFAULT 0," +
                    "interval_days REAL NOT NULL DEFAULT 0," +
                    "stability REAL NOT NULL DEFAULT 0," +
                    "difficulty REAL NOT NULL DEFAULT 0," +
                    "reps INTEGER NOT NULL DEFAULT 0," +
                    "lapses INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_ITEM_DIFFICULTY(" +
                    "word_id INTEGER NOT NULL," +
                    "mode TEXT NOT NULL," +
                    "difficulty REAL NOT NULL," +
                    "calibrated INTEGER NOT NULL DEFAULT 0," +
                    "source TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "PRIMARY KEY(word_id, mode))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_TEST_RECORDS(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "word_id INTEGER," +
                    "sense_id INTEGER," +
                    "mode TEXT NOT NULL," +
                    "correct INTEGER NOT NULL," +
                    "revealed INTEGER NOT NULL DEFAULT 0," +
                    "elapsed_ms INTEGER NOT NULL DEFAULT 0," +
                    "ability_before REAL," +
                    "ability_after REAL," +
                    "mastery_before REAL," +
                    "mastery_after REAL," +
                    "timestamp INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS test_records_time ON $TABLE_TEST_RECORDS(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS test_records_word ON $TABLE_TEST_RECORDS(word_id)")
            addEventColumns(db)
            addTestRecordColumns(db)
        }

        /**
         * Adds later columns to an existing test_records table.
         *
         * `CREATE TABLE IF NOT EXISTS` silently does nothing when the table already exists, so a
         * database created by an earlier build would keep a table without `grade` for ever and every
         * insert naming that column would fail. Additive ALTERs are the only safe way to evolve it.
         */
        private fun addTestRecordColumns(db: SQLiteDatabase) {
            val existing = db.rawQuery("PRAGMA table_info($TABLE_TEST_RECORDS)", null)
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            if ("grade" !in existing) {
                db.execSQL("ALTER TABLE $TABLE_TEST_RECORDS ADD COLUMN grade INTEGER")
            }
        }

        /** learning_events gains the columns needed to replay a review later. Additive only. */
        private fun addEventColumns(db: SQLiteDatabase) {
            val existing = db.rawQuery("PRAGMA table_info(learning_events)", null)
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            if (EVENT_SENSE_ID !in existing) {
                db.execSQL("ALTER TABLE learning_events ADD COLUMN $EVENT_SENSE_ID INTEGER")
            }
            if (EVENT_MODE !in existing) {
                db.execSQL("ALTER TABLE learning_events ADD COLUMN $EVENT_MODE TEXT")
            }
            if (EVENT_GRADE !in existing) {
                db.execSQL("ALTER TABLE learning_events ADD COLUMN $EVENT_GRADE INTEGER")
            }
            if (EVENT_REVEALED !in existing) {
                db.execSQL("ALTER TABLE learning_events ADD COLUMN $EVENT_REVEALED INTEGER")
            }
        }

        /** Columns created before this version have no mode; they are read as active recall. */
        const val DEFAULT_MODE = "RECALL"

        /** Diagnostic answers are stored under their own mode so they can be analysed separately. */
        const val MODE_DIAGNOSTIC = "DIAGNOSTIC"
    }

    private val db get() = helper.writableDatabase

    // --- Sense units -----------------------------------------------------------------------

    fun sensesFor(wordId: Long): List<SenseUnitRecord> = db.rawQuery(
        "SELECT s.id, s.word_id, COALESCE(w.word, ''), s.ordinal, s.text, s.confidence " +
            "FROM $TABLE_SENSE_UNITS s LEFT JOIN lex_words w ON w.id = s.word_id " +
            "WHERE s.word_id = ? ORDER BY s.ordinal", arrayOf(wordId.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(SenseUnitRecord(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4), c.getDouble(5)))
            }
        }
    }

    fun senseCountByWord(wordId: Long): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_SENSE_UNITS WHERE word_id = ?", arrayOf(wordId.toString())
    ).use { it.moveToFirst(); it.getInt(0) }

    /**
     * The first derived sense of a word, as `wordId to senseId`.
     *
     * Looked up per word when a round needs it, then cached by the caller: resolving every word in a
     * 4500-entry book up front would be a large join for data the round may never reach.
     */
    fun primarySenseOf(word: String): Pair<Long, Long>? = db.rawQuery(
        "SELECT w.id, s.id FROM lex_words w JOIN $TABLE_SENSE_UNITS s ON s.word_id = w.id " +
            "WHERE w.normalized = ? ORDER BY s.ordinal LIMIT 1",
        arrayOf(LexicalText.normalize(word))
    ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }

    /** Resolve reference ids first and derive only words being studied. */
    fun ensurePrimarySense(word: String): Pair<Long, Long> {
        val entry = LocalDictionary(helper).exact(word) ?: error("找不到词条：$word")
        fun existing(): Long? = db.rawQuery("SELECT id FROM sense_units WHERE word_id=? ORDER BY ordinal, id LIMIT 1", arrayOf(entry.id.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        existing()?.let { return entry.id to it }
        val units = LexicalText.splitSenses(entry.zh.ifBlank { entry.en })
        require(units.isNotEmpty()) { "该词暂无可学习释义：$word" }
        db.beginTransaction()
        try {
            units.forEach { unit ->
                db.insertWithOnConflict(TABLE_SENSE_UNITS, null, ContentValues().apply {
                    put("word_id", entry.id); put("ordinal", unit.ordinal); put("text", unit.text)
                    put("confidence", unit.confidence); put("derived_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return entry.id to checkNotNull(existing())
    }

    /**
     * Derives sense units for every dictionary word that has none yet.
     *
     * Idempotent: the uniqueness constraint means re-running adds nothing. Runs in one transaction
     * and reports progress so a first-run build can show it. Words whose gloss cannot be split get a
     * single low-confidence unit, which is how the UI knows not to treat it as a real sense.
     */
    fun deriveMissingSenseUnits(progress: (Int) -> Unit = {}): Int {
        val pending = db.rawQuery(
            "SELECT w.id, w.word, w.zh FROM lex_words w " +
                "WHERE w.zh <> '' AND NOT EXISTS (SELECT 1 FROM $TABLE_SENSE_UNITS s WHERE s.word_id = w.id)",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) } }
        if (pending.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var added = 0
        db.beginTransaction()
        try {
            pending.forEach { (wordId, _, gloss) ->
                LexicalText.splitSenses(gloss).forEach { unit ->
                    db.insertWithOnConflict(
                        TABLE_SENSE_UNITS, null,
                        ContentValues().apply {
                            put("word_id", wordId)
                            put("ordinal", unit.ordinal)
                            put("text", unit.text)
                            put("confidence", unit.confidence)
                            put("derived_at", now)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    added++
                }
                if (added % 500 == 0) progress(added)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    // --- Sense knowledge -------------------------------------------------------------------

    fun knowledgeFor(senseIds: List<Long>): Map<Long, SenseKnowledge> {
        if (senseIds.isEmpty()) return emptyMap()
        val placeholders = senseIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT sense_id, attempts, correct_streak, last_result, last_tested_at, due_at, interval_days " +
                "FROM $TABLE_USER_SENSE_KNOWLEDGE WHERE sense_id IN ($placeholders)",
            senseIds.map { it.toString() }.toTypedArray()
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val lastResult = if (c.isNull(3)) null else c.getInt(3) == 1
                    put(
                        id,
                        SenseKnowledge(id, c.getInt(1), c.getInt(2), lastResult, c.getLong(4), c.getLong(5), c.getDouble(6))
                    )
                }
            }
        }
    }

    /**
     * Writes one test result for a sense and updates its knowledge row.
     *
     * Both the evidence row and the derived state are written in one transaction: a crash can never
     * leave knowledge that no answer backs. Memory parameters are supplied by the caller (the FSRS
     * scheduler), so this layer stores them without reimplementing the model.
     */
    fun recordSenseAnswer(
        wordId: Long,
        senseId: Long,
        mode: String,
        correct: Boolean,
        revealed: Boolean,
        elapsedMillis: Long,
        now: Long,
        grade: Int? = null,
        abilityBefore: Double? = null,
        abilityAfter: Double? = null,
        masteryBefore: Double? = null,
        masteryAfter: Double? = null,
        difficulty: Double? = null,
        stability: Double? = null,
        dueAt: Long? = null,
        intervalDays: Double? = null,
        reps: Int? = null,
        lapses: Int? = null,
        resolvedWord: String? = null
    ) {
        db.beginTransaction()
        try {
            val current = knowledgeFor(listOf(senseId))[senseId] ?: SenseKnowledge.unseen(senseId)
            val streak = if (correct && !revealed) current.correctStreak + 1 else 0
            val attempts = current.attempts + 1
            // A revealed answer or a miss is not evidence of knowledge, but both are recorded.
            val values = ContentValues().apply {
                put("sense_id", senseId)
                put("attempts", attempts)
                put("correct_streak", streak)
                put("last_result", if (correct) 1 else 0)
                put("last_tested_at", now)
                put("updated_at", now)
                dueAt?.let { put("due_at", it) }
                intervalDays?.let { put("interval_days", it) }
                difficulty?.let { put("difficulty", it) }
                stability?.let { put("stability", it) }
                reps?.let { put("reps", it) }
                lapses?.let { put("lapses", it) }
                if (dueAt == null && current.dueAt > 0) put("due_at", current.dueAt)
                if (intervalDays == null && current.intervalDays > 0) put("interval_days", current.intervalDays)
            }
            // Recognition self-reports estimate ability; they are not recall or mastery evidence.
            if (mode != MODE_DIAGNOSTIC) {
                db.insertWithOnConflict(TABLE_USER_SENSE_KNOWLEDGE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    .also { check(it != -1L) { "义项状态保存失败" } }
            }

            db.insertOrThrow(
                TABLE_TEST_RECORDS, null,
                ContentValues().apply {
                    put("word_id", wordId)
                    put("sense_id", senseId)
                    put("mode", mode)
                    put("correct", if (correct) 1 else 0)
                    put("revealed", if (revealed) 1 else 0)
                    put("elapsed_ms", elapsedMillis.coerceIn(0, 300_000))
                    grade?.let { put("grade", it) }
                    abilityBefore?.let { put("ability_before", it) }
                    abilityAfter?.let { put("ability_after", it) }
                    masteryBefore?.let { put("mastery_before", it) }
                    masteryAfter?.let { put("mastery_after", it) }
                    put("timestamp", now)
                }
            )
            // The shared evidence log is written in the same transaction: one answer must never
            // exist in the sense table without appearing in the replayable log, or vice versa.
            val word = resolvedWord ?: db.rawQuery("SELECT word FROM lex_words WHERE id=?", arrayOf(wordId.toString()))
                .use { if (it.moveToFirst()) it.getString(0) else "" }
            db.insertOrThrow(
                "learning_events", null,
                ContentValues().apply {
                    put("word", word)
                    put("kind", "test")
                    put("correct", if (correct) 1 else 0)
                    put("elapsed", elapsedMillis.coerceIn(0, 300_000))
                    put("timestamp", now)
                    put("sense_id", senseId)
                    put("mode", mode)
                    if (grade == null) putNull("grade") else put("grade", grade)
                    put("revealed", if (revealed) 1 else 0)
                }
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Clears a sense back to untested, used when the user corrects the app's judgement. */
    fun resetSense(senseId: Long, now: Long) {        db.beginTransaction()
        try {
            db.delete(TABLE_USER_SENSE_KNOWLEDGE, "sense_id=?", arrayOf(senseId.toString()))
            db.execSQL(
                "INSERT OR REPLACE INTO $TABLE_USER_SENSE_KNOWLEDGE(sense_id, attempts, correct_streak, " +
                    "last_result, last_tested_at, due_at, interval_days, stability, difficulty, reps, lapses, updated_at) " +
                    "VALUES(?,0,0,NULL,0,0,0,0,0,0,0,?)",
                arrayOf(senseId.toString(), now.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun verifiedSenseCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_USER_SENSE_KNOWLEDGE WHERE correct_streak >= ?",
        arrayOf(LearningRepository.VERIFY_STREAK.toString())
    ).use { it.moveToFirst(); it.getInt(0) }

    fun testedSenseCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_USER_SENSE_KNOWLEDGE WHERE attempts > 0", null
    ).use { it.moveToFirst(); it.getInt(0) }

    // --- Memory state for the spaced-repetition scheduler ------------------------------------

    /** The FSRS memory parameters stored for a sense, or null when it was never reviewed. */
    data class MemoryRecord(
        val difficulty: Double,
        val stability: Double,
        val lastReview: Long,
        val reps: Int,
        val lapses: Int,
        val dueAt: Long,
        val intervalDays: Double
    )

    fun memoryFor(senseId: Long): MemoryRecord? = db.rawQuery(
        "SELECT difficulty, stability, last_tested_at, reps, lapses, due_at, interval_days " +
            "FROM $TABLE_USER_SENSE_KNOWLEDGE WHERE sense_id=?",
        arrayOf(senseId.toString())
    ).use { c ->
        if (!c.moveToFirst()) null
        else MemoryRecord(c.getDouble(0), c.getDouble(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getLong(5), c.getDouble(6))
    }

    /**
     * Persists a review verdict and the memory state the scheduler derived from it.
     *
     * Everything lands in one transaction: the sense's knowledge row, the replayable test record, and
     * the shared evidence log. A crash can therefore never leave a memory state that no answer backs,
     * and the parameters needed to re-fit the scheduler later are always present.
     */
    fun recordReview(
        wordId: Long,
        senseId: Long,
        word: String,
        mode: String,
        correct: Boolean,
        revealed: Boolean,
        elapsedMillis: Long,
        now: Long,
        grade: Int?,
        difficulty: Double?,
        stability: Double?,
        dueAt: Long?,
        intervalDays: Double?,
        reps: Int?,
        lapses: Int?,
        masteryBefore: Double?,
        masteryAfter: Double?,
        abilityBefore: Double?,
        abilityAfter: Double?
    ) {
        recordSenseAnswer(
            wordId = wordId, senseId = senseId, mode = mode, correct = correct, revealed = revealed,
            elapsedMillis = elapsedMillis, now = now, grade = grade,
            abilityBefore = abilityBefore, abilityAfter = abilityAfter,
            masteryBefore = masteryBefore, masteryAfter = masteryAfter,
            difficulty = difficulty, stability = stability, dueAt = dueAt,
            intervalDays = intervalDays, reps = reps, lapses = lapses, resolvedWord = word
        )
    }

    /** Senses that are due for review, soonest first. Drives the review half of the daily plan. */
    data class DueSense(val senseId: Long, val wordId: Long, val word: String, val dueAt: Long)

    fun dueSenses(now: Long, limit: Int = 200): List<DueSense> = db.rawQuery(
        "SELECT k.sense_id, s.word_id, COALESCE(w.word, ''), k.due_at " +
            "FROM $TABLE_USER_SENSE_KNOWLEDGE k " +
            "JOIN $TABLE_SENSE_UNITS s ON s.id = k.sense_id " +
            "LEFT JOIN lex_words w ON w.id = s.word_id " +
            "WHERE k.due_at > 0 AND k.due_at <= ? ORDER BY k.due_at LIMIT ?",
        arrayOf(now.toString(), limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(DueSense(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3)))
            }
        }
    }

    /** Senses with real test evidence, with the parameters needed to schedule them. */
    fun reviewedSenses(limit: Int = 400): List<Pair<String, MemoryRecord>> = db.rawQuery(
        "SELECT COALESCE(w.word, ''), k.difficulty, k.stability, k.last_tested_at, k.reps, k.lapses, " +
            "k.due_at, k.interval_days FROM $TABLE_USER_SENSE_KNOWLEDGE k " +
            "JOIN $TABLE_SENSE_UNITS s ON s.id = k.sense_id " +
            "LEFT JOIN lex_words w ON w.id = s.word_id " +
            "WHERE k.attempts > 0 AND k.stability > 0 ORDER BY k.due_at LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    c.getString(0) to MemoryRecord(
                        c.getDouble(1), c.getDouble(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getLong(6), c.getDouble(7)
                    )
                )
            }
        }
    }

    // --- Difficulty priors -----------------------------------------------------------------

    /** Reads a stored prior, or null when this word/mode has never been given one. */
    fun difficultyFor(wordId: Long, mode: String): ItemDifficultyRecord? = db.rawQuery(
        "SELECT word_id, mode, difficulty, calibrated, source, updated_at FROM $TABLE_ITEM_DIFFICULTY " +
            "WHERE word_id=? AND mode=?", arrayOf(wordId.toString(), mode)
    ).use { c ->
        if (!c.moveToFirst()) null
        else ItemDifficultyRecord(c.getLong(0), c.getString(1), c.getDouble(2), c.getInt(3) == 1, c.getString(4), c.getLong(5))
    }

    /**
     * Stores a prior for a word and mode. Refuses to overwrite a calibrated value with a heuristic
     * one, so a future calibration step cannot be silently reverted by the prior generator.
     */
    fun saveDifficulty(
        wordId: Long,
        mode: String,
        difficulty: Double,
        source: String,
        calibrated: Boolean = false,
        now: Long = System.currentTimeMillis()
    ) {
        val existing = difficultyFor(wordId, mode)
        if (existing != null && existing.calibrated && !calibrated) return
        db.insertWithOnConflict(
            TABLE_ITEM_DIFFICULTY, null,
            ContentValues().apply {
                put("word_id", wordId)
                put("mode", mode)
                put("difficulty", difficulty)
                put("calibrated", if (calibrated) 1 else 0)
                put("source", source)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun calibratedDifficultyCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_ITEM_DIFFICULTY WHERE calibrated=1", null
    ).use { it.moveToFirst(); it.getInt(0) }

    fun priorDifficultyCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_ITEM_DIFFICULTY WHERE calibrated=0", null
    ).use { it.moveToFirst(); it.getInt(0) }

    // --- Evidence log ----------------------------------------------------------------------

    /** Reviews that can be replayed for a future parameter fit: sense, mode and grade known. */
    fun replayableRecordCount(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $TABLE_TEST_RECORDS WHERE sense_id IS NOT NULL AND mode IS NOT NULL",
        null
    ).use { it.moveToFirst(); it.getInt(0) }

    data class GradeCount(val grade: Int, val count: Int)

    fun gradeHistogram(): List<GradeCount> = db.rawQuery(
        "SELECT grade, COUNT(*) FROM $TABLE_TEST_RECORDS WHERE grade IS NOT NULL GROUP BY grade ORDER BY grade",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(GradeCount(c.getInt(0), c.getInt(1))) } }

    // --- Diagnostic candidates --------------------------------------------------------------

    /** A word offered to the adaptive diagnostic, with the evidence already held about it. */
    data class Candidate(
        val wordId: Long,
        val word: String,
        val senseId: Long,
        val senseText: String,
        val senseConfidence: Double,
        val tags: String,
        /** Test attempts already on this sense; high values make it a weak diagnostic choice. */
        val attempts: Int
    )

    /**
     * Candidate words for the adaptive test, spread across the whole dictionary.
     *
     * Ordered by word id so the same seed produces the same pool, and capped: a diagnostic never
     * needs the entire dictionary in memory, and the selector only ever examines this list.
     */
    fun diagnosticCandidates(limit: Int = 600): List<Candidate> = db.rawQuery(
        "SELECT w.id, w.word, s.id, s.text, s.confidence, COALESCE(w.tags,''), " +
            "COALESCE(k.attempts, 0) FROM lex_words w " +
            "JOIN $TABLE_SENSE_UNITS s ON s.word_id = w.id AND s.ordinal = 0 " +
            "LEFT JOIN $TABLE_USER_SENSE_KNOWLEDGE k ON k.sense_id = s.id " +
            "WHERE w.zh <> '' ORDER BY w.id LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Candidate(
                        wordId = c.getLong(0), word = c.getString(1), senseId = c.getLong(2),
                        senseText = c.getString(3), senseConfidence = c.getDouble(4),
                        tags = c.getString(5).orEmpty(), attempts = c.getInt(6)
                    )
                )
            }
        }
    }

    /** Diagnostic pool uses the same selected words as learning, spread across the full list. */
    fun diagnosticCandidates(words: Collection<String>, tags: Map<String, String>, limit: Int = 600): List<Candidate> {
        db.beginTransaction()
        try {
            val result = words.sortedBy { it.hashCode() }.take(limit).map { word ->
                val (wordId, senseId) = ensurePrimarySense(word)
                val sense = sensesFor(wordId).first { it.id == senseId }
                Candidate(wordId, word, senseId, sense.text, sense.confidence, tags[word].orEmpty(),
                    knowledgeFor(listOf(senseId))[senseId]?.attempts ?: 0)
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    /** Records one diagnostic answer, storing the ability before and after so it can be replayed. */
    fun recordDiagnostic(
        candidate: Candidate,
        correct: Boolean,
        elapsedMillis: Long,
        now: Long,
        abilityBefore: Double,
        abilityAfter: Double,
        difficulty: Double
    ) {
        recordSenseAnswer(
            wordId = candidate.wordId,
            senseId = candidate.senseId,
            mode = MODE_DIAGNOSTIC,
            correct = correct,
            revealed = false,
            elapsedMillis = elapsedMillis,
            now = now,
            abilityBefore = abilityBefore,
            abilityAfter = abilityAfter,
            difficulty = difficulty, resolvedWord = candidate.word
        )
    }

    /** How many answers were recorded per practice mode. */
    fun evidenceByMode(): Map<String, Int> = db.rawQuery(
        "SELECT mode, COUNT(*) FROM $TABLE_TEST_RECORDS GROUP BY mode", null
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }
}
