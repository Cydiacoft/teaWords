package com.tea.teawords.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tea.teawords.data.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StatsDashboard(
    dbHelper: DatabaseHelper,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val analytics = remember { ReviewAnalytics(dbHelper) }
    var selectedTab by remember { mutableStateOf(0) }

    var progress by remember { mutableStateOf<LearningProgress?>(null) }

    LaunchedEffect(Unit) {
        progress = analytics.getLearningProgress()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "学习统计",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            val currentProgress = progress
            if (currentProgress != null) {
                // Header
                HeaderCard(currentProgress)

                // Tab Selection
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        if (selectedTab < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab])
                            )
                        }
                    }
                ) {
                     Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("概览") },
                        icon = { Icon(Icons.Default.BarChart, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("词库") },
                        icon = { Icon(Icons.Default.School, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("错题") },
                        icon = { Icon(Icons.Default.Warning, contentDescription = null) }
                    )
                }

                // Content
                Box(modifier = Modifier.padding(bottom = 20.dp)) {
                    when (selectedTab) {
                        0 -> OverviewTab(analytics)
                        1 -> VocabularyTab(analytics, dbHelper)
                        2 -> ErrorTab(analytics)
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(progress: LearningProgress) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // User Level and Streak
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "等级 ${progress.level}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${progress.totalSessionCount} 个学习会话",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (progress.streak > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "${progress.streak} 天连续",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Accuracy Gauge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatItem(
                    label = "总体准确率",
                    value = "${progress.accuracyPercentage}%",
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "最近 7 天",
                    value = "${progress.recentAccuracyPercentage}%",
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "学习时长",
                    value = "${progress.timeHours}h",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun OverviewTab(analytics: ReviewAnalytics) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "学习趋势",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val trend = remember { analytics.getLearningTrend(7) }
        
        // 迷你图表
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                trend.forEach { day ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .fillMaxHeight((day.accuracy / 100f).coerceIn(0.01f, 1f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    when {
                                        day.accuracy >= 80f -> Color(0xFF4CAF50)
                                        day.accuracy >= 60f -> Color(0xFFFFA500)
                                        else -> Color(0xFFF44336)
                                    }
                                )
                        )
                        Text(
                            day.date.split("-").last(),
                            fontSize = 8.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 难度分布
        Text(
            "难度分布",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val distribution = remember { analytics.getDifficultyDistribution() }
        
        DifficultyItem("简单", distribution.easyAccuracy)
        DifficultyItem("普通", distribution.mediumAccuracy)
        DifficultyItem("困难", distribution.hardAccuracy)
        DifficultyItem("专家", distribution.expertAccuracy)
        DifficultyItem("大师", distribution.masterAccuracy)

        Spacer(modifier = Modifier.height(20.dp))

        // 推荐
        Text(
            "建议",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val nextDifficulty = remember { analytics.recommendDifficulty() }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    "下次推荐难度：${when(nextDifficulty) { 1 -> "简单" 2 -> "普通" 3 -> "困难" 4 -> "专家" else -> "大师" }}",
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun DifficultyItem(label: String, accuracy: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.width(50.dp), fontSize = 13.sp)
        
        LinearProgressIndicator(
            progress = { (accuracy / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = when {
                accuracy >= 80f -> Color(0xFF4CAF50)
                accuracy >= 60f -> Color(0xFFFFA500)
                else -> Color(0xFFF44336)
            },
            trackColor = Color.LightGray.copy(alpha = 0.3f)
        )
        
        Text("${accuracy.toInt()}%", modifier = Modifier.width(40.dp), fontSize = 12.sp)
    }
}

@Composable
private fun VocabularyTab(analytics: ReviewAnalytics, dbHelper: DatabaseHelper) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "掌握度分析",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val recommended = remember { analytics.getRecommendedWords(5) }
        
        if (recommended.isEmpty()) {
            Text(
                "暂无学习数据，先去完成几次复习吧",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                textAlign = TextAlign.Center,
                color = Color.Gray
            )
        } else {
            Text(
                "待巩固（${recommended.size}）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            recommended.forEach { word ->
                val stats = dbHelper.getVocabStats(word)
                if (stats != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(word, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    "准确率: ${(stats.accuracy * 100).toInt()}% | 复习: ${stats.reviewCount}次",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            
                            val masterLevel = when {
                                stats.accuracy < 0.3f -> "未学"
                                stats.accuracy < 0.6f -> "学习"
                                stats.accuracy < 0.8f -> "熟悉"
                                stats.accuracy < 0.95f -> "熟练"
                                else -> "精通"
                            }
                            
                            Text(
                                masterLevel,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when {
                                            stats.accuracy >= 0.8f -> Color(0xFFE8F5E9)
                                            stats.accuracy >= 0.6f -> Color(0xFFFFF3E0)
                                            else -> Color(0xFFFFEBEE)
                                        }
                                    )
                                    .padding(6.dp, 4.dp),
                                color = when {
                                    stats.accuracy >= 0.8f -> Color(0xFF2E7D32)
                                    stats.accuracy >= 0.6f -> Color(0xFFE65100)
                                    else -> Color(0xFFC62828)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorTab(analytics: ReviewAnalytics) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "错题分析",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val errorAnalysis = remember { analytics.getErrorAnalysis() }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${errorAnalysis.totalErrors}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text("总错误", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${errorAnalysis.errorWordsCount}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text("错误单词", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${errorAnalysis.errorRate.toInt()}%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("错题率", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (errorAnalysis.frequentErrors.isNotEmpty()) {
            Text(
                "高频错题",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            errorAnalysis.frequentErrors.take(10).forEach { (word, count) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(word, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            "$count 次",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无错题记录，真棒！", color = Color.Gray)
            }
        }
    }
}
