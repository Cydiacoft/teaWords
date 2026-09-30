package com.teameow.teawords.ui.screens

import android.content.Context
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.teameow.teawords.R
import com.teameow.teawords.data.*
import com.teameow.teawords.ui.StatsDashboard

enum class AppTab(val title: String) {
    TRANSLATE("翻译"),
    HISTORY("回望"),
    REVIEW("学习")
}

enum class SettingsPage {
    Main,
    Interface,
    Appearance,
    Strategy,
    Pronunciation,
    Translation,
    Wordbooks,
    About,
    Update,
    Logs
}

data class BingWallpaper(val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    dbHelper: DatabaseHelper,
    levelProvider: WordLevelProvider,
    api: DictionaryApi,
    appPreferences: AppPreferences? = null,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
    studyStrategy: StudyStrategyPreference = StudyStrategyPreference.BALANCED,
    onStudyStrategyChange: (StudyStrategyPreference) -> Unit = {},
    dailyNewCapOverride: Int = 0,
    onDailyNewCapChange: (Int) -> Unit = {},
    targetRetentionOverride: Double = 0.0,
    onTargetRetentionChange: (Double) -> Unit = {}
) {
    val context = LocalContext.current
    val preferences = remember(appPreferences) { appPreferences ?: AppPreferences(context) }
    var currentTab by rememberSaveable { mutableStateOf(AppTab.TRANSLATE) }
    val pronunciationServices = rememberPronunciationServices()
    val pronunciationPreference by pronunciationServices.preference.collectAsState()
    val pronunciationDialect = pronunciationPreference.dialect ?: PronunciationDialect.US
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
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<WordResponse?>(null) }
    var translatedText by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var isLookupExecuted by remember { mutableStateOf(false) }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var lookupRequestId by rememberSaveable { mutableStateOf(0L) }
    var isStarred by remember { mutableStateOf(false) }
    var wordLevels by remember { mutableStateOf<List<String>>(emptyList()) }

    // History and Vocabulary States
    var historyList by remember { mutableStateOf(emptyList<HistoryItem>()) }
    var vocabularyList by remember { mutableStateOf(emptyList<VocabularyItem>()) }

    // Review State
    var showLookupReview by rememberSaveable { mutableStateOf(false) }
    var lookupReviewEntries by remember { mutableStateOf(emptyList<LocalEntry>()) }
    var lookupReviewCount by remember { mutableIntStateOf(0) }
    var recordsLoading by remember { mutableStateOf(false) }
    var recordsError by remember { mutableStateOf<String?>(null) }
    var lookupReviewBusy by remember { mutableStateOf(false) }
    var reviewSession by remember { mutableStateOf<ReviewSession?>(null) }

    // Navigation sub-states
    var showStats by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showReviewStats by remember { mutableStateOf(false) }
    // Which page the learning tab is on: "home", "screen", "session" or "diagnose". The host needs
    // it because the floating dock stays visible on all of them except a running round.
    var learningMode by remember { mutableStateOf("home") }

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
    LaunchedEffect(currentTab, isLookupExecuted, showLookupReview) {
        historyList = withContext(kotlinx.coroutines.Dispatchers.IO) { LocalDictionary(dbHelper).unifiedHistory() }
        vocabularyList = withContext(kotlinx.coroutines.Dispatchers.IO) { dbHelper.getVocabulary() }
        if (currentTab == AppTab.HISTORY && (!showLookupReview || lookupReviewEntries.isEmpty())) {
            recordsLoading = true
            recordsError = null
            try {
                val loaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    check(com.teameow.teawords.data.search.DictionaryGateway.open(context)) { "离线词典未就绪" }
                    val repository = LookupReviewRepository(dbHelper)
                    val entries = repository.entries()
                    entries to repository.queue(entries, System.currentTimeMillis(), Int.MAX_VALUE).size
                }
                lookupReviewEntries = loaded.first
                lookupReviewCount = loaded.second
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { recordsError = "读取生词失败：${e.message}" }
            finally { recordsLoading = false }
        }
    }

    // Suggestions logic
    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length >= 2 && LexicalText.dictionaryMode(searchQuery)) {
            val q = searchQuery.trim().lowercase()
            suggestions = withContext(kotlinx.coroutines.Dispatchers.IO) { LocalDictionary(dbHelper).search(q).take(8).map { it.word }.ifEmpty { levelProvider.searchPrefix(q, limit = 8) } }
        } else {
            suggestions = emptyList()
        }
    }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val density = LocalDensity.current
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var dockClearance by remember { mutableStateOf(80.dp + systemBottom) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lookupSucceeded by remember { mutableStateOf(false) }
    val closeLookup: () -> Unit = {
        searchJob?.cancel()
        isLookupExecuted = false
        isSearching = false
        focusManager.clearFocus()
    }
    val triggerSearch: (String, Boolean) -> Unit = { queryText, _ ->
        val q = queryText.trim()
        if (q.isNotEmpty()) {
            searchJob?.cancel()
            focusManager.clearFocus()
            searchQuery = q
            submittedQuery = q
            lookupRequestId++
            isLookupExecuted = true
        }
    }
    // Only dismiss input here. Each visible page owns its own back gesture.
    BackHandler(enabled = isInputFocused && !isLookupExecuted && !showStats && !showSettings) {
        focusManager.clearFocus()
        isInputFocused = false
    }
    val pageStates = rememberSaveableStateHolder()
    val childVisible = showStats || showSettings || showReviewStats || isLookupExecuted || reviewSession != null || showLookupReview
    // Top-level destinations alone own the navigation bar. Child screens clear system insets.
    val showDock = !childVisible && !keyboardVisible && learningMode != "session" && learningMode != "diagnose" && learningMode != "pocket" && learningMode != "pocketSetup"
    val selectTab: (AppTab) -> Unit = { tab ->
        focusManager.clearFocus()
        keyboardController?.hide()
        isInputFocused = false
        currentTab = tab
    }

    CompositionLocalProvider(LocalPredictiveBackEnabled provides predictiveBackEnabled) {
    Scaffold(
        bottomBar = {
            if (showDock) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
                modifier = Modifier.onSizeChanged { dockClearance = with(density) { it.height.toDp() } }
            ) {
                TeaNavItem(currentTab, AppTab.TRANSLATE, AppSymbols.Search) { selectTab(AppTab.TRANSLATE) }
                TeaNavItem(currentTab, AppTab.HISTORY, AppSymbols.Update) { selectTab(AppTab.HISTORY) }
                TeaNavItem(currentTab, AppTab.REVIEW, AppSymbols.School) { selectTab(AppTab.REVIEW) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        val screen: @Composable (String, () -> Unit) -> Unit = { targetStateString, requestBack ->
          pageStates.SaveableStateProvider(targetStateString) {
            val isTopLevelPage = AppTab.entries.any { it.name == targetStateString }
            val reservedBottomForPage: Dp? = if (isTopLevelPage && !keyboardVisible) dockClearance else null
            CompositionLocalProvider(
                LocalReservedBottom provides reservedBottomForPage
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (targetStateString) {
                    "stats" -> StatsDashboard(dbHelper = dbHelper, onBack = requestBack)
                    "settings" -> SettingsView(
                        pronunciationDialect = pronunciationDialect,
                        selectedWordbookIds = selectedWordbookIds,
                        homeTitle = homeTitle,
                        homeSubtitle = homeSubtitle,
                        homeSubtitleMode = homeSubtitleMode,
                        bingWallpaperEnabled = bingWallpaperEnabled,
                        onPronunciationDialectChange = pronunciationServices::setDefault,
                        pronunciationPreferenceReady = pronunciationPreference.dialect != null,
                        pronunciationPreferenceError = pronunciationPreference.error,
                        onRetryPronunciationPreference = pronunciationServices::reloadPreference,
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
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        dynamicColor = dynamicColor,
                        onDynamicColorChange = onDynamicColorChange,
                        predictiveBackEnabled = predictiveBackEnabled,
                        onPredictiveBackChange = { enabled ->
                            predictiveBackEnabled = enabled
                            preferences.predictiveBackEnabled = enabled
                        },
                        studyStrategy = studyStrategy,
                        onStudyStrategyChange = onStudyStrategyChange,
                        dailyNewCapOverride = dailyNewCapOverride,
                        onDailyNewCapChange = onDailyNewCapChange,
                        targetRetentionOverride = targetRetentionOverride,
                        onTargetRetentionChange = onTargetRetentionChange,
                        onBack = requestBack
                    )
                    AppTab.TRANSLATE.name -> {
                        Box(modifier = Modifier.fillMaxSize()) {
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
                                        searchQuery = word
                                        triggerSearch(word, false)
                                    },
                                    onClearHistory = {
                                        val now = System.currentTimeMillis()
                                        homeLastClearedTimestamp = now
                                        preferences.homeLastClearedTimestamp = now
                                    },
                                    onMenuClick = { focusManager.clearFocus(); showSettings = true },
                                    onPersonClick = { focusManager.clearFocus(); showStats = true },
                                    wallpaperUrl = if (bingWallpaperEnabled) bingWallpaperUrl else null,
                                    bottomClearance = if (keyboardVisible) innerPadding.calculateBottomPadding() else dockClearance
                                )
                        }
                    }
                    "result" -> {
                        LookupScreen(initialText = submittedQuery, onBack = requestBack, requestId = lookupRequestId, onInputChange = { searchQuery = it })
                    }
                    AppTab.HISTORY.name -> {
                        RecordsView(
                            onSettings = { showSettings = true }, onStats = { showStats = true },
                            onStartReview = { showLookupReview = true },
                            reviewCount = lookupReviewCount, loading = recordsLoading, error = recordsError,
                            vocabularyList = vocabularyList,
                            onItemClick = { word ->
                                searchQuery = word
                                triggerSearch(word, false)
                            },
                            onDeleteVocab = { word ->
                                scope.launch {
                                    vocabularyList = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        dbHelper.removeVocabulary(word)
                                        dbHelper.getVocabulary()
                                    }
                                }
                            }
                        )
                    }
                    AppTab.REVIEW.name -> {
                        LearningScreen(
                            dbHelper = dbHelper,
                            selectedBookIds = selectedWordbookIds,
                            onSelectedBooksChange = { ids -> selectedWordbookIds = ids; preferences.selectedWordbookIds = ids },
                            onShowStats = { showStats = true },
                            onModeChange = { learningMode = it }
                        )
                    }
                    "lookup-review" -> LookupReviewScreen(dbHelper, lookupReviewEntries, requestBack) { lookupReviewBusy = it }
                }
            }
            }
        }
        }
        Box(Modifier.fillMaxSize()) {
            CoveredPage(covered = currentTab != AppTab.TRANSLATE || childVisible) {
                screen(AppTab.TRANSLATE.name) { }
            }
            if (currentTab != AppTab.TRANSLATE) {
                PredictiveBackLayer(onBack = { selectTab(AppTab.TRANSLATE) }, enabled = !childVisible, topLevel = true) { requestBack ->
                    AnimatedContent(
                        targetState = currentTab,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { direction * it / 20 }) togetherWith
                                fadeOut(tween(90))
                        },
                        label = "main-tab"
                    ) { tab ->
                        CoveredPage(covered = childVisible || tab != currentTab) { screen(tab.name, requestBack) }
                    }
                }
            }
            val overlay = when {
                showStats -> "stats"
                showSettings -> "settings"
                showLookupReview -> "lookup-review"
                isLookupExecuted -> "result"
                else -> null
            }
            if (overlay != null) {
                key(overlay) {
                    PredictiveBackLayer(enabled = !lookupReviewBusy, onBack = {
                        when (overlay) {
                            "stats" -> showStats = false
                            "settings" -> showSettings = false
                            "lookup-review" -> showLookupReview = false
                            else -> closeLookup()
                        }
                    }) { requestBack -> screen(overlay, requestBack) }
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    NavigationBarItem(
        enabled = enabled,
        selected = currentTab == tab,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = tab.title) },
        label = { Text(tab.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            // Without these a disabled item fades to almost nothing and leaves the pill looking
            // like an empty white block.
            disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    )
}

// --- SCREEN COMPONENTS ---

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
                                DefaultPronunciationButton(query)
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
                text = "${historyList.size} 条查阅记录",
                style = MaterialTheme.typography.titleMedium
            )
            if (historyList.isNotEmpty()) {
                IconButton(onClick = onClearAll) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "清空查阅记录",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (historyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TeaEmptyState("还没有查阅记录", "在首页查一个词，下一次从这里继续。")
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
                text = "${vocabularyList.size} 个生词",
                style = MaterialTheme.typography.titleMedium
            )
            if (vocabularyList.isNotEmpty()) {
                IconButton(onClick = {
                    flashcardMode = !flashcardMode
                    flashcardIndex = 0
                    flashcardFlipped = false
                }) {
                    Icon(
                        imageVector = if (flashcardMode) Icons.Default.List else Icons.Default.PlayArrow,
                        contentDescription = if (flashcardMode) "查看生词列表" else "开始卡片复习",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (vocabularyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TeaEmptyState("给新词留个位置", "收藏的单词会出现在这里，随时回来复习。")
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
    showStats: Boolean,
    onShowStatsChange: (Boolean) -> Unit,
    clozeGenerator: ClozeGenerator,
    dbHelper: DatabaseHelper,
    onStartReview: (List<ClozeProblem>) -> Unit,
    onCancelReview: () -> Unit,
    onAnswerSubmit: (ReviewAnswer) -> Unit,
    onReviewComplete: () -> Unit
) {

    Box(Modifier.fillMaxSize()) {
        CoveredPage(covered = reviewSession != null || showStats) {
          Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            ReviewStartScreen(
                historyList = historyList,
                selectedWordbookIds = selectedWordbookIds,
                levelProvider = levelProvider,
                clozeGenerator = clozeGenerator,
                onStartReview = onStartReview,
                onShowStats = { onShowStatsChange(true) }
            )
        }
        }
        if (reviewSession != null) {
            PredictiveBackLayer(onBack = {
                if (reviewSession.isComplete) onReviewComplete() else onCancelReview()
            }) { requestBack ->
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    ReviewSessionScreen(
                        session = reviewSession,
                        onCancelReview = requestBack,
                        onAnswerSubmit = onAnswerSubmit,
                        onReviewComplete = requestBack
                    )
                }
            }
        }
        if (showStats) {
            PredictiveBackLayer(onBack = { onShowStatsChange(false) }) { requestBack ->
                StatisticsView(dbHelper = dbHelper, onBack = requestBack)
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
    var selectedAnswer by remember(session.sessionId, session.currentProblemIndex) { mutableStateOf<String?>(null) }
    var showFeedback by remember(session.sessionId, session.currentProblemIndex) { mutableStateOf(false) }
    var isAnswered by remember(session.sessionId, session.currentProblemIndex) { mutableStateOf(false) }

    // Timer logic
    var timeLeft by remember(session.sessionId, session.currentProblemIndex) { mutableIntStateOf(15) } // 15 seconds per problem
    var timerActive by remember(session.sessionId, session.currentProblemIndex) { mutableStateOf(true) }

    LaunchedEffect(session.currentProblemIndex, timerActive) {
        if (timerActive && !isAnswered && !session.isComplete) {
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
                                // 固定 Locale.US：这是机器可读的时间戳格式，且在 composable 里读
                                // Locale.getDefault() 属于不可观察读取（lint: NonObservableLocale）。
                                text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(session.timestamp)),
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

/** Preserve layout and scroll state while excluding covered controls from accessibility. */
@Composable
internal fun CoveredPage(covered: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)) {
        content()
    }
}
