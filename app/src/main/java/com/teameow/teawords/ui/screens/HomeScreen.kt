package com.teameow.teawords.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.teameow.teawords.data.HistoryItem
import com.teameow.teawords.data.LexicalText
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
    searchQuery: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit,
    suggestions: List<String>, isInputFocused: Boolean, onFocusChange: (Boolean) -> Unit,
    historyList: List<HistoryItem>, homeTitle: String, homeSubtitle: String,
    onHistoryClick: (String) -> Unit, onClearHistory: () -> Unit,
    onMenuClick: () -> Unit, onPersonClick: () -> Unit,
    wallpaperUrl: String? = null, bottomClearance: Dp = 0.dp
) {
    var recentOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var inputEverFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    val searchFocused by rememberUpdatedState(isInputFocused || searchOpen)
    val pullThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var pullDistance by remember { mutableFloatStateOf(0f) }
    var pulling by remember { mutableStateOf(false) }
    val pullOffset by animateFloatAsState(
        targetValue = pullDistance * 0.35f,
        animationSpec = if (pulling) snap() else spring(),
        label = "pull-down-search"
    )
    val pullSearch = remember(pullThreshold, scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (searchFocused || source != NestedScrollSource.UserInput || pullDistance <= 0f || available.y >= 0f) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-pullDistance)
                pullDistance += consumed
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only pull at the top; normal scrolling and suggestion browsing keep their gestures.
                if (searchFocused || source != NestedScrollSource.UserInput || scroll.value != 0 || available.y <= 0f) return Offset.Zero
                pulling = true
                pullDistance = (pullDistance + available.y).coerceAtMost(pullThreshold * 1.5f)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                val openSearch = pullDistance >= pullThreshold && !searchFocused
                pulling = false
                pullDistance = 0f
                if (openSearch) {
                    onFocusChange(true)
                    searchOpen = true
                }
                return Velocity(0f, available.y)
            }
        }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            pulling = false
            pullDistance = 0f
            scroll.scrollTo(0)
            requester.requestFocus()
            keyboard?.show()
        }
    }
    val search = { focus.clearFocus(); onSearch() }
    val exitSearch = {
        inputEverFocused = false
        focus.clearFocus(force = true)
        keyboard?.hide()
        searchOpen = false
        onFocusChange(false)
    }
    // 开启每日壁纸时它是整页背景，不再缩成一张 100dp 的小卡片——卡片只截到画面中间一小条，
    // 等于把「每日壁纸」这个功能做废。另加一层渐变遮罩，保证白字在任何一张图上都读得出来。
    val wallpaper = wallpaperUrl?.takeIf { it.isNotBlank() }
    val onWallpaper = wallpaper != null
    val lightPalette = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val wallpaperForeground = if (lightPalette) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    val wallpaperScrim = if (lightPalette) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surface
    val strongColor = if (onWallpaper) wallpaperForeground else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (onWallpaper) wallpaperForeground.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            AsyncImage(wallpaper, "每日壁纸", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to wallpaperScrim.copy(alpha = 0.16f),
                        0.55f to wallpaperScrim.copy(alpha = 0f),
                        1f to wallpaperScrim.copy(alpha = 0.38f))
                )
            )
        }
        Column(
            Modifier.fillMaxSize()
                .background(if (onWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else MaterialTheme.colorScheme.surface)
                .padding(bottom = bottomClearance)
                .consumeWindowInsets(PaddingValues(bottom = bottomClearance))
                .imePadding()
        ) {
            TopAppBar(title = {}, navigationIcon = {
                IconButton(onClick = onMenuClick) { Icon(AppSymbols.Settings, "设置") }
            }, actions = {
                IconButton(onClick = onPersonClick) { Icon(AppSymbols.BarChart, "学习统计") }
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (onWallpaper) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else MaterialTheme.colorScheme.surface,
                navigationIconContentColor = strongColor,
                actionIconContentColor = strongColor
            ))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().nestedScroll(pullSearch)) {
                val viewportHeight = maxHeight
                val suggestionMaxHeight = (maxHeight - 80.dp).coerceAtLeast(72.dp)
                if (!searchOpen && !isInputFocused) Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                ) {
                    // A full-height scroll target lets a downward drag open search from anywhere
                    // on the wallpaper without placing an input field over the image.
                    Spacer(Modifier.height(viewportHeight))
                }
                if (searchOpen || isInputFocused) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 72.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (suggestions.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().heightIn(max = suggestionMaxHeight),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                LazyColumn {
                                    items(suggestions.take(8)) { word ->
                                        Surface(onClick = { focus.clearFocus(); onHistoryClick(word) },
                                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            ListItem(headlineContent = { Text(word) },
                                                trailingContent = { Icon(AppSymbols.ChevronRight, null) },
                                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (searchOpen || isInputFocused) TextField(
                    value = searchQuery, onValueChange = onQueryChange,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .heightIn(min = 56.dp)
                        .focusRequester(requester).onFocusChanged {
                            if (it.isFocused) {
                                inputEverFocused = true
                                onFocusChange(true)
                            } else if (inputEverFocused) {
                                inputEverFocused = false
                                onFocusChange(false)
                                searchOpen = false
                            }
                        },
                    placeholder = { Text("单词、中文释义或一段文字", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { IconButton(onClick = exitSearch) { Icon(AppSymbols.ArrowBack, "退出搜索") } },
                    trailingIcon = {
                        IconButton(onClick = { onQueryChange("") }, enabled = searchQuery.isNotEmpty()) {
                            Icon(AppSymbols.Close, "清空输入")
                        }
                    },
                    shape = CircleShape, singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() })
                )
                if (pullOffset > 1f && !searchOpen && !isInputFocused) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(AppSymbols.Search, null, Modifier.size(18.dp), tint = mutedColor)
                        Text(
                            if (pullDistance >= pullThreshold) "松开进入搜索" else "下拉进入搜索",
                            style = MaterialTheme.typography.labelMedium,
                            color = mutedColor
                        )
                    }
                }
                if (!searchOpen && !isInputFocused) Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { onFocusChange(true); searchOpen = true }, modifier = Modifier.size(56.dp),
                        colors = if (onWallpaper) IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = wallpaperForeground.copy(alpha = 0.22f),
                            contentColor = wallpaperForeground
                        ) else IconButtonDefaults.filledTonalIconButtonColors()
                    ) { Icon(AppSymbols.Search, "打开下拉搜索", Modifier.size(28.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(homeTitle.ifBlank { "茶词" }, style = MaterialTheme.typography.titleLarge,
                            color = strongColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (homeSubtitle.isNotBlank()) Text(homeSubtitle,
                            style = MaterialTheme.typography.bodySmall, color = mutedColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledIconButton(
                        onClick = { focus.clearFocus(); recentOpen = true }, modifier = Modifier.size(56.dp)
                    ) { Icon(AppSymbols.Schedule, "最近查阅", Modifier.size(24.dp)) }
                }
            }
        }
    }
    if (recentOpen) {
        ModalBottomSheet(onDismissRequest = { recentOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 480.dp).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近查阅", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    FilledTonalIconButton(onClick = { confirmClear = true }, enabled = historyList.isNotEmpty()) {
                        Icon(AppSymbols.Delete, "清空最近查阅")
                    }
                }
                if (historyList.isEmpty()) TeaEmptyState("还没有查阅记录", "查过的单词和翻译会出现在这里")
                else LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(historyList, key = { "${it.word}:${it.timestamp}" }) { entry ->
                        Surface(onClick = { recentOpen = false; onHistoryClick(entry.word) }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.word, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SuggestionChip(onClick = { recentOpen = false; onHistoryClick(entry.word) }, label = {
                                    // 设计稿在历史行上标的是查询方向，而不是内部的「查词 / 翻译」模式名。
                                    Text(if (LexicalText.isChinese(entry.word)) "中译英" else "英译中")
                                })
                                Icon(AppSymbols.ChevronRight, null)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                FilledIconButton(onClick = { recentOpen = false }, modifier = Modifier.align(Alignment.End).padding(vertical = 8.dp).size(56.dp)) {
                    Icon(AppSymbols.ArrowDownward, "收起最近查阅")
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空最近查阅？") },
        text = { Text("仅清理首页的最近列表，查过的生词仍可在回望中复习。") },
        confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
