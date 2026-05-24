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
                    val isWallpaperVisible = currentTab == AppTab.TRANSLATE && !isLookupExecuted && bingWallpaperUrl != null
                    
                    NavigationBar(
                        containerColor = if (isWallpaperVisible) MaterialTheme.colorScheme.surface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                1.dp,
                                if (isWallpaperVisible) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
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
                    .padding(bottom = if (targetStateString != "stats" && targetStateString != "settings" && !isWallpaperVisible) innerPadding.calculateBottomPadding() else 0.dp)
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
                        hitokotoRefreshInterval = hitokotoRefreshInterval,
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
                                    session.answers.add(answer)
                                    
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

// --- SCREEN COMPONENTS ---

@Composable
fun HomeView(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    suggestions: List<String>,
    isInputFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    historyList: List<HistoryItem>,
    homeTitle: String,
    homeSubtitle: String,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onMenuClick: () -> Unit,
    onPersonClick: () -> Unit,
    wallpaperUrl: String? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val animatedScale by animateFloatAsState(
        targetValue = if (isInputFocused) 1.01f else 1f,
        animationSpec = tween(200)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Wallpaper Background
        if (wallpaperUrl != null) {
            AsyncImage(
                model = wallpaperUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Scrim to make text readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            )
        }

        val primaryTextColor = if (wallpaperUrl != null) Color.White else MaterialTheme.colorScheme.primary
        val secondaryTextColor = if (wallpaperUrl != null) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Top Header (Fixed at top)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = if (wallpaperUrl != null) Color.White else MaterialTheme.colorScheme.outline)
            }
            Text(
                text = "teawords",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    letterSpacing = 0.05.sp
                ),
                color = if (wallpaperUrl != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
            IconButton(onClick = onPersonClick) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = if (wallpaperUrl != null) Color.White else MaterialTheme.colorScheme.outline)
            }
        }

        // Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val contentSpacing by animateDpAsState(
                targetValue = if (isInputFocused) 32.dp else 80.dp,
                animationSpec = tween(400)
            )
            Spacer(modifier = Modifier.height(contentSpacing))

            AnimatedVisibility(
                visible = !isInputFocused || searchQuery.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = homeTitle,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 36.sp
                        ),
                        color = primaryTextColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = homeSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            // Search Bar Input Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(if (wallpaperUrl != null) MaterialTheme.colorScheme.surface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        if (isInputFocused) (if (wallpaperUrl != null) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        else (if (wallpaperUrl != null) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        MaterialTheme.shapes.extraLarge
                    )
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .animateContentSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isInputFocused) {
                        IconButton(onClick = {
                            onQueryChange("")
                            focusManager.clearFocus()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Exit Search",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        placeholder = {
                            Text(
                                "键入单词或长句...",
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.primary,
                            unfocusedTextColor = MaterialTheme.colorScheme.primary
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            onSearch()
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Suggestions List
            AnimatedVisibility(visible = suggestions.isNotEmpty() && isInputFocused) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                MaterialTheme.shapes.large
                            )
                    ) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            suggestions.forEach { word ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onQueryChange(word)
                                            onSearch()
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = word,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Lower Part: Recent Searches Timeline
            AnimatedVisibility(visible = historyList.isNotEmpty() && (suggestions.isEmpty() || !isInputFocused)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最近查阅",
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryTextColor
                        )
                        Text(
                            text = "清空",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = secondaryTextColor,
                            modifier = Modifier.clickable { onClearHistory() }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Column {
                        historyList.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onHistoryClick(item.word) }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = item.word,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = primaryTextColor
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.translation,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = secondaryTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Go",
                                    tint = if (wallpaperUrl != null) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            Divider(color = if (wallpaperUrl != null) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
}

@Composable
fun ResultView(
    query: String,
    isSearching: Boolean,
    result: WordResponse?,
    translation: String?,
    levels: List<String>,
    pronunciationDialect: PronunciationDialect,
    isStarred: Boolean,
    onStarToggle: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Status Bar Spacer
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // Result Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            // Word Level Badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.weight(1f)
            ) {
                levels.forEach { lvl ->
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = lvl,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Query Word Info
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = query,
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 32.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        val phoneticsText = result?.phonetic ?: result?.phonetics?.firstOrNull { !it.text.isNullOrEmpty() }?.text
                        if (!phoneticsText.isNullOrEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = phoneticsText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                val audioUrl = selectAudioUrl(result?.phonetics, pronunciationDialect)
                                if (!audioUrl.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { playAudio(context, audioUrl) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = pronunciationDialect.label,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Favorite/Star Action
                    IconButton(onClick = onStarToggle) {
                        Icon(
                            imageVector = if (isStarred) Icons.Filled.Star else Icons.Filled.Star,
                            contentDescription = "Save word",
                            tint = if (isStarred) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }

            // Display Translation Fallback / Sentence Translation Card
            if (translation != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                MaterialTheme.shapes.large
                            )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (result != null) "中文释义" else "翻译结果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = translation,
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Normal),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Display Dictionary Definition Response
            if (result != null && result.meanings != null) {
                items(result.meanings) { meaning ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Part of speech
                        Box(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = meaning.partOfSpeech?.uppercase() ?: "",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    letterSpacing = 0.1.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        meaning.definitions?.forEach { def ->
                            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                Text(
                                    text = def.definition ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!def.example.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "“${def.example}”",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

private fun selectAudioUrl(
    phonetics: List<Phonetic>?,
    dialect: PronunciationDialect
): String? {
    val available = phonetics.orEmpty().mapNotNull { it.audio?.takeIf { audio -> audio.isNotBlank() } }
    if (available.isEmpty()) return null

    val preferredMarkers = when (dialect) {
        PronunciationDialect.US -> listOf("us", "en-us", "american")
        PronunciationDialect.UK -> listOf("uk", "gb", "en-gb", "british")
    }

    return available.firstOrNull { audio ->
        val normalized = audio.lowercase()
        preferredMarkers.any { marker -> normalized.contains(marker) }
    } ?: available.first()
}

@Composable
fun HistoryView(
    historyList: List<HistoryItem>,
    onItemClick: (String) -> Unit,
    onClearAll: () -> Unit,
    onDeleteItem: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "查阅记录",
                style = MaterialTheme.typography.headlineMedium
            )
            if (historyList.isNotEmpty()) {
                IconButton(onClick = onClearAll) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear all",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (historyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无查阅历史",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyList) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                                MaterialTheme.shapes.large
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onItemClick(item.word) }
                            ) {
                                Text(
                                    text = item.word,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.translation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteItem(item.word) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除记录",
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    pronunciationDialect: PronunciationDialect,
    selectedWordbookIds: Set<String>,
    homeTitle: String,
    homeSubtitle: String,
    homeSubtitleMode: SubtitleMode,
    bingWallpaperEnabled: Boolean,
    hitokotoRefreshInterval: Int,
    api: DictionaryApi,
    onPronunciationDialectChange: (PronunciationDialect) -> Unit,
    onSelectedWordbooksChange: (Set<String>) -> Unit,
    onHomeTitleChange: (String) -> Unit,
    onHomeSubtitleChange: (String) -> Unit,
    onHomeSubtitleModeChange: (SubtitleMode) -> Unit,
    onBingWallpaperEnabledChange: (Boolean) -> Unit,
    onHitokotoRefreshIntervalChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    var currentPage by remember { mutableStateOf<SettingsPage>(SettingsPage.Main) }

    BackHandler(enabled = currentPage != SettingsPage.Main) {
        currentPage = SettingsPage.Main
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (currentPage == SettingsPage.Main) onBack() else currentPage = SettingsPage.Main
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = when(currentPage) {
                    SettingsPage.Main -> "设置"
                    SettingsPage.Interface -> "界面定制"
                    SettingsPage.Pronunciation -> "发音设置"
                    SettingsPage.Wordbooks -> "词书管理"
                    SettingsPage.About -> "关于茶词"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState != SettingsPage.Main) {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                } else {
                    slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }
            },
            label = "SettingsTransition"
        ) { page ->
            when (page) {
                SettingsPage.Main -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        item { SettingsGroupTitle("个性化") }
                        item {
                            SettingsItem(
                                icon = Icons.Default.Palette,
                                title = "界面定制",
                                subtitle = "修改主页标题和名言",
                                onClick = { currentPage = SettingsPage.Interface }
                            )
                        }
                        
                        item { SettingsGroupTitle("学习设置") }
                        item {
                            SettingsItem(
                                icon = Icons.Default.RecordVoiceOver,
                                title = "发音设置",
                                subtitle = pronunciationDialect.label,
                                onClick = { currentPage = SettingsPage.Pronunciation }
                            )
                        }
                        item {
                            SettingsItem(
                                icon = Icons.Default.AutoStories,
                                title = "词书管理",
                                subtitle = "已选择 ${selectedWordbookIds.size} 本词书",
                                onClick = { currentPage = SettingsPage.Wordbooks }
                            )
                        }
                        
                        item { SettingsGroupTitle("关于") }
                        item {
                            SettingsItem(
                                icon = Icons.Default.Info,
                                title = "版本信息",
                                subtitle = "v1.1.0",
                                onClick = { currentPage = SettingsPage.About }
                            )
                        }
                    }
                }
                SettingsPage.Interface -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = homeTitle,
                            onValueChange = onHomeTitleChange,
                            label = { Text("主页标题") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("启用 Bing 每日壁纸", style = MaterialTheme.typography.bodyLarge)
                                Text("主页背景将自动更换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Switch(
                                checked = bingWallpaperEnabled,
                                onCheckedChange = onBingWallpaperEnabledChange
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text("副标题模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = homeSubtitleMode == SubtitleMode.CUSTOM,
                                onClick = { onHomeSubtitleModeChange(SubtitleMode.CUSTOM) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text("自定义") }
                            )
                            SegmentedButton(
                                selected = homeSubtitleMode == SubtitleMode.HITOKOTO,
                                onClick = { onHomeSubtitleModeChange(SubtitleMode.HITOKOTO) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text("一言") }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (homeSubtitleMode == SubtitleMode.CUSTOM) {
                            OutlinedTextField(
                                value = homeSubtitle,
                                onValueChange = onHomeSubtitleChange,
                                label = { Text("自定义副标题/名言") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3,
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            val scope = rememberCoroutineScope()
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("当前一言：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(homeSubtitle, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val client = okhttp3.OkHttpClient()
                                            val request = okhttp3.Request.Builder().url("https://v1.hitokoto.cn/?encode=text&c=i").build()
                                            val response = client.newCall(request).execute()
                                            val quote = response.body?.string()
                                            if (!quote.isNullOrEmpty()) {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    onHomeSubtitleChange(quote)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("Settings", "Failed to fetch hitokoto", e)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("获取随机名言")
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("自动刷新间隔", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                TextButton(onClick = { onHitokotoRefreshIntervalChange(5) }) {
                                    Text("恢复默认", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = hitokotoRefreshInterval.toFloat(),
                                    onValueChange = { onHitokotoRefreshIntervalChange(it.toInt()) },
                                    valueRange = 1f..60f,
                                    steps = 59,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("${hitokotoRefreshInterval} 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                SettingsPage.Pronunciation -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            "选择默认发音口音",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        PronunciationDialect.entries.forEach { dialect ->
                            val selected = pronunciationDialect == dialect
                            Surface(
                                onClick = { onPronunciationDialectChange(dialect) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = { onPronunciationDialectChange(dialect) })
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(dialect.label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                SettingsPage.Wordbooks -> {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        items(LearningWordbook.entries.toTypedArray()) { wordbook ->
                            val selected = wordbook.id in selectedWordbookIds
                            Surface(
                                onClick = {
                                    val next = if (selected) selectedWordbookIds - wordbook.id else selectedWordbookIds + wordbook.id
                                    onSelectedWordbooksChange(next)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = {
                                            val next = if (it) selectedWordbookIds + wordbook.id else selectedWordbookIds - wordbook.id
                                            onSelectedWordbooksChange(next)
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(wordbook.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                        Text(wordbook.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsPage.About -> {
                    AboutView()
                }
            }
        }
    }
}

@Composable
fun AboutView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // App Icon
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "teaWords Logo",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Fit
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "teaWords",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = "v1.1.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "茶词（teaWords）是一款极致简洁、专注单词学习与翻译的应用。旨在为你提供纯净的查词体验，并在不经意间通过 Bing 壁纸和一言名句，带给你一丝片刻的宁静。",
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 26.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "致谢与致敬",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        AcknowledgmentItem("Jetpack Compose", "现代原生 Android UI 工具包")
        AcknowledgmentItem("Material 3", "Google 的新一代设计语言")
        AcknowledgmentItem("Coil", "现代 Android 图片加载库")
        AcknowledgmentItem("Hitokoto 一言", "提供温暖的人心文字")
        AcknowledgmentItem("Bing Wallpaper", "提供每日精美壁纸")
        AcknowledgmentItem("Free Dictionary API", "基础词典数据支持")
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "teaMeow Technology",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun AcknowledgmentItem(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

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

    Column(modifier = Modifier.fillMaxSize()) {
        // Status Bar Spacer
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
            indicator = { tabPositions ->
                if (selectedTabIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex])
                    )
                }
            }
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text("查阅记录") }
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text("生词本") }
            )
        }

        if (selectedTabIndex == 0) {
            HistoryView(
                historyList = historyList,
                onItemClick = onItemClick,
                onClearAll = onClearHistory,
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

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                MaterialTheme.shapes.large
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun NotebookView(
    vocabularyList: List<VocabularyItem>,
    onItemClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    var flashcardMode by remember { mutableStateOf(false) }
    var flashcardIndex by remember { mutableStateOf(0) }
    var flashcardFlipped by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "生词本",
                style = MaterialTheme.typography.headlineMedium
            )
            if (vocabularyList.isNotEmpty()) {
                IconButton(onClick = {
                    flashcardMode = !flashcardMode
                    flashcardIndex = 0
                    flashcardFlipped = false
                }) {
                    Icon(
                        imageVector = if (flashcardMode) Icons.Default.List else Icons.Default.PlayArrow,
                        contentDescription = "Toggle review mode",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (vocabularyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "生词本空空如也",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return
        }

        if (flashcardMode) {
            // Flashcard Review Mode (3D flipping animation)
            val currentWord = vocabularyList.getOrNull(flashcardIndex)
            if (currentWord != null) {
                val rotation by animateFloatAsState(
                    targetValue = if (flashcardFlipped) 180f else 0f,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "卡片记忆 (${flashcardIndex + 1}/${vocabularyList.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // 3D Flip Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .graphicsLayer {
                                rotationY = rotation
                                cameraDistance = 12f * density
                            }
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                MaterialTheme.shapes.large
                            )
                            .clickable { flashcardFlipped = !flashcardFlipped },
                        contentAlignment = Alignment.Center
                    ) {
                        if (rotation <= 90f) {
                            // Front Side
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = currentWord.word,
                                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 36.sp),
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                                if (!currentWord.phonetic.isNullOrEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = currentWord.phonetic,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.outline,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = "点击卡片翻面",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            // Back Side (Rotated 180 deg to prevent mirroring)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .graphicsLayer { rotationY = 180f }
                                    .padding(24.dp)
                            ) {
                                Text(
                                    text = currentWord.definition,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Normal),
                                    color = MaterialTheme.colorScheme.secondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    // Next/Prev Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (flashcardIndex > 0) {
                                    flashcardFlipped = false
                                    flashcardIndex--
                                }
                            },
                            enabled = flashcardIndex > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), MaterialTheme.shapes.medium)
                        ) {
                            Text("上一个")
                        }

                        Button(
                            onClick = {
                                if (flashcardIndex < vocabularyList.size - 1) {
                                    flashcardFlipped = false
                                    flashcardIndex++
                                } else {
                                    flashcardMode = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Text(if (flashcardIndex == vocabularyList.size - 1) "完成" else "下一个")
                        }
                    }
                }
            }
        } else {
            // Standard Vocabulary List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vocabularyList) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                                MaterialTheme.shapes.large
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onItemClick(item.word) }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.word,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (!item.phonetic.isNullOrEmpty()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = item.phonetic,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.definition,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteClick(item.word) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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

    BackHandler(enabled = showStats) {
        showStats = false
    }

    if (showStats) {
        StatisticsView(
            dbHelper = dbHelper,
            onBack = { showStats = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (reviewSession == null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                ReviewStartScreen(
                    historyList = historyList,
                    selectedWordbookIds = selectedWordbookIds,
                    levelProvider = levelProvider,
                    clozeGenerator = clozeGenerator,
                    onStartReview = onStartReview,
                    onShowStats = { showStats = true }
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                ReviewSessionScreen(
                    session = reviewSession,
                    onCancelReview = onCancelReview,
                    onAnswerSubmit = onAnswerSubmit,
                    onReviewComplete = onReviewComplete
                )
            }
        }
    }
}

@Composable
fun ReviewStartScreen(
    historyList: List<HistoryItem>,
    selectedWordbookIds: Set<String>,
    levelProvider: WordLevelProvider,
    clozeGenerator: ClozeGenerator,
    onStartReview: (List<ClozeProblem>) -> Unit,
    onShowStats: () -> Unit
) {
    var isGenerating by remember { mutableStateOf(false) }
    val selectedBooks = LearningWordbook.values().filter { it.id in selectedWordbookIds }
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

        TextButton(onClick = onShowStats) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("学习历史与统计分析", style = MaterialTheme.typography.labelLarge)
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
    }
}

@Composable
fun ReviewTypeCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onClick() }
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                MaterialTheme.shapes.large
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

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

    // Timer logic
    var timeLeft by remember { mutableIntStateOf(15) } // 15 seconds per problem
    var timerActive by remember { mutableStateOf(true) }

    LaunchedEffect(session.currentProblemIndex, timerActive) {
        if (timerActive && !isAnswered) {
            timeLeft = 15
            while (timeLeft > 0 && !isAnswered) {
                kotlinx.coroutines.delay(1000)
                timeLeft--
            }
            if (timeLeft == 0 && !isAnswered) {
                // Time's up!
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
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Top Info Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancelReview) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit")
                }
                Text(
                    text = "${session.currentProblemIndex + 1} / ${session.problems.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Timer Display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (timeLeft < 5) Color.Red else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${timeLeft}s",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (timeLeft < 5) Color.Red else MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Progress Bar
        LinearProgressIndicator(
            progress = session.progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        Spacer(modifier = Modifier.height(24.dp))
        
        // Problem Statement
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
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
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = problem.blankedSentence,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 22.sp,
                                lineHeight = 32.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isAnswered && showFeedback) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "完整句子：${problem.sentence}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontStyle = FontStyle.Italic
                                ),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // Answer Options
            item { Spacer(modifier = Modifier.height(24.dp)) }

            items(problem.options.size) { index ->
                val option = problem.options[index]
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
                        if (showResult && isCorrect) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "正确",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (showResult && isSelected && !isCorrect) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "错误",
                                tint = Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isAnswered) {
                Button(
                    onClick = {
                        isAnswered = true
                        showFeedback = true
                    },
                    enabled = selectedAnswer != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("提交答案")
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
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (session.currentProblemIndex == session.problems.size - 1) "完成学习" else "下一题"
                    )
                }
            }
        }
    }
}

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

        // Score Circle
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

// --- HELPER FUNCTION ---

fun playAudio(context: Context, url: String) {
    if (url.isEmpty()) return
    try {
        MediaPlayer().apply {
            setAudioStreamType(AudioManager.STREAM_MUSIC)
            setDataSource(url)
            prepareAsync()
            setOnPreparedListener { start() }
            setOnCompletionListener { release() }
            setOnErrorListener { mp, what, extra ->
                Log.e("AudioPlayer", "Error playing media (what: $what, extra: $extra)")
                mp.release()
                true
            }
        }
    } catch (e: Exception) {
        Log.e("AudioPlayer", "Error preparing media: ${e.message}", e)
    }
}

@Composable
fun StatisticsView(
    dbHelper: DatabaseHelper,
    onBack: () -> Unit
) {
    var stats by remember { mutableStateOf<ReviewSessionRecord?>(null) }
    var sessions by remember { mutableStateOf(emptyList<ReviewSessionRecord>()) }
    var weakWords by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        stats = dbHelper.getReviewStats()
        sessions = dbHelper.getReviewSessions(10)
        weakWords = dbHelper.getWeakWords(5)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("统计分析", style = MaterialTheme.typography.headlineMedium)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("总体表现", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem("总题目", "${stats?.totalProblems ?: 0}")
                            StatItem("准确率", "${stats?.accuracy?.toInt() ?: 0}%")
                            StatItem("总时长", "${(stats?.timeSpentSeconds ?: 0) / 60}m")
                        }
                    }
                }
            }

            item {
                Text("薄弱词汇", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(8.dp))
                if (weakWords.isEmpty()) {
                    Text("暂无数据", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        mainAxisSpacing = 8.dp,
                        crossAxisSpacing = 8.dp
                    ) {
                        weakWords.forEach { word ->
                            SuggestionChip(
                                onClick = { },
                                label = { Text(word) }
                            )
                        }
                    }
                }
            }

            item {
                Text("最近记录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            }

            items(sessions) { session ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text("正确率: ${session.accuracy.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("${session.correctCount}/${session.totalProblems}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    mainAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    crossAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(mainAxisSpacing),
        verticalArrangement = Arrangement.spacedBy(crossAxisSpacing),
        content = { content() }
    )
}
