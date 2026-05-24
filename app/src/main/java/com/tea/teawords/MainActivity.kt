package com.tea.teawords

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tea.teawords.data.DatabaseHelper
import com.tea.teawords.data.DictionaryApi
import com.tea.teawords.data.WordLevelProvider
import com.tea.teawords.ui.screens.MainScreen
import com.tea.teawords.ui.theme.TeaWordsTheme

class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var levelProvider: WordLevelProvider
    private lateinit var api: DictionaryApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)
        levelProvider = WordLevelProvider(this)
        api = DictionaryApi()

        // Preload word level lists in background
        Thread { levelProvider.loadIfNeeded() }.start()

        setContent {
            TeaWordsTheme {
                MainScreen(
                    dbHelper = dbHelper,
                    levelProvider = levelProvider,
                    api = api
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dbHelper.close()
    }
}
