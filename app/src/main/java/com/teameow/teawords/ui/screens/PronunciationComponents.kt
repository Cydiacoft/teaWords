package com.teameow.teawords.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.teameow.teawords.data.PronunciationDialect
import com.teameow.teawords.data.pronunciation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PronunciationPreferenceState(val dialect: PronunciationDialect? = null, val error: String? = null)

/** Activity-scoped playback; application-scoped DataStore owns the persisted preference. */
class PronunciationServices(context: Context, val manager: PronunciationManager = PronunciationManager(context)) : AutoCloseable {
    private val preferences = PronunciationPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutablePreference = MutableStateFlow(PronunciationPreferenceState())
    val preference = mutablePreference.asStateFlow()
    private var reader: Job? = null
    init { reloadPreference() }
    fun reloadPreference() {
        reader?.cancel()
        reader = scope.launch {
            try { preferences.dialect.collect { mutablePreference.value = PronunciationPreferenceState(it) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutablePreference.value = mutablePreference.value.copy(error = "无法读取默认口音，请重试") }
        }
    }
    fun setDefault(dialect: PronunciationDialect) {
        scope.launch {
            try {
                preferences.setDialect(dialect)
                // Restart a failed collector as well; never claim a save succeeded before disk write.
                mutablePreference.value = PronunciationPreferenceState(dialect)
                if (reader?.isActive != true) reloadPreference()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutablePreference.value = mutablePreference.value.copy(error = "默认口音保存失败，请重试") }
        }
    }
    override fun close() { scope.cancel(); manager.close() }
}

internal val LocalPronunciationServices = staticCompositionLocalOf<PronunciationServices?> { null }

@Composable
internal fun rememberPronunciationServices(): PronunciationServices {
    LocalPronunciationServices.current?.let { return it }
    val context = LocalContext.current.applicationContext
    val services = remember(context) { PronunciationServices(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(services, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> services.manager.stop()
                Lifecycle.Event.ON_RESUME -> services.manager.refreshVoices()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); services.close() }
    }
    return services
}

@Composable
fun ProvidePronunciation(content: @Composable () -> Unit) {
    val services = rememberPronunciationServices()
    CompositionLocalProvider(LocalPronunciationServices provides services, content = content)
}

@Composable
private fun StopPronunciationOnLeave(word: String, manager: PronunciationManager) {
    DisposableEffect(word, manager) {
        onDispose { if (manager.state.value.word == word) manager.stop() }
    }
}

/** ECDICT's unlabelled phonetic stays unlabelled. Only explicitly sourced regional IPA is listed. */
@Composable
fun DualPronunciation(word: String, basicPhonetic: String = "", basicSource: String = "") {
    val services = rememberPronunciationServices()
    val manager = services.manager
    val speech by manager.state.collectAsState()
    val context = LocalContext.current.applicationContext
    var entries by remember(word) { mutableStateOf(emptyList<WordPronunciationEntity>()) }
    var loadError by remember(word) { mutableStateOf(false) }
    LaunchedEffect(word) {
        try { entries = withContext(Dispatchers.IO) { WordPronunciationRepository(context).lookup(word) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    StopPronunciationOnLeave(word, manager)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PronunciationDialect.UK, PronunciationDialect.US).forEach { dialect ->
            val region = if (dialect == PronunciationDialect.UK) PronunciationRegion.UK else PronunciationRegion.US
            val variants = entries.filter { it.region == region && !it.ipa.isNullOrBlank() }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${dialect.tag} · ${dialect.label}", style = MaterialTheme.typography.titleSmall)
                            if (variants.isEmpty()) TeaCaption(if (loadError) "音标读取失败" else if (dialect == PronunciationDialect.UK) "暂无独立英式音标" else "暂无独立美式音标")
                        }
                        FilledTonalButton(onClick = { manager.play(word, dialect) }, enabled = speech.phase != SpeechPhase.INITIALIZING) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${dialect.tag} 发音")
                        }
                    }
                    variants.forEach { entry ->
                        Text(entry.ipa.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        entry.ipaAttribution?.let { TeaCaption("${it.sourceId} · ${it.licenseId} · ${it.attribution}") }
                    }
                    TeaCaption(voiceDescription(speech, dialect))
                }
            }
        }
        if (basicPhonetic.isNotBlank()) TeaCaption("基础音标（来源未标注口音）· $basicPhonetic${if (basicSource.isBlank()) "" else " · $basicSource"}")
        TeaCaption("系统合成发音 · 非真人录音")
        PronunciationFeedback(word, manager)
    }
}

private fun voiceDescription(state: PronunciationState, dialect: PronunciationDialect): String = when {
    state.phase == SpeechPhase.INITIALIZING -> "正在检测系统语音…"
    state.voices[dialect] == null -> "${dialect.tag} 语音不可用 · 请安装 ${dialect.locale.toLanguageTag()} 语音资源"
    state.voices[dialect]?.networkRequired == true -> "${dialect.locale.toLanguageTag()} · 系统合成，需要网络"
    else -> "${dialect.locale.toLanguageTag()} · 本地系统合成"
}

/** Used only when the word is visible, so playback never reveals a spelling-test answer. */
@Composable
fun DefaultPronunciationButton(word: String) {
    val services = rememberPronunciationServices()
    val pref by services.preference.collectAsState()
    val speech by services.manager.state.collectAsState()
    StopPronunciationOnLeave(word, services.manager)
    Column {
        TextButton(onClick = { pref.dialect?.let { services.manager.play(word, it) } }, enabled = pref.dialect != null && speech.phase != SpeechPhase.INITIALIZING) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(pref.dialect?.let { "${it.tag} 发音 · 默认${it.label}" } ?: "正在读取默认口音…")
        }
        pref.error?.let { TeaCaption(it) }
        if (pref.error != null) TextButton(onClick = services::reloadPreference) { Text("重试读取默认口音") }
        PronunciationFeedback(word, services.manager)
    }
}

/**
 * Compact outlined version of [DefaultPronunciationButton] for the word card, where the full
 * text button does not fit. It plays the same default dialect and reports the same errors inline.
 */
@Composable
fun CompactPronunciationButton(word: String) {
    val services = rememberPronunciationServices()
    val pref by services.preference.collectAsState()
    val speech by services.manager.state.collectAsState()
    StopPronunciationOnLeave(word, services.manager)
    OutlinedIconButton(
        onClick = { pref.dialect?.let { services.manager.play(word, it) } },
        enabled = pref.dialect != null && speech.phase != SpeechPhase.INITIALIZING,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(AppSymbols.VolumeUp, pref.dialect?.let { "播放${it.label}发音" } ?: "播放发音", Modifier.size(20.dp))
    }
}

@Composable
internal fun PronunciationFeedback(word: String, manager: PronunciationManager) {    val state by manager.state.collectAsState()
    if (state.word == word || state.word == null) {
        state.message?.let { TeaCaption(it) }
        if (state.word == word && state.phase in listOf(SpeechPhase.QUEUED, SpeechPhase.PLAYING)) {
            TextButton(onClick = manager::stop) { Text("停止发音") }
        }
    }
    if (state.phase == SpeechPhase.ERROR || state.voices.size < 2 && state.phase != SpeechPhase.INITIALIZING) {
        SpeechResourceActions(manager)
    }
}

@Composable
private fun SpeechResourceActions(manager: PronunciationManager) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: Exception) { error = "无法打开语音设置，请前往系统设置中的文字转语音输出" }
        }) { Text("系统语音设置") }
        TextButton(onClick = manager::initialize) { Text("重新检测语音") }
    }
    error?.let { TeaCaption(it) }
}

@Composable
fun PronunciationResourcePanel() {
    val services = rememberPronunciationServices()
    val state by services.manager.state.collectAsState()
    TeaCard {
        Text("系统语音资源", style = MaterialTheme.typography.titleMedium)
        listOf(PronunciationDialect.UK, PronunciationDialect.US).forEach { dialect ->
            TeaCaption("${dialect.tag} · ${voiceDescription(state, dialect)}")
            TextButton(onClick = { services.manager.play("tomato", dialect) }, enabled = state.phase != SpeechPhase.INITIALIZING) { Text("试听 ${dialect.tag} · tomato") }
        }
        state.message?.let { TeaCaption(it) }
        if (state.phase == SpeechPhase.PLAYING || state.phase == SpeechPhase.QUEUED) TextButton(onClick = services.manager::stop) { Text("停止发音") }
        TeaCaption("只使用地区匹配的语音；缺少资源时不会改用另一种口音。")
        SpeechResourceActions(services.manager)
    }
    StopPronunciationOnLeave("tomato", services.manager)
}
