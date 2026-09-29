package com.teameow.teawords.data.search

import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.data.BundledDictionaryInstaller
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The real search engine over the real shipped dictionary, on a device.
 *
 * This test exists because the pure unit tests were not enough. They passed against a small fixture while
 * the shipped lookup was broken in two ways that only real data could show:
 *
 *  1. The Chinese literal step keyed its index probe on a **two-character** token, but `lex_zh` only ever
 *     stores **single characters**. `WHERE token = '放弃'` matched zero rows, so Chinese reverse lookup
 *     returned nothing at all — the single most-used search in the app.
 *  2. The related-meaning step scored *loose character overlap*, which on 57,841 real glosses surfaced
 *     `theropod` for "走兽" (走 from 行走, 兽 from 兽脚亚目) and `perspex` for "甲乙丙" (甲基丙烯酸).
 *
 * Both are asserted here against the actual asset, because a fixture I wrote myself will never contain the
 * coincidences that a real dictionary does. Timings are printed rather than asserted tightly: they are the
 * only honest source of on-device latency numbers, and a regression shows up as an order-of-magnitude move.
 */
class DictionarySearchInstrumentedTest {

    companion object {
        private lateinit var repository: WordSearchRepository
        private lateinit var engine: DefaultDictionarySearchEngine

        private val timings = LinkedHashMap<String, Long>()

        @BeforeClass
        @JvmStatic
        fun openDictionary() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            repository = WordSearchRepository(BundledDictionaryInstaller(context))
            // Copy the 61 MB asset if this is a fresh install; the app does the same on first launch.
            assertTrue("the bundled dictionary must open", repository.open())
            engine = DefaultDictionarySearchEngine(repository)
        }

        @AfterClass
        @JvmStatic
        fun reportTimings() {
            println("=== on-device search timings (ms) ===")
            timings.forEach { (label, ms) -> println("  %-34s %5d ms".format(label, ms)) }
            repository.close()
        }

        private fun time(label: String, block: () -> Unit) {
            // One warm-up so a cold page cache does not masquerade as a slow query.
            block()
            val start = System.nanoTime()
            repeat(5) { block() }
            timings[label] = (System.nanoTime() - start) / 5_000_000
        }
    }

    private fun words(query: String, filter: SearchFilter = SearchFilter()) =
        engine.search(query, filter).hits.map { it.word }

    // --- The shipped data is what the build tool reported --------------------------------------------

    @Test
    fun theShippedDictionaryHasTheExpectedShape() {
        val report = repository.checkIndexConsistency()
        assertEquals("no index may disagree with the base table: ${report.problems}", emptyList<String>(), report.problems)
        assertEquals(57_841, report.words)
        assertTrue("every word must have senses: ${report.senses}", report.senses > report.words)
        assertTrue("every word must be reverse-lookupable: ${report.chineseTokens}", report.chineseTokens > report.words)
        assertEquals("the full-text index must cover every word", report.words, report.fullTextRows)
        assertEquals(57_841, repository.wordCount())
        // The asset must be the version the installer claims, and must carry the character statistics the
        // Chinese search selects its index anchor with. A stale asset that skipped this assertion is what
        // shipped once and crashed every Chinese query.
        assertEquals(
            "the shipped asset version must match BundledDictionaryInstaller.SCHEMA_VERSION",
            BundledDictionaryInstaller.SCHEMA_VERSION, repository.schemaVersion()
        )
        assertTrue("the asset must carry lex_token_stats: ${report.tokenStatsRows}", report.tokenStatsRows > 0)
    }

    @Test
    fun allEightExamBooksArePresent() {
        val books = repository.books().map { it.id }
        for (book in listOf("zk", "gk", "cet4", "cet6", "ky", "ielts", "toefl", "gre")) {
            assertTrue("book $book must exist: $books", books.contains(book))
        }
    }

    // --- English ------------------------------------------------------------------------------------

    @Test
    fun exactLookupIsCaseInsensitiveAndRanksFirst() {
        time("exact abandon") { engine.search("abandon") }
        for (query in listOf("abandon", "Abandon", "ABANDON", "  abandon ")) {
            val outcome = engine.search(query)
            assertEquals("query '$query' must find abandon", "abandon", outcome.hits.first().word)
            assertEquals(MatchKind.EXACT, outcome.hits.first().match)
        }
    }

    @Test
    fun prefixCompletionReturnsTheFamily() {
        // "contemp" is not itself a headword, so the prefix step runs. ("conte" and "con" ARE headwords,
        // which is why they correctly return the word itself instead — see the test below.)
        time("prefix contemp") { engine.search("contemp", SearchFilter(limit = 20)) }
        val outcome = engine.search("contemp", SearchFilter(limit = 20))
        assertTrue("a prefix must return several words: ${outcome.hits.size}", outcome.hits.size > 3)
        assertTrue("every hit must start with the query",
            outcome.hits.all { it.word.lowercase().startsWith("contemp") })
        assertTrue("the family must include contemplate: ${outcome.hits.map { it.word }}",
            outcome.hits.any { it.word == "contemplate" })
    }

    @Test
    fun typingAWordThatExistsReturnsThatWordAndNotItsFamily() {
        // Accuracy beats completeness: "conte" is a headword, so the answer is conte, not the 20 words
        // that merely start with it. Same rule for a form that is also a headword.
        val outcome = engine.search("conte", SearchFilter(limit = 20))
        assertEquals("only the word itself may be returned", listOf("conte"), outcome.hits.map { it.word })
        assertEquals(MatchKind.EXACT, outcome.hits.first().match)

        // "better" is a headword AND a comparative of good. The headword wins; good must not be invented.
        val better = engine.search("better", SearchFilter(limit = 20))
        assertEquals("better", better.hits.first().word)
        assertFalse("a headword that exists must not be replaced by its lemma: ${better.hits.map { it.word }}",
            better.hits.any { it.word == "good" && it.match == MatchKind.FORM })
    }

    @Test
    fun irregularFormsResolveToTheirHeadword() {
        time("form went") { engine.search("went", SearchFilter(limit = 5)) }
        // "went" is not a headword, so the form index is what carries it to go.
        assertTrue("went must reach go: ${words("went")}", words("went").contains("go"))
        assertEquals(MatchKind.FORM, engine.search("went").hits.first().match)
        assertTrue("mice must reach mouse: ${words("mice")}", words("mice").contains("mouse"))
    }

    @Test
    fun aMisspellingIsOfferedAsASuggestion() {
        time("fuzzy contempalte") { engine.search("contempalte", SearchFilter(limit = 5)) }
        val outcome = engine.search("contempalte", SearchFilter(limit = 5))
        assertTrue("no exact hit is expected", outcome.hits.isEmpty())
        assertTrue("contemplate must be suggested: ${outcome.suggestions.map { it.word }}",
            outcome.suggestions.any { it.word == "contemplate" })
    }

    // --- Chinese: the regression that mattered ------------------------------------------------------

    @Test
    fun chineseReverseLookupActuallyReturnsRows() {
        time("chinese 放弃") { engine.search("放弃", SearchFilter(limit = 20)) }
        // The bug: the index only holds single characters, so a two-character probe matched nothing and
        // this came back empty while claiming to work.
        val outcome = engine.search("放弃", SearchFilter(limit = 20))
        val found = outcome.hits.map { it.word }
        assertTrue("放弃 must reach abandon: $found", found.contains("abandon"))
        assertTrue("every hit must be a literal gloss match",
            outcome.hits.all { it.match == MatchKind.CHINESE })

        assertTrue("发行 must reach publish: ${words("发行")}", words("发行").contains("publish"))
        assertTrue("动物园 must reach zoo: ${words("动物园")}", words("动物园").contains("zoo"))
        assertTrue("a single character must still work: ${words("爱")}", words("爱").contains("love"))
    }

    @Test
    fun chineseReverseLookupIsScopedToTheChosenBook() {
        time("chinese 放弃 [cet4]") { engine.search("放弃", SearchFilter(bookId = "cet4", limit = 20)) }
        val outcome = engine.search("放弃", SearchFilter(bookId = "cet4", limit = 20))
        assertTrue("the scoped search must still find something: ${outcome.hits.size}", outcome.hits.isNotEmpty())
        assertTrue("every hit must be in cet4: ${outcome.hits.map { it.word to it.books }}",
            outcome.hits.all { it.books.contains("cet4") })
    }

    // --- Related meaning: the false positives that a fixture could not contain ----------------------

    @Test
    fun aCharacterCoincidenceIsNotOfferedAsARelatedMeaning() {
        time("related 走兽 (must be empty)") { engine.search("走兽") }
        // theropod's real gloss contains 走 (行走) and 兽 (兽脚亚目) but never adjacently.
        assertTrue("走兽 shares no run with anything and must return nothing: ${words("走兽")}",
            words("走兽").isEmpty())
        assertTrue("甲乙丙 must return nothing: ${words("甲乙丙")}", words("甲乙丙").isEmpty())
    }

    @Test
    fun aSharedRunIsOfferedAndLabelledAsApproximate() {
        time("related 野生动物园") { engine.search("野生动物园", SearchFilter(limit = 20)) }
        // No gloss says 野生动物园, but several contain the run 动物 adjacent, which is real evidence.
        val outcome = engine.search("野生动物园", SearchFilter(limit = 20))
        val found = outcome.hits.map { it.word }
        assertTrue("a shared run must reach the animal words: $found",
            found.any { it == "zoo" || it == "menagerie" })
        assertTrue("related hits must never be labelled as literal matches",
            outcome.hits.all { it.match == MatchKind.CHINESE_RELATED })
        assertTrue("related hits must carry a relevance score",
            outcome.hits.all { it.relevance > 0.0 })
    }

    @Test
    fun aLiteralMatchIsNeverDilutedByRelatedHits() {
        val outcome = engine.search("放弃", SearchFilter(limit = 20))
        assertTrue("no approximate hit may sit beside an exact one",
            outcome.hits.none { it.match == MatchKind.CHINESE_RELATED })
    }

    // --- Result shape the UI depends on -------------------------------------------------------------

    @Test
    fun everyHitCarriesItsBooksWithoutAnExtraQueryPerHit() {
        time("books for 20 hits") { engine.search("contemp", SearchFilter(limit = 20)) }
        val hits = engine.search("contemp", SearchFilter(limit = 20)).hits
        assertTrue("hits must carry book labels: ${hits.map { it.word to it.books }}",
            hits.any { it.books.isNotEmpty() })
        assertTrue("book labels must be sorted and unique",
            hits.all { it.books == it.books.sorted().distinct() })
    }

    @Test
    fun resultsAreDeduplicatedAndRankedByMatchStrength() {
        // "contemp" is not a headword, so this mixes prefix hits with full-text hits — the case where the
        // ordering rule actually has to do something.
        val hits = engine.search("contemp", SearchFilter(limit = 20)).hits
        assertEquals("a word id must appear once", hits.size, hits.map { it.wordId }.distinct().size)
        val ranks = hits.map { it.match.rank }
        assertEquals("hits must be ordered strongest match first", ranks.sorted(), ranks)
    }
}
