package com.teameow.teawords.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val search = { focus.clearFocus(); onSearch() }
    // 开启每日壁纸时它是整页背景，不再缩成一张 100dp 的小卡片——卡片只截到画面中间一小条，
    // 等于把「每日壁纸」这个功能做废。另加一层渐变遮罩，保证白字在任何一张图上都读得出来。
    val wallpaper = wallpaperUrl?.takeIf { it.isNotBlank() }
    val onWallpaper = wallpaper != null
    val strongColor = if (onWallpaper) Color.White else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (onWallpaper) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            AsyncImage(wallpaper, "每日壁纸", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.55f))
                    )
                )
            )
        }
        Column(
            Modifier.fillMaxSize()
                .background(if (onWallpaper) Color.Transparent else MaterialTheme.colorScheme.surface)
                .padding(bottom = bottomClearance)
        ) {
            TopAppBar(title = {}, navigationIcon = {
                IconButton(onClick = onMenuClick) { Icon(AppSymbols.Settings, "设置") }
            }, actions = {
                IconButton(onClick = onPersonClick) { Icon(AppSymbols.BarChart, "学习统计") }
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (onWallpaper) Color.Transparent else MaterialTheme.colorScheme.surface,
                navigationIconContentColor = strongColor,
                actionIconContentColor = strongColor
            ))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 先取出再把值传进 Column：Column 的内容 lambda 里隐式接收者是 ColumnScope，
                // 直接写 maxHeight 会编译不过。
                val topSpace = (maxHeight * .2f).coerceAtMost(156.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(if (isInputFocused) 16.dp else topSpace))
                    if (!isInputFocused) {
                        FilledTonalIconButton(
                            onClick = { requester.requestFocus() },
                            modifier = Modifier.size(56.dp),
                            colors = if (onWallpaper) IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.22f),
                                contentColor = Color.White
                            ) else IconButtonDefaults.filledTonalIconButtonColors()
                        ) {
                            Icon(AppSymbols.Search, "输入要查询的内容", Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(homeTitle.ifBlank { "茶词" }, style = MaterialTheme.typography.headlineMedium, color = strongColor)
                        Spacer(Modifier.height(24.dp))
                    }
                    TextField(
                        value = searchQuery, onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .focusRequester(requester).onFocusChanged { onFocusChange(it.isFocused) },
                        placeholder = { Text("单词、中文释义或一段文字", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(AppSymbols.Search, null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {{
                            IconButton(onClick = search) { Icon(AppSymbols.ArrowForward, "查询") }
                        }} else null,
                        shape = CircleShape, singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() })
                    )
                    if (isInputFocused && suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            suggestions.take(6).forEach { word ->
                                Surface(onClick = { focus.clearFocus(); onHistoryClick(word) }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    ListItem(headlineContent = { Text(word) }, trailingContent = { Icon(AppSymbols.ChevronRight, null) },
                                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                                }
                            }
                        }
                    } else if (!isInputFocused && homeSubtitle.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(homeSubtitle, style = MaterialTheme.typography.bodyMedium, color = mutedColor)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                FilledIconButton(
                    onClick = { focus.clearFocus(); recentOpen = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp)
                ) { Icon(AppSymbols.Schedule, "最近查阅", Modifier.size(24.dp)) }
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
