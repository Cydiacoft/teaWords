package com.teameow.teawords.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

data class PendingAppUpdate(val id: Long, val tag: String, val fileName: String, val asset: UpdateApk)

sealed interface AppDownloadStatus {
    data class Progress(val downloaded: Long, val total: Long, val waiting: Boolean = false) : AppDownloadStatus
    data class Complete(val file: File) : AppDownloadStatus
    data class Failed(val message: String) : AppDownloadStatus
}

/** Downloads continue through Android's download service if the update page or app is closed. */
class AppUpdateManager(context: Context) {
    private val app = context.applicationContext
    private val downloads = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val directory get() = File(checkNotNull(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "updates")

    fun pending(): PendingAppUpdate? = runCatching {
        prefs.getString("pending", null)?.let { gson.fromJson(it, PendingAppUpdate::class.java) }
            ?.takeIf { it.id > 0 && trustedUpdateUrl(it.asset.url) && it.fileName.matches(Regex("teaWords-update-[a-zA-Z0-9.-]+\\.apk")) }
    }.getOrNull()

    fun start(info: UpdateInfo): PendingAppUpdate {
        val asset = checkNotNull(info.apk) { "这个版本还没有可下载的 APK，请打开发布页查看" }
        require(trustedUpdateUrl(asset.url) && asset.size in 1..536_870_912L) { "安装包地址或大小无效" }
        clear()
        check(directory.mkdirs() || directory.isDirectory) { "无法创建更新下载目录" }
        val fileName = "teaWords-update-${info.tag.replace(Regex("[^a-zA-Z0-9.-]"), "-")}.apk"
        val file = File(directory, fileName)
        if (file.exists()) check(file.delete()) { "无法替换之前的安装包，请重试" }
        val id = downloads.enqueue(DownloadManager.Request(Uri.parse(asset.url))
            .setTitle("茶词 ${info.tag}")
            .setDescription("正在下载更新，完成后可安装")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName"))
        return PendingAppUpdate(id, info.tag, fileName, asset).also {
            check(prefs.edit().putString("pending", gson.toJson(it)).commit()) { "无法保存下载记录" }
        }
    }

    fun clear() {
        pending()?.let { downloads.remove(it.id) }
        prefs.edit().remove("pending").apply()
    }

    fun status(pending: PendingAppUpdate): AppDownloadStatus = downloads.query(DownloadManager.Query().setFilterById(pending.id)).use { cursor ->
        if (!cursor.moveToFirst()) return@use AppDownloadStatus.Failed("下载记录已失效，请重新下载")
        fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
        when (number(DownloadManager.COLUMN_STATUS).toInt()) {
            DownloadManager.STATUS_SUCCESSFUL -> AppDownloadStatus.Complete(File(directory, pending.fileName))
            DownloadManager.STATUS_FAILED -> {
                val reason = number(DownloadManager.COLUMN_REASON).toInt()
                AppDownloadStatus.Failed(when (reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重试"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用，请检查后重试"
                    else -> "下载失败（$reason），请检查网络后重试，也可打开发布页下载"
                })
            }
            else -> AppDownloadStatus.Progress(number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                number(DownloadManager.COLUMN_STATUS).toInt() != DownloadManager.STATUS_RUNNING)
        }
    }

    /** Verify the downloaded bytes and upgrade identity before offering the system installer. */
    fun validate(pending: PendingAppUpdate, file: File) {
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.name == pending.fileName) { "安装包路径无效" }
        require(file.isFile && file.length() == pending.asset.size) { "安装包不完整，请重新下载" }
        pending.asset.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(expected, true)) {
                "安装包校验未通过，请重新下载"
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("安装包无法读取，请重新下载")
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        require(archive.packageName == app.packageName) { "安装包与当前应用不匹配" }
        val remoteVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(remoteVersion > installedVersion) { "这个版本已经安装，无需重复更新" }
        if (Build.VERSION.SDK_INT >= 28) {
            val old = checkNotNull(installed.signingInfo).apkContentsSigners.map { it.toCharsString() }.toSet()
            val new = checkNotNull(archive.signingInfo)
            val signers = new.apkContentsSigners.map { it.toCharsString() }.toSet()
            val history = if (new.hasMultipleSigners()) emptySet() else new.signingCertificateHistory.map { it.toCharsString() }.toSet()
            require(old == signers || old.size == 1 && old.all { it in history }) { SIGNATURE_MISMATCH }
        } else {
            require(installed.signatures?.map { it.toCharsString() }?.toSet() == archive.signatures?.map { it.toCharsString() }?.toSet()) { SIGNATURE_MISMATCH }
        }
    }

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = ClipData.newRawUri("茶词更新", uri) }
    }

    companion object {
        private const val SIGNATURE_MISMATCH = "此安装包与当前应用签名不同，无法保留数据覆盖更新。请使用与你当前版本同签名的安装包。"
    }
}
