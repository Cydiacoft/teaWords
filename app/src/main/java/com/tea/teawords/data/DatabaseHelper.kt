package com.tea.teawords.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "teawords.db"
        private const val DATABASE_VERSION = 2  // 升级版本

        private const val TABLE_HISTORY = "history"
        private const val TABLE_VOCABULARY = "vocabulary"
        private const val TABLE_ERROR_RECORDS = "error_records"
        private const val TABLE_REVIEW_SESSIONS = "review_sessions"
        private const val TABLE_VOCAB_STATS = "vocab_stats"

        private const val KEY_ID = "id"
        private const val KEY_WORD = "word"
        private const val KEY_TRANSLATION = "translation"
        private const val KEY_PHONETIC = "phonetic"
        private const val KEY_DEFINITION = "definition"
        private const val KEY_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createHistoryTable = ("CREATE TABLE " + TABLE_HISTORY + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_WORD + " TEXT UNIQUE,"
                + KEY_TRANSLATION + " TEXT,"
                + KEY_TIMESTAMP + " INTEGER" + ")")
        
        val createVocabularyTable = ("CREATE TABLE " + TABLE_VOCABULARY + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_WORD + " TEXT UNIQUE,"
                + KEY_PHONETIC + " TEXT,"
                + KEY_DEFINITION + " TEXT,"
                + KEY_TIMESTAMP + " INTEGER" + ")")

        // 错题记录表
        val createErrorTable = ("CREATE TABLE " + TABLE_ERROR_RECORDS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "problem_id TEXT,"
                + KEY_WORD + " TEXT,"
                + "user_answer TEXT,"
                + "correct_answer TEXT,"
                + "attempt_count INTEGER DEFAULT 1,"
                + "last_attempt_time INTEGER" + ")")

        // 复习会话记录表
        val createSessionTable = ("CREATE TABLE " + TABLE_REVIEW_SESSIONS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session_id TEXT UNIQUE,"
                + "total_problems INTEGER,"
                + "correct_count INTEGER,"
                + "accuracy REAL,"
                + "time_spent_seconds INTEGER,"
                + "avg_time_per_problem REAL,"
                + "difficulty INTEGER,"
                + KEY_TIMESTAMP + " INTEGER" + ")")

        // 词汇统计表
        val createStatsTable = ("CREATE TABLE " + TABLE_VOCAB_STATS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_WORD + " TEXT UNIQUE,"
                + "review_count INTEGER DEFAULT 0,"
                + "correct_count INTEGER DEFAULT 0,"
                + "error_count INTEGER DEFAULT 0,"
                + "last_review_time INTEGER,"
                + "mastery_level REAL DEFAULT 0.0,"
                + "difficulty INTEGER DEFAULT 1,"
                + "needs_review INTEGER DEFAULT 0" + ")")

        db.execSQL(createHistoryTable)
        db.execSQL(createVocabularyTable)
        db.execSQL(createErrorTable)
        db.execSQL(createSessionTable)
        db.execSQL(createStatsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_ERROR_RECORDS("
                    + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "problem_id TEXT,"
                    + KEY_WORD + " TEXT,"
                    + "user_answer TEXT,"
                    + "correct_answer TEXT,"
                    + "attempt_count INTEGER DEFAULT 1,"
                    + "last_attempt_time INTEGER" + ")")
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_REVIEW_SESSIONS("
                    + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "session_id TEXT UNIQUE,"
                    + "total_problems INTEGER,"
                    + "correct_count INTEGER,"
                    + "accuracy REAL,"
                    + "time_spent_seconds INTEGER,"
                    + "avg_time_per_problem REAL,"
                    + "difficulty INTEGER,"
                    + KEY_TIMESTAMP + " INTEGER" + ")")
            db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_VOCAB_STATS("
                    + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + KEY_WORD + " TEXT UNIQUE,"
                    + "review_count INTEGER DEFAULT 0,"
                    + "correct_count INTEGER DEFAULT 0,"
                    + "error_count INTEGER DEFAULT 0,"
                    + "last_review_time INTEGER,"
                    + "mastery_level REAL DEFAULT 0.0,"
                    + "difficulty INTEGER DEFAULT 1,"
                    + "needs_review INTEGER DEFAULT 0" + ")")
        }
    }

    // --- Search History Operations ---

    fun addHistory(word: String, translation: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_WORD, word)
            put(KEY_TRANSLATION, translation)
            put(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        // Insert or replace on unique key (word)
        db.insertWithOnConflict(TABLE_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getHistory(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val selectQuery = "SELECT * FROM $TABLE_HISTORY ORDER BY $KEY_TIMESTAMP DESC LIMIT 100"
        val db = this.readableDatabase
        val cursor = db.rawQuery(selectQuery, null)

        if (cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndex(KEY_ID)
            val wordIndex = cursor.getColumnIndex(KEY_WORD)
            val translationIndex = cursor.getColumnIndex(KEY_TRANSLATION)
            val timestampIndex = cursor.getColumnIndex(KEY_TIMESTAMP)

            do {
                if (idIndex != -1 && wordIndex != -1 && translationIndex != -1 && timestampIndex != -1) {
                    list.add(
                        HistoryItem(
                            id = cursor.getInt(idIndex),
                            word = cursor.getString(wordIndex),
                            translation = cursor.getString(translationIndex),
                            timestamp = cursor.getLong(timestampIndex)
                        )
                    )
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun clearHistory() {
        val db = this.writableDatabase
        db.delete(TABLE_HISTORY, null, null)
    }

    fun deleteHistoryItem(word: String) {
        val db = this.writableDatabase
        db.delete(TABLE_HISTORY, "$KEY_WORD = ?", arrayOf(word))
    }

    // --- Vocabulary Book Operations ---

    fun addVocabulary(word: String, phonetic: String?, definition: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_WORD, word)
            put(KEY_PHONETIC, phonetic ?: "")
            put(KEY_DEFINITION, definition)
            put(KEY_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_VOCABULARY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeVocabulary(word: String) {
        val db = this.writableDatabase
        db.delete(TABLE_VOCABULARY, "$KEY_WORD = ?", arrayOf(word))
    }

    fun isInVocabulary(word: String): Boolean {
        val db = this.readableDatabase
        val query = "SELECT 1 FROM $TABLE_VOCABULARY WHERE $KEY_WORD = ?"
        val cursor = db.rawQuery(query, arrayOf(word))
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    fun getVocabulary(): List<VocabularyItem> {
        val list = mutableListOf<VocabularyItem>()
        val selectQuery = "SELECT * FROM $TABLE_VOCABULARY ORDER BY $KEY_TIMESTAMP DESC"
        val db = this.readableDatabase
        val cursor = db.rawQuery(selectQuery, null)

        if (cursor.moveToFirst()) {
            val idIndex = cursor.getColumnIndex(KEY_ID)
            val wordIndex = cursor.getColumnIndex(KEY_WORD)
            val phoneticIndex = cursor.getColumnIndex(KEY_PHONETIC)
            val definitionIndex = cursor.getColumnIndex(KEY_DEFINITION)
            val timestampIndex = cursor.getColumnIndex(KEY_TIMESTAMP)

            do {
                if (idIndex != -1 && wordIndex != -1 && phoneticIndex != -1 && definitionIndex != -1 && timestampIndex != -1) {
                    list.add(
                        VocabularyItem(
                            id = cursor.getInt(idIndex),
                            word = cursor.getString(wordIndex),
                            phonetic = cursor.getString(phoneticIndex),
                            definition = cursor.getString(definitionIndex),
                            timestamp = cursor.getLong(timestampIndex)
                        )
                    )
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    // --- Error Tracking Operations ---

    fun recordError(errorRecord: ErrorRecord) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("problem_id", errorRecord.problemId)
            put(KEY_WORD, errorRecord.word)
            put("user_answer", errorRecord.userAnswer)
            put("correct_answer", errorRecord.correctAnswer)
            put("attempt_count", errorRecord.attemptCount)
            put("last_attempt_time", errorRecord.lastAttemptTime)
        }
        db.insertWithOnConflict(TABLE_ERROR_RECORDS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getErrorRecords(limit: Int = 100): List<ErrorRecord> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_ERROR_RECORDS, null, null, null, null, null, "last_attempt_time DESC", "$limit")
        val list = mutableListOf<ErrorRecord>()

        if (cursor.moveToFirst()) {
            do {
                val idIndex = cursor.getColumnIndex(KEY_ID)
                val wordIndex = cursor.getColumnIndex(KEY_WORD)
                val userAnswerIndex = cursor.getColumnIndex("user_answer")
                val correctAnswerIndex = cursor.getColumnIndex("correct_answer")

                if (idIndex != -1 && wordIndex != -1) {
                    list.add(
                        ErrorRecord(
                            id = cursor.getInt(idIndex),
                            problemId = cursor.getString(cursor.getColumnIndex("problem_id")),
                            word = cursor.getString(wordIndex),
                            userAnswer = cursor.getString(userAnswerIndex),
                            correctAnswer = cursor.getString(correctAnswerIndex),
                            attemptCount = cursor.getInt(cursor.getColumnIndex("attempt_count")),
                            lastAttemptTime = cursor.getLong(cursor.getColumnIndex("last_attempt_time"))
                        )
                    )
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getErrorWords(): List<String> {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT DISTINCT word FROM $TABLE_ERROR_RECORDS", null)
        val list = mutableListOf<String>()

        if (cursor.moveToFirst()) {
            do {
                list.add(cursor.getString(0))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    // --- Review Session Recording ---

    fun recordReviewSession(session: ReviewSessionRecord) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("session_id", session.sessionId)
            put("total_problems", session.totalProblems)
            put("correct_count", session.correctCount)
            put("accuracy", session.accuracy)
            put("time_spent_seconds", session.timeSpentSeconds)
            put("avg_time_per_problem", session.averageTimePerProblem)
            put("difficulty", session.difficulty)
            put(KEY_TIMESTAMP, session.timestamp)
        }
        db.insert(TABLE_REVIEW_SESSIONS, null, values)
    }

    fun getReviewSessions(limit: Int = 100): List<ReviewSessionRecord> {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_REVIEW_SESSIONS, null, null, null, null, null, KEY_TIMESTAMP + " DESC", "$limit")
        val list = mutableListOf<ReviewSessionRecord>()

        if (cursor.moveToFirst()) {
            do {
                val idIndex = cursor.getColumnIndex(KEY_ID)
                val sessionIdIndex = cursor.getColumnIndex("session_id")
                val totalIndex = cursor.getColumnIndex("total_problems")
                val correctIndex = cursor.getColumnIndex("correct_count")
                val accuracyIndex = cursor.getColumnIndex("accuracy")
                val timeIndex = cursor.getColumnIndex("time_spent_seconds")
                val avgTimeIndex = cursor.getColumnIndex("avg_time_per_problem")
                val diffIndex = cursor.getColumnIndex("difficulty")
                val timestampIndex = cursor.getColumnIndex(KEY_TIMESTAMP)

                if (idIndex != -1) {
                    list.add(
                        ReviewSessionRecord(
                            id = cursor.getInt(idIndex),
                            sessionId = cursor.getString(sessionIdIndex),
                            totalProblems = cursor.getInt(totalIndex),
                            correctCount = cursor.getInt(correctIndex),
                            accuracy = cursor.getFloat(accuracyIndex),
                            timeSpentSeconds = cursor.getLong(timeIndex),
                            averageTimePerProblem = cursor.getFloat(avgTimeIndex),
                            difficulty = cursor.getInt(diffIndex),
                            timestamp = cursor.getLong(timestampIndex)
                        )
                    )
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getReviewStats(): ReviewSessionRecord? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("""
            SELECT 
                COUNT(*) as session_count,
                SUM(total_problems) as total_problems,
                SUM(correct_count) as total_correct,
                AVG(accuracy) as avg_accuracy,
                SUM(time_spent_seconds) as total_time,
                AVG(avg_time_per_problem) as avg_per_problem,
                AVG(difficulty) as avg_difficulty
            FROM $TABLE_REVIEW_SESSIONS
        """.trimIndent(), null)

        var stats: ReviewSessionRecord? = null
        if (cursor.moveToFirst()) {
            val totalSessions = cursor.getInt(0)
            if (totalSessions > 0) {
                stats = ReviewSessionRecord(
                    sessionId = "overall",
                    totalProblems = cursor.getInt(1),
                    correctCount = cursor.getInt(2),
                    accuracy = cursor.getFloat(3),
                    timeSpentSeconds = cursor.getLong(4),
                    averageTimePerProblem = cursor.getFloat(5),
                    difficulty = cursor.getFloat(6).toInt()
                )
            }
        }
        cursor.close()
        return stats
    }

    // --- Vocabulary Statistics ---

    fun updateVocabStats(word: String, isCorrect: Boolean) {
        val db = this.writableDatabase
        val cursor = db.query(TABLE_VOCAB_STATS, null, "$KEY_WORD = ?", arrayOf(word), null, null, null)

        val values = ContentValues()
        if (cursor.moveToFirst()) {
            // 更新现有记录
            values.put("review_count", cursor.getInt(cursor.getColumnIndex("review_count")) + 1)
            if (isCorrect) {
                values.put("correct_count", cursor.getInt(cursor.getColumnIndex("correct_count")) + 1)
            } else {
                values.put("error_count", cursor.getInt(cursor.getColumnIndex("error_count")) + 1)
            }
            values.put("last_review_time", System.currentTimeMillis())
            db.update(TABLE_VOCAB_STATS, values, "$KEY_WORD = ?", arrayOf(word))
        } else {
            // 插入新记录
            values.put(KEY_WORD, word)
            values.put("review_count", 1)
            values.put("correct_count", if (isCorrect) 1 else 0)
            values.put("error_count", if (isCorrect) 0 else 1)
            values.put("last_review_time", System.currentTimeMillis())
            db.insert(TABLE_VOCAB_STATS, null, values)
        }
        cursor.close()
    }

    fun getVocabStats(word: String): VocabularyStatistics? {
        val db = this.readableDatabase
        val cursor = db.query(TABLE_VOCAB_STATS, null, "$KEY_WORD = ?", arrayOf(word), null, null, null)

        var stats: VocabularyStatistics? = null
        if (cursor.moveToFirst()) {
            val reviewCount = cursor.getInt(cursor.getColumnIndex("review_count"))
            val correctCount = cursor.getInt(cursor.getColumnIndex("correct_count"))
            stats = VocabularyStatistics(
                word = word,
                reviewCount = reviewCount,
                correctCount = correctCount,
                errorCount = cursor.getInt(cursor.getColumnIndex("error_count")),
                lastReviewTime = cursor.getLong(cursor.getColumnIndex("last_review_time")),
                masteryLevel = if (reviewCount > 0) correctCount.toFloat() / reviewCount else 0f,
                difficulty = cursor.getInt(cursor.getColumnIndex("difficulty")),
                needsReview = cursor.getInt(cursor.getColumnIndex("needs_review")) > 0
            )
        }
        cursor.close()
        return stats
    }

    fun getWeakWords(limit: Int = 10): List<String> {
        val db = this.readableDatabase
        val cursor = db.rawQuery("""
            SELECT word FROM $TABLE_VOCAB_STATS 
            WHERE review_count > 0 
            ORDER BY (CAST(error_count AS FLOAT) / review_count) DESC 
            LIMIT $limit
        """.trimIndent(), null)

        val list = mutableListOf<String>()
        if (cursor.moveToFirst()) {
            do {
                list.add(cursor.getString(0))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }
}
