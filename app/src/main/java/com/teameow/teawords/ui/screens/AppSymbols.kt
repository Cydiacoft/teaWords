package com.teameow.teawords.ui.screens

import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.teameow.teawords.R

/** Official Material Symbols Rounded, Apache-2.0, bundled for offline use. */
object AppSymbols {
    val Translate: ImageVector get() = androidx.compose.material.icons.Icons.Rounded.Translate
    val Help: ImageVector get() = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.HelpOutline
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_settings)
    val BarChart: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_bar_chart)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_search)
    val Update: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_update)
    val School: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_school)
    val Schedule: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_schedule)
    val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_delete)
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_chevron_right)
    val ArrowDownward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_arrow_downward)
    val ArrowForward: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_arrow_forward)
    val ArrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_arrow_back)
    val Person: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_person)
    val Palette: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_palette)
    val Wallpaper: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_wallpaper)
    val Book5: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_book_5)
    val BookmarkFlag: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_bookmark_flag)
    val VoiceSelection: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_voice_selection)
    val Info: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_info)
    val Feedback: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_feedback)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_close)
    val Image: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_image)
    val VolumeUp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_volume_up)
    val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_add)
    val Insights: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_insights)
    val History: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_history)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_check)
    val ExpandMore: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_expand_more)
    val Refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_refresh)
    val Favorite: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_favorite)
    val FavoriteFilled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_favorite_filled)
    val ContentCopy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.symbol_content_copy)
}
