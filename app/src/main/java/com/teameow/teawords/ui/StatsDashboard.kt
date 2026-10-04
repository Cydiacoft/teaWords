package com.teameow.teawords.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.*
import com.teameow.teawords.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Self-reported known words are left alone for this long before the first spot check. */
private const val SAMPLE_AFTER_MS = 7 * 86_400_000L
private const val DAY_MS = 86_400_000L

/**
 * Personal vocabulary knowledge profile.
 *
 * Layout follows the reference mock, with one rule applied everywhere: **nothing is drawn that the
 * app cannot measure**. The hero number is words verified by real test answers, never a model guess;
 * the band chart uses the word books that actually exist in the dictionary (CET-4 / CET-6 / outside
 * the syllabus) instead of inventing CEFR levels; and the dimension panel states plainly that
 * per-mode knowledge is not recorded yet rather than showing invented recognition/recall numbers.
 */
@Composable
fun StatsDashboard(dbHelper: DatabaseHelper, modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val repo = remember(dbHelper) { LearningRepository(dbHelper) }
    var rows by remember { mutableStateOf(emptyList<LearningRepository.WordRow>()) }
    var totals by remember { mutableStateOf(LearningRepository.Totals(0, 0, 0, 0)) }
    var today by remember { mutableStateOf(LearningRepository.Totals(0, 0, 0, 0)) }
    var trend by remember { mutableStateOf(emptyList<LearningRepository.Daily>()) }
    var windowDays by remember { mutableIntStateOf(30) }
    var streak by remember { mutableIntStateOf(0) }
    var dictionarySize by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("待确认") }
    var legacyTests by remember { mutableIntStateOf(0) }
    var skippedExpanded by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(windowDays) {
        loading = true
        try {
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val loaded = withContext(Dispatchers.IO) {
                val days = repo.activeDays()
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                var streakCount = 0
                var cursor = midnight
                while (days.contains(format.format(Date(cursor)))) {
                    streakCount++
                    cursor -= DAY_MS
                }
                ProfileSnapshot(
                    rows = repo.wordRows(),
                    totals = repo.totals(),
                    today = repo.totals(midnight),
                    trend = repo.daily(midnight - (windowDays - 1) * DAY_MS),
                    streak = streakCount,
                    dictionarySize = repo.dictionarySize(),
                    legacyTests = dbHelper.getReviewStats()?.totalProblems ?: 0
                )
            }
            rows = loaded.rows
            totals = loaded.totals
            today = loaded.today
            trend = loaded.trend
            streak = loaded.streak
            dictionarySize = loaded.dictionarySize
            legacyTests = loaded.legacyTests
        } catch (e: Exception) {
            error = "读取统计失败：${e.message}"
        } finally {
            loading = false
        }
    }

    val verified = rows.count { it.verified }
    val selfKnown = rows.count { it.selfReportedKnown }
    val learning = rows.count { it.attempts > 0 && !it.verified }
    val fuzzy = rows.count { it.report == "FUZZY" && it.attempts == 0 }
    val unknown = rows.count { it.report == "UNKNOWN" && it.attempts == 0 }
    val pending = (rows.size - verified - selfKnown - learning - fuzzy - unknown).coerceAtLeast(0)

    fun category(row: LearningRepository.WordRow): String = when {
        row.verified -> "已验证"
        row.selfReportedKnown -> "自评认识"
        row.attempts > 0 -> "学习中"
        row.report == "FUZZY" -> "模糊认识"
        row.report == "UNKNOWN" -> "完全陌生"
        else -> "待确认"
    }
    val categories = listOf("已验证", "学习中", "自评认识", "模糊认识", "完全陌生", "待确认")
    val accents = mapOf(
        "已验证" to MaterialTheme.colorScheme.primary,
        "学习中" to MaterialTheme.colorScheme.secondary,
        "自评认识" to MaterialTheme.colorScheme.tertiary,
        "模糊认识" to MaterialTheme.colorScheme.tertiary,
        "完全陌生" to MaterialTheme.colorScheme.error,
        "待确认" to MaterialTheme.colorScheme.outline
    )

    TeaListPage(
        title = "词汇知识画像",
        subtitle = "实测证据与模型预测分开统计",
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        navigationContent = {},
        actions = {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(AppSymbols.Close, "关闭统计")
            }
        }
    ) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { TeaCaption(it) } }

        // Hero: the number that is actually evidence, with its definition underneath.
        item {
            TeaCard {
                Text(
                    "$verified",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("个词已通过测试验证", style = MaterialTheme.typography.titleMedium)
                TeaCaption("连续 3 次主动回忆正确才算验证；只代表学习过的释义，不代表该词所有义项。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TeaMetric("$selfKnown", "自评认识 · 待验证", Modifier.weight(1f))
                    TeaMetric("$pending", "尚未判断", Modifier.weight(1f))
                    TeaMetric("$dictionarySize", "本地词库词条", Modifier.weight(1f))
                }
            }
        }

        // Distribution across the word books that actually exist.
        item {
            LearningCard("覆盖分布", "按词书分档 · 柱内为已验证词数") {
                val bands = listOf(
                    "CET-4" to rows.count { it.tags.contains("cet4") && it.verified },
                    "CET-6" to rows.count { it.tags.contains("cet6") && it.verified },
                    "大纲外" to rows.count { it.tags.isBlank() && it.verified }
                )
                BarChart(bands, "本地词典中没有词频数据，因此不显示 A1–C2 之类的等级分布。")
                val bandTotals = listOf(
                    "CET-4" to rows.count { it.tags.contains("cet4") },
                    "CET-6" to rows.count { it.tags.contains("cet6") },
                    "大纲外" to rows.count { it.tags.isBlank() }
                )
                TeaCaption(
                    "有学习记录的词：" + bandTotals.joinToString(" · ") { "${it.first} ${it.second}" }
                )
            }
        }

        // The five-way split, each row labelled as evidence or prediction.
        item {
            LearningCard("词汇知识状态", "$dictionarySize 个词条在库 · 每个词只归入一类") {
                TeaProgressRow("已验证 · 测试证据", verified, dictionarySize, accent = accents.getValue("已验证"))
                TeaProgressRow("学习中 · 有测试记录但未验证", learning, dictionarySize, accent = accents.getValue("学习中"))
                TeaProgressRow("自评认识 · 仅你的判断，非测试证据", selfKnown, dictionarySize, accent = accents.getValue("自评认识"))
                TeaProgressRow("模糊认识 · 自评待巩固", fuzzy, dictionarySize, accent = accents.getValue("模糊认识"))
                TeaProgressRow("完全陌生 · 自评未学", unknown, dictionarySize, accent = accents.getValue("完全陌生"))
                TeaProgressRow("待确认 · 尚无任何记录", pending, dictionarySize, accent = accents.getValue("待确认"))
                TeaCaption("前两类来自答题记录。「自评认识」是你自己的判断，不计入已验证。")
            }
        }

        // Learning trend with a switchable window.
        item {
            LearningCard("学习趋势", "$windowDays 天内的真实答题") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30, 90).forEach { days ->
                        FilterChip(
                            selected = windowDays == days,
                            onClick = { windowDays = days },
                            label = { Text("$days 天") }
                        )
                    }
                }
                if (trend.isEmpty()) {
                    TeaCaption("这 $windowDays 天没有答题记录。完成一次学习后这里会出现真实数据。")
                } else {
                    TrendChart(trend, windowDays)
                    val tests = trend.sumOf { it.tests }
                    val correct = trend.sumOf { it.correct }
                    val activeDays = trend.count { it.tests > 0 }
                    TeaCaption(
                        "$windowDays 天共 $tests 次回忆 · 正确 ${if (tests == 0) 0 else correct * 100 / tests}% · " +
                            "活跃 $activeDays 天 · 累计时长 ${formatDuration(trend.sumOf { it.seconds })}"
                    )
                }
            }
        }

        // Ability dimensions: only shown where the data exists.
        item {
            LearningCard("能力维度", "按作答方式分别记录掌握情况") {
                Text(
                    "英文识别 · 中文回忆 · 拼写：三种作答方式已按独立难度建模，但当前版本尚未把每次作答的方式写进记录。",
                    style = MaterialTheme.typography.bodyMedium
                )
                TeaCaption("因此这里不显示各维度的分数，避免用未记录的数据拼出好看的比例。")
                TeaMetricRow(
                    "$streak" to "连续学习天数",
                    "${totals.tests}" to "累计回忆次数",
                    "${totals.seconds / 3600}h" to "累计专注时长"
                )
            }
        }

        item {
            LearningCard("今日", "前台单题耗时；每题最多计 5 分钟") {
                TeaMetricRow(
                    "${today.tests}" to "回忆次数",
                    "${today.seconds / 60}:${(today.seconds % 60).toString().padStart(2, '0')}" to "专注时长",
                    if (today.tests == 0) "—" to "正确率" else "${today.correct * 100 / today.tests}%" to "正确率"
                )
                if (today.tests == 0) TeaCaption("今天还没有答题记录。")
            }
        }

        item {
            LearningCard("减少无效重复", "自评认识、且尚无测试记录的词不再占用新词队列") {
                val skipped = rows.filter { it.selfReportedKnown }
                val hiddenNow = skipped.count { System.currentTimeMillis() - it.updated < SAMPLE_AFTER_MS }
                Text("$hiddenNow 个词按你的判断暂离队列，系统只做少量间隔抽查。", style = MaterialTheme.typography.bodyMedium)
                if (skipped.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { skippedExpanded = !skippedExpanded },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (skippedExpanded) "收起这些词" else "查看这 ${skipped.size} 个词") }
                }
                Text("模型预测：未启用", fontWeight = FontWeight.SemiBold)
                if (legacyTests > 0) {
                    TeaCaption("旧版练习保留 $legacyTests 道答题记录；未换算成新的知识画像，避免混淆证据来源。")
                }
            }
        }

        if (skippedExpanded) {
            item { TeaSectionLabel("自评认识 · 暂离新词队列") }
            items(rows.filter { it.selfReportedKnown }, key = { "skipped-${it.word}" }) { k ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(k.word, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            TeaCaption(
                                if (System.currentTimeMillis() - k.updated < SAMPLE_AFTER_MS)
                                    "自评认识 · 暂不抽查，可撤销"
                                else "自评认识 · 已到抽查时间"
                            )
                        }
                        TextButton(
                            enabled = !reverting,
                            onClick = {
                                reverting = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { repo.revertSelfReport(k.word) }
                                        rows = rows.map { if (it.word == k.word) it.copy(report = "FUZZY") else it }
                                    } finally {
                                        reverting = false
                                    }
                                }
                            }
                        ) { Text("撤销认识") }
                    }
                }
            }
        }

        item {
            TeaSectionLabel("词汇明细")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { label ->
                    FilterChip(
                        selected = filter == label,
                        onClick = { filter = label },
                        label = { Text("$label · ${rows.count { category(it) == label }}") }
                    )
                }
            }
        }

        val filtered = rows.filter { category(it) == filter }
        if (filtered.isEmpty() && !loading) {
            item { TeaCard { TeaEmptyState("暂无词汇", "完成排雷或学习后，相应词汇会出现在这里。") } }
        }
        items(filtered, key = { "profile-${it.word}" }) { k ->
            WordEvidenceRow(k, accents[category(k)] ?: MaterialTheme.colorScheme.primary)
        }
    }
}

private data class ProfileSnapshot(
    val rows: List<LearningRepository.WordRow>,
    val totals: LearningRepository.Totals,
    val today: LearningRepository.Totals,
    val trend: List<LearningRepository.Daily>,
    val streak: Int,
    val dictionarySize: Int,
    val legacyTests: Int
)

@Composable
private fun WordEvidenceRow(k: LearningRepository.WordRow, accent: Color) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(k.word, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                TeaCaption(
                    when {
                        k.verified -> "测试证据 · 连续答对 ${k.streak} 次"
                        k.attempts > 0 -> "测试证据 · 答过 ${k.attempts} 次，连续答对 ${k.streak} 次"
                        k.selfReportedKnown -> "自评认识 · 等待抽样验证"
                        k.report == "FUZZY" -> "自评模糊 · 尚无测试记录"
                        k.report == "UNKNOWN" -> "自评陌生 · 尚未学习"
                        else -> "尚无任何记录"
                    }
                )
                if (k.due > 0) {
                    TeaCaption("下次复习 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(k.due))}")
                }
                if (k.tags.isNotBlank()) TeaCaption("词书 · ${k.tags}")
            }
            TeaChip(
                when {
                    k.verified -> "已验证"
                    k.attempts > 0 -> "学习中"
                    k.selfReportedKnown -> "自评认识"
                    k.report == "FUZZY" -> "模糊"
                    k.report == "UNKNOWN" -> "陌生"
                    else -> "待确认"
                },
                accent = accent
            )
        }
    }
}

/** Horizontal bars, one per band, scaled to the largest value. */
@Composable
private fun BarChart(entries: List<Pair<String, Int>>, caption: String) {
    val peak = (entries.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(56.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    if (value > 0) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(value.toFloat() / peak)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "$value",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
        TeaCaption(caption)
    }
}

/** Daily bar chart over the selected window, with the peak day called out. */
@Composable
private fun TrendChart(days: List<LearningRepository.Daily>, windowDays: Int) {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val window = (windowDays - 1 downTo 0).map { offset ->
        val day = format.format(Date(calendar.timeInMillis - offset * DAY_MS))
        days.firstOrNull { it.day == day } ?: LearningRepository.Daily(day, 0, 0, 0)
    }
    val peak = window.maxByOrNull { it.tests }
    val peakValue = (peak?.tests ?: 0).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        peak?.takeIf { it.tests > 0 }?.let { best ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("单日最多 ${best.tests} 次回忆", style = MaterialTheme.typography.labelLarge)
                    Text(best.day, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(if (windowDays > 40) 1.dp else 3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            window.forEach { day ->
                val fraction = day.tests.toFloat() / peakValue
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (day.tests == 0) 0.03f else fraction.coerceAtLeast(0.06f))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (day.tests == 0) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                }
            }
        }
        if (windowDays <= 40) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(window.first().day.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(window.last().day.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
}
