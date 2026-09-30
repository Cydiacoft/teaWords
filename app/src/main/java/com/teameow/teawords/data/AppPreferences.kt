package com.teameow.teawords.data

import android.content.Context

enum class PronunciationDialect(val label: String) {
    US("美音"),
    UK("英音")
}

enum class SubtitleMode {
    CUSTOM,
    HITOKOTO
}

/** Appearance preference. SYSTEM follows the device; the other two pin the app. */
enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

/**
 * How aggressively to trade coverage against time.
 *
 * This is user preference storage only: the numbers that drive scheduling live in
 * `LearningStrategy` in the algorithm package, which the decision engine reads. The two are linked
 * by `id` so a stored preference can never invent thresholds the engine does not implement.
 */
enum class StudyStrategyPreference(val id: String, val label: String, val detail: String) {
    EFFICIENCY("EFFICIENCY", "效率优先", "更少测试与复习，跳过较可能已掌握的词"),
    BALANCED("BALANCED", "均衡", "在效率与覆盖面之间取平衡"),
    COVERAGE("COVERAGE", "覆盖优先", "增加验证与复习，减少遗漏");

    companion object {
        fun fromId(id: String?): StudyStrategyPreference =
            values().firstOrNull { it.id == id } ?: BALANCED
    }
}

enum class LearningWordbook(
    val id: String,
    val title: String,
    val subtitle: String
) {
    CET4("cet4", "大学英语四级", "CET-4"),
    CET6("cet6", "大学英语六级", "CET-6"),
    IELTS("ielts", "雅思 IELTS", "IELTS"),
    TOEFL("toefl", "托福 TOEFL", "TOEFL"),
    GRE("gre", "GRE", "GRE"),
    KY("ky", "考研英语", "考研"),
    ZK("zk", "中考词汇", "中考"),
    GK("gk", "高考词汇", "高考"),
    TEM4("tem4", "英语专业四级", "TEM-4"),
    TEM8("tem8", "英语专业八级", "TEM-8");

    companion object {
        val available get() = entries.filter { it != TEM4 && it != TEM8 }
        fun fromId(id: String): LearningWordbook? = values().firstOrNull { it.id == id }
    }
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("teawords_settings", Context.MODE_PRIVATE)

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
        get() = prefs.getBoolean(KEY_BING_WALLPAPER_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BING_WALLPAPER_ENABLED, value).apply()
        }

    var themeMode: AppThemeMode
        get() = AppThemeMode.values().getOrElse(
            prefs.getInt(KEY_THEME_MODE, AppThemeMode.SYSTEM.ordinal)
        ) { AppThemeMode.SYSTEM }
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value.ordinal).apply()
        }

    /**
     * Material You 动态取色。默认关闭：设计稿的 `dynamicColor` 是 false、palette 固定为 purple，
     * 所以默认保持品牌配色，用户显式打开后才跟随系统壁纸配色（仅 Android 12+ 生效）。
     */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }

    var predictiveBackEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREDICTIVE_BACK, false)
        set(value) { prefs.edit().putBoolean(KEY_PREDICTIVE_BACK, value).apply() }

    var studyStrategy: StudyStrategyPreference
        get() = StudyStrategyPreference.fromId(prefs.getString(KEY_STUDY_STRATEGY, null))
        set(value) {
            prefs.edit().putString(KEY_STUDY_STRATEGY, value.id).apply()
        }

    /** Daily cap override; 0 means "use the strategy's own cap". */
    var dailyNewCapOverride: Int
        get() = prefs.getInt(KEY_DAILY_NEW_CAP, 0)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_NEW_CAP, value).apply()
        }

    /** Target retention override; 0 means "use the strategy's own target". */
    var targetRetentionOverride: Double
        get() = prefs.getFloat(KEY_TARGET_RETENTION, 0f).toDouble()
        set(value) {
            prefs.edit().putFloat(KEY_TARGET_RETENTION, value.toFloat()).apply()
        }

    companion object {
        private const val KEY_SELECTED_WORDBOOK_IDS = "selected_wordbook_ids"
        private const val KEY_HOME_TITLE = "home_title"
        private const val KEY_HOME_SUBTITLE = "home_subtitle"
        private const val KEY_HOME_SUBTITLE_MODE = "home_subtitle_mode"
        private const val KEY_HOME_LAST_CLEARED = "home_last_cleared"
        private const val KEY_HITOKOTO_REFRESH_INTERVAL = "hitokoto_refresh_interval"
        private const val KEY_BING_WALLPAPER_ENABLED = "bing_wallpaper_enabled"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_PREDICTIVE_BACK = "predictive_back_enabled"
        private const val KEY_STUDY_STRATEGY = "study_strategy"
        private const val KEY_DAILY_NEW_CAP = "daily_new_cap"
        private const val KEY_TARGET_RETENTION = "target_retention"
    }
}
