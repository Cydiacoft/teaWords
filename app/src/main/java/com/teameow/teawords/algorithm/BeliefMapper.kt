package com.teameow.teawords.algorithm

/**
 * Converts the stored word-level record into the algorithm's belief type.
 *
 * The stored row is the older, simpler shape (self-report plus a streak). The algorithm layer must
 * not depend on that storage class, so the mapping lives here as a pure function and is unit tested.
 * Every field is classified for what it is: a self-report becomes [MasterySource.SELF_REPORT] and can
 * never be presented as test evidence, and only a real streak produces [KnowledgeBelief.verifiedByTest].
 */
object BeliefMapper {
    /** Rows created before the algorithm layer existed carry no mastery probability. */
    const val DEFAULT_CONFIDENCE = 0.5

    fun from(
        report: String,
        attempts: Int,
        streak: Int,
        updatedAt: Long,
        now: Long
    ): KnowledgeBelief {
        if (attempts <= 0) {
            return when (report.uppercase()) {
                "KNOWN" -> KnowledgeBelief.fromSelfReport("KNOWN", updatedAt)
                "FUZZY" -> KnowledgeBelief.fromSelfReport("FUZZY", updatedAt)
                "UNKNOWN" -> KnowledgeBelief.fromSelfReport("UNKNOWN", updatedAt)
                else -> KnowledgeBelief.unseen(updatedAt)
            }
        }
        // Test evidence exists: mastery is bracketed by the streak rather than guessed precisely,
        // because the older row does not store a probability.
        val mastery = when {
            streak >= BayesianKnowledgeTracing.VERIFY_STREAK -> 0.95
            streak == 2 -> 0.80
            streak == 1 -> 0.65
            else -> 0.40
        }
        return KnowledgeBelief(
            mastery = mastery,
            confidence = (attempts / 5.0).coerceIn(0.0, 1.0),
            verifiedByTest = streak >= BayesianKnowledgeTracing.VERIFY_STREAK,
            correctStreak = streak,
            totalAnswers = attempts,
            lastUpdated = updatedAt,
            masterySource = MasterySource.TEST_EVIDENCE
        )
    }
}
