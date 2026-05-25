package com.tea.teawords.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.data.*
import kotlinx.coroutines.launch

@Composable
fun ReviewSessionScreenEnhanced(
    session: ReviewSession,
    dbHelper: DatabaseHelper,
    onAnswerSubmit: (ReviewAnswer) -> Unit,
    onReviewComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val timerManager = remember { TimerManager(scope) }
    var timeRemaining by remember { mutableStateOf(0) }
    var showTimeWarning by remember { mutableStateOf(false) }
    var isAutoSubmitTriggered by remember { mutableStateOf(false) }

    // 启动计时器
    LaunchedEffect(Unit) {
        val recommendedTime = timerManager.calculateRecommendedTime(
            session.problems.size,
            session.difficulty
        )
        timerManager.onTick = { remaining ->
            timeRemaining = remaining
            // 10 秒时显示警告
            if (remaining == 10 && !showTimeWarning) {
                showTimeWarning = true
            }
        }
        timerManager.onTimeout = {
            isAutoSubmitTriggered = true
            // 自动提交空答案或标记为未完成
            onReviewComplete()
        }
        timerManager.startTimer(recommendedTime)
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            timerManager.stopTimer()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // 顶部进度条和计时器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 进度指示
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "第 ${session.currentProblemIndex + 1}/${session.problems.size} 题",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    LinearProgressIndicator(
                        progress = { session.currentProblemIndex.toFloat() / session.problems.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.LightGray
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 计时器
                TimerDisplay(
                    timeRemaining = timeRemaining,
                    isWarning = showTimeWarning,
                    isTimeUp = timerManager.isTimeUp()
                )
            }
        }

        Divider()

        // 题目内容
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = session.currentProblemIndex,
                transitionSpec = {
                    if (targetState < session.problems.size && initialState < session.problems.size) {
                        fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(animationSpec = tween(200))
                    } else {
                        fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(200))
                    }
                },
                label = "ProblemTransition"
            ) { index ->
                if (index < session.problems.size) {
                    val problem = session.problems[index]

                    ProblemDisplay(
                        problem = problem,
                        onOptionSelected = { selectedOption ->
                            val answer = ReviewAnswer(
                                problemId = problem.id,
                                userAnswer = selectedOption,
                                isCorrect = selectedOption.equals(problem.clozeWord, ignoreCase = true),
                                userDefinition = null,
                                timestamp = System.currentTimeMillis()
                            )

                            onAnswerSubmit(answer)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(16.dp)
                    )
                } else {
                    // 全部完成
                    CompletionScreen(
                        correctCount = session.correctCount,
                        totalCount = session.problems.size,
                        timeSpent = timerManager.calculateRecommendedTime(session.problems.size, session.difficulty) - timeRemaining,
                        onComplete = onReviewComplete,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerDisplay(
    timeRemaining: Int,
    isWarning: Boolean,
    isTimeUp: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        isTimeUp -> Color.Red
        isWarning -> Color(0xFFFFB74D)
        else -> Color(0xFFE8F5E9)
    }

    val textColor = when {
        isTimeUp -> Color.White
        isWarning -> Color.White
        else -> Color.Green
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(12.dp, 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Timer,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                formatTime(timeRemaining),
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ProblemDisplay(
    problem: ClozeProblem,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 题型标识
        Text(
            when (problem.clozeType) {
                ClozeType.WORD_CLOZE -> "单词填空"
                ClozeType.SEMANTIC_CLOZE -> "语义填空"
            },
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 难度指示器
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            repeat(5) { index ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index < problem.difficulty) Color.Red else Color.LightGray
                        )
                )
            }
        }

        // 句子（带空白）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                problem.blankedSentence,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = Color.Black
            )
        }

        // 选项
        Text(
            "请选择正确答案：",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 打乱选项顺序
        val shuffledOptions = remember { problem.options.shuffled() }

        shuffledOptions.forEachIndexed { index, option ->
            OptionButton(
                option = option,
                isCorrect = option.equals(problem.clozeWord, ignoreCase = true),
                onClick = { onOptionSelected(option) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun OptionButton(
    option: String,
    isCorrect: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            option,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompletionScreen(
    correctCount: Int,
    totalCount: Int,
    timeSpent: Int,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val completionScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "completionScale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp).scale(completionScale),
            tint = Color.Green
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "复习完成！",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 成绩卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${(correctCount * 100 / totalCount)}%",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "正确：$correctCount / $totalCount",
                    fontSize = 16.sp
                )

                Text(
                    "耗时：${formatTime(timeSpent)}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("返回")
        }
    }
}

private fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", minutes, secs)
}
