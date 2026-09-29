package com.teameow.teawords.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.teameow.teawords.data.search.MatchKind
import com.teameow.teawords.data.search.SearchFilter
import com.teameow.teawords.data.search.SearchHit
import com.teameow.teawords.data.search.ShippedDictionarySource
import java.io.Reader

data class LocalEntry(
    val id: Long,
    val word: String,
    val phonetic: String,
    val zh: String,
    val en: String,
    val pos: String,
    val tags: String,
    val forms: String,
    val frequency: String,
    val source: String,
    /** How the shipped engine found this word, so the UI can explain why it is listed. */
    val match: MatchKind? = null,
    /** Exam books this word belongs to in the shipped dictionary. */
    val books: List<String> = emptyList()
)
data class QueryRecord(val id: Long, val text: String, val kind: String, val direction: String, val time: Long, val headword: String? = null)

/**
 * Word lookup and the user's own word store.
 *
 * Lookup is delegated to the shipped [DictionarySearchEngine], which owns the five query paths (exact,
 * prefix, inflected form, Chinese reverse lookup, spelling correction) and their unified ranking. This
 * class keeps what is genuinely local: the user's imported words, query history, self-report and the
 * knowledge rows that make progress reusable by word.
 *
 * The bundled 5,707-word index in this database is therefore no longer the search corpus — it is kept
 * because user imports and history live here, and because it is the only place a word the shipped
 * dictionary lacks can be stored. Its own search still runs, spelling correction and full text included,
 * because a user who imported a CSV has words the shipped dictionary does not know.
 */
class LocalDictionary(
    private val helper: DatabaseHelper,
    /**
     * The shipped reference dictionary to search alongside this database's own words. Tests and any
     * sandboxed caller pass [ShippedDictionarySource.localOnly] so the local store is measured on its own.
     */
    private val shipped: ShippedDictionarySource = ShippedDictionarySource.processWide
) {
    companion object {
        /** Rows per bulk statement while building the bundled index. */
        private const val BULK_CHUNK = 400

        /** Results per lookup. Enough to see the family, few enough to stay readable. */
        const val SEARCH_LIMIT = 30

        fun ensureQueryHistoryColumns(db: SQLiteDatabase) {
            val columns = db.rawQuery("PRAGMA table_info(query_history)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            if ("headword" !in columns) db.execSQL("ALTER TABLE query_history ADD COLUMN headword TEXT")
        }

        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS lex_words(id INTEGER PRIMARY KEY AUTOINCREMENT, normalized TEXT NOT NULL UNIQUE, word TEXT NOT NULL, phonetic TEXT NOT NULL DEFAULT '', zh TEXT NOT NULL DEFAULT '', en TEXT NOT NULL DEFAULT '', pos TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '', forms TEXT NOT NULL DEFAULT '', frequency TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS lex_senses(id INTEGER PRIMARY KEY AUTOINCREMENT, word_id INTEGER NOT NULL REFERENCES lex_words(id), source TEXT NOT NULL, language TEXT NOT NULL, ordinal INTEGER NOT NULL, definition TEXT NOT NULL, UNIQUE(word_id, source, language, ordinal, definition))")
            db.execSQL("CREATE TABLE IF NOT EXISTS lex_forms(form TEXT NOT NULL, word_id INTEGER NOT NULL REFERENCES lex_words(id), kind TEXT NOT NULL, PRIMARY KEY(form, word_id, kind))")
            db.execSQL("CREATE TABLE IF NOT EXISTS lex_zh(token TEXT NOT NULL, word_id INTEGER NOT NULL REFERENCES lex_words(id), PRIMARY KEY(token, word_id))")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS lex_fts USING fts4(lemma, definition, tokenize=unicode61)")
            db.execSQL("CREATE TABLE IF NOT EXISTS lex_sources(name TEXT PRIMARY KEY, license TEXT NOT NULL, imported_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS query_history(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, kind TEXT NOT NULL, direction TEXT NOT NULL, word_id INTEGER, timestamp INTEGER NOT NULL)")
            ensureQueryHistoryColumns(db)
            db.execSQL("CREATE INDEX IF NOT EXISTS query_history_time ON query_history(timestamp)")
            db.execSQL("CREATE TABLE IF NOT EXISTS lex_meta(key TEXT PRIMARY KEY, value TEXT)")
        }
    }
    private val db get() = helper.writableDatabase
    private fun rows(sql: String, args: Array<String> = emptyArray()): List<LocalEntry> = db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(LocalEntry(c.getLong(0), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10))) }
    }

    /** Total words available for lookup: the shipped dictionary when it is ready, else the local store. */
    fun count(): Long {
        val reference = shipped.wordCount().takeIf { it > 0 }
        return reference?.toLong() ?: db.rawQuery("SELECT COUNT(*) FROM lex_words", null).use { it.moveToFirst(); it.getLong(0) }
    }

    private fun SearchHit.toEntry() = LocalEntry(
        id = wordId, word = word, phonetic = phonetic, zh = zh, en = en, pos = pos, tags = tags,
        forms = forms, frequency = frequency, source = "ECDICT", match = match, books = books
    )

    /**
     * The engine's answer, or null when the reference dictionary is not available.
     *
     * Returning null rather than an empty list matters: "not installed yet" and "no such word" are
     * different answers, and the UI must not tell the user a word does not exist because the dictionary
     * was still copying.
     */
    private fun engineSearch(text: String, bookId: String?): List<LocalEntry>? {
        val engine = shipped.engineOrNull() ?: return null
        if (text.isBlank()) return null
        return engine.search(text, SearchFilter(bookId = bookId, limit = SEARCH_LIMIT)).hits.map { it.toEntry() }
    }

    /**
     * Exact, inflected-form, prefix and Chinese-reverse matches against the local store.
     *
     * These are the trustworthy local hits: they correspond to a word that actually exists here. The
     * guessing steps (spelling correction, full-text) are deliberately excluded, because when the shipped
     * dictionary is available its ranked guesses are better and mixing both answers the user twice.
     */
    private fun localPrecise(text: String): List<LocalEntry> {
        val q = LexicalText.normalize(text).take(128)
        if (q.isBlank()) return emptyList()
        if (LexicalText.isChinese(q)) {
            val token = Regex("[\\u3400-\\u9fff]{1,2}").find(q)?.value ?: return emptyList()
            return rows("SELECT w.* FROM lex_zh z JOIN lex_words w ON w.id=z.word_id WHERE z.token=? AND instr(w.zh,?)>0 LIMIT 30", arrayOf(token, q))
        }
        val exact = resolveLocal(q)
        val prefix = rows("SELECT * FROM lex_words WHERE normalized>=? AND normalized<? ORDER BY normalized LIMIT 24", arrayOf(q, q + '\uffff'))
        return (exact + prefix).distinctBy { it.id }.take(SEARCH_LIMIT)
    }

    /**
     * The local store's full lookup, guessing steps included.
     *
     * Only used when the reference dictionary is unavailable, which is the situation this index was built
     * for. It keeps its own Chinese token form on purpose: this index holds bigrams as well as single
     * characters, so a two-character probe is correct here, whereas the shipped index stores single
     * characters only and the same probe would match nothing.
     */
    private fun localSearch(text: String): List<LocalEntry> {
        val precise = localPrecise(text)
        if (precise.isNotEmpty()) return precise
        val q = LexicalText.normalize(text).take(128)
        if (q.isBlank() || LexicalText.isChinese(q)) return precise
        val candidates = LexicalText.spellingCandidates(q).chunked(200).flatMap { chunk -> rows("SELECT * FROM lex_words WHERE normalized IN (${chunk.joinToString(",") { "?" }}) LIMIT 12", chunk.toTypedArray()) }.distinctBy { it.id }.take(12)
        if (candidates.isNotEmpty()) return candidates
        val terms = Regex("[a-zA-Z]+").findAll(q).take(8).map { "\"${it.value}\"" }.joinToString(" AND ")
        return if (terms.isBlank()) emptyList() else rows("SELECT w.* FROM lex_fts f JOIN lex_words w ON w.id=f.docid WHERE lex_fts MATCH ? LIMIT 24", arrayOf(terms))
    }

    /** Exact and inflected-form lookup against the local store only. */
    private fun resolveLocal(text: String): List<LocalEntry> {
        val q = LexicalText.normalize(text)
        val forms = rows("SELECT w.* FROM lex_forms f JOIN lex_words w ON w.id=f.word_id WHERE f.form=? LIMIT 8", arrayOf(q))
        return (rows("SELECT * FROM lex_words WHERE normalized=?", arrayOf(q)) + forms).distinctBy { it.id }
    }

    /**
     * The one lookup entry point used by the UI.
     *
     * The shipped dictionary is authoritative whenever it is available, and the local store only adds words
     * the reference does not contain. That precedence matters: the superseded inline index also does
     * spelling correction, and letting it answer meant a typo was resolved from the old `cet6.txt` data
     * (showing "来源 · 原项目 cet6.txt") instead of surfacing the shipped dictionary's ranked suggestion
     * list. Measured on this device: `contempalte` came back from the old index and the suggestion UI never
     * appeared.
     */
    fun search(text: String, bookId: String? = null): List<LocalEntry> {
        val reference = engineSearch(text, bookId)
        // No reference dictionary: the local store is the only corpus and answers everything.
        if (reference == null) return localSearch(text)
        if (reference.isEmpty()) return localPrecise(text)
        // A word found both ways is one word, and the reference entry is the better one to show.
        val seen = reference.map { LexicalText.normalize(it.word) }.toSet()
        return reference + localPrecise(text).filterNot { seen.contains(LexicalText.normalize(it.word)) }
    }

    fun exact(text: String): LocalEntry? =
        (engineSearch(text, null)?.firstOrNull { it.match == MatchKind.EXACT || it.match == MatchKind.NORMALIZED })
            ?: rows("SELECT * FROM lex_words WHERE normalized=?", arrayOf(LexicalText.normalize(text))).firstOrNull()

    fun resolve(text: String): List<LocalEntry> {
        val q = LexicalText.normalize(text)
        val reference = engineSearch(q, null)
        if (reference != null) {
            val exactish = reference.filter { it.match == MatchKind.EXACT || it.match == MatchKind.FORM }
            if (exactish.isNotEmpty()) return exactish.distinctBy { it.id }
        }
        return resolveLocal(q)
    }
    fun upsert(word: String, zh: String, en: String = "", phonetic: String = "", pos: String = "", tags: String = "", forms: String = "", frequency: String = "", source: String = "用户词条"): Long {
        val normalized = LexicalText.normalize(word)
        require(normalized.isNotEmpty() && normalized.length <= 160)
        db.insertWithOnConflict("lex_words", null, ContentValues().apply { put("normalized", normalized); put("word", word.trim()) }, SQLiteDatabase.CONFLICT_IGNORE)
        val old = exact(normalized)!!
        val values = ContentValues().apply {
            if (zh.isNotBlank() && (old.zh.isBlank() || source.startsWith("ECDICT"))) put("zh", zh.replace("\\n", "\n"))
            if (en.isNotBlank()) put("en", en.replace("\\n", "\n"))
            if (phonetic.isNotBlank()) put("phonetic", phonetic)
            if (pos.isNotBlank()) put("pos", pos)
            if (tags.isNotBlank()) put("tags", (old.tags.split(' ') + tags.split(' ')).filter { it.isNotBlank() }.distinct().joinToString(" "))
            if (forms.isNotBlank()) put("forms", forms)
            if (frequency.isNotBlank()) put("frequency", frequency)
            if (old.source.isBlank() || source.startsWith("ECDICT")) put("source", source)
        }
        if (values.size() > 0) db.update("lex_words", values, "id=?", arrayOf(old.id.toString()))
        listOf("zh" to zh, "en" to en).forEach { (lang, definitions) ->
            definitions.replace("\\n", "\n").lineSequence().filter { it.isNotBlank() }.forEachIndexed { i, definition ->
                db.insertWithOnConflict("lex_senses", null, ContentValues().apply { put("word_id", old.id); put("source", source); put("language", lang); put("ordinal", i); put("definition", definition) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        forms.split('/').forEach { form ->
            val parts = form.split(':', limit = 2)
            if (parts.size == 2 && parts[0] !in listOf("0", "1")) parts[1].split(',').forEach { value ->
                db.insertWithOnConflict("lex_forms", null, ContentValues().apply { put("form", LexicalText.normalize(value)); put("word_id", old.id); put("kind", parts[0]) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        val current = exact(normalized)!!
        LexicalText.chineseTokens(current.zh).forEach { token -> db.insertWithOnConflict("lex_zh", null, ContentValues().apply { put("token", token); put("word_id", old.id) }, SQLiteDatabase.CONFLICT_IGNORE) }
        db.execSQL("INSERT OR REPLACE INTO lex_fts(docid, lemma, definition) VALUES(?,?,?)", arrayOf(old.id, normalized, current.en))
        return old.id
    }
    fun seed(context: Context, progress: (Int) -> Unit = {}) {
        val seeded = db.rawQuery("SELECT 1 FROM lex_meta WHERE key='bundled-v1'", null).use { it.moveToFirst() }
        if (seeded) return
        val rows = mutableListOf<Triple<String, String, String>>()   // normalized, display, zh
        val phonetics = mutableListOf<Triple<String, String, String>>() // normalized, phonetic, tag
        listOf("cet4", "cet6").forEach { book ->
            val source = "原项目 $book.txt"
            context.assets.open("$book.txt").bufferedReader().useLines { lines -> lines.forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].matches(Regex("[A-Za-z]+(?:[-'][A-Za-z]+)*"))) {
                    val definition = WordImport.cleanDefinition(parts[1])
                    if (definition.isNotBlank()) {
                        val normalized = LexicalText.normalize(parts[0])
                        val phonetic = Regex("\\[([^]]+)]").find(parts[1])?.groupValues?.get(1).orEmpty()
                        rows += Triple(normalized, parts[0].trim(), definition)
                        phonetics += Triple(normalized, phonetic, book)
                    }
                }
            } }
            source(source, "原工程附带词表，授权范围待核验")
        }
        // Words the user imported in-app keep their own definition; they are only filled in if missing.
        val userRows = mutableListOf<Triple<String, String, String>>()
        helper.readableDatabase.rawQuery("SELECT word, definition FROM vocabulary", null).use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0) ?: continue
                val definition = c.getString(1).orEmpty()
                if (definition.isNotBlank()) userRows += Triple(LexicalText.normalize(word), word, definition)
            }
        }
        // Set-based bulk load: the previous per-word upsert issued tens of thousands of statements and
        // took minutes, which is unusable on first launch. This runs the same work as a few statements.
        db.beginTransaction()
        try {
            bulkInsertLexWords(rows.distinctBy { it.first })
            progress(rows.size)
            applySeedAttributes(phonetics)
            val userNormalized = userRows.distinctBy { it.first }
            bulkInsertLexWords(userNormalized)
            progress(rows.size + userNormalized.size)
            rebuildDerivedTables()
            db.execSQL("UPDATE learning_knowledge SET word_id=(SELECT id FROM lex_words WHERE normalized=lower(trim(learning_knowledge.word))) WHERE word_id IS NULL")
            db.execSQL("INSERT OR REPLACE INTO lex_meta(key,value) VALUES('bundled-v1','1')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (cleanLegacyDefinitions() > 0) db.execSQL("INSERT OR REPLACE INTO lex_meta(key,value) VALUES('clean-pos-v1','1')")
    }

    private fun bulkInsertLexWords(batch: List<Triple<String, String, String>>) {
        batch.chunked(BULK_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "(?,?,?,'','','','','','','')" }
            val args = chunk.flatMap { listOf(it.first, it.second, it.third) }.toTypedArray()
            db.execSQL(
                "INSERT OR IGNORE INTO lex_words(normalized,word,zh,phonetic,en,pos,tags,forms,frequency,source) VALUES $placeholders",
                args
            )
        }
    }

    /** Bundled books may lack the phonetic for a word an in-app import already created. */
    private fun applySeedAttributes(batch: List<Triple<String, String, String>>) {
        batch.chunked(BULK_CHUNK).forEach { chunk ->
            chunk.forEach { (normalized, phonetic, book) ->
                if (phonetic.isNotBlank()) {
                    db.execSQL("UPDATE lex_words SET phonetic=? WHERE normalized=? AND phonetic=''", arrayOf(phonetic, normalized))
                }
                db.execSQL(
                    "UPDATE lex_words SET tags=trim(tags || ' ' || ?), source=? WHERE normalized=? AND instr(tags, ?)=0",
                    arrayOf(book, "原项目 $book.txt", normalized, book)
                )
            }
        }
    }

    /** Rebuilds senses, Chinese reverse tokens and the FTS index from lex_words in a few statements. */
    private fun rebuildDerivedTables() {
        db.execSQL("DELETE FROM lex_senses WHERE source LIKE '原项目 %' OR source=''")
        db.execSQL("DELETE FROM lex_zh WHERE word_id IN (SELECT id FROM lex_words WHERE source LIKE '原项目 %')")
        db.execSQL("DELETE FROM lex_fts WHERE docid IN (SELECT id FROM lex_words WHERE source LIKE '原项目 %')")
        db.execSQL("INSERT INTO lex_senses(word_id, source, language, ordinal, definition) SELECT id, source, 'zh', 0, zh FROM lex_words WHERE zh<>'' AND source LIKE '原项目 %'")
        db.execSQL("INSERT INTO lex_fts(docid, lemma, definition) SELECT id, normalized, en FROM lex_words WHERE source LIKE '原项目 %'")
        helper.readableDatabase.rawQuery("SELECT id, zh FROM lex_words WHERE zh<>'' AND source LIKE '原项目 %'", null).use { c ->
            val insert = "INSERT OR IGNORE INTO lex_zh(token, word_id) VALUES(?,?)"
            while (c.moveToNext()) {
                val id = c.getLong(0)
                LexicalText.chineseTokens(c.getString(1).orEmpty()).distinct().forEach { token ->
                    db.execSQL(insert, arrayOf(token, id))
                }
            }
        }
    }

    /**
     * One-off repair for installs indexed by an earlier build that stored "a.有病的" as if the
     * part-of-speech shorthand were part of the meaning. Only rows whose Chinese column starts
     * with such a marker are touched, and the pass runs at most once per install.
     */
    private fun cleanLegacyDefinitions(): Int {
        val marker = db.rawQuery("SELECT 1 FROM lex_meta WHERE key='clean-pos-v1'", null).use { it.moveToFirst() }
        if (marker) return 0
        // Drop everything up to and including the part-of-speech marker: "a.有病的" -> "有病的".
        val prefixes = listOf("pron", "pl", "sing", "pt", "pp", "adv", "adj", "prep", "conj", "num", "int", "art", "aux", "abbr", "n", "v", "vi", "vt", "a", "ad")
        val where = prefixes.joinToString(" OR ") { "zh GLOB '$it.[^a-zA-Z]*'" }
        db.beginTransaction()
        try {
            db.execSQL("UPDATE lex_words SET zh=trim(substr(zh, instr(zh, '.')+1)) WHERE $where")
            db.execSQL("INSERT OR REPLACE INTO lex_meta(key,value) VALUES('clean-pos-v1','1')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return 1
    }

    /** True when the word is already indexed from the same book, so seeding can skip the rewrite. */
    private fun indexed(word: String, source: String, tag: String): Boolean = db.rawQuery(
        "SELECT 1 FROM lex_words WHERE normalized=? AND source=? AND zh<>'' AND (?='' OR instr(tags,?)>0) LIMIT 1",
        arrayOf(LexicalText.normalize(word), source, tag, tag)
    ).use { it.moveToFirst() }
    /** Copies the live database to the app cache so a debug build can be inspected with adb pull. */
    fun exportTo(context: Context, name: String) {
        db.execSQL("VACUUM INTO ?", arrayOf(java.io.File(context.cacheDir, name).absolutePath))
    }

    private fun source(name: String, license: String) {
        db.insertWithOnConflict("lex_sources", null, ContentValues().apply { put("name", name); put("license", license); put("imported_at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun importEcdict(reader: Reader, checkCancelled: () -> Unit = {}, progress: (Int) -> Unit): Int {
        val csv = CsvRows(reader)
        val header = csv.next()?.map { it.removePrefix("\uFEFF").trim() } ?: error("文件为空")
        require(header.containsAll(listOf("word", "translation", "definition"))) { "请选择包含 word、translation、definition 列的 ECDICT CSV" }
        var count = 0
        db.beginTransaction()
        try {
            while (true) {
                checkCancelled()
                val row = csv.next() ?: break
                if (row.all { it.isBlank() }) continue
                require(row.size == header.size) { "第 ${count + 2} 条数据列数不正确，已取消本次导入" }
                fun field(key: String) = header.indexOf(key).let { if (it >= 0) row[it] else "" }
                if (field("word").isBlank()) continue
                upsert(field("word"), field("translation"), field("definition"), field("phonetic"), field("pos"), field("tag"), field("exchange"), "BNC ${field("bnc")} / FRQ ${field("frq")}", "ECDICT · 用户导入")
                count++
                if (count % 250 == 0) progress(count)
            }
            require(count > 0) { "文件没有有效词条" }
            checkCancelled()
            source("ECDICT · 用户导入", "上游仓库 MIT；混合数据来源的具体授权需另行核验")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }
    fun knowledge(word: String): Knowledge = db.rawQuery("SELECT * FROM learning_knowledge WHERE word=?", arrayOf(LexicalText.normalize(word))).use { c ->
        if (!c.moveToFirst()) Knowledge(LexicalText.normalize(word)) else Knowledge(c.getString(0), SelfReport.valueOf(c.getString(1)), c.getInt(2), c.getInt(3), c.getDouble(4), c.getLong(5), c.getLong(6))
    }
    fun mark(entry: LocalEntry, report: SelfReport) {
        db.beginTransaction()
        try {
            if (!helper.isInVocabulary(LexicalText.normalize(entry.word))) helper.addVocabulary(LexicalText.normalize(entry.word), entry.phonetic, entry.zh.ifBlank { entry.en })
            val old = knowledge(entry.word)
            val corrected = old.copy(report = report, streak = 0, intervalHours = if (report == SelfReport.KNOWN) old.intervalHours else 0.0, due = if (report == SelfReport.KNOWN) System.currentTimeMillis() + 7 * 86400000L else 0, updated = System.currentTimeMillis())
            LearningRepository(helper).save(corrected, "manual")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun analyze(text: String): List<Pair<LocalEntry, Knowledge>> = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(text.take(20000)).map { LexicalText.normalize(it.value) }.distinct().take(500)
        .flatMap { resolve(it).asSequence() }.distinctBy { it.id }.map { it to knowledge(it.word) }.filter { !it.second.verified && it.second.report != SelfReport.KNOWN }.take(40).toList()
    fun record(text: String, kind: String, direction: String, wordId: Long? = null, headword: String? = null) {
        db.insertOrThrow("query_history", null, ContentValues().apply { put("text", text.take(TranslationText.MAX_INPUT_CHARS)); put("kind", kind); put("direction", direction); if (wordId != null) put("word_id", wordId); headword?.let { put("headword", LexicalText.normalize(it)) }; put("timestamp", System.currentTimeMillis()) })
    }
    fun history(limit: Int = 100): List<QueryRecord> = db.rawQuery("SELECT id,text,kind,direction,timestamp,headword FROM query_history ORDER BY timestamp DESC, id DESC LIMIT ?", arrayOf(limit.coerceIn(1, 200).toString())).use { c -> buildList { while (c.moveToNext()) add(QueryRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), if (c.isNull(5)) null else c.getString(5))) } }

    /** Review uses all retained word lookups, independent of the recent-list display limit. */
    fun reviewHeadwords(): List<String> = db.rawQuery(
        "SELECT COALESCE(headword,text) FROM query_history WHERE kind='词典' " +
            "GROUP BY lower(trim(COALESCE(headword,text))) ORDER BY MAX(timestamp) DESC, MAX(id) DESC", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val word = cursor.getString(0)
            if (LookupReviewPolicy.isWord(word)) add(LexicalText.normalize(word))
        }
    } }

    fun unifiedHistory(): List<HistoryItem> = (history().map { HistoryItem(word = it.text, translation = "${it.kind} · ${it.direction}", timestamp = it.time) } + helper.getHistory()).sortedByDescending { it.timestamp }.distinctBy { it.word }.take(100)
    fun deleteHistoryText(text: String) { db.delete("query_history", "text=?", arrayOf(text)); helper.deleteHistoryItem(text) }
    fun deleteHistory(id: Long? = null) { if (id == null) { db.delete("query_history", null, null); helper.clearHistory() } else db.delete("query_history", "id=?", arrayOf(id.toString())) }
}
