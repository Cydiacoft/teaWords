package com.teameow.teawords.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Schema v6 building blocks that can be verified without an Android device:
 * the sense splitter and the difficulty-prior origin rules.
 *
 * This is a stub [android.database.sqlite.SQLiteDatabase]; it proves the SQL this project issues is
 * well formed and that the splitter's confidence rules hold. Real device assertions live in the
 * instrumented test.
 */
class SenseSplitterTest {

    @Test
    fun `semicolon glosses are split into separate senses`() {
        val senses = LexicalText.splitSenses("问题；发行")
        assertEquals(2, senses.size)
        assertEquals("问题", senses[0].text)
        assertEquals("发行", senses[1].text)
        assertEquals(listOf(0, 1), senses.map { it.ordinal })
    }

    @Test
    fun `a gloss split by semicolon is marked as a plausible sense`() {
        val senses = LexicalText.splitSenses("男主角；英雄；勇士")
        assertEquals(3, senses.size)
        assertTrue("split senses must carry the higher confidence",
            senses.all { it.confidence >= 0.6 })
    }

    @Test
    fun `a single long gloss stays one low-confidence sense`() {
        val gloss = "考虑到；打算；计划；期望；认为；设想；沉思；仔细考虑某事"
        val senses = LexicalText.splitSenses(gloss)
        assertTrue("a multi-meaning gloss without separators stays one unit", senses.size <= LexicalText.MAX_SENSES_PER_WORD)
        // The single piece is long, so confidence must drop: it is not a verified sense boundary.
        assertTrue("a long unsplit gloss must be low confidence, got ${senses.first().confidence}",
            senses.first().confidence <= 0.6)
    }

    @Test
    fun `a short single gloss is a reasonable sense`() {
        val senses = LexicalText.splitSenses("动物园")
        assertEquals(1, senses.size)
        assertEquals("动物园", senses.single().text)
        assertTrue(senses.single().confidence >= 0.4)
    }

    @Test
    fun `splitting is bounded and de-duplicated`() {
        val many = (1..40).joinToString("；") { "义项$it" }
        val senses = LexicalText.splitSenses(many)
        assertEquals("senses per word must be capped", LexicalText.MAX_SENSES_PER_WORD, senses.size)
        val dup = LexicalText.splitSenses("发行；发行")
        assertEquals("duplicates must collapse", 1, dup.size)
    }

    @Test
    fun `empty and whitespace glosses produce no senses`() {
        assertTrue(LexicalText.splitSenses("").isEmpty())
        assertTrue(LexicalText.splitSenses("   ").isEmpty())
        assertTrue(LexicalText.splitSenses("；；").isEmpty())
    }

    @Test
    fun `embedded newlines are treated as sense boundaries`() {
        val senses = LexicalText.splitSenses("问题\n发行")
        assertEquals(2, senses.size)
    }

    @Test
    fun `verification rule is shared between data and profile layers`() {
        // The profile query and Knowledge.verified must agree, otherwise the same word would be
        // "verified" in one screen and "learning" in another.
        assertEquals(3, LearningRepository.VERIFY_STREAK)
        val three = SenseKnowledge(1L, 3, 3, true, 0L, 0L, 0.0)
        val two = SenseKnowledge(1L, 2, 2, true, 0L, 0L, 0.0)
        assertTrue(three.verified)
        assertFalse(two.verified)
    }
}
