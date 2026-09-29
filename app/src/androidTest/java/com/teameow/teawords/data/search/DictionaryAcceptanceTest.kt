package com.teameow.teawords.data.search

import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.data.BundledDictionaryInstaller
import com.teameow.teawords.data.DatabaseHelper
import com.teameow.teawords.data.LocalDictionary
import com.teameow.teawords.data.WordIdMigration
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Acceptance tests A–L for shipping the pre-built dictionary into the app.
 *
 * These run on a device against the **real 61 MB asset**, not a fixture. That is deliberate: the pure
 * unit tests passed while the shipped Chinese lookup was completely broken, because a fixture I wrote
 * myself does not contain the character coincidences or the id ranges that real data does.
 *
 * The list, one test each:
 *
 * | | Acceptance |
 * |---|---|
 * | A | first launch installs the asset; a second call does not copy again |
 * | B | exact lookup, case and whitespace insensitive, exact hit first |
 * | C | prefix completion returns the family, all starting with the query |
 * | D | inflected forms resolve to their headword from data, not suffix stripping |
 * | E | Chinese reverse lookup matches the whole query |
 * | F | a misspelling yields edit-distance suggestions, probed in one batch |
 * | G | unified ranking: stronger match kinds always outrank weaker ones |
 * | H | book scope filters results and all eight books exist |
 * | I | stored knowledge is reused through one global word id across books |
 * | J | closing and reopening the dictionary keeps searching and keeps knowledge |
 * | K | re-installing does not duplicate words or book associations |
 * | L | index consistency: senses, reverse tokens and full-text rows all agree |
 */
class DictionaryAcceptanceTest {

    companion object {
        private lateinit var context: android.content.Context
        private lateinit var installer: BundledDictionaryInstaller
        private lateinit var repository: WordSearchRepository
        private lateinit var engine: DictionarySearchEngine

        /** The dictionary's own identity, so the migration is bound to this exact build. */
        private var builtAt: String = ""

        /** Documented shape of the shipped build; asserted rather than assumed. */
        private const val WORDS = 57_841
        private const val ASSOCIATIONS = 38_855
        private const val GENERIC_WORD_ID = 526L      // "address", tagged in all eight books
        private const val ABANDON_ID = 16L
        private const val ZOO_ID = 57_818L            // id above the superseded index's 5,707 rows

        @BeforeClass
        @JvmStatic
        fun setUp() {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            installer = BundledDictionaryInstaller(context)
            installer.install(force = false)
            repository = WordSearchRepository(installer)
            assertTrue("the shipped dictionary must open", repository.open())
            engine = DefaultDictionarySearchEngine(repository)
            builtAt = repository.meta("built_at") ?: ""
            assertTrue("the build must identify itself", builtAt.isNotEmpty())
        }

        @AfterClass
        @JvmStatic
        fun tearDown() = repository.close()

        private fun words(query: String, filter: SearchFilter = SearchFilter()) =
            engine.search(query, filter).hits
    }

    // --- A: first launch install ---------------------------------------------------------------

    @Test
    fun aFirstLaunchInstallsTheAssetAndASecondCallDoesNotCopyAgain() {
        // The copy is what makes a cold start slow, so it must happen exactly once per asset version.
        val second = installer.install(force = false)
        assertFalse("a current dictionary must not be copied again", second.installed)
        assertTrue("the installed file must not be empty", second.target.length() > 50L * 1024 * 1024)
        assertTrue("the repository reports the installer's file", repository.isOpen())
        assertEquals(
            "installed asset must match the installer's declared version",
            BundledDictionaryInstaller.SCHEMA_VERSION, repository.schemaVersion()
        )
        assertEquals(WORDS, repository.wordCount())
    }

    // --- B: exact -------------------------------------------------------------------------------

    @Test
    fun bExactLookupIsCaseInsensitiveAndRanksFirst() {
        for (query in listOf("abandon", "Abandon", "ABANDON", "  abandon  ")) {
            val outcome = engine.search(query)
            assertEquals("query '$query'", "abandon", outcome.hits.first().word)
            assertEquals(MatchKind.EXACT, outcome.hits.first().match)
            assertEquals(ABANDON_ID, outcome.hits.first().wordId)
        }
    }

    // --- C: prefix ------------------------------------------------------------------------------

    @Test
    fun cPrefixCompletionReturnsTheWholeFamily() {
        // "contemp" is not a headword, so the prefix step actually runs.
        val hits = words("contemp", SearchFilter(limit = 20))
        val prefixHits = hits.filter { it.match == MatchKind.PREFIX }
        assertTrue("a prefix must return a family: ${prefixHits.size}", prefixHits.size > 3)
        assertTrue("every prefix hit must start with the query: ${prefixHits.map { it.word }}",
            prefixHits.all { it.word.lowercase().startsWith("contemp") })
        assertTrue("contemplate must be in the family", prefixHits.any { it.word == "contemplate" })
    }

    // --- D: inflected forms ---------------------------------------------------------------------

    @Test
    fun dInflectedFormsResolveFromDataNotFromSuffixStripping() {
        // ECDICT's exchange column is the source of these, which is why an invented suffix rule cannot
        // reproduce them and an unknown form must stay a miss instead of guessing.
        val went = engine.search("went")
        assertEquals("went is not a headword, so the form index carries it", "go", went.hits.first().word)
        assertEquals(MatchKind.FORM, went.hits.first().match)
        assertTrue("mice must reach mouse: ${words("mice").map { it.word }}",
            words("mice").map { it.word }.contains("mouse"))
        assertTrue("a made-up form must not invent a headword: ${words("goed").map { it.word }}",
            words("goed").none { it.word == "go" })
    }

    // --- E: Chinese reverse lookup ---------------------------------------------------------------

    @Test
    fun eChineseReverseLookupMatchesTheWholeQuery() {
        // The index stores single characters, so the query has to be verified as a substring. Keying the
        // probe on a two-character token matched nothing at all and this returned empty.
        val fangqi = engine.search("放弃", SearchFilter(limit = 20))
        assertTrue("放弃 must reach abandon: ${fangqi.hits.map { it.word }}",
            fangqi.hits.map { it.word }.contains("abandon"))
        assertTrue("literal gloss matches must be labelled CHINESE",
            fangqi.hits.all { it.match == MatchKind.CHINESE })
        // Ranking, not just matching: the word that *means* the query must come before the common word
        // whose gloss merely lists it. Ordering by frequency alone put "go" first for 放弃.
        val ranked = fangqi.hits.map { it.word }
        assertTrue("放弃 must lead with a word that means it: $ranked",
            ranked.first() == "abandon" || ranked.first() == "relinquish")
        assertTrue("abandon must outrank go: $ranked",
            ranked.indexOf("abandon") < ranked.indexOf("go"))
        assertEquals("爱 must lead with love", "love", engine.search("爱").hits.first().word)
        // A three-character query must be more precise, not less.
        assertEquals("动物园 must reach zoo", "zoo", engine.search("动物园").hits.first().word)
        assertTrue("a single character must still work", words("爱").map { it.word }.contains("love"))
    }

    // --- F: spelling correction ------------------------------------------------------------------

    @Test
    fun fMisspellingYieldsBatchedEditDistanceSuggestions() {
        val outcome = engine.search("contempalte", SearchFilter(limit = 5))
        assertTrue("a misspelling must not return direct hits", outcome.hits.isEmpty())
        assertTrue("contemplate must be suggested: ${outcome.suggestions.map { it.word }}",
            outcome.suggestions.any { it.word == "contemplate" })
        assertTrue("a miss must be reported as a miss", outcome.exactMatchMissing)
        // The batch is the point: one probe per candidate measured 189 ms on this device.
        val candidates = SpellCorrectionEngine.candidates("contempalte")
        assertTrue("candidates must be generated", candidates.isNotEmpty())
        assertTrue("candidate set must stay bounded", candidates.size <= SpellCorrectionEngine.MAX_CANDIDATES)
    }

    // --- G: unified ranking ----------------------------------------------------------------------

    @Test
    fun gStrongerMatchKindsAlwaysOutrankWeakerOnes() {
        // A headword the user typed must not be pushed down by the words that merely start with it.
        assertEquals(listOf("conte"), engine.search("conte", SearchFilter(limit = 20)).hits.map { it.word })

        val mixed = engine.search("contemp", SearchFilter(limit = 20)).hits
        val ranks = mixed.map { it.match.rank }
        assertEquals("hits must be ordered strongest match first", ranks.sorted(), ranks)
        assertEquals("a word id must appear once", mixed.size, mixed.map { it.wordId }.distinct().size)

        // A literal Chinese match must never be diluted by approximate ones.
        assertTrue("no approximate hit may sit beside an exact one",
            engine.search("放弃", SearchFilter(limit = 20)).hits.none { it.match == MatchKind.CHINESE_RELATED })
    }

    // --- H: book scope ---------------------------------------------------------------------------

    @Test
    fun hBookScopeFiltersResultsAndAllEightBooksExist() {
        val books = repository.books()
        assertEquals("all eight exam books must be present: ${books.map { it.id }}",
            listOf("cet4", "cet6", "gk", "gre", "ielts", "ky", "toefl", "zk"), books.map { it.id }.sorted())

        val scoped = engine.search("con", SearchFilter(bookId = "cet4", limit = 20)).hits
        assertTrue("a scoped lookup must still find words: ${scoped.size}", scoped.isNotEmpty())
        assertTrue("every hit must belong to cet4: ${scoped.map { it.word to it.books }}",
            scoped.all { it.books.contains("cet4") })
        assertTrue("no hit may come from another book only",
            scoped.none { it.books.isNotEmpty() && !it.books.contains("cet4") })
        // "con" is a headword in no exam book at all. Scoping to cet4 must exclude it; applying the scope
        // to every step except the strongest one would have let it through.
        assertTrue("an out-of-scope headword must not be returned: ${scoped.map { it.word }}",
            scoped.none { it.word == "con" })
    }

    // --- I: knowledge reuse by global word id ----------------------------------------------------

    @Test
    fun iOneWordKeepsOneGlobalIdAcrossEveryBook() {
        // "address" is tagged in all eight books. Progress must attach to one word, not eight copies,
        // which is the whole point of a global id.
        val id = repository.exact("address", SearchFilter(limit = 1)).first().wordId
        assertEquals(GENERIC_WORD_ID, id)
        val perBook = repository.books().mapNotNull { book ->
            repository.exact("address", SearchFilter(bookId = book.id, limit = 1)).firstOrNull()?.wordId
        }
        assertEquals("address must be found in all eight books: ${perBook.size}", 8, perBook.size)
        assertEquals("every book must report the same global id", 1, perBook.distinct().size)

        // And that id is what the row stores, so knowledge written once is read from every book.
        val temp = tempHelper("global-id")
        try {
            val db = temp.writableDatabase
            db.execSQL(
                "INSERT INTO learning_knowledge(word,report,attempts,streak,interval_hours,due,updated,word_id) " +
                    "VALUES('address','KNOWN',0,0,0.0,0,?,?)", arrayOf<Any>(1L, id)
            )
            val read = db.rawQuery("SELECT word_id FROM learning_knowledge WHERE word='address'", null)
                .use { it.moveToFirst(); it.getLong(0) }
            assertEquals("stored knowledge must carry the global id", id, read)
        } finally {
            temp.close(); context.deleteDatabase(temp.databaseName)
        }
    }

    // --- J: reopening --------------------------------------------------------------------------

    @Test
    fun jReopeningTheDictionaryKeepsSearchingAndKeepsKnowledge() {
        repository.close()
        assertFalse(repository.isOpen())
        assertTrue("the dictionary must reopen without reinstalling", repository.open())
        assertEquals("lookups must still work after reopening", "abandon",
            engine.search("abandon").hits.first().word)
        assertEquals(WORDS, repository.wordCount())

        // A knowledge row written before reopening is still there afterwards.
        val temp = tempHelper("reopen")
        try {
            temp.writableDatabase.execSQL(
                "INSERT INTO learning_knowledge(word,report,attempts,streak,interval_hours,due,updated,word_id) " +
                    "VALUES('zoo','KNOWN',0,0,0.0,0,?,?)", arrayOf<Any>(1L, ZOO_ID)
            )
            temp.close()
            val reopened = DatabaseHelper(context, temp.databaseName)
            try {
                val rows = reopened.readableDatabase
                    .rawQuery("SELECT COUNT(*) FROM learning_knowledge WHERE word='zoo'", null)
                    .use { it.moveToFirst(); it.getInt(0) }
                assertEquals("knowledge must survive closing and reopening the database", 1, rows)
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(temp.databaseName)
        }
    }

    // --- K: reinstalling must not duplicate ------------------------------------------------------

    @Test
    fun kReinstallingDoesNotDuplicateWordsOrAssociations() {
        val before = repository.checkIndexConsistency()
        val forced = installer.install(force = true)
        assertTrue("a forced install must actually copy", forced.installed)
        assertTrue("the reopened dictionary must still work", repository.open(forceReinstall = false))
        val after = repository.checkIndexConsistency()
        assertEquals("reinstalling must not change the word count", before.words, after.words)
        assertEquals("reinstalling must not change the associations",
            before.bookAssociations, after.bookAssociations)
        assertEquals(WORDS, after.words)
        assertEquals("the association key must prevent duplicates", 0, after.duplicateAssociations)
        assertEquals("associations must be exactly the built set", ASSOCIATIONS, after.bookAssociations)
    }

    // --- L: index consistency ---------------------------------------------------------------------

    @Test
    fun lAuxiliaryIndexesAgreeWithTheBaseTable() {
        val report = repository.checkIndexConsistency()
        assertEquals("no index may disagree with the base table: ${report.problems}",
            emptyList<String>(), report.problems)
        assertEquals(WORDS, report.words)
        assertTrue("every word must have at least one sense", report.senses >= report.words)
        assertTrue("reverse tokens must cover every word", report.chineseTokens >= report.words)
        assertEquals("the full-text index must cover every word", report.words, report.fullTextRows)
        assertEquals("every word must be reachable through full text", report.words, report.indexedWords)
        assertTrue("the anchor statistics must be present", report.tokenStatsRows > 0)
        assertNotNull("the build must be identifiable", repository.meta("built_at"))
    }

    // --- The id rebinding that makes I possible ---------------------------------------------------

    @Test
    fun mStoredProgressIsReboundToTheGlobalIdSpaceIncludingIdsAboveTheOldIndex() {
        val helper = tempHelper("migration")
        try {
            val db = helper.writableDatabase
            // The superseded in-app index numbered words its own way: abandon was 1, zoo was 3.
            for ((id, word) in listOf(1L to "abandon", 2L to "air-condition", 3L to "zoo")) {
                db.execSQL(
                    "INSERT INTO lex_words(id,normalized,word,zh) VALUES(?,?,?,'旧释义')",
                    arrayOf<Any>(id, word, word)
                )
            }
            for ((word, id) in listOf("abandon" to 1L, "air-condition" to 2L, "zoo" to 3L)) {
                db.execSQL(
                    "INSERT INTO learning_knowledge(word,report,attempts,streak,interval_hours,due,updated,word_id) " +
                        "VALUES(?,'KNOWN',0,0,0.0,0,?,?)", arrayOf<Any>(word, 1L, id)
                )
            }
            db.execSQL(
                "INSERT INTO sense_units(word_id,ordinal,text,confidence,derived_at) VALUES(3,0,'动物园',0.6,1)"
            )

            val report = WordIdMigration(helper).apply(installer.targetFile(), builtAt)
            assertTrue("the migration must apply on an unbound database", report.applied)

            fun idOf(word: String): Long = db.rawQuery(
                "SELECT word_id FROM learning_knowledge WHERE word=?", arrayOf(word)
            ).use { it.moveToFirst(); it.getLong(0) }

            assertEquals("abandon must be rebound to the dictionary id", ABANDON_ID, idOf("abandon"))
            // The regression: zoo's dictionary id (57,818) is far above the old index's 5,707 rows, so an
            // implementation that checked for "unmatched" *after* mapping would negate this correctly
            // bound row. Words the dictionary lacks must be the only negative ids.
            assertEquals("zoo must be rebound, not negated", ZOO_ID, idOf("zoo"))
            assertEquals("a word the dictionary lacks keeps a negative id", -2L, idOf("air-condition"))

            val senseWordId = db.rawQuery("SELECT word_id FROM sense_units LIMIT 1", null)
                .use { it.moveToFirst(); it.getLong(0) }
            assertEquals("sense rows must be rebound through the old word text", ZOO_ID, senseWordId)

            // Binding is once per dictionary build, and re-running must not move anything again.
            val again = WordIdMigration(helper).apply(installer.targetFile(), builtAt)
            assertFalse("a second run must be a no-op", again.applied)
            assertEquals(ABANDON_ID, idOf("abandon"))
            assertEquals(ZOO_ID, idOf("zoo"))
            assertEquals(-2L, idOf("air-condition"))
            assertTrue(report.describe().isNotEmpty())
        } finally {
            helper.close(); context.deleteDatabase(helper.databaseName)
        }
    }

    private fun tempHelper(tag: String): DatabaseHelper {
        val helper = DatabaseHelper(context, "acceptance-$tag-${System.nanoTime()}.db")
        // The tables the migration touches must exist before it runs, exactly as on a real upgrade.
        helper.ensureSchema()
        return helper
    }

    /**
     * The shipped dictionary this test opened, as an injectable source.
     *
     * Deliberately not [ShippedDictionarySource.processWide]: that reads the process-wide gateway, which a
     * test cannot rely on because instrumentation does not promise an order between classes. Injecting the
     * engine this class opened makes the precedence being tested deterministic.
     */
    private fun referenceSource(): ShippedDictionarySource = object : ShippedDictionarySource {
        override fun engineOrNull(): DictionarySearchEngine = engine
        override fun wordCount(): Int = WORDS
    }

    // --- The superseded inline index must not answer over the shipped dictionary ------------------

    @Test
    fun nTheSupersededInlineIndexDoesNotShadowTheShippedDictionary() {
        val helper = tempHelper("precedence")
        try {
            // A word that exists in the *old* inline index only, and a typo that the old index can resolve.
            LocalDictionary(helper, ShippedDictionarySource.localOnly)
                .upsert("contemplate", "旧词表释义", source = "原项目 cet6.txt")
            LocalDictionary(helper, ShippedDictionarySource.localOnly)
                .upsert("zoo", "旧词表动物园释义", source = "原项目 cet6.txt")

            val authoritative = LocalDictionary(helper, referenceSource())
            val localOnly = LocalDictionary(helper, ShippedDictionarySource.localOnly)

            // The old index's own spelling correction still works when it is the only corpus.
            assertTrue("the local store must still resolve typos on its own",
                localOnly.search("contempalte").any { it.word == "contemplate" })

            // With the shipped dictionary present, a typo must produce no direct hit, so the UI shows the
            // shipped dictionary's ranked suggestions instead of an entry from the superseded index.
            assertTrue("the old index must not answer a typo over the shipped dictionary: " +
                authoritative.search("contempalte").map { it.word },
                authoritative.search("contempalte").isEmpty())

            // A real word resolves to the shipped entry, not the old one.
            val zoo = authoritative.search("zoo").first { it.word == "zoo" }
            assertEquals("the entry must come from the shipped dictionary", "ECDICT", zoo.source)
            assertTrue("the shipped entry must carry its exam tags: ${zoo.tags}", zoo.tags.isNotBlank())
            assertEquals("the shipped gloss must be used, not the old one", ZOO_ID, zoo.id)
        } finally {
            helper.close(); context.deleteDatabase(helper.databaseName)
        }
    }
}
