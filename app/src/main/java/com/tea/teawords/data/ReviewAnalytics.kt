package com.tea.teawords.data

import android.util.Log
import kotlin.math.max

class ReviewAnalytics(private val dbHelper: DatabaseHelper) {

    /**
     * 获取学习进度统计
     */
    fun getLearningProgress(): LearningProgress {
        val sessions = dbHelper.getReviewSessions()
        val totalSessionCount = sessions.size
        val totalProblems = sessions.sumOf { it.totalProblems }
        val totalCorrect = sessions.sumOf { it.correctCount }
        val overallAccuracy = if (totalProblems > 0) (totalCorrect * 100f) / totalProblems else 0f
        val totalTimeSeconds = sessions.sumOf { it.timeSpentSeconds }

        // 最近 7 天的数据
        val weekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        val recentSessions = sessions.filter { it.timestamp > weekAgo }
        val recentAccuracy = if (recentSessions.isNotEmpty()) {
            val recentCorrect = recentSessions.sumOf { it.correctCount }
            val recentTotal = recentSessions.sumOf { it.totalProblems }
            if (recentTotal > 0) (recentCorrect * 100f) / recentTotal else 0f
        } else {
            0f
        }

        return LearningProgress(
            totalSessionCount = totalSessionCount,
            totalProblems = totalProblems,
            totalCorrect = totalCorrect,
            overallAccuracy = overallAccuracy,
            recentAccuracy = recentAccuracy,
            totalTimeSeconds = totalTimeSeconds,
            averageAccuracyPerSession = if (totalSessionCount > 0) overallAccuracy else 0f,
            streak = calculateStreak(sessions),
            level = calculateUserLevel(overallAccuracy, totalSessionCount)
        )
    }

    /**
     * 计算用户连续复习的天数
     */
    private fun calculateStreak(sessions: List<ReviewSessionRecord>): Int {
        if (sessions.isEmpty()) return 0

        val sortedSessions = sessions.sortedByDescending { it.timestamp }
        var streak = 0
        val calendar = java.util.Calendar.getInstance()

        for ((index, session) in sortedSessions.withIndex()) {
            calendar.timeInMillis = session.timestamp
            val sessionDate = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            val sessionYear = calendar.get(java.util.Calendar.YEAR)

            if (index == 0) {
                // 检查第一条是否是今天或昨天
                val today = java.util.Calendar.getInstance()
                val todayDate = today.get(java.util.Calendar.DAY_OF_YEAR)
                val todayYear = today.get(java.util.Calendar.YEAR)

                if (sessionYear == todayYear && sessionDate == todayDate) {
                    streak = 1
                } else if (sessionYear == todayYear && sessionDate == todayDate - 1) {
                    streak = 1
                } else {
                    return 0
                }
            } else {
                val prevSession = sortedSessions[index - 1]
                val prevCalendar = java.util.Calendar.getInstance()
                prevCalendar.timeInMillis = prevSession.timestamp
                val prevDate = prevCalendar.get(java.util.Calendar.DAY_OF_YEAR)
                val prevYear = prevCalendar.get(java.util.Calendar.YEAR)

                if (sessionYear == prevYear && sessionDate == prevDate - 1) {
                    streak++
                } else {
                    break
                }
            }
        }

        return streak
    }

    /**
     * 根据准确率和会话数计算用户等级
     */
    private fun calculateUserLevel(accuracy: Float, sessionCount: Int): Int {
        return when {
            accuracy < 50f -> 1
            accuracy < 70f && sessionCount < 5 -> 2
            accuracy < 70f -> 3
            accuracy < 85f && sessionCount < 10 -> 3
            accuracy < 85f -> 4
            accuracy < 95f -> 5
            else -> 6
        }
    }

    /**
     * 获取单词掌握度分析
     */
    fun getVocabularyMastery(words: List<String>): List<WordMastery> {
        return words.mapNotNull { word ->
            val stats = dbHelper.getVocabStats(word)
            if (stats != null) {
                WordMastery(
                    word = word,
                    accuracy = stats.accuracy,
                    reviewCount = stats.reviewCount,
                    masterLevel = calculateMasterLevel(stats.accuracy),
                    lastReviewTime = stats.lastReviewTime,
                    needsReview = stats.accuracy < 0.7f && stats.reviewCount >= 3
                )
            } else {
                null
            }
        }
    }

    /**
     * 计算掌握等级
     */
    private fun calculateMasterLevel(accuracy: Float): MasterLevel {
        return when {
            accuracy < 0.3f -> MasterLevel.UNKNOWN
            accuracy < 0.6f -> MasterLevel.LEARNING
            accuracy < 0.8f -> MasterLevel.FAMILIAR
            accuracy < 0.95f -> MasterLevel.PROFICIENT
            else -> MasterLevel.MASTERED
        }
    }

    /**
     * 推荐需要复习的词汇
     */
    fun getRecommendedWords(limit: Int = 10): List<String> {
        return dbHelper.getWeakWords(limit)
    }

    /**
     * 获取错题分析
     */
    fun getErrorAnalysis(): ErrorAnalysis {
        val errors = dbHelper.getErrorRecords()
        val errorWords = errors.map { it.word }.distinct()
        
        val frequentErrors = errors
            .groupingBy { it.word }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(10)

        return ErrorAnalysis(
            totalErrors = errors.size,
            errorWordsCount = errorWords.size,
            frequentErrors = frequentErrors,
            mostProblematicWord = frequentErrors.firstOrNull()?.first,
            errorRate = if (errors.isNotEmpty()) {
                val sessions = dbHelper.getReviewSessions()
                val totalProblems = sessions.sumOf { it.totalProblems }
                if (totalProblems > 0) (errors.size * 100f) / totalProblems else 0f
            } else {
                0f
            }
        )
    }

    /**
     * 获取学习趋势
     */
    fun getLearningTrend(days: Int = 7): List<DailyStats> {
        val sessions = dbHelper.getReviewSessions()
        val calendar = java.util.Calendar.getInstance()
        val dailyStatsMap = mutableMapOf<String, DailyStats>()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd")

        // 初始化包括今天在内的最近 N 天的数据
        for (i in 0 until days) {
            val dateKey = dateFormat.format(calendar.time)
            dailyStatsMap[dateKey] = DailyStats(
                date = dateKey,
                problemCount = 0,
                correctCount = 0,
                accuracy = 0f
            )
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }

        // 填充实际数据
        for (session in sessions) {
            val calendar2 = java.util.Calendar.getInstance()
            calendar2.timeInMillis = session.timestamp
            val dateKey = dateFormat.format(calendar2.time)

            if (dailyStatsMap.containsKey(dateKey)) {
                val current = dailyStatsMap[dateKey]!!
                val newProblemCount = current.problemCount + session.totalProblems
                val newCorrectCount = current.correctCount + session.correctCount
                dailyStatsMap[dateKey] = current.copy(
                    problemCount = newProblemCount,
                    correctCount = newCorrectCount,
                    accuracy = if (newProblemCount > 0) (newCorrectCount * 100f) / newProblemCount else 0f
                )
            }
        }

        return dailyStatsMap.values.sortedBy { it.date }
    }

    /**
     * 获取难度分布
     */
    fun getDifficultyDistribution(): DifficultyDistribution {
        val sessions = dbHelper.getReviewSessions()
        val byDifficulty = sessions.groupingBy { it.difficulty }.fold(0f) { acc, session ->
            acc + session.accuracy
        }

        val counts = sessions.groupingBy { it.difficulty }.eachCount()

        return DifficultyDistribution(
            easyAccuracy = if (counts[1] != null) byDifficulty[1]!! / counts[1]!! else 0f,
            mediumAccuracy = if (counts[2] != null) byDifficulty[2]!! / counts[2]!! else 0f,
            hardAccuracy = if (counts[3] != null) byDifficulty[3]!! / counts[3]!! else 0f,
            expertAccuracy = if (counts[4] != null) byDifficulty[4]!! / counts[4]!! else 0f,
            masterAccuracy = if (counts[5] != null) byDifficulty[5]!! / counts[5]!! else 0f
        )
    }

    /**
     * 推荐下一个合适的难度等级
     */
    fun recommendDifficulty(currentDifficulty: Int = 2): Int {
        val sessions = dbHelper.getReviewSessions()
            .filter { it.difficulty == currentDifficulty }

        if (sessions.isEmpty()) return currentDifficulty

        val avgAccuracy = sessions.map { it.accuracy }.average().toFloat()
        
        return when {
            avgAccuracy > 85f && currentDifficulty < 5 -> currentDifficulty + 1
            avgAccuracy < 60f && currentDifficulty > 1 -> currentDifficulty - 1
            else -> currentDifficulty
        }
    }
}

// 数据类定义

data class LearningProgress(
    val totalSessionCount: Int,
    val totalProblems: Int,
    val totalCorrect: Int,
    val overallAccuracy: Float,
    val recentAccuracy: Float,  // 最近 7 天
    val totalTimeSeconds: Long,
    val averageAccuracyPerSession: Float,
    val streak: Int,  // 连续复习天数
    val level: Int  // 1-6 级
) {
    val accuracyPercentage: Int get() = overallAccuracy.toInt()
    val recentAccuracyPercentage: Int get() = recentAccuracy.toInt()
    val timeHours: Long get() = totalTimeSeconds / 3600
    val isActive: Boolean get() = streak > 0
}

data class WordMastery(
    val word: String,
    val accuracy: Float,
    val reviewCount: Int,
    val masterLevel: MasterLevel,
    val lastReviewTime: Long,
    val needsReview: Boolean
)

enum class MasterLevel {
    UNKNOWN,      // 未学习
    LEARNING,     // 正在学习 (0.3-0.6)
    FAMILIAR,     // 基本熟悉 (0.6-0.8)
    PROFICIENT,   // 熟练 (0.8-0.95)
    MASTERED      // 完全掌握 (0.95+)
}

data class ErrorAnalysis(
    val totalErrors: Int,
    val errorWordsCount: Int,
    val frequentErrors: List<Pair<String, Int>>,  // (单词, 出错次数)
    val mostProblematicWord: String?,
    val errorRate: Float  // 百分比
)

data class DailyStats(
    val date: String,
    val problemCount: Int,
    val correctCount: Int,
    val accuracy: Float
)

data class DifficultyDistribution(
    val easyAccuracy: Float,      // 难度 1
    val mediumAccuracy: Float,    // 难度 2
    val hardAccuracy: Float,      // 难度 3
    val expertAccuracy: Float,    // 难度 4
    val masterAccuracy: Float     // 难度 5
)
