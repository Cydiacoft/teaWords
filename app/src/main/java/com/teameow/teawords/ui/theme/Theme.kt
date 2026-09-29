package com.teameow.teawords.ui.theme

import android.app.Activity
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.teameow.teawords.data.AppThemeMode

/** 品牌配色：浅色（设计稿 palette=purple）。 */
private val PurpleLight = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF635A75), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFEF7FF), onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF), onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE6E0E9), onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9), surfaceTint = Color(0xFF6750A4),
    surfaceDim = Color(0xFFDED8E1), surfaceBright = Color(0xFFFEF7FF),
    outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF322F35), inverseOnSurface = Color(0xFFF5EFF7), inversePrimary = Color(0xFFD0BCFF),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B)
)

/**
 * 品牌配色的深色版本：同一套紫色 tonal palette 的暗端。
 *
 * 设计稿本身只画了浅色（`theme.dark=false`），但深色模式此前是已交付的功能，不能因为改版就丢掉，
 * 所以这里补齐 M3 baseline 的深色 token，让「跟随系统 / 浅色 / 深色」三档都真的有效。
 */
private val PurpleDark = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218), onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F), onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B), surfaceTint = Color(0xFFD0BCFF),
    surfaceDim = Color(0xFF141218), surfaceBright = Color(0xFF3B383E),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF49454F),
    inverseSurface = Color(0xFFE6E0E9), inverseOnSurface = Color(0xFF322F35), inversePrimary = Color(0xFF6750A4),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC)
)

/** Preserve the legacy preference mapping for data compatibility. */
fun resolveDarkTheme(mode: AppThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    AppThemeMode.SYSTEM -> systemInDark
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

/** 动态取色只在 Android 12（API 31）及以上可用。 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun TeaWordsTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    // 动态取色（Material You）用系统壁纸生成的配色；关掉时永远是品牌 Purple，设计稿的默认行为。
    // 这里必须内联 SDK_INT 判断：lint 的 NewApi 检查看不懂上面那个辅助函数。
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicColorScheme(context, dark = darkTheme)
        darkTheme -> PurpleDark
        else -> PurpleLight
    }
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                // 深色背景必须配浅色状态栏图标，否则时间/电量在深色下会看不见。
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme, motionScheme = MotionScheme.expressive(),
        typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content
    )
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun dynamicColorScheme(context: android.content.Context, dark: Boolean): ColorScheme =
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)