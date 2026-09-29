package com.teameow.teawords.data

import org.junit.Assert.*
import org.junit.Test

class LexicalTextTest {
    @Test fun normalizationRetainsCompoundsAndAbbreviations() {
        assertEquals("don't", LexicalText.normalize("DON’T"))
        assertEquals("ice cream", LexicalText.normalize(" Ice   Cream "))
        assertEquals("u.s.", LexicalText.normalize("U.S."))
    }
    @Test fun quotedCsvSupportsNewlinesCommasAndEscapedQuotes() {
        val reader = CsvRows("word,translation\r\nissue,\"问题,发行\n\"\"说明\"\"\"\r\n".reader())
        assertEquals(listOf("word", "translation"), reader.next())
        assertEquals(listOf("issue", "问题,发行\n\"说明\""), reader.next())
        assertNull(reader.next())
    }
    @Test fun chineseTokensDoNotDependOnEnglishTokenizer() {
        assertTrue(LexicalText.chineseTokens("n.发表声明").containsAll(listOf("发表", "声明", "声")))
    }
    @Test fun typoCandidatesAreBoundedAndIncludeTranspositions() {
        assertTrue("issue" in LexicalText.spellingCandidates("isuse"))
        assertTrue(LexicalText.spellingCandidates("abcdefghijklmno").size <= 768)
        assertTrue(LexicalText.spellingCandidates("a".repeat(200)).isEmpty())
    }
    @Test fun sentencesUseTranslationAndShortChineseAllowsReverseLookup() {
        assertFalse(LexicalText.dictionaryMode("She contemplated moving abroad."))
        assertTrue(LexicalText.dictionaryMode("问题"))
        assertTrue(LexicalText.dictionaryMode("ice cream"))
        assertTrue(LexicalText.dictionaryMode("U.S."))
    }
}
