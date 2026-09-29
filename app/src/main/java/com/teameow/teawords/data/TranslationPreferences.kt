package com.teameow.teawords.data

import android.content.Context

data class TranslationSettings(val engine: TranslationEngine, val email: String, val hasKey: Boolean)

/** Shared settings, read again whenever lookup opens so a previous ViewModel cannot use stale routing. */
class TranslationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("lookup_privacy", 0)
    private val secrets = TranslationSecretStore(context)
    fun load(): TranslationSettings {
        val engine = runCatching { TranslationEngine.valueOf(prefs.getString("translation_engine", null).orEmpty()) }.getOrElse {
            if (prefs.getBoolean("translation_online", true)) TranslationEngine.MYMEMORY else TranslationEngine.DEVICE
        }
        return TranslationSettings(engine, prefs.getString("translation_contact", "").orEmpty(), secrets.configured())
    }
    fun save(engine: TranslationEngine, email: String, newKey: String = "", clearKey: Boolean = false) {
        require(engine != TranslationEngine.MYMEMORY || TranslationSettingsPolicy.validEmail(email)) { "请填写有效的本人邮箱，或留空。" }
        require(newKey.none { it.isWhitespace() }) { "密钥不能包含空格或换行。" }
        if (clearKey) secrets.clear() else if (newKey.isNotBlank()) secrets.save(newKey)
        check(prefs.edit().putString("translation_engine", engine.name).putString("translation_contact", email.trim()).commit()) { "翻译设置保存失败，请重试。" }
    }
}
