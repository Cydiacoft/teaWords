package com.teameow.teawords.data.pronunciation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teameow.teawords.data.PronunciationDialect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DIALECT_KEY = "pronunciation_dialect"
private val Context.pronunciationDataStore by preferencesDataStore(
    name = "pronunciation",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "teawords_settings", setOf(DIALECT_KEY)))
    }
)

/** One DataStore per process; only the legacy pronunciation key is migrated. */
class PronunciationPreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.pronunciationDataStore)
    private val key = intPreferencesKey(DIALECT_KEY)
    val dialect: Flow<PronunciationDialect> = store.data.map {
        if (it[key] == 1) PronunciationDialect.UK else PronunciationDialect.US
    }
    suspend fun setDialect(value: PronunciationDialect) {
        store.edit { it[key] = if (value == PronunciationDialect.UK) 1 else 0 }
    }
}
