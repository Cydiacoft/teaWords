package com.tea.teawords.data

import android.content.Context

enum class PronunciationDialect(val label: String) {
    US("美音"),
    UK("英音")
}

enum class SubtitleMode {
    CUSTOM,
    HITOKOTO
}

enum class LearningWordbook(
    val id: String,
    val title: String,
    val subtitle: String
) {
    CET4("cet4", "大学英语四级", "CET-4"),
    CET6("cet6", "大学英语六级", "CET-6"),
    TEM4("tem4", "英语专业四级", "TEM-4"),
    TEM8("tem8", "英语专业八级", "TEM-8");

    companion object {
        fun fromId(id: String): LearningWordbook? = values().firstOrNull { it.id == id }
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("teawords_settings", Context.MODE_PRIVATE)

    var pronunciationDialect: PronunciationDialect
        get() = PronunciationDialect.values().getOrElse(
            prefs.getInt(KEY_PRONUNCIATION_DIALECT, PronunciationDialect.US.ordinal)
        ) { PronunciationDialect.US }
        set(value) {
            prefs.edit().putInt(KEY_PRONUNCIATION_DIALECT, value.ordinal).apply()
        }

    var selectedWordbookIds: Set<String>
        get() {
            val raw = prefs.getString(KEY_SELECTED_WORDBOOK_IDS, null)
                ?: return setOf(LearningWordbook.CET4.id)
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        set(value) {
            prefs.edit().putString(KEY_SELECTED_WORDBOOK_IDS, value.joinToString(",")).apply()
        }

    var homeTitle: String
        get() = prefs.getString(KEY_HOME_TITLE, "茶词") ?: "茶词"
        set(value) {
            prefs.edit().putString(KEY_HOME_TITLE, value).apply()
        }

    var homeSubtitle: String
        get() = prefs.getString(KEY_HOME_SUBTITLE, "专注翻译，见字如面") ?: "专注翻译，见字如面"
        set(value) {
            prefs.edit().putString(KEY_HOME_SUBTITLE, value).apply()
        }

    var homeSubtitleMode: SubtitleMode
        get() = SubtitleMode.values().getOrElse(
            prefs.getInt(KEY_HOME_SUBTITLE_MODE, SubtitleMode.CUSTOM.ordinal)
        ) { SubtitleMode.CUSTOM }
        set(value) {
            prefs.edit().putInt(KEY_HOME_SUBTITLE_MODE, value.ordinal).apply()
        }

    var homeLastClearedTimestamp: Long
        get() = prefs.getLong(KEY_HOME_LAST_CLEARED, 0L)
        set(value) {
            prefs.edit().putLong(KEY_HOME_LAST_CLEARED, value).apply()
        }

    var hitokotoRefreshInterval: Int
        get() = prefs.getInt(KEY_HITOKOTO_REFRESH_INTERVAL, 5) // Default 5 minutes
        set(value) {
            prefs.edit().putInt(KEY_HITOKOTO_REFRESH_INTERVAL, value).apply()
        }

    var bingWallpaperEnabled: Boolean
        get() = prefs.getBoolean(KEY_BING_WALLPAPER_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BING_WALLPAPER_ENABLED, value).apply()
        }

    companion object {
        private const val KEY_PRONUNCIATION_DIALECT = "pronunciation_dialect"
        private const val KEY_SELECTED_WORDBOOK_IDS = "selected_wordbook_ids"
        private const val KEY_HOME_TITLE = "home_title"
        private const val KEY_HOME_SUBTITLE = "home_subtitle"
        private const val KEY_HOME_SUBTITLE_MODE = "home_subtitle_mode"
        private const val KEY_HOME_LAST_CLEARED = "home_last_cleared"
        private const val KEY_HITOKOTO_REFRESH_INTERVAL = "hitokoto_refresh_interval"
        private const val KEY_BING_WALLPAPER_ENABLED = "bing_wallpaper_enabled"
    }
}
