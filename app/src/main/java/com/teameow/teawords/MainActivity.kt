package com.teameow.teawords

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.teameow.teawords.data.AppPreferences
import com.teameow.teawords.data.AppThemeMode
import com.teameow.teawords.data.DatabaseHelper
import com.teameow.teawords.data.DictionaryApi
import com.teameow.teawords.data.WordLevelProvider
import com.teameow.teawords.data.search.DictionaryGateway
import com.teameow.teawords.ui.screens.MainScreen
import com.teameow.teawords.ui.screens.ProvidePronunciation
import com.teameow.teawords.ui.theme.TeaWordsTheme
import com.teameow.teawords.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var levelProvider: WordLevelProvider
    private lateinit var api: DictionaryApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)
        // Bring an existing database up to the current additive schema before anything reads it.
        // onUpgrade does not fire when the version already matches, so this cannot be left to it.
        dbHelper.ensureSchema()
        levelProvider = WordLevelProvider(this)
        api = DictionaryApi()
        val preferences = AppPreferences(this)

        // Preload word level lists in background
        Thread { levelProvider.loadIfNeeded() }.start()

        // Install the shipped 61 MB dictionary on the app's own path, not on the first query: the copy
        // takes seconds on a cold start, and doing it inside a search would look like the app hanging.
        // It runs on a plain thread because it is a file copy with no need for a coroutine scope that
        // outlives this activity.
        Thread {
            DictionaryGateway.open(this) { progress ->
                Log.i("MainActivity", "dictionary install ${progress.percent}% done=${progress.done}")
            }
        }.start()

        setContent {
            // Read on every composition so choosing a mode in settings re-themes immediately.
            var mode by remember { mutableStateOf(preferences.themeMode) }
            var dynamicColor by remember { mutableStateOf(preferences.dynamicColor) }
            var strategy by remember { mutableStateOf(preferences.studyStrategy) }
            var dailyCap by remember { mutableIntStateOf(preferences.dailyNewCapOverride) }
            var retention by remember { mutableStateOf(preferences.targetRetentionOverride) }
            TeaWordsTheme(
                darkTheme = resolveDarkTheme(mode, isSystemInDarkTheme()),
                dynamicColor = dynamicColor
            ) {
              ProvidePronunciation {
                MainScreen(
                    dbHelper = dbHelper,
                    levelProvider = levelProvider,
                    api = api,
                    appPreferences = preferences,
                    themeMode = mode,
                    onThemeModeChange = {
                        preferences.themeMode = it
                        mode = it
                    },
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = {
                        preferences.dynamicColor = it
                        dynamicColor = it
                    },
                    studyStrategy = strategy,
                    onStudyStrategyChange = {
                        preferences.studyStrategy = it
                        strategy = it
                    },
                    dailyNewCapOverride = dailyCap,
                    onDailyNewCapChange = {
                        preferences.dailyNewCapOverride = it
                        dailyCap = it
                    },
                    targetRetentionOverride = retention,
                    onTargetRetentionChange = {
                        preferences.targetRetentionOverride = it
                        retention = it
                    }
                )
              }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
}
