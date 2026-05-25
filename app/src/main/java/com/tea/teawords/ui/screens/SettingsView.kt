package com.tea.teawords.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.R
import com.tea.teawords.data.*
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    pronunciationDialect: PronunciationDialect,
    selectedWordbookIds: Set<String>,
    homeTitle: String,
    homeSubtitle: String,
    homeSubtitleMode: SubtitleMode,
    bingWallpaperEnabled: Boolean,
    predictiveBackEnabled: Boolean,
    hitokotoRefreshInterval: Int,
    api: DictionaryApi,
    onPronunciationDialectChange: (PronunciationDialect) -> Unit,
    onSelectedWordbooksChange: (Set<String>) -> Unit,
    onHomeTitleChange: (String) -> Unit,
    onHomeSubtitleChange: (String) -> Unit,
    onHomeSubtitleModeChange: (SubtitleMode) -> Unit,
    onBingWallpaperEnabledChange: (Boolean) -> Unit,
    onPredictiveBackEnabledChange: (Boolean) -> Unit,
    onHitokotoRefreshIntervalChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val client = remember { okhttp3.OkHttpClient() }
    var currentPage by remember { mutableStateOf<SettingsPage>(SettingsPage.Main) }

    BackHandler(enabled = currentPage != SettingsPage.Main) {
        currentPage = SettingsPage.Main
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (currentPage == SettingsPage.Main) onBack() else currentPage = SettingsPage.Main
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = when (currentPage) {
                    SettingsPage.Main -> "设置"
                    SettingsPage.Interface -> "界面定制"
                    SettingsPage.Pronunciation -> "发音设置"
                    SettingsPage.Wordbooks -> "词书管理"
                    SettingsPage.About -> "关于茶词"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState != SettingsPage.Main) {
                    (slideInHorizontally { it } + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)))
                        .togetherWith(slideOutHorizontally { -it } + fadeOut(animationSpec = tween(250)))
                } else {
                    (slideInHorizontally { -it } + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)))
                        .togetherWith(slideOutHorizontally { it } + fadeOut(animationSpec = tween(250)))
                }
            },
            label = "SettingsTransition"
        ) { page ->
            when (page) {
                SettingsPage.Main -> SettingsMainPage(
                    pronunciationDialect = pronunciationDialect,
                    selectedWordbookIds = selectedWordbookIds,
                    predictiveBackEnabled = predictiveBackEnabled,
                    onPredictiveBackEnabledChange = onPredictiveBackEnabledChange,
                    onNavigate = { currentPage = it }
                )
                SettingsPage.Interface -> InterfaceSubPage(
                    homeTitle = homeTitle,
                    homeSubtitle = homeSubtitle,
                    homeSubtitleMode = homeSubtitleMode,
                    bingWallpaperEnabled = bingWallpaperEnabled,
                    hitokotoRefreshInterval = hitokotoRefreshInterval,
                    client = client,
                    onHomeTitleChange = onHomeTitleChange,
                    onHomeSubtitleChange = onHomeSubtitleChange,
                    onHomeSubtitleModeChange = onHomeSubtitleModeChange,
                    onBingWallpaperEnabledChange = onBingWallpaperEnabledChange,
                    onHitokotoRefreshIntervalChange = onHitokotoRefreshIntervalChange
                )
                SettingsPage.Pronunciation -> PronunciationSubPage(
                    pronunciationDialect = pronunciationDialect,
                    onPronunciationDialectChange = onPronunciationDialectChange
                )
                SettingsPage.Wordbooks -> WordbooksSubPage(
                    selectedWordbookIds = selectedWordbookIds,
                    onSelectedWordbooksChange = onSelectedWordbooksChange
                )
                SettingsPage.About -> AboutView()
            }
        }
    }
}

// --- Main settings page (card-grouped style) ---

@Composable
private fun SettingsMainPage(
    pronunciationDialect: PronunciationDialect,
    selectedWordbookIds: Set<String>,
    predictiveBackEnabled: Boolean,
    onPredictiveBackEnabledChange: (Boolean) -> Unit,
    onNavigate: (SettingsPage) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { SectionTitle("个性化") }
        item {
            SettingsGroupCard {
                SettingsArrowRow(
                    icon = Icons.Default.Palette,
                    title = "界面定制",
                    subtitle = "主页标题、壁纸、副标题",
                    onClick = { onNavigate(SettingsPage.Interface) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionTitle("系统") }
        item {
            SettingsGroupCard {
                SettingsSwitchRow(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    title = "预见性返回手势",
                    subtitle = "返回时预览上一页面，Android 14+ 可用",
                    checked = predictiveBackEnabled,
                    onCheckedChange = onPredictiveBackEnabledChange
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionTitle("学习设置") }
        item {
            SettingsGroupCard {
                SettingsArrowRow(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "发音设置",
                    subtitle = pronunciationDialect.label,
                    onClick = { onNavigate(SettingsPage.Pronunciation) }
                )
                SettingsDivider()
                SettingsArrowRow(
                    icon = Icons.Default.AutoStories,
                    title = "词书管理",
                    subtitle = "已选择 ${selectedWordbookIds.size} 本词书",
                    onClick = { onNavigate(SettingsPage.Wordbooks) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionTitle("关于") }
        item {
            SettingsGroupCard {
                SettingsArrowRow(
                    icon = Icons.Default.Info,
                    title = "版本信息",
                    subtitle = "v1.1.0",
                    onClick = { onNavigate(SettingsPage.About) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// --- Sub-pages ---

@Composable
private fun InterfaceSubPage(
    homeTitle: String,
    homeSubtitle: String,
    homeSubtitleMode: SubtitleMode,
    bingWallpaperEnabled: Boolean,
    hitokotoRefreshInterval: Int,
    client: okhttp3.OkHttpClient,
    onHomeTitleChange: (String) -> Unit,
    onHomeSubtitleChange: (String) -> Unit,
    onHomeSubtitleModeChange: (SubtitleMode) -> Unit,
    onBingWallpaperEnabledChange: (Boolean) -> Unit,
    onHitokotoRefreshIntervalChange: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { SectionTitle("主页标题") }
        item {
            SettingsGroupCard {
                SettingsTextFieldRow(
                    value = homeTitle,
                    onValueChange = onHomeTitleChange,
                    placeholder = "输入主页标题"
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionTitle("壁纸") }
        item {
            SettingsGroupCard {
                SettingsSwitchRow(
                    icon = Icons.Default.Wallpaper,
                    title = "启用 Bing 每日壁纸",
                    subtitle = "主页背景将自动更换",
                    checked = bingWallpaperEnabled,
                    onCheckedChange = onBingWallpaperEnabledChange
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionTitle("副标题") }
        item {
            SettingsGroupCard {
                SettingsSegmentedRow(
                    selected = homeSubtitleMode,
                    options = SubtitleMode.entries.toList(),
                    labelOf = { when (it) { SubtitleMode.CUSTOM -> "自定义"; SubtitleMode.HITOKOTO -> "一言" } },
                    onSelect = onHomeSubtitleModeChange
                )
                if (homeSubtitleMode == SubtitleMode.CUSTOM) {
                    SettingsDivider()
                    SettingsTextFieldRow(
                        value = homeSubtitle,
                        onValueChange = onHomeSubtitleChange,
                        placeholder = "自定义副标题/名言",
                        multiLine = true
                    )
                } else {
                    SettingsDivider()
                    HitokotoSection(
                        homeSubtitle = homeSubtitle,
                        hitokotoRefreshInterval = hitokotoRefreshInterval,
                        client = client,
                        onHomeSubtitleChange = onHomeSubtitleChange,
                        onHitokotoRefreshIntervalChange = onHitokotoRefreshIntervalChange
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun PronunciationSubPage(
    pronunciationDialect: PronunciationDialect,
    onPronunciationDialectChange: (PronunciationDialect) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { SectionTitle("默认发音口音") }
        item {
            SettingsGroupCard {
                PronunciationDialect.entries.forEachIndexed { index, dialect ->
                    if (index > 0) SettingsDivider()
                    SettingsRadioRow(
                        title = dialect.label,
                        selected = pronunciationDialect == dialect,
                        onClick = { onPronunciationDialectChange(dialect) }
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun WordbooksSubPage(
    selectedWordbookIds: Set<String>,
    onSelectedWordbooksChange: (Set<String>) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { SectionTitle("选择词书") }
        item {
            SettingsGroupCard {
                LearningWordbook.entries.forEachIndexed { index, wordbook ->
                    if (index > 0) SettingsDivider()
                    SettingsCheckRow(
                        title = wordbook.title,
                        subtitle = wordbook.subtitle,
                        checked = wordbook.id in selectedWordbookIds,
                        onClick = {
                            val next = if (wordbook.id in selectedWordbookIds)
                                selectedWordbookIds - wordbook.id
                            else
                                selectedWordbookIds + wordbook.id
                            onSelectedWordbooksChange(next)
                        }
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// --- Shared composable building blocks ---

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SettingsGroupCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    )
}

@Composable
private fun SettingsArrowRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsCheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onClick() })
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SettingsTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    multiLine: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = !multiLine,
        maxLines = if (multiLine) 3 else 1,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.secondary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsSegmentedRow(
    selected: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(labelOf(option)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HitokotoSection(
    homeSubtitle: String,
    hitokotoRefreshInterval: Int,
    client: okhttp3.OkHttpClient,
    onHomeSubtitleChange: (String) -> Unit,
    onHitokotoRefreshIntervalChange: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "当前一言",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                homeSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    TextButton(
        onClick = {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url("https://v1.hitokoto.cn/?encode=text&c=i").build()
                    val response = client.newCall(request).execute()
                    val quote = response.body?.string()
                    if (!quote.isNullOrEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onHomeSubtitleChange(quote)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Settings", "Failed to fetch hitokoto", e)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("获取随机一言")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "自动刷新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "${hitokotoRefreshInterval} 分钟",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(56.dp)
        )
        Slider(
            value = hitokotoRefreshInterval.toFloat(),
            onValueChange = { onHitokotoRefreshIntervalChange(it.toInt()) },
            valueRange = 1f..60f,
            steps = 59,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun IconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

// --- About view (unchanged style, already standalone) ---

@Composable
private fun AboutView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "teaWords Logo",
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Fit
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("teaWords", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("v1.1.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Text(
                text = "茶词（teaWords）是一款极致简洁、专注单词学习与翻译的应用。旨在为你提供纯净的查词体验，并在不经意间通过 Bing 壁纸和一言名句，带给你一丝片刻的宁静。",
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 26.sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "致谢与致敬",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        val acknowledgments = listOf(
            "Jetpack Compose" to "现代原生 Android UI 工具包",
            "Material 3" to "Google 的新一代设计语言",
            "Coil" to "现代 Android 图片加载库",
            "Hitokoto 一言" to "提供温暖的人心文字",
            "Bing Wallpaper" to "提供每日精美壁纸",
            "Free Dictionary API" to "基础词典数据支持"
        )
        acknowledgments.forEach { (title, sub) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
        Text("teaMeow Technology", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
