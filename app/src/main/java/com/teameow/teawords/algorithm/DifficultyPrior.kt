package com.teameow.teawords.algorithm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Difficulty prior for a vocabulary item.
 *
 * IMPORTANT: these are **priors derived from the word book a word comes from**, not parameters
 * fitted on real response data. Nothing in this file may be presented as a calibrated IRT
 * parameter. [ItemDifficulty.calibrated] is false for every prior and the UI must say so.
 *
 * There is no frequency data in the bundled dictionary (verified: 0/5707 rows carry a frequency
 * value), so the word book tag is the only available ordering signal.
 */
data class ItemDifficulty(
    val value: Double,
    val calibrated: Boolean,
    val source: String
)

object DifficultyPrior {
    /** Logit scale, roughly -2 (very easy) .. +4 (very hard). */
    const val CET4 = 0.0
    const val CET6 = 0.55
    /** Not in either bundled book: outside the syllabus, treated as harder. */
    const val OUTSIDE_SYLLABUS = 1.10
    /**
     * Longer headwords are on average less frequent, but the word book tag already carries the main
     * signal. A large length weight swamped it: `administration` (13 characters) landed at 1.26
     * against a CET-4 baseline of 0.0, which pushed ordinary syllabus words out of reach of the
     * "predicted known" band. Kept small, and measured from 8 characters so short and average words
     * are not penalised at all.
     */
    const val PER_EXTRA_CHARACTER = 0.015
    const val LONG_WORD_BASELINE = 8

    /**
     * @param tags the word book tags carried by the dictionary row, e.g. "cet4 cet6"
     * @param word the headword itself, used only for a length adjustment
     */
    fun forWord(word: String, tags: String): ItemDifficulty {
        val normalized = tags.lowercase()
        val base = when {
            normalized.contains("cet4") -> CET4
            normalized.contains("cet6") -> CET6
            normalized.contains("zk") -> -0.6
            normalized.contains("gk") -> -0.3
            normalized.contains("ky") -> 0.6
            normalized.contains("ielts") -> 0.7
            normalized.contains("toefl") -> 0.8
            normalized.contains("gre") -> 1.2
            else -> OUTSIDE_SYLLABUS
        }
        val length = (word.length - LONG_WORD_BASELINE).coerceAtLeast(0)
        val adjustment = length * PER_EXTRA_CHARACTER
        val source = when {
            normalized.contains("cet4") -> "词书标签 cet4"
            normalized.contains("cet6") -> "词书标签 cet6"
            else -> "词书标签先验：${tags.ifBlank { "无标签" }}（未标定）"
        }
        return ItemDifficulty(base + adjustment, calibrated = false, source = source)
    }

    /**
     * Recognition (English -> Chinese) is easier than active recall (Chinese -> English), so the
     * same word has a different difficulty per practice mode. Offset, not a fitted shift.
     */
    fun adjustForMode(difficulty: ItemDifficulty, mode: PracticeMode): ItemDifficulty {
        val delta = when (mode) {
            PracticeMode.RECOGNITION -> -0.30
            PracticeMode.RECALL -> 0.0
            PracticeMode.SPELLING -> 0.35
        }
        val label = when (mode) {
            PracticeMode.RECOGNITION -> "英文识别更容易"
            PracticeMode.RECALL -> "中文回忆为基准"
            PracticeMode.SPELLING -> "拼写更难"
        }
        return difficulty.copy(
            value = difficulty.value + delta,
            source = "${difficulty.source} · $label"
        )
    }
}
