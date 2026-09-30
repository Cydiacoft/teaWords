package com.teameow.teawords.data.pronunciation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.MainThread
import com.teameow.teawords.data.PronunciationDialect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechPhase { INITIALIZING, READY, QUEUED, PLAYING, ERROR, RELEASED }
data class PronunciationState(
    val phase: SpeechPhase = SpeechPhase.INITIALIZING,
    val voices: Map<PronunciationDialect, SpeechVoice> = emptyMap(),
    val word: String? = null,
    val dialect: PronunciationDialect? = null,
    val message: String? = null,
    val playbackCompleted: Boolean = false
)

/** Backend boundary permits deterministic tests without depending on device voice downloads. */
interface SpeechBackend {
    fun initialize(onReady: (Boolean) -> Unit, onEvent: (String, SpeechPhase, String?) -> Unit)
    fun voices(): List<SpeechVoice>
    fun select(voice: SpeechVoice): Boolean
    fun speak(word: String, id: String): Boolean
    fun stop()
    fun close()
}

/** Main-thread owner. Only exact-region voices are used, never setLanguage's implicit fallback. */
@MainThread
class PronunciationManager(private val backend: SpeechBackend) : AutoCloseable {
    constructor(context: Context) : this(AndroidSpeechBackend(context.applicationContext))
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(PronunciationState())
    val state = mutable.asStateFlow()
    private var ready = false
    private var closed = false
    private var generation = 0
    private var sequence = 0L
    private var activeId: String? = null
    private var timeout: Runnable? = null

    init { initialize() }

    fun initialize() {
        if (closed) return
        stop()
        ready = false
        val token = ++generation
        mutable.value = PronunciationState()
        armTimeout(15_000) {
            generation++
            runCatching { backend.close() }
            fail("语音引擎初始化超时，请检查系统文字转语音设置后重试")
        }
        try {
            backend.initialize({ success ->
                handler.post {
                    if (!closed && token == generation) {
                        cancelTimeout()
                        ready = success
                        if (success) refreshVoices() else fail("无法初始化系统语音引擎，请安装或启用文字转语音服务")
                    }
                }
            }, { id, phase, error ->
                handler.post {
                    if (!closed && token == generation && id == activeId) {
                        if (phase == SpeechPhase.PLAYING) {
                            mutable.value = mutable.value.copy(phase = phase)
                        } else {
                            activeId = null
                            cancelTimeout()
                            if (phase == SpeechPhase.ERROR) {
                                runCatching { backend.stop() }
                                fail(error ?: "发音失败，请检查对应口音的语音资源")
                            } else mutable.value = mutable.value.copy(phase = SpeechPhase.READY, message = null,
                                playbackCompleted = phase == SpeechPhase.READY)
                        }
                    }
                }
            })
        } catch (_: Exception) {
            cancelTimeout()
            fail("无法启动系统语音引擎，请检查文字转语音服务")
        }
    }

    fun refreshVoices() {
        if (closed || !ready || activeId != null) return
        try {
            val all = backend.voices()
            val exact = PronunciationDialect.entries.mapNotNull { dialect ->
                matchingVoices(all, dialect).firstOrNull()?.let { dialect to it }
            }.toMap()
            mutable.value = mutable.value.copy(phase = SpeechPhase.READY, voices = exact, message = null)
        } catch (_: Exception) { fail("无法读取系统语音资源，请检查语音引擎后重试") }
    }

    fun play(word: String, dialect: PronunciationDialect) {
        if (closed) return
        if (!ready) {
            mutable.value = mutable.value.copy(message = "语音引擎尚未就绪，请稍后重试")
            return
        }
        stop()
        mutable.value = mutable.value.copy(word = word, dialect = dialect)
        if (word.isBlank() || word.length > 300) { fail("请输入 1–300 个字符的单词或短语"); return }
        try {
            // Re-query before each playback: resources may have been removed or downloaded.
            val candidates = matchingVoices(backend.voices(), dialect)
            val voice = candidates.firstOrNull { backend.select(it) }
            if (voice == null) {
                mutable.value = mutable.value.copy(voices = mutable.value.voices - dialect)
                fail("${dialect.tag} ${dialect.label}资源不可用，请在系统文字转语音设置中安装 ${dialect.locale.toLanguageTag()} 语音")
                return
            }
            val id = "pronunciation-${++sequence}"
            activeId = id
            mutable.value = mutable.value.copy(
                phase = SpeechPhase.QUEUED, voices = mutable.value.voices + (dialect to voice),
                message = if (voice.networkRequired) "正在使用联网合成语音" else "正在使用本地合成语音",
                playbackCompleted = false
            )
            armTimeout(30_000) {
                activeId = null
                runCatching { backend.stop() }
                fail("${dialect.tag} 发音超时，请检查网络或下载该口音的离线语音")
            }
            if (!backend.speak(word.trim(), id)) {
                activeId = null
                cancelTimeout()
                fail("${dialect.tag} 发音启动失败，请检查语音资源与音频输出")
            }
        } catch (_: Exception) {
            activeId = null
            cancelTimeout()
            runCatching { backend.stop() }
            fail("${dialect.tag} 发音失败，请检查系统语音服务后重试")
        }
    }

    fun stop() {
        val hadUtterance = activeId != null
        activeId = null // Late callbacks from the old utterance must not affect the next one.
        // Leaving an Activity while initialization is pending must not remove its watchdog.
        if (ready || hadUtterance) cancelTimeout()
        runCatching { backend.stop() }
        if (!closed && ready) mutable.value = mutable.value.copy(phase = SpeechPhase.READY, word = null, dialect = null, message = null, playbackCompleted = false)
    }
    private fun fail(message: String) { mutable.value = mutable.value.copy(phase = SpeechPhase.ERROR, message = message, playbackCompleted = false) }
    private fun cancelTimeout() { timeout?.let(handler::removeCallbacks); timeout = null }
    private fun armTimeout(delay: Long, action: () -> Unit) {
        cancelTimeout()
        timeout = Runnable { if (!closed) action() }.also { handler.postDelayed(it, delay) }
    }
    override fun close() {
        if (closed) return
        stop()
        cancelTimeout()
        closed = true
        ready = false
        generation++
        runCatching { backend.close() }
        mutable.value = PronunciationState(phase = SpeechPhase.RELEASED)
    }
}

private class AndroidSpeechBackend(private val context: Context) : SpeechBackend {
    private var engine: TextToSpeech? = null
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var onEvent: ((String, SpeechPhase, String?) -> Unit)? = null
    private var utterance: String? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) {
            val interrupted = utterance
            stop()
            interrupted?.let { onEvent?.invoke(it, SpeechPhase.ERROR, "发音已被其他音频中断，请重新播放") }
        }
    }
    private var focusRequest: AudioFocusRequest? = null

    override fun initialize(onReady: (Boolean) -> Unit, onEvent: (String, SpeechPhase, String?) -> Unit) {
        close()
        this.onEvent = onEvent
        val main = Handler(Looper.getMainLooper())
        // Always defer the callback until engine has been assigned, even with synchronous failures.
        engine = TextToSpeech(context) { status -> main.post {
            if (status == TextToSpeech.SUCCESS) engine?.setAudioAttributes(attributes)
            onReady(status == TextToSpeech.SUCCESS)
        } }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = onEvent(id, SpeechPhase.PLAYING, null)
            override fun onDone(id: String) { main.post { finish(id, SpeechPhase.READY, null) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { onError(id, TextToSpeech.ERROR) }
            override fun onError(id: String, code: Int) {
                main.post { finish(id, SpeechPhase.ERROR, "语音播放失败（$code），请检查该口音资源、网络或系统语音引擎") }
            }
            override fun onStop(id: String, interrupted: Boolean) {
                main.post { finish(id, SpeechPhase.ERROR, "发音被中断，请重新播放") }
            }
        })
    }

    override fun voices(): List<SpeechVoice> = engine?.voices.orEmpty().map {
        SpeechVoice(it.name, reportedVoiceLocale(it.name, it.locale, it.features.orEmpty()), it.isNetworkConnectionRequired,
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty(), it.quality, it.latency)
    }

    override fun select(voice: SpeechVoice): Boolean {
        val tts = engine ?: return false
        val actual = tts.voices?.firstOrNull { it.name == voice.name &&
            reportedVoiceLocale(it.name, it.locale, it.features.orEmpty()) == voice.locale } ?: return false
        if (tts.setVoice(actual) != TextToSpeech.SUCCESS) return false
        // Engines must confirm the requested voice; success alone is not proof of accent.
        return tts.voice?.let { it.name == actual.name &&
            reportedVoiceLocale(it.name, it.locale, it.features.orEmpty()) == voice.locale } == true
    }

    override fun speak(word: String, id: String): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build()
            focusRequest = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { abandonFocus(); return false }
        utterance = id
        val ok = engine?.speak(word, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        if (!ok) { utterance = null; abandonFocus() }
        return ok
    }
    private fun finish(id: String, phase: SpeechPhase, error: String?) {
        if (utterance == id) { utterance = null; abandonFocus() }
        onEvent?.invoke(id, phase, error)
    }
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else { @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener) }
        focusRequest = null
    }
    override fun stop() { utterance = null; engine?.stop(); abandonFocus() }
    override fun close() { stop(); engine?.shutdown(); engine = null }
}
