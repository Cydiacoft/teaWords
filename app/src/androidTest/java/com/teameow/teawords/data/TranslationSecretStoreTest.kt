package com.teameow.teawords.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranslationSecretStoreTest {
    @Test fun credentialsAreEncryptedExcludedFromBackupAndRemovable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "secret-test-${System.nanoTime()}"
        val store = TranslationSecretStore(context, name)
        try {
            assertFalse(store.configured())
            store.save("synthetic-test-key")
            assertTrue(store.configured())
            assertEquals("synthetic-test-key", TranslationSecretStore(context, name).read())
            assertFalse(File(context.noBackupFilesDir, name).readText().contains("synthetic-test-key"))
            store.clear()
            assertFalse(store.configured())
            assertEquals("", store.read())
        } finally { store.clear() }
    }
}
