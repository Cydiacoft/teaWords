package com.teameow.teawords.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.teameow.teawords.algorithm.*
import com.teameow.teawords.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A round scoped to queried words, independent of book selection and the new-word budget. */
@Composable
fun LookupReviewScreen(helper: DatabaseHelper, entries: List<LocalEntry>, onBack: () -> Unit, onBusyChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val repository = remember(helper) { LearningRepository(helper) }
    val dictionary = remember(helper) { LocalDictionary(helper) }
    val senses = remember(helper) { SenseRepository(helper) }
    val coordinator = remember { ReviewCoordinator() }
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf(emptyList<Knowledge>()) }
    var position by remember { mutableIntStateOf(0) }
    var correctCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { onBusyChange(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }
    var error by remember { mutableStateOf<String?>(null) }
    val store = remember { AbilityStore(context) }
    var ability by remember { mutableStateOf(store.load()) }
    val byWord = remember(entries) { entries.associateBy { LexicalText.normalize(it.word) } }
    val preferences = remember { AppPreferences(context) }
    val retention = preferences.targetRetentionOverride.takeIf { it > 0 }
        ?: LearningStrategy.valueOf(preferences.studyStrategy.id).targetRetention

    LaunchedEffect(entries) {
        try {
            val limit = context.getSharedPreferences("learning", 0).getInt("expressive_round_limit", 10)
                .takeIf { it in listOf(10, 15, 20) } ?: 10
            queue = withContext(Dispatchers.IO) {
                LookupReviewRepository(helper, dictionary).queue(entries, System.currentTimeMillis(), limit)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "读取生词失败：${e.message}" }
        finally { loading = false }
    }

    BackHandler { if (!busy) onBack() }
    TeaListPage(title = "生词复习", subtitle = "巩固查过的单词", onBack = onBack, backEnabled = !busy) {
        if (loading || busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { TeaCaption(it) } }
        val current = queue.getOrNull(position)
        when {
            loading -> Unit
            current == null -> item {
                TeaCard {
                    Text(if (position == 0) "暂无待复习的生词" else "本轮复习完成", style = MaterialTheme.typography.headlineSmall)
                    if (position > 0) TeaMetricRow("$position" to "复习单词", "$correctCount" to "独立回忆正确")
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("回到回望") }
                }
            }
            else -> item(key = current.word) {
                val entry = byWord.getValue(current.word)
                RecallCard(
                    state = current, definition = entry.zh.ifBlank { entry.en }.replace("\\n", "\n"),
                    index = position + 1, total = queue.size, busy = busy,
                    scheduleNote = "查过的生词", previewFirst = false,
                    onComplete = { correct, elapsed, revealed ->
                        if (!busy) {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val now = System.currentTimeMillis()
                                    val verdict = withContext(Dispatchers.IO) {
                                        val known = dictionary.knowledge(current.word)
                                        val (wordId, senseId) = senses.ensurePrimarySense(current.word)
                                        val stored = senses.memoryFor(senseId)
                                        val memory = stored?.let {
                                            MemoryState(it.difficulty, it.stability, it.lastReview, it.reps, it.lapses)
                                        } ?: MemoryState.unseen
                                        val grade = coordinator.grade(
                                            BeliefMapper.from(known.report.name, known.attempts, known.streak, known.updated, now),
                                            memory, ability, DifficultyPrior.forWord(entry.word, entry.tags).value,
                                            correct, revealed, elapsed, now, retention
                                        )
                                        repository.saveReview(known, grade, wordId, senseId, correct, revealed, elapsed, now, ability.theta)
                                        grade
                                    }
                                    store.save(verdict.ability)
                                    ability = verdict.ability
                                    if (correct && !revealed) correctCount++
                                    position++
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = "保存失败，请重试：${e.message}" }
                                finally { busy = false }
                            }
                        }
                    }
                )
            }
        }
    }
}
