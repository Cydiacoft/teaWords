package com.teameow.teawords.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.*
import com.teameow.teawords.ui.theme.TeaWordsTheme
import com.teameow.teawords.ui.theme.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var mode by mutableStateOf(AppThemeMode.SYSTEM)
    private var books by mutableStateOf(setOf("cet4"))
    private var predictiveBack by mutableStateOf(false)

    private fun launch(compact: Boolean = false) {
        compose.setContent {
            var title by remember { mutableStateOf("茶词") }
            var subtitle by remember { mutableStateOf("专注翻译，见字如面") }
            var subtitleMode by remember { mutableStateOf(SubtitleMode.CUSTOM) }
            var wallpaperMode by remember { mutableStateOf(HomeWallpaperMode.DAILY) }
            var customWallpaperUri by remember { mutableStateOf<String?>(null) }
            var dialect by remember { mutableStateOf(PronunciationDialect.US) }
            var strategy by remember { mutableStateOf(StudyStrategyPreference.BALANCED) }
            var cap by remember { mutableIntStateOf(0) }
            // The actual representation returned from SharedPreferences after restarting.
            var retention by remember { mutableStateOf(0.94f.toDouble()) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalReservedBottom provides 100.dp,
                LocalDensity provides Density(density.density, if (compact) 1.3f else 1f)
            ) {
                TeaWordsTheme(darkTheme = resolveDarkTheme(mode, false)) {
                    Box(if (compact) Modifier.width(320.dp).height(640.dp) else Modifier) {
                        SettingsView(
                            pronunciationDialect = dialect, selectedWordbookIds = books,
                            homeTitle = title, homeSubtitle = subtitle, homeSubtitleMode = subtitleMode,
                            homeWallpaperMode = wallpaperMode, customWallpaperUri = customWallpaperUri,
                            hitokotoRefreshInterval = 5, themeMode = mode,
                            onPronunciationDialectChange = { dialect = it }, onSelectedWordbooksChange = { books = it },
                            onHomeTitleChange = { title = it }, onHomeSubtitleChange = { subtitle = it },
                            onHomeSubtitleModeChange = { subtitleMode = it },
                            onHomeWallpaperModeChange = { wallpaperMode = it },
                            onCustomWallpaperUriChange = { customWallpaperUri = it },
                            onHitokotoRefreshIntervalChange = {}, onThemeModeChange = { mode = it },
                            dynamicColor = false, onDynamicColorChange = {},
                            studyStrategy = strategy, onStudyStrategyChange = { strategy = it },
                            dailyNewCapOverride = cap, onDailyNewCapChange = { cap = it },
                            targetRetentionOverride = retention, onTargetRetentionChange = { retention = it },
                            onBack = {}, predictiveBackEnabled = predictiveBack,
                            onPredictiveBackChange = { predictiveBack = it }
                        )
                    }
                }
            }
        }
    }

    @Test fun predictiveBackIsOffByDefaultAndCanBeEnabledInAppearance() {
        launch()
        compose.onNodeWithText("外观模式").performClick()
        compose.onNode(hasText("预测性返回") and isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(predictiveBack) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("外观模式").performClick()
        compose.onNode(hasText("预测性返回") and isToggleable()).assertIsOn()
    }

    @Test fun appearanceChangeAndNestedBackKeepSettingsUsable() {
        launch()
        compose.onNodeWithText("外观模式").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppThemeMode.DARK, mode) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("个性化").assertIsDisplayed()
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("关于App"))
        compose.onNodeWithText("关于App").performClick()
        compose.onNodeWithText("茶词 · teaWords").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("teaMeow Technology"))
        compose.onNodeWithText("teaMeow Technology").assertIsDisplayed()
    }

    @Test fun storedRetentionAndWrappedOptionsWorkOnSmallScreen() {
        launch(compact = true)
        compose.onNodeWithText("学习策略").performScrollTo().performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("94%"))
        compose.onNodeWithText("94%").assertIsSelected()
        compose.onNodeWithText("85%").performClick().assertIsSelected()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("40 个"))
        compose.onNodeWithText("40 个").performClick().assertIsSelected()
    }

    @Test fun lastWordbookClearsDockAndEmptySelectionIsExplained() {
        launch(compact = true)
        compose.onNodeWithText("词书管理").performScrollTo().performClick()
        compose.onNodeWithText("大学英语四级").performScrollTo().performClick()
        compose.onNodeWithText("选择至少一本词书，开启词书学习。").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("高考词汇"))
        compose.onNodeWithText("高考词汇").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(setOf("gk"), books) }
        val bottom = compose.onNodeWithText("高考词汇").getUnclippedBoundsInRoot().bottom
        assertTrue("Last option must stay above the 100dp dock reservation", bottom <= 540.dp)
    }
}
