package com.teameow.teawords.data

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.ProcessTextActivity
import com.teameow.teawords.data.search.ShippedDictionarySource
import org.junit.Assert.*
import org.junit.Test

class LocalDictionaryTest {
    /**
     * These cases are about this database's own word store, so the reference dictionary is switched off
     * explicitly. Otherwise a process that happens to have the shipped dictionary open would answer with
     * 57,841 reference words and the local behaviour under test would never be exercised.
     */
    private fun withDictionary(test: (DatabaseHelper, LocalDictionary) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "dictionary-test-${System.nanoTime()}.db"
        val helper = DatabaseHelper(context, name)
        try {
            test(helper, LocalDictionary(helper, ShippedDictionarySource.localOnly))
        } finally { helper.close(); context.deleteDatabase(name) }
    }
    @Test fun offlineLookupReverseFormsAndTypoShareAnIdentity() = withDictionary { helper, dictionary ->
        val id = dictionary.upsert("issue", "n. 问题\nv. 发行", "a subject for discussion", phonetic = "isju", forms = "p:issued/d:issued/i:issuing/3:issues", source = "test")
        assertEquals(id, dictionary.exact(" ISSUE ")!!.id)
        assertEquals(id, dictionary.search("问题").single().id)
        assertEquals(id, dictionary.resolve("issued").single().id)
        assertEquals(id, dictionary.search("isuse").single().id)
        assertEquals(id, dictionary.search("discussion").single().id)
        assertTrue(helper.getVocabulary().isEmpty())
        assertEquals(0, dictionary.knowledge("issue").attempts)
        dictionary.mark(dictionary.exact("issue")!!, SelfReport.KNOWN)
        assertEquals(SelfReport.KNOWN, LearningRepository(helper).states().single().report)
        assertEquals(SelfReport.KNOWN, dictionary.knowledge("ISSUE").report)
        assertEquals(0, dictionary.knowledge("issue").attempts)
        helper.readableDatabase.rawQuery("SELECT word_id FROM learning_knowledge", null).use { it.moveToFirst(); assertEquals(id, it.getLong(0)) }
    }
    @Test fun ecdictRepeatImportIsIdempotentAndBadFileRollsBack() = withDictionary { _, dictionary ->
        val csv = "word,phonetic,definition,translation,pos,tag,exchange\nissue,,subject,问题,n,cet4,p:issued\n"
        assertEquals(1, dictionary.importEcdict(csv.reader()) {})
        val id = dictionary.exact("issue")!!.id
        dictionary.importEcdict(csv.reader()) {}
        assertEquals(1L, dictionary.count())
        assertEquals(id, dictionary.exact("issue")!!.id)
        try {
            dictionary.importEcdict((csv + "bad,row\n").replace("issue,", "other,").reader()) {}
            fail("Malformed import should fail")
        } catch (_: IllegalArgumentException) { }
        assertNull(dictionary.exact("other"))
        assertEquals(id, dictionary.exact("issue")!!.id)
    }
    @Test fun analysisFiltersKnownWordsAndNeverAutoAdds() = withDictionary { helper, dictionary ->
        dictionary.upsert("contemplate", "考虑", forms = "p:contemplated")
        dictionary.upsert("move", "移动", forms = "i:moving")
        dictionary.upsert("abroad", "在国外")
        dictionary.mark(dictionary.exact("move")!!, SelfReport.KNOWN)
        val candidates = dictionary.analyze("She contemplated moving abroad.")
        assertEquals(setOf("contemplate", "abroad"), candidates.map { it.first.word }.toSet())
        assertEquals(listOf("move"), helper.getVocabulary().map { it.word })
    }
    @Test fun historyCleanupDoesNotEraseLearning() = withDictionary { helper, dictionary ->
        dictionary.upsert("issue", "问题")
        dictionary.mark(dictionary.exact("issue")!!, SelfReport.FUZZY)
        dictionary.record("issue", "词典", "en-zh")
        dictionary.record("句子", "翻译", "zh-en")
        dictionary.deleteHistory(dictionary.history().first().id)
        assertEquals(1, dictionary.history().size)
        dictionary.deleteHistory()
        assertTrue(dictionary.history().isEmpty())
        assertEquals(SelfReport.FUZZY, LearningRepository(helper).states().single().report)
    }
    @Test fun externalIntentReadsOnlyExplicitSharedText() {
        assertEquals("issue", ProcessTextActivity.extractText(Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "issue")))
        assertEquals("A sentence.", ProcessTextActivity.extractText(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A sentence.")))
        assertEquals("", ProcessTextActivity.extractText(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "private")))
    }
}
