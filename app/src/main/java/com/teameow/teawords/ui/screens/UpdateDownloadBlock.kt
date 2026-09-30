package com.teameow.teawords.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.teameow.teawords.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
internal fun UpdateDownloadBlock(info: UpdateInfo?, currentVersion: String) {
    val context = LocalContext.current
    val manager = remember { AppUpdateManager(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pending by remember { mutableStateOf(manager.pending()?.takeIf { isNewerVersion(it.tag, currentVersion) }) }
    var status by remember { mutableStateOf<AppDownloadStatus?>(null) }
    var verified by remember { mutableStateOf<File?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var installRequested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) installRequested = true
        else message = "尚未允许安装。允许茶词安装应用后，再点“安装更新”即可。"
    }

    LaunchedEffect(pending?.id, lifecycle) {
        val download = pending ?: return@LaunchedEffect
        status = null
        verified = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    val next = withContext(Dispatchers.IO) { manager.status(download) }
                    status = next
                    if (next is AppDownloadStatus.Complete) {
                        verifying = true
                        try {
                            withContext(Dispatchers.IO) { manager.validate(download, next.file) }
                            verified = next.file
                        } finally { verifying = false }
                        break
                    }
                    if (next is AppDownloadStatus.Failed) break
                    delay(800)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = AppDownloadStatus.Failed(e.message ?: "下载读取失败，请重试") }
        }
    }
    LaunchedEffect(verified, installRequested) {
        val file = verified ?: return@LaunchedEffect
        if (!installRequested) return@LaunchedEffect
        installRequested = false
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                message = "首次安装更新需允许茶词安装应用，开启后返回即可继续。"
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } else {
                context.startActivity(manager.installIntent(file))
                message = "请在系统安装页面确认更新。"
            }
        } catch (e: Exception) { message = "无法打开安装页面：${e.message}" }
    }
    LaunchedEffect(Unit) {
        val old = manager.pending()
        if (old != null && !isNewerVersion(old.tag, currentVersion)) withContext(Dispatchers.IO) { manager.clear() }
    }

    fun begin(source: UpdateInfo) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                val started = withContext(Dispatchers.IO) { manager.start(source) }
                verified = null
                status = null
                pending = started
                installRequested = true
            } catch (e: Exception) { message = e.message ?: "无法开始下载，请重试" }
            finally { busy = false }
        }
    }
    val source = info?.takeIf { it.apk != null && isNewerVersion(it.tag, currentVersion) } ?: pending?.let {
        UpdateInfo(it.tag, it.tag, "", UpdateChecker.RELEASES_URL, null, apk = it.asset)
    }
    if (source == null && pending == null) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pending == null) {
            Button(onClick = { source?.let(::begin) }, enabled = !busy) { Text(if (busy) "正在开始下载…" else "下载并安装") }
        } else {
            Text("更新 ${pending!!.tag}", style = MaterialTheme.typography.titleMedium)
            when (val progress = status) {
                is AppDownloadStatus.Progress -> {
                    if (progress.total > 0) LinearProgressIndicator(progress = {
                        (progress.downloaded.toFloat() / progress.total).coerceIn(0f, 1f)
                    }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    fun mb(bytes: Long) = String.format(Locale.CHINA, "%.1f MB", bytes / 1_048_576.0)
                    TeaCaption(if (progress.waiting) "正在等待网络，下载会在后台继续"
                        else "${mb(progress.downloaded)} / ${if (progress.total > 0) mb(progress.total) else "未知大小"}")
                }
                is AppDownloadStatus.Failed -> {
                    Text(progress.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { source?.let(::begin) }, enabled = !busy) { Text("重新下载") }
                }
                is AppDownloadStatus.Complete -> {
                    if (verifying) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TeaCaption("正在核对安装包、版本和签名…")
                    } else if (verified != null) {
                        TeaCaption("下载完成，安装包已核对")
                        Button(onClick = { installRequested = true }, enabled = !busy) { Text("安装更新") }
                    }
                }
                null -> TeaCaption("正在读取下载进度…")
            }
            TextButton(onClick = {
                if (!busy) {
                    busy = true
                    installRequested = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { manager.clear() }
                            pending = null; status = null; verified = null; message = null
                        } catch (e: Exception) { message = "无法取消下载：${e.message}" }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy) { Text(if (verified != null) "清除安装包" else "取消下载") }
        }
        message?.let { TeaCaption(it) }
    }
}
