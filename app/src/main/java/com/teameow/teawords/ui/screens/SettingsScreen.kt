package com.teameow.teawords.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.roundToInt

private val quoteClient by lazy { OkHttpClient() }

@Composable
internal fun installedVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }
}

@Composable
internal fun installedVersionCode(): Long {
    val context = LocalContext.current
    return remember(context) {
        @Suppress("DEPRECATION")
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsView(
    pronunciationDialect: PronunciationDialect,
    selectedWordbookIds: Set<String>,
    homeTitle: String,
    homeSubtitle: String,
    homeSubtitleMode: SubtitleMode,
    bingWallpaperEnabled: Boolean,
    hitokotoRefreshInterval: Int,
    themeMode: AppThemeMode,
    onPronunciationDialectChange: (PronunciationDialect) -> Unit,
    onSelectedWordbooksChange: (Set<String>) -> Unit,
    onHomeTitleChange: (String) -> Unit,
    onHomeSubtitleChange: (String) -> Unit,
    onHomeSubtitleModeChange: (SubtitleMode) -> Unit,
    onBingWallpaperEnabledChange: (Boolean) -> Unit,
    onHitokotoRefreshIntervalChange: (Int) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    studyStrategy: StudyStrategyPreference,
    onStudyStrategyChange: (StudyStrategyPreference) -> Unit,
    dailyNewCapOverride: Int,
    onDailyNewCapChange: (Int) -> Unit,
    targetRetentionOverride: Double,
    onTargetRetentionChange: (Double) -> Unit,
    onBack: () -> Unit,
    pronunciationPreferenceReady: Boolean = true,
    pronunciationPreferenceError: String? = null,
    onRetryPronunciationPreference: () -> Unit = {},
    predictiveBackEnabled: Boolean = false,
    onPredictiveBackChange: (Boolean) -> Unit = {}
) {
    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val version = installedVersionName()
    val settingsContext = LocalContext.current
    val translationPrefs = remember(settingsContext) { TranslationPreferences(settingsContext) }
    val translationSummary = remember(currentPage) { translationPrefs.load().engine.label }
    // 动态取色需要 Android 12+；不支持时开关禁用，而不是假装能开。
    val dynamicColorSupported = remember { com.teameow.teawords.ui.theme.supportsDynamicColor() }
    val books = LearningWordbook.available.filter { it.id in selectedWordbookIds }
    val scope = rememberCoroutineScope()
    var quoteLoading by remember { mutableStateOf(false) }
    var quoteFeedback by remember { mutableStateOf<String?>(null) }
    val screen: @Composable (SettingsPage, () -> Unit) -> Unit = { page, back ->
        pageStates.SaveableStateProvider(page.name) {
            if (page == SettingsPage.Translation) TranslationSettingsPage(onBack = back)
            else TeaListPage(
                title = when (page) {
                    SettingsPage.Main -> "设置"
                    SettingsPage.Interface -> "主页定制"
                    SettingsPage.Appearance -> "外观模式"
                    SettingsPage.Strategy -> "学习策略"
                    SettingsPage.Pronunciation -> "发音偏好"
                    SettingsPage.Translation -> "句子与长文翻译"
                    SettingsPage.Wordbooks -> "词书管理"
                    SettingsPage.About -> "关于茶词"
                    SettingsPage.Update -> "检查更新"
                    SettingsPage.Logs -> "日志获取"
                },
                subtitle = when (page) {
                    SettingsPage.Main -> null
                    SettingsPage.About -> "查词、翻译，慢慢记住"
                    SettingsPage.Update -> "下载更新，通过系统确认安装"
                    else -> "修改后自动保存"
                },
                onBack = back
            ) {
                when (page) {
                    SettingsPage.Main -> {
                        item { SettingsHeading("个性化") }
                        item {
                            SettingsGroup {
                                SettingsRow(AppSymbols.Palette, "外观模式", "${themeMode.label} · ${if (dynamicColor && dynamicColorSupported) "动态取色" else "Purple"}") { currentPage = SettingsPage.Appearance }
                                SettingsRow(AppSymbols.Wallpaper, "主页定制", "${if (bingWallpaperEnabled) "每日壁纸" else "纯色背景"} · ${if (homeSubtitleMode == SubtitleMode.CUSTOM) "自定义文字" else "一言"}") { currentPage = SettingsPage.Interface }
                            }
                        }
                        item { SettingsHeading("偏好") }
                        item {
                            SettingsGroup {
                                SettingsRow(AppSymbols.Book5, "学习策略", "${studyStrategy.label} · 调整节奏") { currentPage = SettingsPage.Strategy }
                                SettingsRow(AppSymbols.BookmarkFlag, "词书管理", books.joinToString("、") { it.subtitle }.ifEmpty { "未选词书 · 点击选择" }) { currentPage = SettingsPage.Wordbooks }
                                SettingsRow(AppSymbols.Search, "句子与长文翻译", translationSummary) { currentPage = SettingsPage.Translation }
                                SettingsRow(AppSymbols.VoiceSelection, "发音偏好", "默认${pronunciationDialect.label} · 美音 / 英音") { currentPage = SettingsPage.Pronunciation }
                            }
                        }
                        item { SettingsHeading("关于") }
                        item {
                            SettingsGroup {
                                SettingsRow(AppSymbols.Info, "关于茶词", "版本 $version · 功能与服务说明") { currentPage = SettingsPage.About }
                                SettingsRow(AppSymbols.Update, "检查更新", "与 GitHub 公开仓库比较版本") { currentPage = SettingsPage.Update }
                                SettingsRow(AppSymbols.Feedback, "日志获取", "便于提交 BUG") { currentPage = SettingsPage.Logs }
                            }
                        }
                    }
                    SettingsPage.Appearance -> {
                        item {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(predictiveBackEnabled, role = Role.Switch, onValueChange = onPredictiveBackChange)
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("预测性返回", style = MaterialTheme.typography.titleMedium)
                                        TeaCaption("侧滑时预览上一页，松手返回或取消。默认关闭；手势预览需要 Android 14 及以上与系统手势导航。")
                                    }
                                    Switch(predictiveBackEnabled, onCheckedChange = null)
                                }
                            }
                        }
                        item {
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppThemeMode.entries.forEach { option ->
                                    SettingsChoice(
                                        option.label,
                                        when (option) {
                                            AppThemeMode.SYSTEM -> "跟随系统的浅色 / 深色设置"
                                            AppThemeMode.LIGHT -> "始终使用浅色 Purple 配色"
                                            AppThemeMode.DARK -> "始终使用深色 Purple 配色"
                                        },
                                        themeMode == option
                                    ) { onThemeModeChange(option) }
                                }
                            }
                        }
                        item {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .toggleable(dynamicColor && dynamicColorSupported, role = Role.Switch) { onDynamicColorChange(it) }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("动态取色 · Material You", style = MaterialTheme.typography.titleMedium)
                                        TeaCaption(
                                            if (dynamicColorSupported) "用系统壁纸生成的配色替换 Purple 品牌配色；深浅两套都跟随。"
                                            else "需要 Android 12 及以上；当前系统不支持，开关不可用。"
                                        )
                                    }
                                    Switch(dynamicColor && dynamicColorSupported, onCheckedChange = null, enabled = dynamicColorSupported)
                                }
                            }
                        }
                        item {
                            TeaCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    if (dynamicColor && dynamicColorSupported) "动态取色" else "Purple",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Text(
                                    "当前外观：${themeMode.label} · ${if (dynamicColor && dynamicColorSupported) "取色自系统壁纸" else "品牌配色"}",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text("Material 3 Expressive", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    SettingsPage.Logs -> {
                        item { DiagnosticsExport() }
                        item { RepositoryCard() }
                    }
                    SettingsPage.Strategy -> {
                        item {
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StudyStrategyPreference.entries.forEach { option ->
                                    SettingsChoice(option.label, option.detail, studyStrategy == option) { onStudyStrategyChange(option) }
                                }
                            }
                        }
                        item {
                            TeaCard {
                                Text("每日新词上限", style = MaterialTheme.typography.titleMedium)
                                TeaCaption("控制每天新增的学习量，到期复习不受此上限影响。")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0, 5, 10, 20, 40).forEach { value ->
                                        FilterChip(dailyNewCapOverride == value, { onDailyNewCapChange(value) }, label = { Text(if (value == 0) "按策略" else "$value 个") })
                                    }
                                }
                            }
                        }
                        item {
                            TeaCard {
                                Text("目标记忆保持率", style = MaterialTheme.typography.titleMedium)
                                TeaCaption("目标越高，复习越频繁；选择「按策略」可自动安排。")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0.0, 0.85, 0.90, 0.94).forEach { value ->
                                        // Preferences stores Float: equality with a Double fails after relaunch.
                                        FilterChip(abs(targetRetentionOverride - value) < 0.001, { onTargetRetentionChange(value) }, label = { Text(if (value == 0.0) "按策略" else "${(value * 100).roundToInt()}%") })
                                    }
                                }
                            }
                        }
                    }
                    SettingsPage.Interface -> {
                        item {
                            TeaCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("主页文字预览", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(homeTitle.ifBlank { "茶词" }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(homeSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                        item {
                            OutlinedTextField(homeTitle, onHomeTitleChange, label = { Text("主页标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                        }
                        item {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().toggleable(bingWallpaperEnabled, role = Role.Switch, onValueChange = onBingWallpaperEnabledChange).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("每日壁纸", style = MaterialTheme.typography.titleMedium)
                                        TeaCaption("从 Bing 获取主页背景，需要网络")
                                    }
                                    Switch(bingWallpaperEnabled, onCheckedChange = null)
                                }
                            }
                        }
                        item {
                            TeaCard {
                                Text("主页副标题", style = MaterialTheme.typography.titleMedium)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    SubtitleMode.entries.forEachIndexed { index, mode ->
                                        SegmentedButton(homeSubtitleMode == mode, { onHomeSubtitleModeChange(mode) }, SegmentedButtonDefaults.itemShape(index, 2)) {
                                            Text(if (mode == SubtitleMode.CUSTOM) "自定义文字" else "一言名句")
                                        }
                                    }
                                }
                                if (homeSubtitleMode == SubtitleMode.CUSTOM) {
                                    OutlinedTextField(homeSubtitle, onHomeSubtitleChange, label = { Text("写一句喜欢的话") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp))
                                } else {
                                    Text(homeSubtitle, style = MaterialTheme.typography.bodyLarge)
                                    FilledTonalButton(
                                        enabled = !quoteLoading,
                                        onClick = {
                                            quoteLoading = true
                                            quoteFeedback = null
                                            scope.launch {
                                                try {
                                                    val quote = withContext(Dispatchers.IO) {
                                                        quoteClient.newCall(Request.Builder().url("https://v1.hitokoto.cn/?encode=text&c=i").build()).execute().use { response ->
                                                            check(response.isSuccessful)
                                                            response.body?.string()?.trim().also { check(!it.isNullOrBlank()) }!!
                                                        }
                                                    }
                                                    onHomeSubtitleChange(quote)
                                                    quoteFeedback = "名句已更新"
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    quoteFeedback = "暂时无法获取，请检查网络后重试"
                                                } finally {
                                                    quoteLoading = false
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (quoteLoading) "正在获取…" else "换一句")
                                    }
                                    quoteFeedback?.let { TeaCaption(it) }
                                    Text("刷新间隔 · $hitokotoRefreshInterval 分钟", style = MaterialTheme.typography.titleSmall)
                                    Slider(hitokotoRefreshInterval.toFloat(), { onHitokotoRefreshIntervalChange(it.roundToInt()) }, valueRange = 1f..60f, steps = 58)
                                }
                            }
                        }
                    }
                    SettingsPage.Pronunciation -> {
                        item { TeaCaption("查词和学习时，优先使用所选口音播放发音。") }
                        if (!pronunciationPreferenceReady) item { TeaCaption("正在读取已保存的默认口音…") }
                        pronunciationPreferenceError?.let { error -> item {
                            TeaCaption(error)
                            TextButton(onClick = onRetryPronunciationPreference) { Text("重试读取偏好") }
                        } }
                        item {
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PronunciationDialect.entries.forEach { dialect ->
                                    SettingsChoice(dialect.label, if (dialect == PronunciationDialect.US) "US · American English" else "UK · British English", pronunciationPreferenceReady && pronunciationDialect == dialect) { onPronunciationDialectChange(dialect) }
                                }
                            }
                        }
                        item { TeaCaption("默认口音用于学习和快速排雷；词典页仍可分别播放 UK 与 US。") }
                        item { PronunciationResourcePanel() }
                    }
                    SettingsPage.Wordbooks -> {
                        item {
                            TeaCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                Text("已选择 ${books.size} 本词书", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(if (books.isEmpty()) "选择至少一本词书，开启词书学习。" else "可同时选择多本词书；调整选择会保留已有学习记录。", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                        items(LearningWordbook.available, key = { it.id }) { book ->
                            val selected = book.id in selectedWordbookIds
                            Surface(shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox) { checked ->
                                    onSelectedWordbooksChange(if (checked) selectedWordbookIds + book.id else selectedWordbookIds - book.id)
                                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                                        Text(book.subtitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Checkbox(selected, onCheckedChange = null)
                                }
                            }
                        }
                    }
                    SettingsPage.Translation -> Unit
                    SettingsPage.About -> {
                        item { AboutIdentity() }
                        item { AboutCredits() }
                    }
                    SettingsPage.Update -> {
                        item { UpdateCheckCard(installedVersionName(), installedVersionCode()) }
                        item {
                            TeaCaption(
                                "版本信息取仓库的发布（release）；仓库还没有发布时退回 git 标签。比较只在本机进行。"
                            )
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        CoveredPage(covered = currentPage != SettingsPage.Main) { screen(SettingsPage.Main, onBack) }
        if (currentPage != SettingsPage.Main) {
            PredictiveBackLayer(onBack = { currentPage = SettingsPage.Main }) { back -> screen(currentPage, back) }
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(text, Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = { Text(summary, style = MaterialTheme.typography.bodyMedium) },
            leadingContent = {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
                }
            },
            trailingContent = { Icon(AppSymbols.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.heightIn(min = 72.dp)
        )
    }
}

@Composable
private fun SettingsChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            RadioButton(selected, onClick = null)
        }
    }
}
