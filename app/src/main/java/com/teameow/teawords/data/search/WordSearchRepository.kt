package com.teameow.teawords.data.search

import android.database.sqlite.SQLiteDatabase
import com.teameow.teawords.data.BundledDictionaryInstaller
import com.teameow.teawords.data.LexicalText

/**
 * Executes search plans against the pre-built, read-only dictionary file.
 *
 * The file is deliberately separate from the user's learning database: it is replaced wholesale when
 * the dictionary is updated, and it contains no personal data. This repository therefore never writes,
 * opens the file read-only, and joins nothing from the learning side. Knowledge state is attached by
 * the caller, which owns `teawords.db` and keys it by the same global `word_id`.
 *
 * Every statement here is a fixed string bound with arguments, so SQLite can reuse its prepared
 * statements and the query planner keeps using `idx_lex_words_norm_unique` and `idx_lex_zh_token`.
 */
class WordSearchRepository(
    private val installer: BundledDictionaryInstaller
) : WordFormResolver, SearchHitSource {

    private var database: SQLiteDatabase? = null

    private companion object {
        /**
         * Bound variables per statement. SQLite's historical default limit is 999; staying well under it
         * keeps the batched candidate probe safe on every build.
         */
        const val MAX_BOUND_VARIABLES = 400
    }

    /** Opens the dictionary file, installing the bundled copy first if it is missing. */
    fun open(forceReinstall: Boolean = false, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        installer.install(forceReinstall, onProgress)
        val file = installer.targetFile()
        if (!file.exists()) return false
        database?.close()
        database = SQLiteDatabase.openDatabase(
            file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        return true
    }

    fun close() {
        database?.close()
        database = null
    }

    private fun db(): SQLiteDatabase = database ?: error("检索数据库尚未打开")

    fun isOpen(): Boolean = database?.isOpen == true

    // --- Column lists ---------------------------------------------------------------------
    // Kept as one constant so every query returns the same shape.

    private val wordColumns =
        "w.id AS word_id, w.word, w.phonetic, w.zh, w.en, w.frequency, w.pos, w.tags, w.forms"

    private fun rankOf(frequency: String): Int {
        // Frequency is stored as "BNC 1234 / FRQ 567"; the first number found is the corpus rank.
        val match = Regex("\\d+").find(frequency) ?: return 0
        return match.value.toIntOrNull() ?: 0
    }

    /**
     * Books a word belongs to, for **all** of the given hits in one round trip.
     *
     * This used to be one query per hit, which made a 20-hit search cost 21 statements for data a single
     * `IN (...)` list returns at once. Book membership is also what the scope filter and the "which exam
     * is this from" label read, so it is on every search path.
     */
    private fun attachBooks(hits: List<SearchHit>): List<SearchHit> {
        if (hits.isEmpty()) return hits
        val ids = hits.map { it.wordId }.distinct()
        val placeholders = ids.joinToString(",") { "?" }
        val byWord = HashMap<Long, MutableList<String>>()
        db().rawQuery(
            "SELECT word_id, book_id FROM word_book_entries WHERE word_id IN ($placeholders) " +
                "ORDER BY word_id, book_id",
            ids.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                byWord.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getString(1))
            }
        }
        return hits.map { h -> byWord[h.wordId]?.let { h.copy(books = it) } ?: h }
    }

    /** Reads one already-built statement into hits, resolving their books in a single follow-up query. */
    private fun read(sql: String, args: Array<String>?, match: MatchKind): List<SearchHit> =
        attachBooks(
            db().rawQuery(sql, args).use { c ->
                buildList { while (c.moveToNext()) add(hit(c, match)) }
            }
        )

    private fun hit(cursor: android.database.Cursor, match: MatchKind): SearchHit = SearchHit(
        wordId = cursor.getLong(0),
        word = cursor.getString(1),
        phonetic = cursor.getString(2).orEmpty(),
        zh = cursor.getString(3).orEmpty(),
        en = cursor.getString(4).orEmpty(),
        match = match,
        freqRank = rankOf(cursor.getString(5).orEmpty()),
        pos = cursor.getString(6).orEmpty(),
        tags = cursor.getString(7).orEmpty(),
        forms = cursor.getString(8).orEmpty(),
        frequency = cursor.getString(5).orEmpty()
    )

    // --- Query steps ----------------------------------------------------------------------

    /**
     * Exact headword lookup, honouring the book scope.
     *
     * The scope filter belongs here as much as on the prefix and Chinese steps. Leaving it out was a real
     * defect: a search restricted to one exam book still returned headwords from outside it, because the
     * strongest match kind was the one step that ignored the restriction.
     */
    override fun exact(normalized: String, filter: SearchFilter): List<SearchHit> {
        val sql = buildString {
            append("SELECT $wordColumns FROM lex_words w WHERE w.normalized = ?")
            if (filter.bookId != null) {
                append(" AND w.id IN (SELECT word_id FROM word_book_entries WHERE book_id = ?)")
            }
            append(" LIMIT ?")
        }
        val args = buildList {
            add(normalized)
            filter.bookId?.let { add(it) }
            add(filter.limit.toString())
        }.toTypedArray()
        return read(sql, args, MatchKind.EXACT)
    }

    /** Prefix autocomplete over the unique normalised index; never a table scan. */
    override fun prefix(prefix: String, upper: String, filter: SearchFilter): List<SearchHit> {
        val sql = buildString {
            append("SELECT $wordColumns FROM lex_words w WHERE w.normalized >= ? AND w.normalized < ?")
            if (filter.bookId != null) {
                append(" AND w.id IN (SELECT word_id FROM word_book_entries WHERE book_id = ?)")
            }
            append(" ORDER BY w.normalized LIMIT ?")
        }
        val args = buildList {
            add(prefix); add(upper)
            filter.bookId?.let { add(it) }
            add(filter.limit.toString())
        }.toTypedArray()
        return read(sql, args, MatchKind.PREFIX).map {
            // A prefix result is exact when the headword equals the query, which happens when the query
            // itself is a word: "con" -> "con" must not be reported as a prefix hit.
            if (LexicalText.normalize(it.word) == prefix) it.copy(match = MatchKind.EXACT) else it
        }
    }

    override fun resolve(form: String): List<Long> {
        if (!LexicalFormKeys.isResolvable(form)) return emptyList()
        return db().rawQuery(
            "SELECT word_id FROM lex_forms WHERE form = ? ORDER BY kind LIMIT 8",
            arrayOf(LexicalText.normalize(form))
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /** Headwords for a surface form, as hits, used after exact and prefix both missed. */
    override fun byForm(form: String, filter: SearchFilter): List<SearchHit> {
        val ids = resolve(form)
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = buildString {
            append("SELECT $wordColumns FROM lex_words w WHERE w.id IN ($placeholders)")
            if (filter.bookId != null) {
                append(" AND w.id IN (SELECT word_id FROM word_book_entries WHERE book_id = ?)")
            }
            append(" LIMIT ?")
        }
        val args = buildList {
            ids.forEach { add(it.toString()) }
            filter.bookId?.let { add(it) }
            add(filter.limit.toString())
        }.toTypedArray()
        return read(sql, args, MatchKind.FORM)
    }

    /**
     * Chinese reverse lookup.
     *
     * SQLite's built-in tokenizers do not segment Chinese, so the index is a hand-built **single-character**
     * token table and the actual substring test is a verified `instr()` on the gloss. The character token
     * narrows the candidates through an index; `instr` then does the real work, which is what makes a
     * two-character query accurate without a real segmenter.
     *
     * The anchor is chosen as the query's rarest character. Any character would return the same rows —
     * `instr(zh, full) > 0` implies every character of `full` is present — so the rarest one simply makes
     * the index scan as short as possible. Picking a *fixed* character (the first, say) would also be
     * correct but would scan the longest lists in the table.
     *
     * The page is taken by corpus frequency, then reordered by how much of each gloss the query accounts
     * for. Reordering only within the page is a deliberate simplification: it means a very rare word with
     * a perfect short gloss can still miss the cut, but it keeps the query to one index scan with a bound
     * on the rows read instead of scoring every word that contains one character.
     */
    override fun chinese(query: String, filter: SearchFilter): List<SearchHit> {
        val anchor = rarestCharacter(query) ?: return emptyList()
        val sql = buildString {
            append("SELECT $wordColumns FROM lex_zh z JOIN lex_words w ON w.id = z.word_id ")
            append("WHERE z.token = ? AND instr(w.zh, ?) > 0")
            if (filter.bookId != null) {
                append(" AND w.id IN (SELECT word_id FROM word_book_entries WHERE book_id = ?)")
            }
            // Frequency ordering matters for the page: many words share a character, and the common ones
            // are almost always what the user means.
            append(" ORDER BY CASE WHEN z.freq_rank <= 0 THEN 2147483647 ELSE z.freq_rank END, w.normalized LIMIT ?")
        }
        val args = buildList {
            add(anchor.toString()); add(query)
            filter.bookId?.let { add(it) }
            add(filter.limit.toString())
        }.toTypedArray()
        return read(sql, args, MatchKind.CHINESE).map { hit ->
            hit.copy(relevance = ChineseRelatedScorer.literalRelevance(query, hit.zh))
        }
    }

    /** Full-text search over the English definitions. */
    override fun fullText(terms: String, filter: SearchFilter): List<SearchHit> {
        if (terms.isBlank()) return emptyList()
        val sql = buildString {
            append("SELECT $wordColumns FROM lex_fts f JOIN lex_words w ON w.id = f.docid ")
            append("WHERE lex_fts MATCH ?")
            if (filter.bookId != null) {
                append(" AND w.id IN (SELECT word_id FROM word_book_entries WHERE book_id = ?)")
            }
            append(" LIMIT ?")
        }
        val args = buildList {
            add(terms)
            filter.bookId?.let { add(it) }
            add(filter.limit.toString())
        }.toTypedArray()
        // A malformed MATCH expression must degrade to "no results", never crash the search screen.
        return runCatching { read(sql, args, MatchKind.FULL_TEXT) }.getOrDefault(emptyList())
    }

    /**
     * Single-word probe used by spelling correction: does this candidate exist at all?
     *
     * Deliberately does **not** resolve books. Books are not part of a suggestion and resolving them made
     * this two statements instead of one, on the path that runs whenever the user mistypes a word.
     */
    override fun exists(normalized: String): SearchHit? =
        db().rawQuery(
            "SELECT $wordColumns FROM lex_words w WHERE w.normalized = ? LIMIT 1",
            arrayOf(normalized)
        ).use { c -> if (c.moveToNext()) hit(c, MatchKind.FUZZY) else null }

    /**
     * Which of these normalised words exist, in one statement.
     *
     * Chunked because SQLite caps the number of bound variables per statement, and
     * `SpellCorrectionEngine.candidates` can produce a few hundred strings.
     */
    override fun existing(normalized: Collection<String>): List<SearchHit> {
        if (normalized.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>(normalized.size)
        for (chunk in normalized.distinct().chunked(MAX_BOUND_VARIABLES)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db().rawQuery(
                "SELECT $wordColumns FROM lex_words w WHERE w.normalized IN ($placeholders)",
                chunk.toTypedArray()
            ).use { c -> while (c.moveToNext()) out.add(hit(c, MatchKind.FUZZY)) }
        }
        return out
    }

    /**
     * Looser Chinese search: words whose gloss contains a contiguous part of the query.
     *
     * Two things keep this cheap. Each probe keys on the rarest character of one 2-character run, so the
     * index scan is short, and one probe returns every candidate for that run — no per-candidate queries.
     * Scoring then happens in Kotlin, where it is unit tested.
     *
     * The run must appear **contiguously** in the gloss. Matching loose characters instead was measured
     * against the real dictionary and produced nonsense (see [ChineseRelatedScorer]), which is why the
     * anchoring character and the verified run are separate things.
     */
    override fun chineseRelated(query: String, filter: SearchFilter): List<SearchHit> {
        val text = SearchQueryNormalizer.chineseQuery(query)
        val probes = ChineseRelatedScorer.probes(text) { docFrequency(it.toString()) }
        if (probes.isEmpty()) return emptyList()

        val best = LinkedHashMap<Long, SearchHit>()
        for (probe in probes) {
            val sql = buildString {
                append("SELECT $wordColumns FROM lex_zh z JOIN lex_words w ON w.id = z.word_id ")
                append("WHERE z.token = ?")
                if (filter.bookId != null) {
                    append(" AND w.id IN (SELECT word_id FROM word_book_entries WHERE book_id = ?)")
                }
                append(" ORDER BY CASE WHEN z.freq_rank <= 0 THEN 2147483647 ELSE z.freq_rank END LIMIT ?")
            }
            val args = buildList {
                add(probe.anchor.toString())
                filter.bookId?.let { add(it) }
                add(ChineseRelatedScorer.MAX_CANDIDATES_PER_PROBE.toString())
            }.toTypedArray()
            db().rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val gloss = c.getString(3).orEmpty()
                    // The probe character only narrowed the index; the run is what earns the hit.
                    if (!gloss.contains(probe.run)) continue
                    val score = ChineseRelatedScorer.score(text, gloss)
                    if (!ChineseRelatedScorer.isRelated(score)) continue
                    val candidate = hit(c, MatchKind.CHINESE_RELATED).copy(relevance = score)
                    val existing = best[candidate.wordId]
                    // A word can share several runs; keep the strongest overlap.
                    if (existing == null || candidate.relevance > existing.relevance) {
                        best[candidate.wordId] = candidate
                    }
                }
            }
        }
        // Closest overlap first; equal overlap falls back to the more frequent word. The engine's ranker
        // reads `relevance`, so this ordering survives being merged with the other steps' hits.
        return attachBooks(
            best.values
                .sortedWith(
                    compareByDescending<SearchHit> { it.relevance }
                        .thenBy { if (it.freqRank <= 0) Int.MAX_VALUE else it.freqRank }
                        .thenBy { it.word }
                )
                .take(filter.limit)
        )
    }

    /**
     * Document frequency of one indexed character, or [Int.MAX_VALUE] when it is unknown.
     *
     * `lex_token_stats` is a **performance** aid, never a correctness requirement: the anchor character
     * only decides how long the index scan is, and the related search re-verifies every candidate against
     * the gloss anyway. So a dictionary built without the statistics must degrade in speed, not fail —
     * and a shipped asset really did go out without the table, which crashed the search screen on every
     * Chinese query. Unknown frequencies therefore answer "as common as anything else", and
     * [rarestCharacter] falls back to the query's first character, which is always correct.
     */
    private fun docFrequency(token: String): Int = runCatching {
        db().rawQuery("SELECT doc_freq FROM lex_token_stats WHERE token = ?", arrayOf(token)).use {
            if (it.moveToFirst()) it.getInt(0) else Int.MAX_VALUE
        }
    }.getOrDefault(Int.MAX_VALUE)

    /** The query's rarest indexed character: the shortest index list to scan. */
    private fun rarestCharacter(query: String): Char? =
        ChineseRelatedScorer.queryChars(query).minByOrNull { docFrequency(it.toString()) }

    /** Books available in the dictionary, for the scope selector. */
    data class BookInfo(val id: String, val name: String, val description: String, val wordCount: Int, val category: String)
    fun books(): List<BookInfo> = db().rawQuery(
        "SELECT id, name, description, total_word_count, category FROM word_books ORDER BY category, name",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(BookInfo(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4)))
            }
        }
    }

    /** Full union of selected books; learning must not use the search result limit. */
    fun learningWords(bookIds: Set<String>): List<SearchHit> {
        val ids = bookIds.intersect(books().map { it.id }.toSet()).sorted()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return db().rawQuery(
            "SELECT $wordColumns FROM lex_words w WHERE EXISTS (" +
                "SELECT 1 FROM word_book_entries b WHERE b.word_id=w.id AND b.book_id IN ($placeholders)) " +
                "ORDER BY w.normalized", ids.toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(hit(c, MatchKind.EXACT)) } }
    }

    fun meta(key: String): String? = db().rawQuery(
        "SELECT value FROM dictionary_meta WHERE key = ?", arrayOf(key)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun wordCount(): Int = meta("word_count")?.toIntOrNull() ?: 0

    /**
     * The asset's own schema version.
     *
     * [BundledDictionaryInstaller.SCHEMA_VERSION] must equal this, and the instrumented test asserts it:
     * a stale asset that still reports the old version is invisible in every other check — it opens, it
     * counts the right number of words, and it only fails later, at query time, on a missing table. That
     * is exactly how a v1 asset shipped without `lex_token_stats` and crashed Chinese search.
     */
    fun schemaVersion(): Int = meta("schema_version")?.toIntOrNull() ?: 0

    /**
     * Index consistency check: compares the auxiliary indexes against the base table.
     *
     * The search indexes must never be the only copy of the data, so this reports whether they still
     * agree with `lex_words`. Used by the tests and available for a maintenance action.
     */
    data class IndexConsistency(
        val words: Int,
        val senses: Int,
        val forms: Int,
        val chineseTokens: Int,
        val fullTextRows: Int,
        val indexedWords: Int,
        val tokenStatsRows: Int,
        val bookAssociations: Int,
        val duplicateAssociations: Int,
        val problems: List<String>
    )

    fun checkIndexConsistency(): IndexConsistency {
        val words = count("lex_words")
        val senses = count("lex_senses")
        val forms = count("lex_forms")
        val zh = count("lex_zh")
        val fts = count("lex_fts")
        val tokenStats = runCatching { count("lex_token_stats") }.getOrDefault(0)
        val associations = count("word_book_entries")
        val duplicates = db().rawQuery(
            "SELECT COUNT(*) FROM (SELECT book_id, word_id FROM word_book_entries " +
                "GROUP BY book_id, word_id HAVING COUNT(*) > 1)",
            null
        ).use { it.moveToFirst(); it.getInt(0) }
        val indexed = db().rawQuery(
            "SELECT COUNT(*) FROM lex_words w WHERE EXISTS (SELECT 1 FROM lex_fts f WHERE f.docid = w.id)",
            null
        ).use { it.moveToFirst(); it.getInt(0) }
        val problems = buildList {
            if (senses < words) add("义项行数少于词条数：$senses < $words")
            if (fts != words) add("全文索引行数与词条数不一致：$fts != $words")
            if (indexed != words) add("有词条未进入全文索引：$indexed / $words")
            if (zh < words) add("中文反查 token 数异常偏少：$zh < $words")
            // Not a correctness fault — search re-verifies every candidate — but a build without these
            // statistics is a stale asset and would make Chinese search slow and arbitrary.
            if (tokenStats == 0) add("缺少字符文档频次统计 lex_token_stats，中文反查无法选择最稀有的锚点字符")
            // The association key is what makes a repeated import idempotent, so this must never happen.
            if (duplicates > 0) add("词书关联出现重复 (book_id, word_id)：$duplicates 组")
        }
        return IndexConsistency(
            words, senses, forms, zh, fts, indexed, tokenStats, associations, duplicates, problems
        )
    }

    private fun count(table: String): Int = db().rawQuery("SELECT COUNT(*) FROM $table", null)
        .use { it.moveToFirst(); it.getInt(0) }
}
