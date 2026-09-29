package com.teameow.teawords.data.search

import android.content.Context
import android.util.Log
import com.teameow.teawords.data.BundledDictionaryInstaller
import com.teameow.teawords.data.DatabaseHelper
import com.teameow.teawords.data.WordIdMigration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the shipped dictionary and the search engine for the whole process.
 *
 * The dictionary is a single 61 MB read-only file that several entry points need — the main screen, the
 * lookup screen, and the `PROCESS_TEXT` / `SEND` activities that Android can start without the main
 * activity existing at all. Copying or opening it once per screen would waste tens of megabytes and
 * several seconds, so there is exactly one owner and every caller borrows it.
 *
 * Opening is deliberately **not** done lazily on first query. The first open also installs the asset,
 * which takes seconds; doing that inside a search would look like the app hanging. [open] is called once
 * from the app's start-up path with a progress callback instead, and callers that arrive early are told
 * the dictionary is still being installed rather than shown an empty result.
 */
object DictionaryGateway {

    /** What the caller should show while the dictionary is being installed. */
    data class Progress(val copied: Long, val total: Long, val done: Boolean, val failed: Boolean) {
        val percent: Int
            get() = if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    @Volatile private var repository: WordSearchRepository? = null
    @Volatile private var engine: DictionarySearchEngine? = null
    @Volatile private var lastError: String? = null
    private val opening = AtomicBoolean(false)

    /** The open dictionary, or null when it is not ready yet. */
    fun sourceOrNull(): WordSearchRepository? = repository

    /** The engine, or null when the dictionary is not ready yet. */
    fun engineOrNull(): DictionarySearchEngine? = engine

    fun error(): String? = lastError

    fun isReady(): Boolean = engine != null

    /**
     * Installs the bundled dictionary when needed and opens it. Blocking: call from `Dispatchers.IO`.
     *
     * @return true when the dictionary is usable afterwards.
     */
    fun open(context: Context, onProgress: (Progress) -> Unit = {}): Boolean {
        repository?.let { if (it.isOpen()) return true }
        // Only one installer may run at a time; a second caller waits for the first to finish rather than
        // starting a competing 61 MB copy.
        if (!opening.compareAndSet(false, true)) {
            return waitForReady()
        }
        return try {
            val installer = BundledDictionaryInstaller(context)
            val result = installer.install(force = false) { copied, total ->
                onProgress(Progress(copied, total, done = false, failed = false))
            }
            val repo = WordSearchRepository(installer)
            val ok = repo.open(forceReinstall = false) { copied, total ->
                onProgress(Progress(copied, total, done = false, failed = false))
            }
            if (!ok) {
                lastError = "词典文件不可用"
                onProgress(Progress(0, 0, done = true, failed = true))
                return false
            }
            if (result.installed) {
                Log.i("DictionaryGateway", "installed ${result.bytes} bytes of bundled dictionary")
            }
            // Progress callbacks stop here, but opening is not finished until progress is rebound: the
            // dictionary numbers words differently from the superseded inline index, and every stored
            // progress row has to be moved before anything reads it.
            rebindProgress(context, repo)
            repository = repo
            engine = DefaultDictionarySearchEngine(repo)
            lastError = null
            onProgress(Progress(result.bytes, result.bytes, done = true, failed = false))
            true
        } catch (e: Exception) {
            Log.e("DictionaryGateway", "opening the bundled dictionary failed", e)
            lastError = e.message ?: "词典打开失败"
            onProgress(Progress(0, 0, done = true, failed = true))
            false
        } finally {
            opening.set(false)
        }
    }

    /**
     * Moves stored progress onto the dictionary's id space.
     *
     * Runs here rather than in the migration's own entry point because it needs *both* databases: the
     * dictionary has to be installed and readable before the old ids can be resolved to new ones.
     * A failure is logged and swallowed on purpose — searching still works with unbound progress, whereas
     * throwing would take down the lookup screen over a bookkeeping step.
     */
    private fun rebindProgress(context: Context, repo: WordSearchRepository) {
        runCatching {
            val builtAt = repo.meta("built_at") ?: repo.schemaVersion().toString()
            val file = BundledDictionaryInstaller(context).targetFile()
            val report = DatabaseHelper(context).use { helper -> WordIdMigration(helper).apply(file, builtAt) }
            if (report.applied) Log.i("DictionaryGateway", report.describe())
        }.onFailure { Log.e("DictionaryGateway", "rebinding stored progress failed", it) }
    }

    /** Waits for a concurrent [open] to finish, so two screens cannot install at once. */
    private fun waitForReady(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (engine != null) return true
            if (!opening.get()) return engine != null
            Thread.sleep(50)
        }
        return engine != null
    }

    /** Releases the dictionary. Called when the process no longer needs it (tests, teardown). */
    fun close() {
        repository?.close()
        repository = null
        engine = null
    }

    private const val WAIT_MS = 30_000L
}

/**
 * Where a caller gets the shipped reference dictionary from.
 *
 * Exists so that "search the shipped dictionary" is a dependency rather than a global read. A test that
 * checks the user's *own* word store must not accidentally search 57,841 reference words instead, and a
 * caller that has to stay sandboxed can say so — neither is expressible while the gateway is consulted
 * directly from inside the repository.
 */
interface ShippedDictionarySource {
    /** The reference engine, or null when it is not installed or not ready. */
    fun engineOrNull(): DictionarySearchEngine?

    /** Reference word count, 0 when unavailable. */
    fun wordCount(): Int

    companion object {
        /** Production behaviour: the one dictionary this process installed. */
        val processWide: ShippedDictionarySource = object : ShippedDictionarySource {
            override fun engineOrNull(): DictionarySearchEngine? = DictionaryGateway.engineOrNull()
            override fun wordCount(): Int = DictionaryGateway.sourceOrNull()?.wordCount() ?: 0
        }

        /** Searches only the caller's own store, as if no reference dictionary were installed. */
        val localOnly: ShippedDictionarySource = object : ShippedDictionarySource {
            override fun engineOrNull(): DictionarySearchEngine? = null
            override fun wordCount(): Int = 0
        }
    }
}
