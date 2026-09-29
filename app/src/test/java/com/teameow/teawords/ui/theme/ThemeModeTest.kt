package com.teameow.teawords.ui.theme

import com.teameow.teawords.data.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test fun systemFollowsTheDevice() {
        assertEquals(true, resolveDarkTheme(AppThemeMode.SYSTEM, systemInDark = true))
        assertEquals(false, resolveDarkTheme(AppThemeMode.SYSTEM, systemInDark = false))
    }

    @Test fun lightAndDarkOverrideTheDevice() {
        assertEquals(false, resolveDarkTheme(AppThemeMode.LIGHT, systemInDark = true))
        assertEquals(false, resolveDarkTheme(AppThemeMode.LIGHT, systemInDark = false))
        assertEquals(true, resolveDarkTheme(AppThemeMode.DARK, systemInDark = false))
        assertEquals(true, resolveDarkTheme(AppThemeMode.DARK, systemInDark = true))
    }

    @Test fun everyModeHasALabel() {
        AppThemeMode.values().forEach { mode ->
            assertEquals(true, mode.label.isNotBlank())
        }
    }
}
