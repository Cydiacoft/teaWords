package com.tea.teawords.ui.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.data.*

// --- ReviewView (top-level orchestrator) ---

@Composable
fun ReviewView(
    historyList: List<HistoryItem>,
    selectedWordbookIds: Set<String>,
    levelProvider: WordLevelProvider,
    reviewSession: ReviewSession?,
    clozeGenerator: ClozeGenerator,
    dbHelper: DatabaseHelper,
    onStartReview: (List<ClozeProblem>) -> Unit,
    onCancelReview: () -> Unit,
    onAnswerSubmit: (ReviewAnswer) -> Unit,
    onReviewComplete: () -> Unit
) {
    var showStats by remember { mutableStateOf(false) }
    var showIPAChart by remember { mutableStateOf(false) }

    BackHandler(enabled = showStats || showIPAChart) {
        showStats = false
        showIPAChart = false
    }

    BackHandler(enabled = showStats || showIPAChart) {
        showStats = false
        showIPAChart = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: Main content (hidden under overlay)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (showStats || showIPAChart) 0f else 1f
                }
        ) {
            if (reviewSession == null) {
                ReviewStartScreen(
                    historyList = historyList,
                    selectedWordbookIds = selectedWordbookIds,
                    levelProvider = levelProvider,
                    clozeGenerator = clozeGenerator,
                    onStartReview = onStartReview,
                    onShowStats = { showStats = true },
                    onShowIPAChart = { showIPAChart = true }
                )
            } else {
                ReviewSessionScreen(
                    session = reviewSession,
                    onCancelReview = onCancelReview,
                    onAnswerSubmit = onAnswerSubmit,
                    onReviewComplete = onReviewComplete
                )
            }
        }

        // Layer 2: Independent overlay
        AnimatedVisibility(
            visible = showStats || showIPAChart,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (showStats) {
                    StatisticsView(
                        dbHelper = dbHelper,
                        onBack = { showStats = false }
                    )
                } else if (showIPAChart) {
                    PhoneticsChartView(
                        onBack = { showIPAChart = false }
                    )
                }
            }
        }
    }
}

// --- ReviewStartScreen ---

@Composable
fun ReviewStartScreen(
    historyList: List<HistoryItem>,
    selectedWordbookIds: Set<String>,
    levelProvider: WordLevelProvider,
    clozeGenerator: ClozeGenerator,
    onStartReview: (List<ClozeProblem>) -> Unit,
    onShowStats: () -> Unit,
    onShowIPAChart: () -> Unit
) {
    var isGenerating by remember { mutableStateOf(false) }
    val selectedBooks = LearningWordbook.entries.filter { it.id in selectedWordbookIds }
    val searchedItems = remember(historyList) {
        historyList
            .filter { it.word.isNotBlank() && it.translation.isNotBlank() }
            .filter { item ->
                item.word.none { it.isWhitespace() } &&
                    item.word.none { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }
            }
            .map {
                VocabularyItem(
                    word = it.word,
                    phonetic = null,
                    definition = it.translation,
                    timestamp = it.timestamp
                )
            }
            .distinctBy { it.word.lowercase() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Edit,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "开始学习",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "从查过的词继续巩固，或按设置里的词书开始新学习",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onShowStats) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("学习分析", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        ReviewTypeCard(
            title = "复习搜索过的单词",
            description = if (searchedItems.isEmpty()) {
                "暂无可学习记录，先去翻译页查几个单词"
            } else {
                "从最近 ${searchedItems.size} 个查询词里随机出题"
            },
            icon = Icons.Default.History,
            onClick = {
                if (searchedItems.isEmpty()) return@ReviewTypeCard
                isGenerating = true
                clozeGenerator.generateLocalMixedSessionProblems(searchedItems, count = 10) { problems ->
                    if (problems.isNotEmpty()) {
                        onStartReview(problems)
                    }
                    isGenerating = false
                }
            },
            isLoading = isGenerating
        )

        Spacer(modifier = Modifier.height(16.dp))

        ReviewTypeCard(
            title = "学习预选词书",
            description = if (selectedBooks.isEmpty()) {
                "请先到设置里选择至少一本词书"
            } else {
                selectedBooks.joinToString(" / ") { it.subtitle }
            },
            icon = Icons.Default.MenuBook,
            onClick = {
                if (selectedBooks.isEmpty()) return@ReviewTypeCard
                isGenerating = true
                val wordbookItems = levelProvider.getWordbookItems(selectedWordbookIds, limitPerBook = 80)
                clozeGenerator.generateLocalMixedSessionProblems(wordbookItems, count = 10) { problems ->
                    if (problems.isNotEmpty()) {
                        onStartReview(problems)
                    }
                    isGenerating = false
                }
            },
            isLoading = isGenerating
        )

        Spacer(modifier = Modifier.height(16.dp))

        ReviewTypeCard(
            title = "音标练习",
            description = "听音辨词，掌握 48 个国际音标的标准发音",
            icon = Icons.Default.RecordVoiceOver,
            onClick = onShowIPAChart
        )
    }
}

// --- ReviewTypeCard ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewTypeCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Card(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

// --- ReviewSessionScreen (the original inline review screen) ---

@Composable
fun ReviewSessionScreen(
    session: ReviewSession,
    onCancelReview: () -> Unit,
    onAnswerSubmit: (ReviewAnswer) -> Unit,
    onReviewComplete: () -> Unit
) {
    val problem = session.currentProblem
    var selectedAnswer by remember { mutableStateOf<String?>(null) }
    var showFeedback by remember { mutableStateOf(false) }
    var isAnswered by remember { mutableStateOf(false) }

    var timeLeft by remember { mutableIntStateOf(15) }
    var timerActive by remember { mutableStateOf(true) }

    LaunchedEffect(session.currentProblemIndex, timerActive) {
        if (timerActive && !isAnswered) {
            timeLeft = 15
            while (timeLeft > 0 && !isAnswered) {
                kotlinx.coroutines.delay(1000)
                timeLeft--
            }
            if (timeLeft == 0 && !isAnswered) {
                isAnswered = true
                showFeedback = true
            }
        }
    }

    if (session.isComplete) {
        ReviewCompleteScreen(
            session = session,
            onReviewComplete = onReviewComplete
        )
        return
    }

    if (problem == null) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancelReview) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${session.currentProblemIndex + 1} / ${session.problems.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (timeLeft < 5) Color.Red else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${timeLeft}s",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = if (timeLeft < 5) Color.Red else MaterialTheme.colorScheme.secondary
                )
            }
        }

        LinearProgressIndicator(
            progress = { session.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }

            item(key = "sentence_card") {
                val showAnswer = isAnswered && showFeedback
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            MaterialTheme.shapes.large
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = problem.blankedSentence,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(if (showAnswer) 0f else 1f),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 22.sp,
                                lineHeight = 32.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = problem.sentence,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(if (showAnswer) 1f else 0f),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 22.sp,
                                lineHeight = 32.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            items(problem.options, key = { it }) { option ->
                val isSelected = selectedAnswer == option
                val isCorrect = option == problem.clozeWord
                val showResult = isAnswered && showFeedback

                val backgroundColor = when {
                    !showResult -> MaterialTheme.colorScheme.surface
                    isCorrect -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                    isSelected && !isCorrect -> Color.Red.copy(alpha = 0.1f)
                    else -> MaterialTheme.colorScheme.surface
                }

                val borderColor = when {
                    !showResult && isSelected -> MaterialTheme.colorScheme.secondary
                    showResult && isCorrect -> MaterialTheme.colorScheme.secondary
                    showResult && isSelected && !isCorrect -> Color.Red
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(enabled = !isAnswered) {
                            selectedAnswer = option
                        }
                        .border(2.dp, borderColor, MaterialTheme.shapes.large),
                    colors = CardDefaults.cardColors(containerColor = backgroundColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(modifier = Modifier.size(20.dp)) {
                            if (showResult && isCorrect) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "正确",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (showResult && isSelected && !isCorrect) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "错误",
                                    tint = Color.Red,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .height(56.dp)
        ) {
            AnimatedContent(
                targetState = isAnswered,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "SubmitButtonTransition"
            ) { answered ->
                if (!answered) {
                    Button(
                        onClick = {
                            isAnswered = true
                            showFeedback = true
                        },
                        enabled = selectedAnswer != null,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("提交答案", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = {
                            val isCorrect = selectedAnswer == problem.clozeWord
                            onAnswerSubmit(
                                ReviewAnswer(
                                    problemId = problem.id,
                                    userAnswer = selectedAnswer ?: "",
                                    isCorrect = isCorrect
                                )
                            )
                            selectedAnswer = null
                            isAnswered = false
                            showFeedback = false
                        },
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            if (session.currentProblemIndex == session.problems.size - 1) "完成学习" else "下一题",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

// --- ReviewCompleteScreen ---

@Composable
fun ReviewCompleteScreen(
    session: ReviewSession,
    onReviewComplete: () -> Unit
) {
    val percentage = if (session.problems.isNotEmpty()) {
        (session.correctCount * 100) / session.problems.size
    } else {
        0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Done,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "学习完成！",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(140.dp)
                .border(
                    4.dp,
                    MaterialTheme.colorScheme.secondary,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 48.sp),
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "${session.correctCount}/${session.problems.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "做对了 ${session.correctCount} 道题，共 ${session.problems.size} 道",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onReviewComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("返回首页")
        }
    }
}
