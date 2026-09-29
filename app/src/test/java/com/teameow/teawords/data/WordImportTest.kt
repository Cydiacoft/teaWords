package com.teameow.teawords.data

import org.junit.Assert.*
import org.junit.Test

class WordImportTest {
    @Test fun syllabusLinesKeepMeaningAndDropPhoneticsAndShorthand() {
        val words = WordImport.parse(
            """
            大学英语四级大纲单词表
            (共 4615 词)

            A

            a art.一(个)；每一(个)
            brute [bruːt] n.禽兽，畜生
            bubble [ˈbʌbl] n.泡 vi.冒泡，沸腾
            """.trimIndent()
        )
        assertEquals(listOf("brute", "bubble"), words.map { it.word })
        assertEquals("禽兽，畜生", words.first { it.word == "brute" }.definition)
        assertTrue(words.first { it.word == "bubble" }.definition.startsWith("泡"))
    }

    @Test fun singleLetterHeadingsAndBlankDefinitionsAreIgnored() {
        val words = WordImport.parse("A\nB\nword definition\napple 苹果\ndon't 不")
        assertEquals(listOf("apple", "don't"), words.map { it.word })
        assertEquals("苹果", words.first().definition)
    }

    @Test fun definitionsWithoutPhoneticsAreStillCleaned() {
        assertEquals("一(个)；每一(个)", WordImport.parse("ab art.一(个)；每一(个)").single().definition)
        assertEquals("干，做", WordImport.cleanDefinition("vt. 干，做"))
        assertEquals("放弃", WordImport.cleanDefinition("[əˈbændən] vt. 放弃"))
        assertEquals("普通释义", WordImport.cleanDefinition("普通释义"))
    }

    @Test fun csvAndJsonSourcesStillWork() {
        assertEquals(listOf("issue" to "问题,发行"), WordImport.parse("issue,\"问题,发行\"").map { it.word to it.definition })
        assertEquals(listOf("issue" to "问题"), WordImport.parse("""[{"word":"issue","definition":"问题"}]""").map { it.word to it.definition })
    }

    @Test fun duplicateWordsKeepTheFirstEntry() {
        val words = WordImport.parse("issue 问题\nissue 发行")
        assertEquals(1, words.size)
        assertEquals("问题", words.first().definition)
    }
}
