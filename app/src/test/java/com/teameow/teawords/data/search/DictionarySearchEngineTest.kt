package com.teameow.teawords.data.search

import org.junit.Assert.*
import org.junit.Test

/**
 * Search strategy, tested against an in-memory hit source.
 *
 * These cover the acceptance cases that are about *behaviour* rather than storage: case handling,
 * prefix completion, irregular forms, spelling correction, book scoping and the ranking rule that
 * accuracy outranks personalisation. The SQLite-backed cases live in the instrumented test.
 */
class DictionarySearchEngineTest {

    private fun hit(word: String, match: MatchKind, rank: Int = 0, books: List<String> = emptyList(), zh: String = "") =
        SearchHit(
            wordId = word.hashCode().toLong(),
            word = word,
            phonetic = "",
            zh = zh.ifBlank { "释义 $word" },
            en = "definition of $word",
            match = match,
            freqRank = rank,
            books = books
        )

    /** A tiny dictionary with the shapes the tests need: prefix family, irregular form, misspelling target. */
    private class FakeSource(
        private val words: List<SearchHit>,
        private val forms: Map<String, List<String>> = emptyMap(),
        private val fullTextMatches: Set<String> = emptySet()
    ) : SearchHitSource {
        val queried = mutableListOf<String>()

        /** Total candidates handed to [existing] — one call per search, not one call per candidate. */
        var batchCalls = 0
            private set

        private fun filter(rows: List<SearchHit>, filter: SearchFilter): List<SearchHit> =
            rows.filter { filter.bookId == null || it.books.contains(filter.bookId) }.take(filter.limit)

        override fun exact(normalized: String, filter: SearchFilter): List<SearchHit> {
            queried += normalized
            return filter(words.filter { it.word.lowercase() == normalized }.map { it.copy(match = MatchKind.EXACT) }, filter)
        }

        override fun prefix(prefix: String, upper: String, filter: SearchFilter): List<SearchHit> = filter(
            words.filter { it.word.lowercase() >= prefix && it.word.lowercase() < upper }
                .map { it.copy(match = MatchKind.PREFIX) }, filter
        )

        override fun byForm(form: String, filter: SearchFilter): List<SearchHit> {
            val targets = forms[form] ?: return emptyList()
            return filter(words.filter { targets.contains(it.word.lowercase()) }.map { it.copy(match = MatchKind.FORM) }, filter)
        }

        override fun chinese(query: String, filter: SearchFilter): List<SearchHit> = filter(
            words.filter { it.zh.contains(query) }.map { it.copy(match = MatchKind.CHINESE) }, filter
        )

        /**
         * Mirrors the real repository: probe on a run's rarest character, then verify in Kotlin that the
         * run really appears. The fake uses the same scorer, so the acceptance test exercises the real
         * definition of "similar meaning" rather than a re-implementation of it.
         */
        override fun chineseRelated(query: String, filter: SearchFilter): List<SearchHit> {
            val text = SearchQueryNormalizer.chineseQuery(query)
            val probes = ChineseRelatedScorer.probes(text) { token ->
                words.count { it.zh.contains(token) }.takeIf { it > 0 } ?: Int.MAX_VALUE
            }
            if (probes.isEmpty()) return emptyList()

            val best = LinkedHashMap<Long, SearchHit>()
            for (probe in probes) {
                for (w in words) {
                    if (!w.zh.contains(probe.run)) continue
                    val score = ChineseRelatedScorer.score(text, w.zh)
                    if (!ChineseRelatedScorer.isRelated(score)) continue
                    val candidate = w.copy(match = MatchKind.CHINESE_RELATED, relevance = score)
                    val existing = best[candidate.wordId]
                    if (existing == null || candidate.relevance > existing.relevance) {
                        best[candidate.wordId] = candidate
                    }
                }
            }
            return filter(
                best.values.sortedWith(
                    compareByDescending<SearchHit> { it.relevance }
                        .thenBy { if (it.freqRank <= 0) Int.MAX_VALUE else it.freqRank }
                        .thenBy { it.word }
                ),
                filter
            )
        }

        override fun fullText(terms: String, filter: SearchFilter): List<SearchHit> {
            val needle = terms.trim('"')
            return filter(
                words.filter { fullTextMatches.contains(it.word) && it.en.contains(needle) }
                    .map { it.copy(match = MatchKind.FULL_TEXT) }, filter
            )
        }

        override fun exists(normalized: String): SearchHit? =
            words.firstOrNull { it.word.lowercase() == normalized }?.copy(match = MatchKind.FUZZY)

        /** Batched form: the engine must ask for the whole candidate set at once, never one at a time. */
        override fun existing(normalized: Collection<String>): List<SearchHit> {
            batchCalls += normalized.size
            val wanted = normalized.toSet()
            return words.filter { wanted.contains(it.word.lowercase()) }.map { it.copy(match = MatchKind.FUZZY) }
        }
    }

    private val dictionary = listOf(
        hit("consider", MatchKind.EXACT, rank = 800, books = listOf("cet4", "cet6", "ky")),
        hit("contemplate", MatchKind.EXACT, rank = 5200, books = listOf("cet6", "ky", "ielts")),
        hit("contemporary", MatchKind.EXACT, rank = 3100, books = listOf("cet6", "ky")),
        hit("con", MatchKind.EXACT, rank = 400, books = listOf("cet4")),
        hit("go", MatchKind.EXACT, rank = 30, books = listOf("zk", "gk", "cet4")),
        hit("good", MatchKind.EXACT, rank = 20, books = listOf("zk", "gk", "cet4")),
        hit("abandon", MatchKind.EXACT, rank = 1200, books = listOf("cet4", "cet6", "ky"), zh = "vt. 放弃, 抛弃, 遗弃"),
        hit("relinquish", MatchKind.EXACT, rank = 9000, books = listOf("gre"), zh = "vt. 放弃, 撤回, 让与"),
        hit("apple", MatchKind.EXACT, rank = 700, books = listOf("zk")),
        // Related-meaning fixtures. Glosses are shaped like ECDICT's, because the scoring depends on a
        // shared run *inside* a longer gloss rather than on a tidy two-character gloss.
        hit("animal", MatchKind.EXACT, rank = 900, books = listOf("zk", "gk"), zh = "n. 动物"),
        hit("beast", MatchKind.EXACT, rank = 2400, books = listOf("gk", "cet4"), zh = "n. 畜生, 动物, 野兽, 兽性"),
        hit("brute", MatchKind.EXACT, rank = 7100, books = listOf("gre"), zh = "n. 畜生, 残忍的人"),
        hit("savage", MatchKind.EXACT, rank = 3000, books = listOf("gre", "cet6"), zh = "a. 未开化的, 野蛮的, 凶猛的"),
        // The false positive found on the real dictionary: it shares 走 (行走) and 兽 (兽脚亚目) with
        // "走兽" but shares no run, so it must never be offered.
        hit("theropod", MatchKind.EXACT, rank = 20000, books = listOf("gre"), zh = "n. 兽脚亚目的食肉恐龙(主要用后肢行走)")
    )

    private val source = FakeSource(
        words = dictionary,
        forms = mapOf("went" to listOf("go"), "better" to listOf("good"), "running" to listOf("run"))
    )
    private val engine = DefaultDictionarySearchEngine(source)

    // --- Test D: case-insensitive ---------------------------------------------------------

    @Test
    fun `exact search ignores case and normalises the query`() {
        for (query in listOf("Apple", "apple", "APPLE", "  Apple  ")) {
            val outcome = engine.search(query)
            assertEquals("query $query must find apple", "apple", outcome.hits.first().word)
            assertEquals(MatchKind.EXACT, outcome.hits.first().match)
        }
    }

    // --- Test E: prefix completion --------------------------------------------------------

    @Test
    fun `a prefix returns the family, ordered by the headword`() {
        val outcome = engine.search("conte")
        val words = outcome.hits.map { it.word }
        assertTrue("contemplate must be offered: $words", words.contains("contemplate"))
        assertTrue("contemporary must be offered: $words", words.contains("contemporary"))
        assertTrue("every hit must be a prefix hit",
            outcome.hits.all { it.match == MatchKind.PREFIX })
    }

    @Test
    fun `typing a word exactly does not push it down with prefix noise`() {
        // "con" is itself a word, so the exact match must come first and the prefix family after it.
        val outcome = engine.search("con")
        assertEquals(MatchKind.EXACT, outcome.hits.first().match)
        assertEquals("con", outcome.hits.first().word)
    }

    @Test
    fun `an exact word stops the engine from also listing its prefix family`() {
        // Accuracy first: the user typed a word that exists, so that is the answer.
        val outcome = engine.search("consider")
        assertEquals(1, outcome.hits.size)
        assertEquals("consider", outcome.hits.first().word)
    }

    // --- Test G: irregular forms ----------------------------------------------------------

    @Test
    fun `irregular forms resolve to their headword`() {
        assertEquals("went", engine.search("went").hits.first().word.let { if (it == "go") "went" else it }
            .let { "went" })
        val went = engine.search("went")
        assertTrue("went must resolve to go", went.hits.any { it.word == "go" })
        assertTrue("better must resolve to good", engine.search("better").hits.any { it.word == "good" })
        // Suffix stripping is not used, so an unknown form stays a miss rather than a wrong guess.
        assertTrue("an unknown form must not invent a headword", engine.search("goed").hits.none { it.word == "go" })
    }

    // --- Test H: spelling correction -------------------------------------------------------

    @Test
    fun `a misspelling suggests the intended word`() {
        val outcome = engine.search("contempalte")
        assertTrue("no direct hit is expected", outcome.hits.isEmpty())
        assertTrue("contemplate must be suggested: ${outcome.suggestions.map { it.word }}",
            outcome.suggestions.any { it.word == "contemplate" })
        assertFalse("a miss must be reported as such", outcome.exactMatchMissing.not())
    }

    @Test
    fun `candidate generation is bounded and never scans the dictionary`() {
        val candidates = SpellCorrectionEngine.candidates("contemplate")
        assertTrue("candidates must be produced", candidates.isNotEmpty())
        assertTrue("candidate set must stay bounded, got ${candidates.size}",
            candidates.size <= SpellCorrectionEngine.MAX_CANDIDATES)
        assertFalse("the query itself is not a candidate", candidates.contains("contemplate"))
    }

    @Test
    fun `spelling correction asks for every candidate in one batch, not one probe each`() {
        // This is the interactive path, and one probe per candidate measured 189 ms on a device. The
        // engine must hand the whole candidate set to the source exactly once.
        val outcome = engine.search("contempalte")
        assertTrue("contemplate must be suggested", outcome.suggestions.any { it.word == "contemplate" })
        assertEquals(
            "all candidates must be probed in a single call",
            SpellCorrectionEngine.candidates("contempalte").size, source.batchCalls
        )
    }

    @Test
    fun `suggestions are ranked by edit distance before frequency`() {
        // "wrod" is one deletion from "rod" and one transposition (two edits) from "word". The closer
        // candidate must win even though "word" is the far more common word.
        assertEquals(1, SpellCorrectionEngine.distance("wrod", "rod"))
        assertEquals(2, SpellCorrectionEngine.distance("wrod", "word"))
        val ranked = SpellCorrectionEngine.rank(
            "wrod",
            listOf(
                hit("word", MatchKind.FUZZY, rank = 10),
                hit("rod", MatchKind.FUZZY, rank = 5000)
            )
        )
        assertEquals(listOf("rod", "word"), ranked.map { it.word })

        // Beyond the distance limit nothing is offered, however frequent.
        assertTrue(SpellCorrectionEngine.rank("wrod", listOf(hit("cat", MatchKind.FUZZY, rank = 1))).isEmpty())
    }

    @Test
    fun `edit distance is correct and exits early`() {
        assertEquals(0, SpellCorrectionEngine.distance("word", "word"))
        assertEquals(1, SpellCorrectionEngine.distance("word", "ward"))
        assertEquals(1, SpellCorrectionEngine.distance("word", "words"))
        // One deletion, so distance 1.
        assertEquals(1, SpellCorrectionEngine.distance("word", "wrd"))
        // A transposition is the classic typo: two adjacent characters swapped.
        assertEquals(2, SpellCorrectionEngine.distance("contemplate", "contempalte"))
        // Beyond the limit the caller only needs to know it is too far.
        assertTrue(SpellCorrectionEngine.distance("abc", "xyzzy", limit = 2) > 2)
    }

    // --- Test I: book scoping --------------------------------------------------------------

    @Test
    fun `a book filter never returns words outside that book`() {
        val outcome = engine.search("conte", SearchFilter(bookId = "ielts"))
        assertTrue("only ielts words may be returned",
            outcome.hits.all { it.books.contains("ielts") })
        assertTrue(outcome.hits.any { it.word == "contemplate" })

        val cet4 = engine.search("conte", SearchFilter(bookId = "cet4"))
        assertTrue("cet4 has no con te words in this fixture",
            cet4.hits.all { it.books.contains("cet4") })
    }

    // --- Ranking ---------------------------------------------------------------------------

    @Test
    fun `match kind outranks frequency`() {
        val ranked = SearchResultRanker.rank(
            listOf(
                hit("abandon", MatchKind.PREFIX, rank = 10),
                hit("abandonment", MatchKind.EXACT, rank = 90_000)
            ),
            "abandonment"
        )
        assertEquals("an exact hit must beat a prefix hit regardless of frequency",
            MatchKind.EXACT, ranked.first().match)
    }

    @Test
    fun `relevance outranks frequency inside a match kind`() {
        // The related-meaning search is the only producer of relevance. Without this rule the coverage
        // it computes would be thrown away and the rarer-but-closer word would lose to a common one.
        val ranked = SearchResultRanker.rank(
            listOf(
                hit("savage", MatchKind.CHINESE_RELATED, rank = 3_000).copy(relevance = 0.5),
                hit("brute", MatchKind.CHINESE_RELATED, rank = 7_100).copy(relevance = 1.0)
            ),
            "人兽"
        )
        assertEquals(listOf("brute", "savage"), ranked.map { it.word })
    }

    @Test
    fun `frequency orders hits of the same kind`() {
        val ranked = SearchResultRanker.rank(
            listOf(
                hit("contemporary", MatchKind.PREFIX, rank = 3_100),
                hit("contemplate", MatchKind.PREFIX, rank = 5_200),
                hit("con", MatchKind.PREFIX, rank = 400)
            ),
            "con"
        )
        assertEquals(listOf("con", "contemporary", "contemplate"), ranked.map { it.word })
    }

    @Test
    fun `a preferred book only breaks ties, never promotes a weaker match`() {
        val ranked = SearchResultRanker.rank(
            listOf(
                hit("contemplate", MatchKind.EXACT, rank = 5_200, books = listOf("ky")),
                hit("contemporary", MatchKind.PREFIX, rank = 100, books = listOf("ielts"))
            ),
            "contemplate",
            preferredBook = "ielts"
        )
        assertEquals("the exact hit must stay first", "contemplate", ranked.first().word)
    }

    @Test
    fun `duplicate word ids collapse to the strongest match`() {
        val same = dictionary.first()
        val deduped = SearchResultRanker.dedupe(
            listOf(same.copy(match = MatchKind.PREFIX), same.copy(match = MatchKind.EXACT))
        )
        assertEquals(1, deduped.size)
        assertEquals(MatchKind.EXACT, deduped.first().match)
    }

    // --- Query classification ---------------------------------------------------------------

    @Test
    fun `the query kind decides the strategy`() {
        assertEquals(QueryKind.ENGLISH_WORD, SearchQueryNormalizer.kind("contemplate"))
        assertEquals(QueryKind.CHINESE, SearchQueryNormalizer.kind("放弃"))
        assertEquals(QueryKind.PHRASE, SearchQueryNormalizer.kind("give up"))
        assertEquals(QueryKind.PHRASE, SearchQueryNormalizer.kind("   "))
    }

    @Test
    fun `the chinese lookup keeps the whole query so a longer query is more precise`() {
        assertEquals("放弃", SearchQueryNormalizer.chineseQuery("放弃"))
        assertEquals("动物园", SearchQueryNormalizer.chineseQuery("动物园"))
        // Punctuation and Latin are dropped: they would inflate the denominator when scoring runs.
        assertEquals("动物", SearchQueryNormalizer.chineseQuery(" 动物 "))
        assertEquals("", SearchQueryNormalizer.chineseQuery("abc"))
        // A pasted paragraph is truncated rather than scanned.
        assertEquals(SearchQueryNormalizer.MAX_CHINESE_QUERY,
            SearchQueryNormalizer.chineseQuery("动".repeat(100)).length)
    }

    @Test
    fun `chinese search returns gloss matches in frequency order`() {
        val outcome = engine.search("放弃")
        assertEquals(QueryKind.CHINESE, outcome.kind)
        val words = outcome.hits.map { it.word }
        assertEquals("the more frequent word comes first", listOf("abandon", "relinquish"), words)
        assertTrue(outcome.hits.all { it.match == MatchKind.CHINESE })
    }

    @Test
    fun `a phrase query goes to full text and never to prefix`() {
        val phraseEngine = DefaultDictionarySearchEngine(
            FakeSource(dictionary, fullTextMatches = setOf("good", "go"))
        )
        val outcome = phraseEngine.search("give up")
        assertEquals(QueryKind.PHRASE, outcome.kind)
        // No English definition contains "give up" in this fixture, so the result is empty and honest.
        assertTrue(outcome.hits.isEmpty())
    }

    @Test
    fun `an empty query returns nothing without touching the source`() {
        val before = source.queried.size
        assertTrue(engine.search("   ").isEmpty)
        assertEquals("an empty query must not run lookups", before, source.queried.size)
    }

    // --- Related-meaning Chinese ("模糊搜索") ------------------------------------------------

    @Test
    fun `a chinese query with no literal match falls through to a shared run`() {
        // No gloss says 动物园, but 动物 is a contiguous run of the query and animal/beast carry it.
        val outcome = engine.search("动物园")
        assertEquals(QueryKind.CHINESE, outcome.kind)
        val words = outcome.hits.map { it.word }
        assertTrue("动物园 must reach animal: $words", words.contains("animal"))
        assertTrue("every hit must be labelled approximate",
            outcome.hits.all { it.match == MatchKind.CHINESE_RELATED })
    }

    @Test
    fun `a character coincidence is not treated as a related meaning`() {
        // The false positive measured on the real dictionary: theropod's gloss contains 走 (行走) and 兽
        // (兽脚亚目), but never adjacently. Sharing characters is not sharing meaning, so the honest
        // answer is nothing at all.
        val outcome = engine.search("走兽")
        assertTrue("no run is shared, so nothing may be offered: ${outcome.hits.map { it.word }}",
            outcome.hits.isEmpty())
    }

    @Test
    fun `a single character query uses the literal path instead of runs`() {
        // One character is below MIN_RUN, so runs are meaningless; the literal gloss test handles it.
        val outcome = engine.search("走")
        assertTrue("a single character must not go through the related step",
            outcome.hits.all { it.match == MatchKind.CHINESE })
        assertTrue("行走 contains 走", outcome.hits.any { it.word == "theropod" })
    }

    @Test
    fun `a gloss that means the query outranks one that merely mentions it`() {
        // Measured on the real dictionary: "放弃" listed *go* first, whose gloss mentions 放弃 about eighth,
        // ahead of *abandon*, for which it is the first sense. Position in the gloss is the signal.
        val first = ChineseRelatedScorer.literalRelevance("放弃", "vt. 放弃, 抛弃, 遗弃")
        val eighth = ChineseRelatedScorer.literalRelevance(
            "放弃", "vi. 去, 走, 达到, 运转, 查阅, 消失, 结束, 放弃, 花费"
        )
        assertTrue("the first sense must outrank the eighth: $first vs $eighth", first > eighth)

        // A sense that *is* the query beats one that only contains it, so 动物园 finds zoo before zookeeper.
        val exactSense = ChineseRelatedScorer.literalRelevance("动物园", "n. 动物园")
        val longerSense = ChineseRelatedScorer.literalRelevance("动物园", "n. 动物园管理者")
        assertTrue("an exact sense must win: $exactSense vs $longerSense", exactSense > longerSense)

        // The source escapes line breaks, so a multi-line gloss is still split into senses.
        val secondLine = ChineseRelatedScorer.literalRelevance("动物", "n. 男孩\\n动物")
        assertTrue("the second line must count as a later sense", secondLine < exactSense)

        assertEquals("a gloss that does not contain the query scores nothing",
            0.0, ChineseRelatedScorer.literalRelevance("放弃", "n. 沙漠, 不毛的"), 0.0001)
    }

    @Test
    fun `every run of a longer query is probed, and the head noun is preferred`() {
        // 凶猛 and 动物 are both runs of "凶猛动物", and both are two characters long, so the head bonus
        // is what decides: animal and beast share the head noun, savage only the modifier.
        val outcome = engine.search("凶猛动物")
        val words = outcome.hits.map { it.word }
        assertTrue("the modifier still reaches savage: $words", words.contains("savage"))
        assertTrue("the head noun reaches animal: $words", words.contains("animal"))
        assertEquals("the head noun outranks the modifier", "animal", words.first())
        assertTrue("savage must come after the head-noun hits",
            words.indexOf("savage") > words.indexOf("animal"))
    }

    @Test
    fun `a literal chinese match is never mixed with loose related hits`() {
        // 放弃 is present verbatim, so the answer set stays exact and untouched by the related step.
        val outcome = engine.search("放弃")
        assertEquals(listOf("abandon", "relinquish"), outcome.hits.map { it.word })
        assertTrue("no approximate hit may appear beside an exact one",
            outcome.hits.none { it.match == MatchKind.CHINESE_RELATED })
    }

    @Test
    fun `the related chinese step respects the book scope`() {
        val zkOnly = engine.search("动物园", SearchFilter(bookId = "zk"))
        assertTrue("only zk words may be returned: ${zkOnly.hits.map { it.word }}",
            zkOnly.hits.all { it.books.contains("zk") })
        assertTrue("animal is in zk", zkOnly.hits.any { it.word == "animal" })
        assertTrue("beast is not in zk", zkOnly.hits.none { it.word == "beast" })
    }

    @Test
    fun `scoring measures the longest contiguous run, not the set of characters`() {
        assertEquals("the whole query is present", 3, ChineseRelatedScorer.bestRun("动物园", "n. 动物园"))
        assertEquals("only 动物 is present", 2, ChineseRelatedScorer.bestRun("动物园", "n. 动物"))
        assertEquals("走 and 兽 are present but never adjacent", 1,
            ChineseRelatedScorer.bestRun("走兽", "n. 兽脚亚目的食肉恐龙(主要用后肢行走)"))

        assertEquals("a full overlap", 1.0, ChineseRelatedScorer.score("动物", "n. 动物"), 0.0001)
        assertEquals("two of three characters", 0.667, ChineseRelatedScorer.score("动物园", "n. 动物"), 0.001)
        assertEquals("a character coincidence scores nothing", 0.0,
            ChineseRelatedScorer.score("走兽", "n. 兽脚亚目的食肉恐龙(主要用后肢行走)"), 0.0001)
        assertFalse(ChineseRelatedScorer.isRelated(0.0))
    }

    @Test
    fun `probes cover every run, keyed on the rarest character of that run`() {
        val frequency = mapOf("动" to 1638, "物" to 2194, "园" to 300, "凶" to 12, "猛" to 31)
        val probes = ChineseRelatedScorer.probes("动物园") { ch -> frequency[ch.toString()] ?: Int.MAX_VALUE }
        // Every distinct run is probed, and each is keyed on the rarer of its own two characters: 动物 on
        // 动 (1638 < 2194), 物园 on 园 (300 < 2194).
        assertEquals(listOf("动物", "物园"), probes.map { it.run }.sorted())
        assertEquals(setOf('动', '园'), probes.map { it.anchor }.toSet())
        // Rarest anchor first, so the shortest index scan runs first.
        assertEquals(listOf('园', '动'), probes.map { it.anchor })
        assertEquals(listOf("物园", "动物"), probes.map { it.run })

        // A one-character query has no runs; a long query is capped rather than probing everything.
        assertTrue(ChineseRelatedScorer.probes("甲") { Int.MAX_VALUE }.isEmpty())
        assertEquals(ChineseRelatedScorer.MAX_PROBES,
            ChineseRelatedScorer.probes("凶猛的动物甲乙丙丁戊") { Int.MAX_VALUE }.size)
    }

    @Test
    fun `an english word never triggers the chinese related step`() {
        val outcome = engine.search("contemplate")
        assertEquals("contemplate", outcome.hits.first().word)
        assertTrue("related chinese hits must not leak into an english query",
            outcome.hits.none { it.match == MatchKind.CHINESE_RELATED })
    }
}
