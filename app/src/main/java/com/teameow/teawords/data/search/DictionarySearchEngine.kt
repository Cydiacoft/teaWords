package com.teameow.teawords.data.search

/**
 * One entry point for every kind of lookup.
 *
 * The engine's job is to run the steps of a [SearchStep] plan in order, keep the strongest match kind
 * per word, add spelling suggestions when the direct lookups came up short, and rank the result. It
 * holds no Android or SQLite types itself, which is what lets the whole strategy be tested with a fake
 * repository — the search behaviour is too important to only be verified through the UI.
 */
interface DictionarySearchEngine {
    fun search(query: String, filter: SearchFilter = SearchFilter()): SearchOutcome
    fun suggest(query: String, limit: Int = 5): List<SearchHit>
}

/** Result of one search, including why it came back empty. */
data class SearchOutcome(
    val query: String,
    val kind: QueryKind,
    val hits: List<SearchHit>,
    /** Spell-correction suggestions, only produced when the direct lookups found nothing. */
    val suggestions: List<SearchHit> = emptyList(),
    /** True when the query looked like a word but the dictionary had no exact match. */
    val exactMatchMissing: Boolean = false
) {
    val isEmpty: Boolean get() = hits.isEmpty() && suggestions.isEmpty()
}

/** Ports the engine needs from storage. Narrow on purpose so a fake is trivial to write in tests. */
interface SearchHitSource {
    fun exact(normalized: String, filter: SearchFilter): List<SearchHit>
    fun prefix(prefix: String, upper: String, filter: SearchFilter): List<SearchHit>
    fun byForm(form: String, filter: SearchFilter): List<SearchHit>
    fun chinese(query: String, filter: SearchFilter): List<SearchHit>
    /** Words whose Chinese gloss contains a contiguous part of the query: "something close to this". */
    fun chineseRelated(query: String, filter: SearchFilter): List<SearchHit>
    fun fullText(terms: String, filter: SearchFilter): List<SearchHit>
    fun exists(normalized: String): SearchHit?
    /**
     * Which of these normalised words exist, with their corpus rank. Answers the whole spelling-correction
     * candidate set in one statement instead of one probe per candidate.
     */
    fun existing(normalized: Collection<String>): List<SearchHit>
}

/**
 * Default engine.
 *
 * Ordering rule that the product depends on: accuracy beats personalisation. Hits are grouped by match
 * kind and every exact hit outranks every prefix hit, so a user typing a word they know always sees that
 * word first. Preferred-book and knowledge signals are only used to order hits that were found the same
 * way, and that is applied by the ranker, not here.
 */
class DefaultDictionarySearchEngine(
    private val source: SearchHitSource
) : DictionarySearchEngine {

    override fun search(query: String, filter: SearchFilter): SearchOutcome {
        val kind = SearchQueryNormalizer.kind(query)
        if (query.isBlank()) return SearchOutcome(query, kind, emptyList())

        val collected = LinkedHashMap<Long, SearchHit>()
        var exactFound = false

        for (step in SearchPlanBuilder.build(query, filter)) {
            val hits = when (step) {
                is SearchStep.Exact -> source.exact(step.normalized, filter).also {
                    if (it.isNotEmpty()) exactFound = true
                }
                is SearchStep.Prefix -> if (exactFound && kind == QueryKind.ENGLISH_WORD) {
                    // An exact hit already answers the query; a prefix list on top of it would only
                    // push the word the user typed down the screen.
                    emptyList()
                } else source.prefix(step.prefix, step.upper, filter)

                is SearchStep.Forms -> if (exactFound) emptyList() else source.byForm(step.form, filter)
                is SearchStep.Chinese -> source.chinese(step.query, filter)
                // Only reached when the literal Chinese lookup found nothing; the loose results are
                // labelled CHINESE_RELATED so the UI can say they are approximate.
                is SearchStep.ChineseRelated -> if (collected.isEmpty()) {
                    source.chineseRelated(step.query, filter)
                } else emptyList()
                is SearchStep.FullText -> if (exactFound || kind == QueryKind.CHINESE) {
                    emptyList()
                } else source.fullText(step.terms, filter)
            }
            for (hit in hits) {
                val existing = collected[hit.wordId]
                if (existing == null || hit.match.rank < existing.match.rank) collected[hit.wordId] = hit
            }
            // Exact and Chinese matches are complete answers; nothing weaker should be mixed in.
            if (collected.isNotEmpty() && (exactFound || kind == QueryKind.CHINESE)) break
            if (collected.size >= filter.limit * 4) break
        }

        val ranked = SearchResultRanker.rank(SearchResultRanker.dedupe(collected.values.toList()), query)
            .take(filter.limit)

        val suggestions = if (ranked.isEmpty() && kind == QueryKind.ENGLISH_WORD &&
            SearchQueryNormalizer.shouldAttemptFuzzy(query)
        ) {
            // A real edit-distance search is never run: candidates come from indexed probes and the
            // distance is only computed for the handful that exist. The lookup is one batched statement,
            // because this is the interactive path and a per-candidate probe measured 189 ms on device.
            suggestCandidates(query, filter.limit)
        } else {
            emptyList()
        }

        return SearchOutcome(
            query = query,
            kind = kind,
            hits = ranked,
            suggestions = suggestions,
            exactMatchMissing = kind == QueryKind.ENGLISH_WORD && !exactFound
        )
    }

    override fun suggest(query: String, limit: Int): List<SearchHit> =
        if (!SearchQueryNormalizer.shouldAttemptFuzzy(query)) emptyList()
        else suggestCandidates(query, limit)

    /**
     * One batched probe for every candidate variant, then rank by edit distance.
     *
     * Separated because both [search] and [suggest] need it, and because the batching is the whole point:
     * `SpellCorrectionEngine.candidates` produces up to 256 strings, and asking for them one at a time
     * cost 189 ms on a device for a single typo.
     */
    private fun suggestCandidates(query: String, limit: Int): List<SearchHit> {
        val candidates = SpellCorrectionEngine.candidates(query)
        if (candidates.isEmpty()) return emptyList()
        return SpellCorrectionEngine.rank(query, source.existing(candidates), limit)
    }
}