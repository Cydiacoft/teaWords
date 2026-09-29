package com.teameow.teawords.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teameow.teawords.algorithm.*
import com.teameow.teawords.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DAY_MS = 86_400_000L

/**
 * Learning entry point.
 *
 * Three things live here and nothing else: screening unfamiliar words out of the way,
 * one plan that merges every word actually worth doing today, and a knowledge snapshot that
 * never presents a self-report as a test result.
 */
@Composable
fun LearningScreen(
    dbHelper: DatabaseHelper,
    selectedBookIds: Set<String> = emptySet(),
    onSelectedBooksChange: (Set<String>) -> Unit = {},
    onShowStats: () -> Unit = {},
    onModeChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember(dbHelper) { LearningRepository(dbHelper) }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("learning", 0) }

    var books by remember { mutableStateOf(emptyList<com.teameow.teawords.data.search.WordSearchRepository.BookInfo>()) }
    var tags by remember { mutableStateOf(emptyMap<String, String>()) }
    var showBooks by remember { mutableStateOf(false) }
    var draftBooks by remember { mutableStateOf(selectedBookIds) }
    val appPrefs = remember { AppPreferences(context) }
    val strategy = LearningStrategy.valueOf(appPrefs.studyStrategy.id)
    val newCap = appPrefs.dailyNewCapOverride.takeIf { it > 0 } ?: strategy.dailyNewCap
    val retention = appPrefs.targetRetentionOverride.takeIf { it > 0 } ?: strategy.targetRetention
    val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    var newStudiedToday by remember { mutableIntStateOf(0) }
    val newBudget = (newCap - newStudiedToday).coerceAtLeast(0)
    var states by remember { mutableStateOf(emptyList<Knowledge>()) }
    var words by remember { mutableStateOf(emptyMap<String, VocabularyItem>()) }
    var mode by rememberSaveable { mutableStateOf("home") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var queue by remember { mutableStateOf(emptyList<Knowledge>()) }
    var position by remember { mutableIntStateOf(0) }
    var sessionCorrect by remember { mutableIntStateOf(0) }
    var selectedLimit by rememberSaveable { mutableStateOf(prefs.getInt("expressive_round_limit", 0).takeIf { it in listOf(10, 15, 20) }) }
    var quickMode by rememberSaveable { mutableStateOf(prefs.getBoolean("expressive_quick_mode", false)) }
    val limit = selectedLimit ?: 10
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var lastScreened by remember { mutableStateOf(emptyList<Knowledge>()) }
    var screeningTotal by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    var importText by rememberSaveable { mutableStateOf("") }

    suspend fun reload() {
        val result = withContext(Dispatchers.IO) {
            check(com.teameow.teawords.data.search.DictionaryGateway.open(context)) { "离线词典未就绪，请稍后重试" }
            val dictionary = com.teameow.teawords.data.search.DictionaryGateway.sourceOrNull()!!
            dictionary.books() to LearningCatalog(dbHelper, dictionary).load(selectedBookIds)
        }
        newStudiedToday = withContext(Dispatchers.IO) { repo.newWordsSince(dayStart) }
        books = result.first
        states = result.second.states
        words = result.second.words
        tags = result.second.tags
    }

    LaunchedEffect(selectedBookIds) {
        busy = true
        try { reload() } catch (e: Exception) { message = "读取失败：${e.message}" }
        finally { busy = false }
        mode = "home"
    }

    fun importWords(source: String = "用户导入", license: String = "用户提供，许可由用户确认", read: () -> String) {
        busy = true
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { repo.importWords(WordImport.parse(read()), source, license) }
                reload()
                screeningTotal = count
                lastScreened = emptyList()
                mode = "home"
                message = "新增 $count 个词，重复词保留原有学习记录"
                showImport = false
            } catch (e: Exception) {
                message = "导入失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWords {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buffer = CharArray(2_000_001)
                var size = 0
                while (size < buffer.size) {
                    val n = reader.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                require(size <= 2_000_000) { "文件超过 2 MB 文本限制" }
                String(buffer, 0, size)
            } ?: error("无法读取文件")
        }
    }

    BackHandler(mode != "home" && !busy) { mode = "home" }

    // The host keeps the floating dock visible on the learning pages; reporting the current mode
    // lets it withhold the dock only during a running round, where leaving would discard it.
    LaunchedEffect(mode) { onModeChange(mode) }
    DisposableEffect(Unit) { onDispose { onModeChange("home") } }

    // The diagnostic is a full page of its own; it owns its navigation and result screen.
    if (mode == "diagnose") {
        DiagnosticScreen(
            dbHelper = dbHelper,
            learningWords = words.keys, wordTags = tags,
            onBack = { mode = "home" },
            onFinished = { mode = "home" }
        )
        return
    }

    val now = System.currentTimeMillis()
    // Recomputed whenever the knowledge states or the round size change; never cached against a
    // frozen clock, otherwise a freshly screened word would not enter today's plan.
    val sampleDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
    val scheduledPlan = remember(states, limit, newBudget, mode, sampleDay) {
        LearningEngine.queue(states, now, limit, newBudget, prefs.getString("sample_day", null) != sampleDay)
    }
    val unseen = states.filter { it.report == SelfReport.UNSEEN && it.attempts == 0 }
    val dueCount = states.count { it.attempts > 0 && it.due <= now }
    val fuzzyCount = states.count { it.report == SelfReport.FUZZY && it.attempts == 0 }
    val selfKnownCount = states.count { it.report == SelfReport.KNOWN && it.attempts == 0 }
    val verifiedCount = states.count { it.verified }
    val learningCount = states.count { it.attempts > 0 && !it.verified }
    val unknownCount = states.count { it.report == SelfReport.UNKNOWN }
    val pendingCount = states.size - verifiedCount - learningCount - fuzzyCount - unknownCount

    // Ability estimate and word book tags drive an ability-scoped screening queue, so a 4500-word
    // book does not have to be judged by hand. See ScreeningPriority for the banding rules.
    var ability by remember { mutableStateOf(AbilityStore(context).load()) }
    LaunchedEffect(mode) { if (mode == "home") ability = AbilityStore(context).load() }
    val tagged = remember(tags) { tags.map { LearningRepository.TaggedWord(it.key, it.value) } }
    val screeningPlan: ScreeningPriority.Plan = remember(unseen, tagged, ability, strategy) {
        val difficultyByWord = tagged.associate { it.word to DifficultyPrior.forWord(it.word, it.tags).value }
        ScreeningPriority.plan(
            ability,
            unseen.map { ScreenCandidate(it.word, difficultyByWord[it.word] ?: 1.0) }, strategy
        )
    }
    // Only the uncertain band is worth asking about; the rest is skipped or learned directly.
    val screeningQueue = remember(screeningPlan, unseen) {
        val byWord = unseen.associateBy { it.word }
        screeningPlan.needsScreening.mapNotNull { byWord[it.first.word] }
    }

    val plan = remember(scheduledPlan, screeningPlan, screeningQueue, states, newBudget, limit) {
        val remainingNew = (newBudget - scheduledPlan.count { it.attempts == 0 }).coerceAtLeast(0)
        val included = scheduledPlan.map { it.word }.toSet()
        val byWord = states.associateBy { it.word }
        val fresh = (screeningPlan.learnDirectly.map { it.first.word } + screeningQueue.map { it.word })
            .distinct().filterNot { it in included }.take(remainingNew).mapNotNull { byWord[it] }
        (scheduledPlan + fresh).take(limit)
    }

    // How many words a round would actually start with. `plan` is only what the spaced-repetition
    // side already owed; when it is empty the ability estimate still names words worth learning
    // (that is what `onStart` queues). Showing `plan.size` alone reported "0 项建议" next to an
    // enabled 开始学习 button, which reads as a contradiction.
    val queueCount = if (plan.isNotEmpty()) plan.size else screeningPlan.learnDirectly.size

    // --- Spaced repetition state -----------------------------------------------------------------
    // Sense ids and stored memory state are needed to grade an answer with FSRS instead of a fixed
    // table. They are loaded once per round rather than per answer.
    val coordinator = remember { ReviewCoordinator() }
    val senses = remember(dbHelper) { SenseRepository(dbHelper) }
    var memoryByWord by remember { mutableStateOf(emptyMap<String, MemoryState>()) }
    // Resolved on first use and cached, so the round only pays for the words it actually reaches.
    val senseCache = remember { mutableMapOf<String, Pair<Long, Long>>() }
    fun senseOf(word: String): Pair<Long, Long> =
        senseCache.getOrPut(word) { senses.ensurePrimarySense(word) }
    LaunchedEffect(mode) {
        if (mode == "session") {
            busy = true
            try {
            memoryByWord = withContext(Dispatchers.IO) {
                queue.mapNotNull { item ->
                    val sense = senseOf(item.word)
                    senses.memoryFor(sense.second)?.let { record -> item.word to MemoryState(
                        difficulty = record.difficulty, stability = record.stability,
                        lastReview = record.lastReview, reps = record.reps, lapses = record.lapses)
                    }
                }.toMap()
            }
            } catch (e: Exception) { message = "准备学习失败：${e.message}"; mode = "home" }
            finally { busy = false }
        }
    }
    val difficultyByWord = remember(states, tagged) {
        tagged.associate { it.word to DifficultyPrior.forWord(it.word, it.tags).value }
    }

    /** At most one self-reported word per day is pulled into the plan as a spot check. */
    val today = remember(now) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(calendar.timeInMillis))
    }
    val dailySample = remember(plan, today) {
        if (prefs.getString("sample_day", null) == today) null
        else plan.firstOrNull { it.report == SelfReport.KNOWN && it.attempts == 0 && now - it.updated >= 7 * DAY_MS }
    }
    fun markScreened(batch: List<Knowledge>, report: SelfReport) {
        if (batch.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val stamp = System.currentTimeMillis()
                val updated = batch.map { it.copy(report = report, updated = stamp) }
                withContext(Dispatchers.IO) { updated.forEach { repo.save(it, "screen") } }
                val byWord = updated.associateBy { it.word }
                states = states.map { byWord[it.word] ?: it }
                lastScreened = batch
            } catch (e: Exception) {
                message = "保存失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun undoLast() {
        if (lastScreened.isEmpty()) return
        val restore = lastScreened
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { restore.forEach { repo.save(it, "undo") } }
                val byWord = restore.associateBy { it.word }
                states = states.map { byWord[it.word] ?: it }
                lastScreened = emptyList()
            } catch (e: Exception) {
                message = "撤销失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    TeaListPage(
        floatingActionButton = if (mode == "home") {{
            FloatingActionButton(onClick = { showProfile = true }, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(AppSymbols.Person, "词汇知识画像")
            }
        }} else null,
        title = when (mode) {
            "screen" -> "快速排雷"
            "session" -> "学习与复习"
            "diagnose" -> "词汇能力诊断"
            else -> "学习"
        },
        subtitle = when (mode) {
            "screen" -> "凭印象判断，自评不计作测试掌握"
            "session" -> "主动回忆，答对一次不代表掌握"
            "diagnose" -> "自适应选题，快速定位你的水平"
            else -> "把今天真正值得学的词集中在一处"
        },
        onBack = if (mode != "home") ({ mode = "home" }) else null,
        backEnabled = !busy,
        actions = {
            if (mode == "home") {
                IconButton(onClick = { mode = "diagnose" }, enabled = !busy) {
                    Icon(AppSymbols.Insights, "词汇能力诊断")
                }
            }
            IconButton(onClick = { showImport = true }, enabled = !busy) { Icon(AppSymbols.Add, "导入词库") }
            IconButton(onClick = onShowStats, enabled = !busy) { Icon(AppSymbols.BarChart, "学习统计") }
        }
    ) {
        if (busy && mode != "session") item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { note -> item { TeaCaption(note) } }

        when {
            mode == "screen" -> screeningMode(
                current = screeningQueue.firstOrNull(),
                upcoming = screeningQueue.take(10),
                screened = (screeningTotal - screeningQueue.size).coerceAtLeast(0),
                total = screeningTotal,
                busy = busy,
                lastScreened = lastScreened,
                abilityNote = ScreeningPriority.describe(ability, screeningPlan),
                skippedKnown = screeningPlan.skipAsKnown.size,
                directLearn = screeningPlan.learnDirectly.size,
                onRate = ::markScreened,
                onUndo = ::undoLast,
                onFinish = { mode = "home" }
            )

            mode == "session" -> sessionMode(
                queue = queue,
                position = position,
                correct = sessionCorrect,
                words = words,
                busy = busy,
                memory = memoryByWord,
                coordinator = coordinator,
                onAnswered = { state, correct, elapsed, revealed ->
                    busy = true
                    scope.launch {
                        try {
                            val now = System.currentTimeMillis()
                            val known = states.firstOrNull { it.word == state.word } ?: state
                            // FSRS + BKT + ability, graded together; the verdict is what gets stored.
                            val verdict = coordinator.grade(
                                belief = BeliefMapper.from(
                                    report = known.report.name,
                                    attempts = known.attempts,
                                    streak = known.streak,
                                    updatedAt = known.updated,
                                    now = now
                                ),
                                memory = memoryByWord[state.word] ?: MemoryState.unseen,
                                ability = ability,
                                difficulty = difficultyByWord[state.word] ?: 0.0,
                                correct = correct,
                                revealed = revealed,
                                elapsedMillis = elapsed,
                                now = now,
                                targetRetention = retention
                            )
                            val updated = withContext(Dispatchers.IO) {
                                val sense = senseOf(state.word)
                                repo.saveReview(known, verdict, sense.first, sense.second,
                                    correct, revealed, elapsed, now, ability.theta)
                            }
                            AbilityStore(context).save(verdict.ability)
                            if (known.attempts == 0) newStudiedToday++
                            ability = verdict.ability
                            memoryByWord = memoryByWord + (state.word to verdict.memory)
                            states = states.map { if (it.word == updated.word) updated else it }
                            if (correct) sessionCorrect++
                            position++
                            if (dailySample?.word == state.word) prefs.edit().putString("sample_day", today).apply()
                        } catch (e: Exception) {
                            message = "保存失败，请重试：${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                onFinish = { mode = "home" }
            )

            else -> homeMode(
                stateCount = states.size,
                plan = plan,
                queueCount = queueCount,
                queueAvailable = plan.isNotEmpty(),
                unseenCount = unseen.size,
                dueCount = dueCount,
                fuzzyCount = fuzzyCount,
                selfKnownCount = selfKnownCount,
                verifiedCount = verifiedCount,
                learningCount = learningCount,
                unknown = unknownCount,
                pending = pendingCount,
                limit = limit,
                selectedLimit = selectedLimit,
                quickMode = quickMode,
                onQuickModeChange = { value -> quickMode = value; prefs.edit().putBoolean("expressive_quick_mode", value).apply() },
                bookTitle = books.filter { it.id in selectedBookIds }.joinToString("、") { it.name }.ifBlank { "个人词库" },
                onChooseBooks = { draftBooks = selectedBookIds; showBooks = true },
                learnDirectlyAvailable = screeningPlan.learnDirectly.size,
                busy = busy,
                dailySample = dailySample,
                onLimitChange = { value ->
                    selectedLimit = value
                    prefs.edit().putInt("expressive_round_limit", value).apply()
                },
                onStart = {
                    // Study must never wait for screening to finish. When the spaced-repetition plan
                    // is empty, the ability estimate still tells us which words are probably unknown,
                    // so those are used directly — that is the whole point of the diagnostic.
                    queue = plan
                    position = 0
                    sessionCorrect = 0
                    message = when {
                        queue.isEmpty() -> "本轮暂无任务：可选择其他词书、调整每日新词上限，或等待复习到期。"
                        plan.isEmpty() -> "按能力估计直接开始：这 ${queue.size} 个词预测为尚未掌握。"
                        else -> null
                    }
                    if (queue.isNotEmpty()) mode = "session"
                },
                onScreen = {
                    // The round is scoped to the queue the ability estimate actually asks about.
                    screeningTotal = screeningQueue.size
                    lastScreened = emptyList()
                    message = null
                    mode = "screen"
                },
                onImport = { showImport = true },
                onShowStats = onShowStats
            )
        }
    }

    if (showProfile) AlertDialog(onDismissRequest = { showProfile = false }, title = { Text("词汇知识画像") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TeaProgressRow("已验证", verifiedCount, states.size)
            TeaProgressRow("学习中", learningCount, states.size)
            TeaProgressRow("模糊", fuzzyCount, states.size)
            TeaProgressRow("陌生", unknownCount, states.size)
            TeaCaption("自评与测试证据分别保存。同一单词在不同词书、口音下共用学习进度。")
        }
    }, confirmButton = { TextButton(onClick = { showProfile = false; onShowStats() }) { Text("查看统计") } },
        dismissButton = { TextButton(onClick = { showProfile = false }) { Text("关闭") } })

    if (showBooks) AlertDialog(onDismissRequest = { showBooks = false }, title = { Text("选择学习词书") }, text = {
        androidx.compose.foundation.lazy.LazyColumn {
            items(books, key = { it.id }) { book ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(book.id in draftBooks, onCheckedChange = { checked -> draftBooks = if (checked) draftBooks + book.id else draftBooks - book.id })
                    Text("${book.name} · ${book.wordCount}")
                }
            }
            item { TeaCaption("词书来自 ECDICT 考试标签，不代表官方完整大纲。取消选择保留学习记录。") }
        }
    }, confirmButton = { TextButton(onClick = { onSelectedBooksChange(draftBooks); showBooks = false }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { showBooks = false }) { Text("取消") } })
    if (showImport) AlertDialog(
        onDismissRequest = { if (!busy) showImport = false },
        title = { Text("添加个人词库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("使用你有权使用的词库。支持 CSV / JSON / TXT；每行“单词,释义”，JSON 使用 word 和 definition 字段。相同词条不会覆盖已有学习记录。")
                TextButton(enabled = !busy, onClick = { showImport = false; draftBooks = selectedBookIds; showBooks = true }) { Text("选择内置词书（含雅思、托福）") }
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("粘贴词条") },
                    minLines = 3,
                    maxLines = 5
                )
                TextButton(enabled = !busy, onClick = {
                    picker.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                }) { Text("选择文件") }
            }
        },
        confirmButton = {
            Button(
                enabled = importText.isNotBlank() && !busy,
                onClick = { importWords { importText } }
            ) { Text("导入词库") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { showImport = false }) { Text("取消") } }
    )
}

// --- Screening: one word, one tap, undo always available -------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.screeningMode(
    current: Knowledge?,
    upcoming: List<Knowledge>,
    screened: Int,
    total: Int,
    busy: Boolean,
    lastScreened: List<Knowledge>,
    abilityNote: String,
    skippedKnown: Int,
    directLearn: Int,
    onRate: (List<Knowledge>, SelfReport) -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit
) {
    // The plan header always shows what the ability estimate is doing, even when nothing is left to
    // ask: "0 words to judge, 4200 skipped" is the product working, not an empty screen.
    item {
        LearningCard("排雷范围", "只问真正需要你判断的词") {
            Text(abilityNote, style = MaterialTheme.typography.bodyMedium)
            if (skippedKnown + directLearn > 0) {
                TeaMetricRow(
                    "$skippedKnown" to "预测已认识 · 跳过",
                    "$directLearn" to "预测陌生 · 直接学",
                    "${upcoming.size + screened}" to "需要你判断"
                )
                TeaCaption("预测来自能力估计与词书先验，不是测试证据；被跳过的词仍会按抽样规则偶尔复核。")
            }
        }
    }
    if (current == null) {
        item {
            TeaCard {
                TeaEmptyState(
                    "排雷完成",
                    if (total == 0) "先导入词库，再从已知的词开始。" else "没有需要手动判断的词了，可以直接开始今日学习。"
                )
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看今日计划") }
            }
        }
        return
    }
    item {
        TeaCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            TeaScreenProgress(
                done = screened + 1,
                total = total.coerceAtLeast(upcoming.size),
                modifier = Modifier.fillMaxWidth()
            )
            // Fixed height on purpose. Animating the word inside a size-changing container would
            // re-measure this list item on every tap and shove the whole page up and down, which
            // reads as flicker in the app bar and counters. Scaling a fixed box keeps the page still.
            Box(
                Modifier.fillMaxWidth().height(132.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = current.word,
                    transitionSpec = {
                        (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.96f)) togetherWith
                            fadeOut(tween(90))
                    },
                    label = "screening-word"
                ) { word ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(word, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 1)
                        Text("先凭印象判断，无需逐个背诵释义", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DefaultPronunciationButton(current.word)
            Button(
                onClick = { onRate(listOf(current), SelfReport.KNOWN) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) { Text("完全认识 · 跳过") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { onRate(listOf(current), SelfReport.FUZZY) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("有些模糊") }
                OutlinedButton(
                    onClick = { onRate(listOf(current), SelfReport.UNKNOWN) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("完全陌生") }
            }
            // Screening a long wordbook one tap at a time is the expensive part, so a run of
            // words the user already knows leaves the queue in a single action (undoable).
            if (upcoming.size > 3) {
                OutlinedButton(
                    onClick = { onRate(upcoming, SelfReport.KNOWN) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("接下来的 ${upcoming.size} 个也认识") }
            }
            // Labels stay single-line and the undo button is capped, so a long word can never widen
            // it enough to wrap this row and shift everything below.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onUndo,
                    enabled = !busy && lastScreened.isNotEmpty(),
                    modifier = Modifier.widthIn(max = 220.dp)
                ) {
                    Text(
                        when {
                            lastScreened.isEmpty() -> "撤销上一项"
                            lastScreened.size == 1 -> "撤销：${lastScreened.first().word}"
                            else -> "撤销这 ${lastScreened.size} 个"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onFinish, enabled = !busy) {
                    Text("先到这里", maxLines = 1)
                }
            }
            TeaCaption("认识或模糊的词会暂离新词队列，模糊词优先复习。自评不是测试证据。")
        }
    }
}

// --- Today's plan ----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.homeMode(
    stateCount: Int,
    plan: List<Knowledge>,
    queueCount: Int,
    queueAvailable: Boolean,
    unseenCount: Int,
    dueCount: Int,
    fuzzyCount: Int,
    selfKnownCount: Int,
    verifiedCount: Int,
    learningCount: Int,
    unknown: Int,
    pending: Int,
    limit: Int,
    selectedLimit: Int?,
    quickMode: Boolean,
    onQuickModeChange: (Boolean) -> Unit,
    bookTitle: String, onChooseBooks: () -> Unit,
    learnDirectlyAvailable: Int,
    busy: Boolean,
    dailySample: Knowledge?,
    onLimitChange: (Int) -> Unit,
    onStart: () -> Unit,
    onScreen: () -> Unit,
    onImport: () -> Unit,
    onShowStats: () -> Unit
) {
    item {
        ExpressiveLearningPlan(dueCount, fuzzyCount, unseenCount, busy,
            if (stateCount == 0) onImport else if (quickMode) onScreen else onStart)
    }
    item {
        OutlinedButton(onClick = onChooseBooks, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("当前词书 · $bookTitle", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    item { LearningControls(selectedLimit, onLimitChange, quickMode, onQuickModeChange) }
    item {
        OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("添加词库")
        }
    }
    if (stateCount == 0) item { TeaEmptyState("从一本词书开始", "选择词书，或导入你有权使用的个人词库") }
    else if (!queueAvailable && !quickMode) item { TeaCaption("今日暂无到期任务。学习记录已保存，可调整词书或稍后再来。") }
}

// --- Recall session --------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.sessionMode(
    queue: List<Knowledge>,
    position: Int,
    correct: Int,
    words: Map<String, VocabularyItem>,
    busy: Boolean,
    memory: Map<String, MemoryState>,
    coordinator: ReviewCoordinator,
    onAnswered: (Knowledge, Boolean, Long, Boolean) -> Unit,
    onFinish: () -> Unit
) {
    val current = queue.getOrNull(position)
    if (current == null) {
        item {
            val accuracy = if (position == 0) 0 else correct * 100 / position
            TeaCard {
                Text("本轮完成", style = MaterialTheme.typography.headlineSmall)
                TeaMetricRow(
                    "$position" to "完成题数",
                    "$correct" to "回忆正确",
                    if (position == 0) "—" to "正确率" else "$accuracy%" to "正确率"
                )
                TeaCaption("每次回答都已保存，下次按复习时间继续。答对一次只证明这一次的主动回忆。")
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("回到今日计划") }
            }
        }
        return
    }
    item(key = "recall-${current.word}") {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RecallCard(
            state = current,
            definition = words[current.word]?.definition.orEmpty(),
            index = position + 1,
            total = queue.size,
            busy = busy,
            // The scheduler explains why this word is here: real retrievability, not a fixed table.
            scheduleNote = memory[current.word]?.let { coordinator.reviewReason(it, System.currentTimeMillis()) }.orEmpty(),
            onComplete = { isCorrect, elapsed, revealed -> onAnswered(current, isCorrect, elapsed, revealed) }
        )
    }
}

@Composable
internal fun RecallCard(
    state: Knowledge,
    definition: String,
    index: Int,
    total: Int,
    busy: Boolean,
    scheduleNote: String,
    previewFirst: Boolean = state.attempts == 0 && state.report != SelfReport.KNOWN,
    onComplete: (Boolean, Long, Boolean) -> Unit
) {
    var answer by rememberSaveable(state.word) { mutableStateOf("") }
    var result by rememberSaveable(state.word) { mutableStateOf<Boolean?>(null) }
    var teaching by rememberSaveable(state.word) { mutableStateOf(previewFirst) }
    /** Whether the user gave up and looked at the answer; it changes how the answer is graded. */
    var revealed by rememberSaveable(state.word) { mutableStateOf(false) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val activeTime = remember(state.word) { longArrayOf(0, 0) }
    DisposableEffect(state.word, lifecycle) {
        fun pause() {
            if (activeTime[1] != 0L) activeTime[0] += android.os.SystemClock.elapsedRealtime() - activeTime[1]
            activeTime[1] = 0
        }
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            activeTime[1] = android.os.SystemClock.elapsedRealtime()
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) activeTime[1] = android.os.SystemClock.elapsedRealtime()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) pause()
        }
        lifecycle.addObserver(observer)
        onDispose { pause(); lifecycle.removeObserver(observer) }
    }
    fun timeSpent() = activeTime[0] + if (activeTime[1] == 0L) 0 else android.os.SystemClock.elapsedRealtime() - activeTime[1]
    var elapsed by rememberSaveable(state.word) { mutableLongStateOf(0) }

    LearningCard(
        title = "$index / $total",
        subtitle = if (scheduleNote.isNotBlank()) scheduleNote
        else LearningEngine.reason(state, System.currentTimeMillis()).orEmpty()
    ) {
        when {
            teaching -> {
                Text(state.word, style = MaterialTheme.typography.headlineMedium)
                DefaultPronunciationButton(state.word)
                if (definition.isNotBlank()) Text(definition, style = MaterialTheme.typography.bodyLarge)
                TeaCaption("先建立一次词义联系，再试着主动回忆。")
                Button(onClick = { teaching = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("记住了，试试回忆")
                }
            }
            result == null -> {
                Text(definition.ifBlank { state.word }, style = MaterialTheme.typography.titleLarge)
                TeaCaption("根据所示释义，输入对应英文词。仅验证本条释义的主动回忆。")
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    singleLine = true,
                    label = { Text("英文单词") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = answer.isNotBlank() && !busy,
                    onClick = {
                        result = answer.trim().equals(state.word, ignoreCase = true)
                        revealed = previewFirst
                        elapsed = timeSpent()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("检查回答") }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        // Looking at the answer is recorded as such: it is practice, not evidence, so
                        // the memory model treats it as a lapse while mastery stays untouched.
                        result = false
                        revealed = true
                        elapsed = timeSpent()
                    }
                ) { Text("想不起来，查看答案") }
            }
            else -> {
                Text(
                    if (result == true) "回忆正确" else "再建立一次联系",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (result == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(state.word, style = MaterialTheme.typography.headlineMedium)
                DefaultPronunciationButton(state.word)
                TeaCaption(
                    when {
                        revealed -> "本次看过答案，作为练习记录，不计作独立回忆通过。"
                        result == true -> "答对一次不代表完全掌握，之后会按记忆模型安排复习。"
                        else -> "稍后再复习这个词，本轮先继续。"
                    }
                )
                Button(
                    enabled = !busy,
                    onClick = { onComplete(result == true, elapsed, revealed) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("保存并继续") }
            }
        }
        Text(
            "已测试 ${state.attempts} 次 · 连续答对 ${state.streak} 次",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
