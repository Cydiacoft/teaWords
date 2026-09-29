package com.teameow.teawords.data

enum class SelfReport { UNSEEN, KNOWN, FUZZY, UNKNOWN }

data class Knowledge(
    val word: String,
    val report: SelfReport = SelfReport.UNSEEN,
    val attempts: Int = 0,
    val streak: Int = 0,
    val intervalHours: Double = 0.0,
    val due: Long = 0,
    val updated: Long = 0
) {
    // Evidence applies only to the imported definition and typed English recall.
    val verified: Boolean get() = streak >= 3
}

/** Phase 1 adaptive interval scheduler; deliberately not presented as FSRS. */
object LearningEngine {
    fun answer(state: Knowledge, correct: Boolean, now: Long): Knowledge {
        val hours = if (!correct) 1.0 / 6 else
            if (state.intervalHours < 1) 24.0 else (state.intervalHours * 2.2).coerceAtMost(2160.0)
        return state.copy(attempts = state.attempts + 1,
            streak = if (correct) state.streak + 1 else 0,
            intervalHours = hours, due = now + (hours * 3600000).toLong(), updated = now)
    }

    fun reason(state: Knowledge, now: Long): String? = when {
        state.attempts > 0 && state.due <= now -> "到期复习 · 巩固已学释义"
        state.attempts > 0 -> null
        state.report == SelfReport.FUZZY -> "模糊词优先 · 补齐知识漏洞"
        state.report == SelfReport.UNKNOWN -> "陌生词 · 建立第一份记忆"
        state.report == SelfReport.KNOWN && now - state.updated >= 7 * 86400000L -> "抽样验证 · 检查自评认识的词"
        else -> null
    }

    fun queue(states: List<Knowledge>, now: Long, limit: Int = 20, newLimit: Int = limit, allowSample: Boolean = true): List<Knowledge> {
        val eligible = states.filter { reason(it, now) != null }
        val reviews = eligible.filter { it.attempts > 0 }.sortedBy { it.due }
        val fresh = eligible.filter { it.attempts == 0 && it.report != SelfReport.KNOWN }
            .sortedWith(compareBy<Knowledge> { if (it.report == SelfReport.FUZZY) 0 else 1 }.thenBy { it.word })
        val sample = if (allowSample) eligible.filter { it.report == SelfReport.KNOWN && it.attempts == 0 }.minByOrNull { it.updated } else null
        val slots = (limit - reviews.size).coerceAtLeast(0).coerceAtMost(newLimit.coerceAtLeast(0))
        val newWords = fresh.take((slots - if (sample != null) 1 else 0).coerceAtLeast(0)) + if (slots > 0) listOfNotNull(sample) else emptyList()
        return (reviews + newWords).take(limit.coerceAtLeast(0))
    }
}
