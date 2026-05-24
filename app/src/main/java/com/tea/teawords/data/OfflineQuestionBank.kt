package com.tea.teawords.data

import android.util.Log

class OfflineQuestionBank {
    
    companion object {
        // 离线备用题库 - 最常见的英语单词和例句
        private val questions = listOf(
            // 基础词汇 - 难度 1
            OfflineQuestion(
                id = "offline_1_1",
                word = "apple",
                sentence = "I eat an apple every day.",
                blankedSentence = "I eat an ___ every day.",
                correctAnswer = "apple",
                options = listOf("apple", "banana", "orange", "grape"),
                difficulty = 1,
                category = "fruit"
            ),
            OfflineQuestion(
                id = "offline_1_2",
                word = "book",
                sentence = "She is reading a book.",
                blankedSentence = "She is reading a ___.",
                correctAnswer = "book",
                options = listOf("book", "pen", "paper", "desk"),
                difficulty = 1,
                category = "object"
            ),
            OfflineQuestion(
                id = "offline_1_3",
                word = "friend",
                sentence = "My best friend lives next to me.",
                blankedSentence = "My best ___ lives next to me.",
                correctAnswer = "friend",
                options = listOf("friend", "family", "teacher", "neighbor"),
                difficulty = 1,
                category = "people"
            ),
            OfflineQuestion(
                id = "offline_1_4",
                word = "house",
                sentence = "I live in a big house.",
                blankedSentence = "I live in a big ___.",
                correctAnswer = "house",
                options = listOf("house", "school", "office", "hospital"),
                difficulty = 1,
                category = "place"
            ),
            OfflineQuestion(
                id = "offline_1_5",
                word = "happy",
                sentence = "She looks very happy today.",
                blankedSentence = "She looks very ___ today.",
                correctAnswer = "happy",
                options = listOf("happy", "sad", "tired", "angry"),
                difficulty = 1,
                category = "emotion"
            ),

            // 四级词汇 - 难度 2
            OfflineQuestion(
                id = "offline_2_1",
                word = "beautiful",
                sentence = "The sunset was absolutely beautiful.",
                blankedSentence = "The sunset was absolutely ___.",
                correctAnswer = "beautiful",
                options = listOf("beautiful", "bright", "colorful", "wonderful"),
                difficulty = 2,
                category = "adjective"
            ),
            OfflineQuestion(
                id = "offline_2_2",
                word = "experience",
                sentence = "This is a valuable experience for me.",
                blankedSentence = "This is a valuable ___ for me.",
                correctAnswer = "experience",
                options = listOf("experience", "experiment", "expectation", "explanation"),
                difficulty = 2,
                category = "noun"
            ),
            OfflineQuestion(
                id = "offline_2_3",
                word = "include",
                sentence = "Does the package include shipping?",
                blankedSentence = "Does the package ___ shipping?",
                correctAnswer = "include",
                options = listOf("include", "involve", "contain", "compose"),
                difficulty = 2,
                category = "verb"
            ),
            OfflineQuestion(
                id = "offline_2_4",
                word = "method",
                sentence = "The traditional method is still effective.",
                blankedSentence = "The traditional ___ is still effective.",
                correctAnswer = "method",
                options = listOf("method", "manner", "mode", "approach"),
                difficulty = 2,
                category = "noun"
            ),
            OfflineQuestion(
                id = "offline_2_5",
                word = "success",
                sentence = "Hard work is the key to success.",
                blankedSentence = "Hard work is the key to ___.",
                correctAnswer = "success",
                options = listOf("success", "succeed", "successful", "successfully"),
                difficulty = 2,
                category = "noun"
            ),

            // 六级词汇 - 难度 3
            OfflineQuestion(
                id = "offline_3_1",
                word = "eloquent",
                sentence = "His eloquent speech moved the audience.",
                blankedSentence = "His ___ speech moved the audience.",
                correctAnswer = "eloquent",
                options = listOf("eloquent", "elegant", "efficient", "enough"),
                difficulty = 3,
                category = "adjective"
            ),
            OfflineQuestion(
                id = "offline_3_2",
                word = "comprehensive",
                sentence = "A comprehensive study requires extensive research.",
                blankedSentence = "A ___ study requires extensive research.",
                correctAnswer = "comprehensive",
                options = listOf("comprehensive", "complex", "complete", "compound"),
                difficulty = 3,
                category = "adjective"
            ),
            OfflineQuestion(
                id = "offline_3_3",
                word = "contemporary",
                sentence = "Contemporary art challenges traditional values.",
                blankedSentence = "___ art challenges traditional values.",
                correctAnswer = "contemporary",
                options = listOf("contemporary", "temporary", "temporary", "contrary"),
                difficulty = 3,
                category = "adjective"
            ),
            OfflineQuestion(
                id = "offline_3_4",
                word = "abundant",
                sentence = "Natural resources are abundant in this region.",
                blankedSentence = "Natural resources are ___ in this region.",
                correctAnswer = "abundant",
                options = listOf("abundant", "acceptable", "absolute", "abstract"),
                difficulty = 3,
                category = "adjective"
            ),
            OfflineQuestion(
                id = "offline_3_5",
                word = "accelerate",
                sentence = "Economic growth will accelerate this year.",
                blankedSentence = "Economic growth will ___ this year.",
                correctAnswer = "accelerate",
                options = listOf("accelerate", "accommodate", "accomplish", "accumulate"),
                difficulty = 3,
                category = "verb"
            )
        )
    }

    fun getRandomQuestions(count: Int = 5, maxDifficulty: Int = 5): List<OfflineQuestion> {
        return questions
            .filter { it.difficulty <= maxDifficulty }
            .shuffled()
            .take(count)
    }

    fun getQuestionsByDifficulty(difficulty: Int): List<OfflineQuestion> {
        return questions.filter { it.difficulty == difficulty }
    }

    fun getQuestionByWord(word: String): OfflineQuestion? {
        return questions.firstOrNull { it.word.equals(word, ignoreCase = true) }
    }

    fun getTotalQuestions(): Int = questions.size

    fun getQuestionsByCategory(category: String): List<OfflineQuestion> {
        return questions.filter { it.category == category }
    }
}
