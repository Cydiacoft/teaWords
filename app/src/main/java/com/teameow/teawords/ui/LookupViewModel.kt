package com.teameow.teawords.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teameow.teawords.data.*
import com.teameow.teawords.algorithm.DifficultyPrior
import com.teameow.teawords.data.search.DictionaryGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** One selectable exam book in the lookup scope picker. */
data class BookChoice(val id: String, val name: String, val wordCount: Int)

// Lookup state is independent of knowledge evidence. Nothing is learned by searching.
data class LookupState(
    val input: String = "", val dictionaryMode: Boolean = true, val automaticMode: Boolean = true, val source: String = "en",
    val entries: List<LocalEntry> = emptyList(), val selected: LocalEntry? = null,
    val knowledge: Knowledge? = null, val translation: String = "", val candidates: List<Pair<LocalEntry, Knowledge>> = emptyList(),
    val busy: Boolean = false, val status: String = "", val error: String? = null,
    val available: Boolean = false, val count: Long = 0, val history: List<QueryRecord> = emptyList(),
    val historyEnabled: Boolean = true, val external: Boolean = false,
    /** Exam books available as a search scope, empty until the dictionary is installed. */
    val books: List<BookChoice> = emptyList(),
    /** Null means the whole dictionary. */
    val scope: String? = null,
    /** Spelling suggestions, offered only when the direct lookups found nothing. */
    val suggestions: List<LocalEntry> = emptyList(),
    /** True once the shipped dictionary is installed and open. */
    val dictionaryReady: Boolean = false,
    /** Whether the selected headword is in the personal notebook (生词本). Not a mastery claim. */
    val starred: Boolean = false,
    val translationEngine: TranslationEngine = TranslationEngine.MYMEMORY,
    val hasTranslationKey: Boolean = false,
    val translationEmail: String = "",
    val translationComplete: Boolean = true,
    val translationAttribution: String = ""
) { val onlineTranslation get() = translationEngine.online }

class LookupViewModel(application: Application) : AndroidViewModel(application) {
    private val helper = DatabaseHelper(application)
    private val dictionary = LocalDictionary(helper)
    private val senses = SenseRepository(helper)
    private val deviceProvider = lazy { DeviceTranslationProvider() }
    private val prefs = application.getSharedPreferences("lookup_privacy", 0)
    private val translationPreferences = TranslationPreferences(application)
    private val secretStore = TranslationSecretStore(application)
    private val deepSeekProvider = DeepSeekTranslationProvider(apiKey = secretStore::read)
    private val initialEngine = runCatching { TranslationEngine.valueOf(prefs.getString("translation_engine", null).orEmpty()) }.getOrElse {
        if (prefs.getBoolean("translation_online", true)) TranslationEngine.MYMEMORY else TranslationEngine.DEVICE
    }
    private val onlineProvider = OnlineTranslationProvider(contactEmail = { prefs.getString("translation_contact", "").orEmpty() })
    private val mutable = MutableStateFlow(LookupState(
        historyEnabled = prefs.getBoolean("history", true),
        translationEngine = initialEngine,
        hasTranslationKey = secretStore.configured(),
        available = initialEngine == TranslationEngine.MYMEMORY || (initialEngine == TranslationEngine.DEEPSEEK && secretStore.configured()),
        translationEmail = prefs.getString("translation_contact", "").orEmpty()
    ))
    val state: StateFlow<LookupState> = mutable.asStateFlow()
    private var job: Job? = null

    /**
     * The shipped dictionary is installed once per app version, not once per query. Installing it inside a
     * search would look like a hang, so it runs on its own scope and only signals completion here.
     */
    private val seeding = AtomicBoolean(false)
    private val seededOnce = CompletableDeferred<Unit>()

    init {
        // The dictionary can be opened directly from another app's text-selection menu, so this path
        // also has to ensure the additive schema is present before writing anything.
        if (seeding.compareAndSet(false, true)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    helper.ensureSchema()
                    val ok = DictionaryGateway.open(getApplication()) { progress ->
                        mutable.update {
                            it.copy(
                                status = if (progress.done) "" else "首次使用正在安装离线词典… ${progress.percent}%",
                                dictionaryReady = progress.done && !progress.failed
                            )
                        }
                    }
                    ensureActive()
                    if (ok) {
                        val books = withContext(Dispatchers.IO) {
                            DictionaryGateway.sourceOrNull()?.books()?.map { BookChoice(it.id, it.name, it.wordCount) }.orEmpty()
                        }
                        val count = dictionary.count()
                        mutable.update { it.copy(books = books, dictionaryReady = true, count = count) }
                    } else {
                        mutable.update { it.copy(error = DictionaryGateway.error() ?: "离线词典不可用", dictionaryReady = false) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("LookupViewModel", "opening the bundled dictionary failed", e)
                    mutable.update { it.copy(error = e.message ?: "离线词典不可用") }
                } finally {
                    seededOnce.complete(Unit)
                }
            }
        }
    }

    fun open(text: String, external: Boolean = false) {
        cancel()
        refreshTranslationPreferences()
        mutable.update { it.copy(input = text, automaticMode = true, dictionaryMode = LexicalText.dictionaryMode(text), source = if (LexicalText.isChinese(text)) "zh" else "en", entries = emptyList(), selected = null, translation = "", candidates = emptyList(), suggestions = emptyList(), external = external, error = null) }
        search()
    }
    fun input(value: String) {
        cancel()
        val wasDictionary = state.value.dictionaryMode
        mutable.update { it.copy(input = value,
            dictionaryMode = if (it.automaticMode) LexicalText.dictionaryMode(value) else it.dictionaryMode,
            source = if (LexicalText.isChinese(value)) "zh" else "en",
            translation = "", candidates = emptyList(), entries = emptyList(), selected = null,
            suggestions = emptyList(), error = null) }
        if (wasDictionary && !state.value.dictionaryMode && !state.value.onlineTranslation) availability()
    }
    fun automaticMode() {
        mutable.update { it.copy(automaticMode = true) }
        input(state.value.input)
    }
    fun mode(dictionaryMode: Boolean) { cancel(); mutable.update { it.copy(dictionaryMode = dictionaryMode, automaticMode = false, translation = "", error = null) }; if (dictionaryMode) search() else availability() }
    fun reverse() { cancel(); mutable.update { it.copy(source = if (it.source == "en") "zh" else "en", translation = "", error = null) } }

    /** Restricts lookup to one exam book, or to the whole dictionary when [bookId] is null. */
    fun scope(bookId: String?) {
        mutable.update { it.copy(scope = bookId) }
        if (state.value.dictionaryMode) search()
    }
    private fun run(status: String, awaitReady: Boolean = true, action: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.update { it.copy(busy = true, status = status, error = null) }
            try {
                if (awaitReady) {
                    // Give the first install a moment to finish quietly; if it is still running we say so
                    // instead of querying a dictionary that does not exist yet (which looks like "no
                    // results"). The dictionary is a plain file copy now, not a write transaction, so it
                    // cannot block readers the way the old index build could.
                    if (withTimeoutOrNull(SEED_WAIT_MS) { seededOnce.await() } == null) {
                        mutable.update { it.copy(status = "首次使用正在安装离线词典…") }
                        seededOnce.await()
                        mutable.update { it.copy(status = status) }
                    }
                    check(state.value.dictionaryReady) {
                        DictionaryGateway.error() ?: "离线词典未就绪，请重新打开查词页面重试"
                    }
                }
                action()
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "操作失败，请重试") } }
            finally { if (currentCoroutineContext().isActive) mutable.update { it.copy(busy = false, status = "") } }
        }
    }
    fun cancel() { job?.cancel(); mutable.update { it.copy(busy = false, status = "") } }
    private suspend fun refresh() {
        val data = withContext(Dispatchers.IO) { dictionary.count() to dictionary.history() }
        mutable.update { it.copy(count = data.first, history = data.second) }
    }
    fun search() {
        val snapshot = state.value
        if (!snapshot.dictionaryMode) { availability(translateWhenReady = true); return }
        run("正在查询离线词典…") {
            val result = withContext(Dispatchers.IO) { dictionary.search(snapshot.input, snapshot.scope) }
            val selected = result.firstOrNull { LexicalText.normalize(it.word) == LexicalText.normalize(snapshot.input) } ?: result.singleOrNull()
            // Suggestions are only meaningful when nothing matched; the engine produces them for exactly
            // that case, so asking on a hit would be wasted work.
            val suggestions = withContext(Dispatchers.IO) {
                if (result.isNotEmpty()) emptyList()
                else DictionaryGateway.engineOrNull()
                    ?.suggest(snapshot.input.trim())
                    ?.map { hit -> LocalEntry(hit.wordId, hit.word, hit.phonetic, hit.zh, hit.en, hit.pos, hit.tags, hit.forms, hit.frequency, "ECDICT", hit.match, hit.books) }
                    .orEmpty()
            }
            val detail = withContext(Dispatchers.IO) {
                if (snapshot.historyEnabled && !snapshot.external && snapshot.input.isNotBlank()) dictionary.record(snapshot.input, "词典", "en↔zh", selected?.id, selected?.word)
                selected?.let { entry ->
                    // Word book tags are the only difficulty signal available for most words, so the prior
                    // is stored with its origin and an explicit "not calibrated" flag the UI can show.
                    val prior = DifficultyPrior.forWord(entry.word, entry.tags)
                    senses.saveDifficulty(entry.id, "RECOGNITION", prior.value, prior.source, prior.calibrated)
                    dictionary.knowledge(entry.word) to helper.isInVocabulary(LexicalText.normalize(entry.word))
                }
            }
            mutable.update {
                it.copy(
                    entries = result, selected = selected,
                    knowledge = detail?.first, starred = detail?.second == true,
                    suggestions = suggestions
                )
            }; refresh()
        }
    }
    fun select(entry: LocalEntry) { run("读取词条…") {
        val snapshot = state.value
        withContext(Dispatchers.IO) {
            if (snapshot.historyEnabled && !snapshot.external) dictionary.record(entry.word, "词典", "en↔zh", entry.id, entry.word)
        }
        val knowledge = withContext(Dispatchers.IO) { dictionary.knowledge(entry.word) }
        val starred = withContext(Dispatchers.IO) { helper.isInVocabulary(LexicalText.normalize(entry.word)) }
        mutable.update { it.copy(selected = entry, knowledge = knowledge, starred = starred) }
        refresh()
    } }
    fun mark(entry: LocalEntry, report: SelfReport) { run("保存你的选择…") {
        val knowledge = withContext(Dispatchers.IO) { dictionary.mark(entry, report); dictionary.knowledge(entry.word) }
        // mark() writes the word into the notebook as well, so the star must follow it or the two
        // views would disagree about the same word.
        val isSelected = state.value.selected?.id == entry.id
        mutable.update { it.copy(knowledge = if (isSelected) knowledge else it.knowledge, starred = if (isSelected) true else it.starred, candidates = it.candidates.filterNot { candidate -> candidate.first.id == entry.id }) }
    } }

    /**
     * The star only manages the personal notebook. Learning evidence is owned by [mark], so taking a
     * word out of the notebook never deletes an answer that was already recorded.
     */
    fun star(entry: LocalEntry, starred: Boolean) { run(if (starred) "加入生词本…" else "移出生词本…") {
        withContext(Dispatchers.IO) {
            val word = LexicalText.normalize(entry.word)
            if (starred) helper.addVocabulary(word, entry.phonetic, entry.zh.ifBlank { entry.en })
            else helper.removeVocabulary(word)
        }
        mutable.update { it.copy(starred = starred) }
    } }
    private fun refreshTranslationPreferences() {
        val settings = translationPreferences.load()
        deepSeekProvider.release()
        mutable.update { it.copy(translationEngine = settings.engine, translationEmail = settings.email,
            hasTranslationKey = settings.hasKey,
            available = settings.engine == TranslationEngine.MYMEMORY || (settings.engine == TranslationEngine.DEEPSEEK && settings.hasKey)) }
    }
    fun availability(translateWhenReady: Boolean = false) {
        if (state.value.onlineTranslation) {
            mutable.update { it.copy(available = it.translationEngine == TranslationEngine.MYMEMORY || it.hasTranslationKey) }
            // Text shared from another app remains on screen until the user presses Translate.
            if (translateWhenReady && state.value.available && !state.value.external && state.value.input.isNotBlank()) translate()
            return
        }
        run("检查离线模型…", awaitReady = false) {
            val available = withTimeoutOrNull(8_000) { deviceProvider.value.isAvailable() }
                ?: throw IllegalStateException("离线模型检查超时，可在翻译设置中切换在线翻译。")
            mutable.update { it.copy(available = available) }
            if (available && translateWhenReady && !state.value.external && state.value.input.isNotBlank()) translate()
        }
    }
    // Translation never waits for dictionary installation. Only history is saved after it is ready.
    fun translate() {
        val snapshot = state.value
        if (snapshot.input.isBlank()) return
        if (snapshot.input.length > TranslationText.MAX_INPUT_CHARS) {
            mutable.update { it.copy(error = "单次最多翻译 20,000 个字符，请分成几次提交；原文没有被截断。") }
            return
        }
        run(if (snapshot.onlineTranslation) "正在联网翻译…" else "正在设备上翻译…", awaitReady = false) {
            val target = if (snapshot.source == "en") "zh" else "en"
            mutable.update { it.copy(translation = "", translationComplete = false,
                translationAttribution = when (snapshot.translationEngine) {
                    TranslationEngine.MYMEMORY -> "MyMemory · 在线机器翻译"
                    TranslationEngine.DEEPSEEK -> "DeepSeek · 长文翻译"
                    TranslationEngine.DEVICE -> "Google Translate · 设备端机器翻译"
                }) }
            val provider: TranslationProvider = when (snapshot.translationEngine) {
                TranslationEngine.MYMEMORY -> onlineProvider
                TranslationEngine.DEEPSEEK -> deepSeekProvider
                TranslationEngine.DEVICE -> deviceProvider.value
            }
            val result = withContext(Dispatchers.IO) {
                provider.translate(snapshot.input, snapshot.source, target) { progress ->
                    mutable.update { it.copy(translation = progress.text,
                        status = if (snapshot.translationEngine == TranslationEngine.DEEPSEEK) "正在联系 DeepSeek 翻译全文…"
                        else "正在翻译 ${progress.completed}/${progress.total} 段…") }
                }
            }
            mutable.update { it.copy(translation = result, translationComplete = true, candidates = emptyList()) }
            // A usable translation must not be lost just because lookup history is not ready or fails.
            if (snapshot.historyEnabled && !snapshot.external) {
                try {
                    seededOnce.await()
                    withContext(Dispatchers.IO) { dictionary.record(snapshot.input, "翻译", "${snapshot.source}→$target") }
                    refresh()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutable.update { it.copy(error = "译文已完成，但本次查阅记录未能保存。") } }
            }
        }
    }
    fun analyze() { val input = state.value.input; run("匹配词库与知识画像…") {
        val candidates = withContext(Dispatchers.IO) { dictionary.analyze(input) }
        mutable.update { it.copy(candidates = candidates, status = if (candidates.isEmpty()) "未发现可推荐词汇" else "") }
    } }
    fun history(enabled: Boolean) { prefs.edit().putBoolean("history", enabled).apply(); mutable.update { it.copy(historyEnabled = enabled) } }
    fun deleteHistory(id: Long? = null) { run("清理查询记录…") { withContext(Dispatchers.IO) { dictionary.deleteHistory(id) }; refresh() } }
    fun importEcdict(uri: Uri) { run("正在导入 ECDICT…") {
        val context = currentCoroutineContext()
        val count = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                dictionary.importEcdict(java.io.InputStreamReader(stream, decoder), checkCancelled = { context.ensureActive() }) { n -> mutable.update { it.copy(status = "已处理 $n 条；取消会回滚本次导入") } }
            } ?: error("无法读取文件")
        }
        refresh(); mutable.update { it.copy(status = "导入 $count 条词条") }
    } }
    override fun onCleared() { cancel(); onlineProvider.release(); deepSeekProvider.release(); if (deviceProvider.isInitialized()) deviceProvider.value.release(); helper.close(); super.onCleared() }

    private companion object {
        /** Bounded first-launch wait; the index keeps building in the background afterwards. */
        const val SEED_WAIT_MS = 2500L
    }
}
