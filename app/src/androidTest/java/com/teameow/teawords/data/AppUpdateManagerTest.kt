package com.teameow.teawords.data

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppUpdateManagerTest {
    @Test fun installerUsesPrivateContentUriAndCurrentVersionCannotReinstall() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppUpdateManager(context)
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        val file = File(directory, "teaWords-update-test.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            val intent = manager.installIntent(file)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val pending = PendingAppUpdate(1, "v1.2.0", file.name, UpdateApk(file.name,
                "https://github.com/Cydiacoft/teaWords/releases/download/v1.2.0/test.apk", file.length()))
            try { manager.validate(pending, file); fail("Already installed version must be rejected") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("已经安装")) }
            try { manager.validate(pending.copy(asset = pending.asset.copy(sha256 = "0".repeat(64))), file); fail("Checksum must be checked") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("校验未通过")) }
        } finally { file.delete() }
    }
}
