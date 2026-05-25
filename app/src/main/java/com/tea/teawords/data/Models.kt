package com.tea.teawords.data

import com.google.gson.annotations.SerializedName
import java.util.*

// Free Dictionary API models
data class WordResponse(
    val word: String,
    val phonetic: String?,
    val phonetics: List<Phonetic>?,
    val meanings: List<Meaning>?
)

data class Phonetic(
    val text: String?,
    val audio: String?
)

data class Meaning(
    val partOfSpeech: String?,
    val definitions: List<Definition>?
)

data class Definition(
    val definition: String?,
    val example: String?,
    val synonyms: List<String>?,
    val antonyms: List<String>?
)

// MyMemory API models
data class MyMemoryResponse(
    val responseData: ResponseData?,
    val responseStatus: Int?
)

data class ResponseData(
    val translatedText: String?,
    val match: Double?
)

// App UI Models for Database and List Views
data class HistoryItem(
    val id: Int = 0,
    val word: String,
    val translation: String,
    val timestamp: Long
)

data class VocabularyItem(
    val id: Int = 0,
    val word: String,
    val phonetic: String?,
    val definition: String,
    val timestamp: Long
)

// Review Feature Models
enum class ClozeType {
    WORD_CLOZE,        // 随机挖空单词
    SEMANTIC_CLOZE     // 语义填空
}

data class ClozeProblem(
    val id: String,                    // 唯一标识
    val originalWord: String,          // 原始单词
    val sentence: String,              // 原始句子
    val clozeType: ClozeType,         // 挖空类型
    val blankedSentence: String,       // 挖空后的句子（含 ___）
    val clozePosition: Int,            // 被挖空单词在句子中的位置
    val clozeWord: String,             // 被挖空的单词
    val options: List<String> = emptyList(),  // 选项（如果是选择题）
    val difficulty: Int = 1            // 难度等级 1-5
)

data class ReviewAnswer(
    val problemId: String,
    val userAnswer: String,
    val isCorrect: Boolean,
    val userDefinition: String? = null,    // 用户输入的定义（语义填空时）
    val timestamp: Long = System.currentTimeMillis()
)

data class ReviewSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val vocabItems: List<VocabularyItem>,   // 本次复习的单词
    val problems: List<ClozeProblem>,       // 生成的题目
    val answers: List<ReviewAnswer> = emptyList(),
    val currentProblemIndex: Int = 0,
    val difficulty: Int = 2,               // 难度
    val startTime: Long = System.currentTimeMillis()
) {
    val currentProblem: ClozeProblem? 
        get() = if (currentProblemIndex < problems.size) problems[currentProblemIndex] else null
    
    val progress: Float 
        get() = if (problems.isEmpty()) 0f else currentProblemIndex.toFloat() / problems.size
    
    val correctCount: Int 
        get() = answers.count { it.isCorrect }
    
    val isComplete: Boolean 
        get() = currentProblemIndex >= problems.size
}

// Error Tracking & Statistics Models
data class ErrorRecord(
    val id: Int = 0,
    val problemId: String,
    val word: String,
    val userAnswer: String,
    val correctAnswer: String,
    val attemptCount: Int = 1,
    val lastAttemptTime: Long = System.currentTimeMillis()
)

data class ReviewSessionRecord(
    val id: Int = 0,
    val sessionId: String,
    val totalProblems: Int,
    val correctCount: Int,
    val accuracy: Float,  // 0-100
    val timeSpentSeconds: Long,
    val averageTimePerProblem: Float,
    val difficulty: Int,  // 1-5
    val timestamp: Long = System.currentTimeMillis()
) {
    val accuracyPercentage: Int get() = accuracy.toInt()
}

data class VocabularyStatistics(
    val word: String,
    val reviewCount: Int = 0,
    val correctCount: Int = 0,
    val errorCount: Int = 0,
    val lastReviewTime: Long = 0L,
    val masteryLevel: Float = 0f,  // 0-1, 0.8+ 表示掌握
    val difficulty: Int = 1,  // 1-5，基于用户表现
    val needsReview: Boolean = false
) {
    val accuracy: Float get() = if (reviewCount > 0) correctCount.toFloat() / reviewCount else 0f
}

// 离线题库题目
data class OfflineQuestion(
    val id: String,
    val word: String,
    val sentence: String,
    val blankedSentence: String,
    val correctAnswer: String,
    val options: List<String>,
    val difficulty: Int = 1,
    val category: String = "general"
)
