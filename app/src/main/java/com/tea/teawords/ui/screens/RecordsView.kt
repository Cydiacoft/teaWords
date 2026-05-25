package com.tea.teawords.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.data.HistoryItem
import com.tea.teawords.data.VocabularyItem

@Composable
fun RecordsView(
    historyList: List<HistoryItem>,
    vocabularyList: List<VocabularyItem>,
    onItemClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteHistory: (String) -> Unit,
    onDeleteVocab: (String) -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Refined Header with Title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "回望",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 32.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            IconButton(
                onClick = { 
                    if (selectedTabIndex == 0) onClearHistory() 
                },
                enabled = selectedTabIndex == 0 && historyList.isNotEmpty()
            ) {
                if (selectedTabIndex == 0 && historyList.isNotEmpty()) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "Clear all",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Custom Tab Switcher (Pill shape style)
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf("查阅记录", "生词本")
            tabs.forEachIndexed { index, title ->
                val selected = selectedTabIndex == index
                val background by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    animationSpec = tween(300)
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    animationSpec = tween(300)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(background)
                        .clickable { selectedTabIndex = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = contentColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedContent(
            targetState = selectedTabIndex,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220, delayMillis = 80)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 80)))
                    .togetherWith(fadeOut(animationSpec = tween(90)))
            },
            label = "RecordsTabTransition",
            modifier = Modifier.weight(1f)
        ) { tab ->
            if (tab == 0) {
                HistoryList(
                    historyList = historyList,
                    onItemClick = onItemClick,
                    onDeleteItem = onDeleteHistory
                )
            } else {
                NotebookView(
                    vocabularyList = vocabularyList,
                    onItemClick = onItemClick,
                    onDeleteClick = onDeleteVocab
                )
            }
        }
    }
}

@Composable
private fun HistoryList(
    historyList: List<HistoryItem>,
    onItemClick: (String) -> Unit,
    onDeleteItem: (String) -> Unit
) {
    if (historyList.isEmpty()) {
        EmptyState("暂无查阅历史")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp, top = 8.dp)
        ) {
            items(historyList) { item ->
                RecordCard(
                    title = item.word,
                    subtitle = item.translation,
                    onClick = { onItemClick(item.word) },
                    onDelete = { onDeleteItem(item.word) }
                )
            }
        }
    }
}

@Composable
private fun RecordCard(
    title: String,
    subtitle: String,
    phonetic: String? = null,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = borderStroke(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!phonetic.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = phonetic,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun borderStroke() = androidx.compose.foundation.BorderStroke(
    1.dp, 
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
)

@Composable
private fun NotebookView(
    vocabularyList: List<VocabularyItem>,
    onItemClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    var flashcardMode by remember { mutableStateOf(false) }
    var flashcardIndex by remember { mutableStateOf(0) }
    var flashcardFlipped by remember { mutableStateOf(false) }

    if (vocabularyList.isEmpty()) {
        EmptyState("生词本空空如也")
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Small mode toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    flashcardMode = !flashcardMode
                    flashcardIndex = 0
                    flashcardFlipped = false
                },
                shape = CircleShape
            ) {
                Icon(
                    imageVector = if (flashcardMode) Icons.Default.List else Icons.Default.Style,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (flashcardMode) "切换列表" else "卡片模式")
            }
        }

        if (flashcardMode) {
            FlashcardStage(
                vocabularyList = vocabularyList,
                index = flashcardIndex,
                isFlipped = flashcardFlipped,
                onFlip = { flashcardFlipped = !flashcardFlipped },
                onPrev = {
                    if (flashcardIndex > 0) {
                        flashcardFlipped = false
                        flashcardIndex--
                    }
                },
                onNext = {
                    if (flashcardIndex < vocabularyList.size - 1) {
                        flashcardFlipped = false
                        flashcardIndex++
                    } else {
                        flashcardMode = false
                    }
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp)
            ) {
                items(vocabularyList) { item ->
                    RecordCard(
                        title = item.word,
                        subtitle = item.definition,
                        phonetic = item.phonetic,
                        onClick = { onItemClick(item.word) },
                        onDelete = { onDeleteClick(item.word) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FlashcardStage(
    vocabularyList: List<VocabularyItem>,
    index: Int,
    isFlipped: Boolean,
    onFlip: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val currentWord = vocabularyList.getOrNull(index) ?: return
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${index + 1} / ${vocabularyList.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                }
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(32.dp)
                )
                .clickable { onFlip() },
            contentAlignment = Alignment.Center
        ) {
            if (rotation <= 90f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = currentWord.word,
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    if (!currentWord.phonetic.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = currentWord.phonetic,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(
                        text = "点击翻面",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .graphicsLayer { rotationY = 180f }
                        .padding(32.dp)
                ) {
                    Text(
                        text = currentWord.definition,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal, lineHeight = 32.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(64.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = onPrev,
                enabled = index > 0,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = null)
            }

            Button(
                onClick = onNext,
                shape = CircleShape,
                modifier = Modifier.height(56.dp).padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = if (index == vocabularyList.size - 1) "完成复习" else "下一个",
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                if (index < vocabularyList.size - 1) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
    }
}
