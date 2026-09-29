package com.teameow.teawords.data.search

import com.teameow.teawords.data.LexicalText
import java.util.Locale

/** What kind of query the user typed. Deciding this once keeps every later step consistent. */
enum class QueryKind {
    /** A single English word: try exact, then forms, then prefix, then spelling. */
    ENGLISH_WORD,
    /** A run of Chinese characters: reverse lookup by meaning. */
    CHINESE,
    /** Several English words: treat as a phrase and search the full-text index. */
    PHRASE
}

/** How a hit was found. The ranker uses this, and the UI can explain why a result appeared. */
enum class MatchKind(val rank: Int) {
    /** The query is the headword itself. */
    EXACT(0),
    /** The query matched after normalisation (case, spacing, apostrophes). */
    NORMALIZED(1),
    /** The query is an inflected form of the headword: went -> go. */
    FORM(2),
    /** The headword starts with the query. */
    PREFIX(3),
    /** The query matched the Chinese gloss. */
    CHINESE(4),
    /**
     * The query matched *part* of the Chinese gloss: a related meaning rather than the exact phrase.
     * Listed after [CHINESE] so a literal match always wins, and labelled in the UI as approximate.
     */
    CHINESE_RELATED(5),
    /** The query matched the English definition text. */
    FULL_TEXT(6),
    /** The query is a likely misspelling of the headword. */
    FUZZY(7);

    /**
     * Why this word is in the list, in the user's words.
     *
     * Lives here rather than in the UI so every screen describes a hit the same way, and so the wording
     * that must not overclaim — a loose meaning match and a spelling suggestion are not answers — is
     * covered by the same tests as the ranking it describes.
     */
    fun describe(): String = when (this) {
        EXACT -> "精确匹配"
        NORMALIZED -> "忽略大小写与空格后匹配"
        FORM -> "词形还原匹配"
        PREFIX -> "前缀匹配"
        CHINESE -> "中文释义包含该词"
        CHINESE_RELATED -> "相近释义（仅部分字词相同，属近似结果）"
        FULL_TEXT -> "英文释义全文匹配"
        FUZZY -> "拼写建议（该词本身未收录）"
    }
}

/** A search hit. Deliberately flat so it can cross the repository boundary without an entity. */
data class SearchHit(
    val wordId: Long,
    val word: String,
    val phonetic: String,
    val zh: String,
    val en: String,
    val match: MatchKind,
    /** Corpus rank, 0 when unknown. Used only to break ties inside a match kind. */
    val freqRank: Int,
    /** Books this word belongs to, for display and filtering. */
    val books: List<String> = emptyList(),
    /** Part of speech as the source records it; shown on the entry, never inferred. */
    val pos: String = "",
    /** The source's own exam tags, space separated. Used as a difficulty signal, still uncalibrated. */
    val tags: String = "",
    /** Inflected forms in the source's own notation, shown verbatim. */
    val forms: String = "",
    /** Corpus rank as text ("BNC 1234 / FRQ 567"), kept so the UI can cite its origin. */
    val frequency: String = "",
    /**
     * How well this word matched *beyond* its [match] kind, in 0..1. Only the related-meaning search
     * fills this in (with the share of the query its gloss carries); everything else leaves it at 0 and
     * keeps ordering by corpus frequency. It exists because coverage is real evidence about which word
     * the user meant, and ranking such hits by frequency instead would discard it.
     */
    val relevance: Double = 0.0
)

/** Filters applied on top of the query. All optional. */
data class SearchFilter(
    /** Restrict to one exam book; null means the whole dictionary. */
    val bookId: String? = null,
    /**
     * Keep only words carrying no evidence and no self-report. Applied by the caller, which owns the
     * learning database: the dictionary file is read-only and knows nothing about the user.
     */
    val onlyUnmastered: Boolean = false,
    /** Keep only words the user marked as fuzzy. Applied by the caller, like [onlyUnmastered]. */
    val onlyFuzzy: Boolean = false,
    val limit: Int = 20
)

/**
 * Decides what the user typed and how to clean it up.
 *
 * Pure Kotlin with no Android or SQLite dependency, which is what makes the whole search pipeline
 * testable without a device.
 */
object SearchQueryNormalizer {
    /** Query shorter than this is not worth a fuzzy pass: too many candidates to be useful. */
    const val MIN_FUZZY_LENGTH = 4
    /** Longest query accepted; beyond this it is not a vocabulary lookup. */
    const val MAX_QUERY_LENGTH = 64
    /**
     * Longest Chinese query accepted. Scoring walks the query's runs, so a pasted paragraph would be
     * quadratic work for a result nobody reads.
     */
    const val MAX_CHINESE_QUERY = 24

    fun kind(raw: String): QueryKind {
        val text = raw.trim()
        if (text.isEmpty()) return QueryKind.PHRASE
        if (LexicalText.isChinese(text)) return QueryKind.CHINESE
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size == 1) QueryKind.ENGLISH_WORD else QueryKind.PHRASE
    }

    /** Normalisation shared with the stored keys, so queries and entries always agree. */
    fun normalize(raw: String): String = LexicalText.normalize(raw.take(MAX_QUERY_LENGTH))

    /**
     * The Chinese text used for reverse lookup.
     *
     * The dictionary's Chinese index is built from **single characters** (`LexicalText.chineseTokens`),
     * so the index key can only ever be one character; the whole query is then verified as a substring of
     * the gloss. Which single character to key on is a storage decision, made by the repository, because
     * only it can see the document frequencies. Keeping the whole query here is what makes a
     * two-character lookup accurate instead of something SQLite's tokenizers have to guess at.
     */
    fun chineseQuery(raw: String): String =
        raw.trim().filter { it in '\u3400'..'\u9fff' }.take(MAX_CHINESE_QUERY)

    fun shouldAttemptFuzzy(raw: String): Boolean {
        val text = normalize(raw)
        return text.length >= MIN_FUZZY_LENGTH && text.matches(Regex("[a-z]+"))
    }

    /** Upper bound for a prefix scan: the smallest string greater than every "con..." word. */
    fun prefixUpperBound(prefix: String): String = prefix + '\uffff'

    fun lower(value: String): String = value.lowercase(Locale.ROOT)
}

/**
 * Ranks hits of different match kinds.
 *
 * Accuracy first, exactly as the product requires: every hit of a stronger match kind outranks every
 * hit of a weaker one, so typing `contemplate` can never surface another exam word first. Frequency and
 * book relevance only order hits that were found the same way.
 */
object SearchResultRanker {
    fun rank(
        hits: List<SearchHit>,
        query: String,
        preferredBook: String? = null,
        bookPriority: Map<String, Int> = emptyMap()
    ): List<SearchHit> {
        val normalized = SearchQueryNormalizer.normalize(query)
        return hits.sortedWith(
            compareBy<SearchHit> { it.match.rank }
                .thenByDescending { exactness(it, normalized) }
                // Relevance is only ever set where a weaker match kind still carries real evidence
                // (related meanings), so it must be spent before frequency.
                .thenByDescending { it.relevance }
                .thenBy { if (it.freqRank <= 0) Int.MAX_VALUE else it.freqRank }
                .thenByDescending { preferredBook != null && it.books.contains(preferredBook) }
                .thenByDescending { bookPriority[it.word] ?: 0 }
                .thenBy { it.word }
        )
    }

    /** 1 when the headword is exactly the query after normalisation, else 0. */
    private fun exactness(hit: SearchHit, normalized: String): Int =
        if (LexicalText.normalize(hit.word) == normalized) 1 else 0

    /** Deduplicates by word id, keeping the strongest match kind found. */
    fun dedupe(hits: List<SearchHit>): List<SearchHit> = hits
        .groupBy { it.wordId }
        .map { (_, group) -> group.minByOrNull { it.match.rank }!! }
}

/**
 * Candidate generation for misspellings.
 *
 * Must never compare the query against the whole dictionary: on a 57k-entry file that is a full scan
 * per keystroke. Candidates are produced from indexed lookups instead — one deletion, one transposition
 * and one substitution per position — which is bounded and uses the same unique index as exact search.
 * Edit distance is only computed for those few candidates.
 */
object SpellCorrectionEngine {
    const val MAX_CANDIDATES = 256
    const val MAX_SUGGESTIONS = 5
    /** A suggestion further than this many edits away is not worth showing. */
    const val MAX_DISTANCE = 2

    /** Query variants that are one edit away, bounded so the probe set stays small. */
    fun candidates(query: String): List<String> {
        val text = SearchQueryNormalizer.normalize(query)
        if (text.length < SearchQueryNormalizer.MIN_FUZZY_LENGTH) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in text.indices) {
            // deletion
            out.add(text.removeRange(i, i + 1))
            // transposition
            if (i + 1 < text.length && text[i] != text[i + 1]) {
                out.add(text.substring(0, i) + text[i + 1] + text[i] + text.substring(i + 2))
            }
            // substitution with the letters most often mistyped nearby
            for (c in "aeiorstlncudpmhgbfvwy") {
                out.add(text.substring(0, i) + c + text.substring(i + 1))
            }
            if (out.size >= MAX_CANDIDATES) break
        }
        // insertion is the most expensive family, so it is added last and only while budget remains
        if (out.size < MAX_CANDIDATES) {
            for (i in 0..text.length) {
                for (c in "aeiorstln") {
                    out.add(text.substring(0, i) + c + text.substring(i))
                    if (out.size >= MAX_CANDIDATES) break
                }
                if (out.size >= MAX_CANDIDATES) break
            }
        }
        return out.filter { it != text && it.isNotEmpty() }.take(MAX_CANDIDATES)
    }

    /** Levenshtein distance with an early exit once [limit] is exceeded. */
    fun distance(a: String, b: String, limit: Int = MAX_DISTANCE): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                best = minOf(best, current[j])
            }
            if (best > limit) return limit + 1
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    /**
     * Orders candidates that were already found: closest first, then the more frequent word. A suggestion
     * that is barely frequent is unlikely to be what the user meant.
     *
     * Split out of [suggest] so a caller can look up every candidate in **one** statement. This runs on
     * the interactive path whenever a word is mistyped, and the probe count is essentially the whole cost:
     * measured on a device, one probe per candidate took 189 ms for a single typo.
     */
    fun rank(query: String, found: List<SearchHit>, limit: Int = MAX_SUGGESTIONS): List<SearchHit> {
        val normalized = SearchQueryNormalizer.normalize(query)
        return found
            .map { it to distance(normalized, SearchQueryNormalizer.normalize(it.word)) }
            .filter { it.second <= MAX_DISTANCE }
            .sortedWith(
                compareBy<Pair<SearchHit, Int>> { it.second }
                    .thenBy { if (it.first.freqRank <= 0) Int.MAX_VALUE else it.first.freqRank }
                    .thenBy { it.first.word }
            )
            .map { it.first }
            .distinctBy { it.wordId }
            .take(limit)
    }

    /** Convenience form for a caller that can only answer one candidate at a time. */
    fun suggest(
        query: String,
        exists: (String) -> SearchHit?,
        limit: Int = MAX_SUGGESTIONS
    ): List<SearchHit> {
        val found = candidates(query).mapNotNull { exists(it) }
        return rank(query, found, limit)
    }
}

/** One planned query: the SQL uses fixed statements, so the plan is data rather than string building. */
sealed interface SearchStep {
    data class Exact(val normalized: String) : SearchStep
    data class Prefix(val prefix: String, val upper: String) : SearchStep
    data class Forms(val form: String) : SearchStep
    /** Reverse lookup by meaning: the gloss must contain the whole query. */
    data class Chinese(val query: String) : SearchStep
    /** Looser Chinese: word shares a contiguous part of the query, for "find something close to this meaning". */
    data class ChineseRelated(val query: String) : SearchStep
    data class FullText(val terms: String) : SearchStep
}

/**
 * Turns a query into an ordered list of steps, strongest match kind first.
 *
 * This is the piece that decides *what* to ask the database. Keeping it here (and free of SQLite)
 * means the search strategy can be unit tested, and the repository below only has to execute the
 * steps it is given.
 */
object SearchPlanBuilder {
    fun build(raw: String, filter: SearchFilter): List<SearchStep> {
        val normalized = SearchQueryNormalizer.normalize(raw)
        if (normalized.isEmpty()) return emptyList()
        return when (SearchQueryNormalizer.kind(raw)) {
            QueryKind.CHINESE -> buildList {
                add(SearchStep.Chinese(SearchQueryNormalizer.chineseQuery(raw)))
                // Always planned, only used when the literal match found nothing: a user typing
                // "动物园" should still reach animal (动物) when no gloss says exactly "动物园".
                add(SearchStep.ChineseRelated(raw.trim()))
            }
            QueryKind.ENGLISH_WORD -> buildList {
                add(SearchStep.Exact(normalized))
                add(SearchStep.Forms(normalized))
                add(SearchStep.Prefix(normalized, SearchQueryNormalizer.prefixUpperBound(normalized)))
                // Full text is only worth a try when the word itself was not found.
                add(SearchStep.FullText("\"$normalized\""))
            }
            QueryKind.PHRASE -> listOf(
                SearchStep.FullText(
                    normalized.split(Regex("\\s+"))
                        .filter { it.isNotBlank() }
                        .take(8)
                        .joinToString(" ") { "\"$it\"" }
                )
            )
        }
    }
}

/**
 * Scoring for the looser Chinese search.
 *
 * Pure and unit tested, because this is where "similar meaning" is defined for the user.
 *
 * The measure is **the longest contiguous run of the query that appears in a word's gloss**, not a bag of
 * characters. That distinction is the whole design: a bag-of-characters score was measured against the real
 * 57,841-word dictionary and returned nonsense — "走兽" surfaced *theropod* (whose gloss contains 走 in
 * 行走 and 兽 in 兽脚亚目), "动物" surfaced *he*, *still* and *tree*, and "甲乙丙" surfaced *perspex*
 * (甲基丙烯酸). Long glosses contain most common characters by coincidence, so sharing characters is not
 * evidence of sharing meaning.
 *
 * Requiring the shared characters to be **adjacent and in order** removes exactly that class of false
 * positive, because a coincidence has to be a coincidence of a whole word, not of loose characters. It
 * remains a lexical measure — the app has no embeddings and will not pretend to — but it is one that says
 * something true: "this gloss contains part of what you typed".
 */
object ChineseRelatedScorer {
    /** A shared run shorter than this is a coincidence, not a meaning. */
    const val MIN_RUN = 2
    /** Upper bound on index probes: one per distinct 2-character run of the query. */
    const val MAX_PROBES = 6
    /** Probing with the rarest character keeps each candidate set small. */
    const val MAX_CANDIDATES_PER_PROBE = 400
    /**
     * Multiplier for a gloss sense that contains the query without being it. Small enough that position
     * still dominates, large enough that an exact sense wins a tie at the same position.
     */
    const val INCIDENTAL_MENTION = 0.85
    /**
     * Chinese puts the head noun last ("凶猛的动物" is a kind of 动物), so a run at the end of the query is
     * a slightly better guide to what was meant than one in the modifier. Small on purpose: it may only
     * break a tie between equally long runs.
     */
    const val HEAD_BONUS = 0.05

    /** One index probe: the anchor character to key on, and the run that must then appear in the gloss. */
    data class Probe(val anchor: Char, val run: String)

    /**
     * Probes for a query, rarest anchor first so the cheapest index scan runs first.
     *
     * Every distinct 2-character run is probed. A longer query therefore also finds words that share only
     * its head ("凶猛的动物" reaches both 凶猛 and 动物) instead of depending on one arbitrary character.
     */
    fun probes(query: String, docFrequency: (Char) -> Int): List<Probe> {
        val chars = queryChars(query)
        if (chars.size < MIN_RUN) return emptyList()
        return (0..chars.size - MIN_RUN)
            .map { i ->
                val run = chars.subList(i, i + MIN_RUN).joinToString("")
                // The rarer of the run's characters is the narrower index lookup.
                Probe(run.toCharArray().minByOrNull { docFrequency(it) }!!, run)
            }
            .distinctBy { it.anchor to it.run }
            .sortedBy { docFrequency(it.anchor) }
            .take(MAX_PROBES)
    }

    /**
     * The length of the longest contiguous part of the query that appears in the gloss, 0 when there is
     * none. This is what makes a hit explainable: "this gloss contains 动物".
     */
    fun bestRun(query: String, gloss: String): Int {
        val chars = queryChars(query)
        var best = 0
        for (start in chars.indices) {
            for (end in start + best + 1..chars.size) {
                if (gloss.contains(chars.subList(start, end).joinToString(""))) {
                    best = end - start
                } else {
                    break
                }
            }
        }
        return best
    }

    /**
     * How early in a gloss the query appears, as a 0..1 relevance.
     *
     * Literal Chinese hits are ordered by this before corpus frequency, because frequency alone answers the
     * wrong question. Measured on the shipped dictionary, "放弃" listed *go* first: its gloss mentions 放弃
     * as roughly its eighth sense, ahead of *abandon*, for which 放弃 is the first. The source lists senses
     * in order, so where the query lands is a cheap, honest signal of whether a word *means* the query or
     * merely mentions it.
     *
     * A ratio of query length to gloss length was tried first and was clearly worse — it promoted rare words
     * with one-word glosses, putting *Ireland* above *love* for "爱". Ordering by position and then by
     * corpus frequency keeps the common words in front while demoting incidental mentions.
     */
    fun literalRelevance(query: String, gloss: String): Double {
        val chars = queryChars(query)
        if (chars.isEmpty() || gloss.isEmpty()) return 0.0
        // The source escapes line breaks as the two characters "\n" rather than storing them, so they have
        // to be normalised before splitting or a whole gloss counts as one sense.
        val segments = gloss.replace("\\r\\n", "\n").replace("\\n", "\n")
            .split(',', '，', ';', '；', '\n')
        // The query can straddle a separator, so fall back to the first sense that holds all of it.
        val ordinal = segments.indexOfFirst { it.contains(query) }
            .takeIf { it >= 0 }
            ?: segments.indexOfFirst { segment -> chars.all { segment.contains(it) } }
        if (ordinal < 0) return 0.0
        // A sense that *is* the query beats one that merely contains it: "动物园" belongs to *zoo*
        // ("n. 动物园") before *zookeeper* ("n. 动物园管理者"). The part-of-speech marker is stripped first.
        val segment = segments[ordinal].trim()
        val bare = segment.substringAfter(' ').trim().ifEmpty { segment }
        val position = 1.0 / (1.0 + ordinal)
        return if (bare == query) position else position * INCIDENTAL_MENTION
    }

    /**
     * @return relevance in 0..1, or 0.0 when the overlap is too small to be offered at all.
     */
    fun score(query: String, gloss: String): Double {
        val chars = queryChars(query)
        if (chars.isEmpty()) return 0.0
        val run = bestRun(query, gloss)
        if (run < MIN_RUN) return 0.0
        val base = run.toDouble() / chars.size
        // Head bonus only when the run reaches the end of the query.
        val atHead = gloss.contains(chars.takeLast(run).joinToString(""))
        return (if (atHead) base + HEAD_BONUS else base).coerceAtMost(1.0)
    }

    fun isRelated(score: Double): Boolean = score > 0.0

    /**
     * The distinct Chinese characters of a query, in order.
     *
     * Only CJK ideographs count: punctuation and Latin letters in a mixed query would otherwise inflate
     * the denominator and make a genuinely close gloss look like a partial match.
     */
    fun queryChars(query: String): List<Char> = SearchQueryNormalizer.chineseQuery(query).toList()
}
