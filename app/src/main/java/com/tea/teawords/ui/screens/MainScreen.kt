package com.tea.teawords.ui.screens

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tea.teawords.R
import com.tea.teawords.data.*
import com.tea.teawords.ui.StatsDashboard

enum class AppTab(val title: String) {
    TRANSLATE("翻译"),
    HISTORY("回望"),
    REVIEW("学习")
}

enum class SettingsPage {
    Main,
    Interface,
    Pronunciation,
    Wordbooks,
    About
}

data class BingWallpaper(val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    dbHelper: DatabaseHelper,
    levelProvider: WordLevelProvider,
    api: DictionaryApi
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var currentTab by remember { mutableStateOf(AppTab.TRANSLATE) }
    var pronunciationDialect by remember { mutableStateOf(preferences.pronunciationDialect) }
    var selectedWordbookIds by remember { mutableStateOf(preferences.selectedWordbookIds) }
    var homeTitle by remember { mutableStateOf(preferences.homeTitle) }
    var homeSubtitle by remember { mutableStateOf(preferences.homeSubtitle) }
    var homeSubtitleMode by remember { mutableStateOf(preferences.homeSubtitleMode) }
    var homeLastClearedTimestamp by remember { mutableLongStateOf(preferences.homeLastClearedTimestamp) }
    var hitokotoRefreshInterval by remember { mutableIntStateOf(preferences.hitokotoRefreshInterval) }
    var bingWallpaperEnabled by remember { mutableStateOf(preferences.bingWallpaperEnabled) }
    var predictiveBackEnabled by remember { mutableStateOf(preferences.predictiveBackEnabled) }
    
    // Initialize ClozeGenerator for review feature
    val clozeGenerator = remember { ClozeGenerator(api) }

    // Hitokoto Auto-refresh
    LaunchedEffect(homeSubtitleMode, hitokotoRefreshInterval) {
        if (homeSubtitleMode == SubtitleMode.HITOKOTO) {
            while (true) {
                try {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url("https://v1.hitokoto.cn/?encode=text&c=i").build()
                        val response = client.newCall(request).execute()
                        val quote = response.body?.string()
                        if (!quote.isNullOrEmpty()) {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                homeSubtitle = quote
                                preferences.homeSubtitle = quote
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainScreen", "Failed to auto-fetch hitokoto", e)
                }
                kotlinx.coroutines.delay(hitokotoRefreshInterval * 60 * 1000L)
            }
        }
    }

    // Search / Translation State
    var searchQuery by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<WordResponse?>(null) }
    var translatedText by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var isLookupExecuted by remember { mutableStateOf(false) }
    var returnTabAfterResult by remember { mutableStateOf(AppTab.TRANSLATE) }
    var isStarred by remember { mutableStateOf(false) }
    var wordLevels by remember { mutableStateOf<List<String>>(emptyList()) }

    // History and Vocabulary States
    var historyList by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var vocabularyList by remember { mutableStateOf(emptyList<VocabularyItem>()) }
    
    // Review State
    var reviewSession by remember { mutableStateOf<ReviewSession?>(null) }

    // Navigation sub-states
    var showStats by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Offline Suggestions
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var isInputFocused by remember { mutableStateOf(false) }
    var bingWallpaperUrl by remember { mutableStateOf<String?>(null) }

    // Fetch Bing Wallpaper
    LaunchedEffect(bingWallpaperEnabled) {
        if (bingWallpaperEnabled) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder()
                        .url("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1")
                        .build()
                    val response = client.newCall(request).execute()
                    val json = response.body?.string()
                    if (json != null) {
                        val jsonObj = com.google.gson.JsonParser.parseString(json).asJsonObject
                        val images = jsonObj.getAsJsonArray("images")
                        if (images != null && images.size() > 0) {
                            val url = "https://www.bing.com" + images[0].asJsonObject.get("url").asString
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                bingWallpaperUrl = url
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainScreen", "Failed to fetch Bing wallpaper", e)
                }
            }
        } else {
            bingWallpaperUrl = null
        }
    }

    // Load Lists
    LaunchedEffect(currentTab, isLookupExecuted) {
        historyList = dbHelper.getHistory()
        vocabularyList = dbHelper.getVocabulary()
    }

    // Suggestions logic
    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length >= 2) {
            val q = searchQuery.trim().lowercase()
            suggestions = levelProvider.searchPrefix(q, limit = 8)
        } else {
            suggestions = emptyList()
        }
    }

    val triggerSearch: (String, Boolean) -> Unit = { queryText, addToHistory ->
        val q = queryText.trim()
        if (q.isNotEmpty()) {
            isSearching = true
            isLookupExecuted = true
            searchQuery = q
            // Check levels offline
            wordLevels = levelProvider.getLevels(q)

            val isSentence = q.contains(" ") || q.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }
            if (isSentence) {
                // Directly translate
                api.translate(q) { translation ->
                    isSearching = false
                    searchResult = null
                    translatedText = translation ?: "翻译失败，请检查网络设置"
                    // Add to history if required
                    if (addToHistory) {
                        dbHelper.addHistory(q, translatedText ?: "")
                    }
                }
            } else {
                // Lookup in dictionary
                api.lookupWord(q) { result ->
                    if (result != null) {
                        isSearching = false
                        searchResult = result
                        translatedText = null
                        isStarred = dbHelper.isInVocabulary(q)

                        if (addToHistory) {
                            // Format definition for history snippet
                            val firstDefinition = result.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition ?: ""
                            val firstTranslation = result.meanings?.firstOrNull()?.definitions?.firstOrNull()?.example ?: ""
                            val snippet = if (firstDefinition.isNotEmpty()) firstDefinition else firstTranslation
                            dbHelper.addHistory(q, snippet)
                        }

                        val firstDefinition = result.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition ?: ""
                        if (firstDefinition.isNotEmpty()) {
                            api.translate(firstDefinition) { chineseDefinition ->
                                translatedText = chineseDefinition
                            }
                        }
                    } else {
                        // Fallback to translation API if dictionary lookup fails
                        api.translate(q) { translation ->
                            isSearching = false
                            searchResult = null
                            translatedText = translation ?: "未找到释义"
                            isStarred = dbHelper.isInVocabulary(q)
                            if (addToHistory) {
                                dbHelper.addHistory(q, translatedText ?: "")
                            }
                        }
                    }
                }
            }
        }
    }

    val focusManager = LocalFocusManager.current

    // Handle system back button with logic priority
    BackHandler(enabled = showStats || showSettings || reviewSession != null || isLookupExecuted || currentTab != AppTab.TRANSLATE || isInputFocused) {
        when {
            showStats -> showStats = false
            showSettings -> showSettings = false
            reviewSession != null -> reviewSession = null
            isLookupExecuted -> {
                isLookupExecuted = false
                searchQuery = ""
                searchResult = null
                translatedText = null
                currentTab = returnTabAfterResult
            }
            isInputFocused -> {
                focusManager.clearFocus()
                isInputFocused = false
            }
            currentTab != AppTab.TRANSLATE -> currentTab = AppTab.TRANSLATE
        }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !showStats && !showSettings,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.1f),
                                RoundedCornerShape(18.dp)
                            )
                    ) {
                        TeaNavItem(currentTab, AppTab.TRANSLATE, Icons.Default.Search) { 
                            currentTab = AppTab.TRANSLATE 
                            showStats = false
                            showSettings = false
                        }
                        TeaNavItem(currentTab, AppTab.HISTORY, Icons.Default.History) {
                            currentTab = AppTab.HISTORY 
                            showStats = false
                            showSettings = false
                        }
                        TeaNavItem(currentTab, AppTab.REVIEW, Icons.Default.AutoStories) { 
                            currentTab = AppTab.REVIEW 
                            showStats = false
                            showSettings = false
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        // Main Content
        AnimatedContent(
            targetState = if (showStats) "stats" else if (showSettings) "settings" else currentTab.name,
            transitionSpec = {
                if (targetState == "stats" || targetState == "settings" || initialState == "stats" || initialState == "settings") {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                } else {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                }
            },
            label = "MainContentTransition"
        ) { targetStateString ->
            val isWallpaperVisible = targetStateString == AppTab.TRANSLATE.name && !isLookupExecuted && bingWallpaperUrl != null && bingWallpaperEnabled
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                when (targetStateString) {
                    "stats" -> StatsDashboard(dbHelper = dbHelper, onBack = { showStats = false })
                    "settings" -> SettingsView(
                        pronunciationDialect = pronunciationDialect,
                        selectedWordbookIds = selectedWordbookIds,
                        homeTitle = homeTitle,
                        homeSubtitle = homeSubtitle,
                        homeSubtitleMode = homeSubtitleMode,
                        bingWallpaperEnabled = bingWallpaperEnabled,
                        api = api,
                        onPronunciationDialectChange = { dialect ->
                            pronunciationDialect = dialect
                            preferences.pronunciationDialect = dialect
                        },
                        onSelectedWordbooksChange = { ids ->
                            selectedWordbookIds = ids
                            preferences.selectedWordbookIds = ids
                        },
                        onHomeTitleChange = { title ->
                            homeTitle = title
                            preferences.homeTitle = title
                        },
                        onHomeSubtitleChange = { subtitle ->
                            homeSubtitle = subtitle
                            preferences.homeSubtitle = subtitle
                        },
                        onHomeSubtitleModeChange = { mode ->
                            homeSubtitleMode = mode
                            preferences.homeSubtitleMode = mode
                        },
                        onBingWallpaperEnabledChange = { enabled ->
                            bingWallpaperEnabled = enabled
                            preferences.bingWallpaperEnabled = enabled
                        },
                        predictiveBackEnabled = predictiveBackEnabled,
                        hitokotoRefreshInterval = hitokotoRefreshInterval,
                        onPredictiveBackEnabledChange = { enabled ->
                            predictiveBackEnabled = enabled
                            preferences.predictiveBackEnabled = enabled
                        },
                        onHitokotoRefreshIntervalChange = { interval ->
                            hitokotoRefreshInterval = interval
                            preferences.hitokotoRefreshInterval = interval
                        },
                        onBack = { showSettings = false }
                    )
                    AppTab.TRANSLATE.name -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!isLookupExecuted) {
                                HomeView(
                                    searchQuery = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    onSearch = { triggerSearch(searchQuery, true) },
                                    suggestions = suggestions,
                                    isInputFocused = isInputFocused,
                                    onFocusChange = { isInputFocused = it },
                                    historyList = historyList.filter { it.timestamp > homeLastClearedTimestamp }.take(5),
                                    homeTitle = homeTitle,
                                    homeSubtitle = homeSubtitle,
                                    onHistoryClick = { word ->
                                        returnTabAfterResult = AppTab.TRANSLATE
                                        searchQuery = word
                                        triggerSearch(word, false)
                                    },
                                    onClearHistory = {
                                        val now = System.currentTimeMillis()
                                        homeLastClearedTimestamp = now
                                        preferences.homeLastClearedTimestamp = now
                                    },
                                    onMenuClick = { showSettings = true },
                                    onPersonClick = { showStats = true },
                                    wallpaperUrl = bingWallpaperUrl
                                )
                            } else {
                                ResultView(
                                    query = searchQuery,
                                    isSearching = isSearching,
                                    result = searchResult,
                                    translation = translatedText,
                                    levels = wordLevels,
                                    pronunciationDialect = pronunciationDialect,
                                    isStarred = isStarred,
                                    onStarToggle = {
                                        if (isStarred) {
                                            dbHelper.removeVocabulary(searchQuery)
                                            isStarred = false
                                        } else {
                                            val defSnippet = searchResult?.meanings?.firstOrNull()?.definitions?.firstOrNull()?.definition 
                                                ?: translatedText ?: ""
                                            val phonetic = searchResult?.phonetic ?: ""
                                            dbHelper.addVocabulary(searchQuery, phonetic, defSnippet)
                                            isStarred = true
                                        }
                                    },
                                    onBack = {
                                        isLookupExecuted = false
                                        searchQuery = ""
                                        searchResult = null
                                        translatedText = null
                                        currentTab = returnTabAfterResult
                                        returnTabAfterResult = AppTab.TRANSLATE
                                    }
                                )
                            }
                        }
                    }
                    AppTab.HISTORY.name -> {
                        RecordsView(
                            historyList = historyList,
                            vocabularyList = vocabularyList,
                            onItemClick = { word ->
                                returnTabAfterResult = AppTab.HISTORY
                                currentTab = AppTab.TRANSLATE
                                searchQuery = word
                                triggerSearch(word, false)
                            },
                            onClearHistory = {
                                dbHelper.clearHistory()
                                historyList = emptyList()
                            },
                            onDeleteHistory = { word ->
                                dbHelper.deleteHistoryItem(word)
                                historyList = dbHelper.getHistory()
                            },
                            onDeleteVocab = { word ->
                                dbHelper.removeVocabulary(word)
                                vocabularyList = dbHelper.getVocabulary()
                            }
                        )
                    }
                    AppTab.REVIEW.name -> {
                        ReviewView(
                            historyList = historyList,
                            selectedWordbookIds = selectedWordbookIds,
                            levelProvider = levelProvider,
                            reviewSession = reviewSession,
                            clozeGenerator = clozeGenerator,
                            dbHelper = dbHelper,
                            onStartReview = { problems ->
                                reviewSession = ReviewSession(
                                    vocabItems = problems.map { problem ->
                                        VocabularyItem(
                                            word = problem.originalWord,
                                            phonetic = null,
                                            definition = problem.sentence,
                                            timestamp = System.currentTimeMillis()
                                        )
                                    },
                                    problems = problems,
                                    difficulty = if (problems.isEmpty()) {
                                        2
                                    } else {
                                        problems.map { it.difficulty }.average().toInt().coerceIn(1, 5)
                                    }
                                )
                            },
                            onCancelReview = { reviewSession = null },
                            onAnswerSubmit = { answer ->
                                reviewSession?.let { session ->
                                    // Update database stats
                                    val word = session.currentProblem?.originalWord ?: ""
                                    if (word.isNotEmpty()) {
                                        dbHelper.updateVocabStats(word, answer.isCorrect)
                                        if (!answer.isCorrect) {
                                            dbHelper.recordError(ErrorRecord(
                                                problemId = answer.problemId,
                                                word = word,
                                                userAnswer = answer.userAnswer,
                                                correctAnswer = session.currentProblem?.clozeWord ?: "",
                                                lastAttemptTime = System.currentTimeMillis()
                                            ))
                                        }
                                    }

                                    reviewSession = session.copy(
                                        answers = session.answers + answer,
                                        currentProblemIndex = session.currentProblemIndex + 1
                                    )
                                }
                            },
                            onReviewComplete = {
                                reviewSession?.let { session ->
                                    val timeSpent = (System.currentTimeMillis() - session.startTime) / 1000
                                    val accuracy = if (session.problems.isNotEmpty()) 
                                        (session.correctCount.toFloat() / session.problems.size) * 100 else 0f
                                    
                                    dbHelper.recordReviewSession(ReviewSessionRecord(
                                        sessionId = session.sessionId,
                                        totalProblems = session.problems.size,
                                        correctCount = session.correctCount,
                                        accuracy = accuracy,
                                        timeSpentSeconds = timeSpent,
                                        averageTimePerProblem = if (session.problems.isNotEmpty()) timeSpent.toFloat() / session.problems.size else 0f,
                                        difficulty = session.difficulty
                                    ))
                                }
                                reviewSession = null
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TeaNavItem(
    currentTab: AppTab,
    tab: AppTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = currentTab == tab,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = tab.title) },
        label = { Text(tab.title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.secondary,
            selectedTextColor = MaterialTheme.colorScheme.secondary,
            indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
            unselectedIconColor = MaterialTheme.colorScheme.outline,
            unselectedTextColor = MaterialTheme.colorScheme.outline
        )
    )
}

