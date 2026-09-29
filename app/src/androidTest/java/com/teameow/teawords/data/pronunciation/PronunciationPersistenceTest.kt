package com.teameow.teawords.data.pronunciation

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.teameow.teawords.data.PronunciationDialect
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PronunciationPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyDialectMigratesAndSurvivesDataStoreReopen() = runBlocking {
        val name = "pronunciation-test-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$name.preferences_pb")
        val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        legacy.edit().putInt("pronunciation_dialect", 1).putString("unrelated", "keep").commit()
        var job = SupervisorJob()
        fun store() = PreferenceDataStoreFactory.create(
            migrations = listOf(SharedPreferencesMigration(context, name, setOf("pronunciation_dialect"))),
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var pref = PronunciationPreferences(store())
            assertEquals(PronunciationDialect.UK, pref.dialect.first())
            assertEquals("keep", legacy.getString("unrelated", null))
            assertFalse(legacy.contains("pronunciation_dialect"))
            pref.setDialect(PronunciationDialect.US)
            job.cancelAndJoin()
            job = SupervisorJob()
            pref = PronunciationPreferences(store())
            assertEquals(PronunciationDialect.US, pref.dialect.first())
        } finally {
            job.cancelAndJoin(); file.delete(); context.deleteSharedPreferences(name)
        }
    }

    @Test fun independentIpaVariantsKeepAttributionAndDoNotTouchLearningDatabase() = runBlocking(Dispatchers.IO) {
        // Unique synthetic fixture; never seed fabricated IPA for real dictionary entries.
        val word = "pronunciation-fixture-${UUID.randomUUID()}"
        val source = PronunciationAttribution("test-only", "https://example.test/fixture", "test-fixture", "", "Synthetic test data", 1L)
        val repository = WordPronunciationRepository(context)
        val learningFile = context.getDatabasePath("teawords.db")
        val modified = learningFile.lastModified()
        try {
            repository.save(WordPronunciationEntity(word, PronunciationRegion.UK, "source-a", "UK-fixture", source))
            repository.save(WordPronunciationEntity(word, PronunciationRegion.US, "source-a", "US-fixture", source))
            repository.save(WordPronunciationEntity(word, PronunciationRegion.UNSPECIFIED, "source-b", "unlabelled", source))
            val entries = WordPronunciationRepository(context).lookup(word.uppercase())
            assertEquals(3, entries.size)
            assertEquals("UK-fixture", entries.single { it.region == PronunciationRegion.UK }.ipa)
            assertEquals("US-fixture", entries.single { it.region == PronunciationRegion.US }.ipa)
            assertTrue(entries.all { it.ipaAttribution == source })
            assertEquals(modified, learningFile.lastModified())
        } finally {
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("pronunciations.db").path, null, 0).use {
                it.delete("word_pronunciations", "word=?", arrayOf(word))
            }
        }
    }

    @Test fun cacheRequiresLicenseAndVerifiedHash() {
        val dir = File(context.cacheDir, "voice-test-${UUID.randomUUID()}")
        val cache = LicensedPronunciationCache(dir)
        val source = PronunciationAttribution("test", "https://example.test", "test-fixture", "", "test", 1)
        val asset = RecordedPronunciation("https://example.test/audio", source,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", true, true)
        try {
            assertTrue(runCatching { cache.store(asset.copy(cachingPermitted = false), "hello".byteInputStream()) }.isFailure)
            assertTrue(runCatching { cache.store(asset, "wrong content".byteInputStream()) }.isFailure)
            cache.store(asset, "hello".byteInputStream())
            assertEquals("hello", cache.find(asset)!!.readText())
            assertNull(cache.find(asset.copy(playbackPermitted = false)))
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
}
