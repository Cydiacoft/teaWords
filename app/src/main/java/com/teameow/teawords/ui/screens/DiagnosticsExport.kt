package com.teameow.teawords.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.teameow.teawords.data.DatabaseHelper
import com.teameow.teawords.data.LocalDictionary
import com.teameow.teawords.data.search.DictionaryGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 → 日志获取。
 *
 * 提交 BUG 时真正需要的只有三样：版本、设备与环境、以及出错前后的日志。这里把它们拼成一段可以
 * 直接粘贴的文本，不写文件、不申请权限、也不读别的应用的日志（Android 4.1 起进程默认只能读到
 * 自己 UID 的 logcat 条目，所以普通应用不需要 READ_LOGS）。
 *
 * 报告里可能出现你查过的词（异常日志会带上下文），所以先展示再让用户决定是否分享，只做复制/分享。
 */
@Composable
internal fun DiagnosticsExport() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    TeaCard {
        Text("诊断报告包含什么", style = MaterialTheme.typography.titleMedium)
        TeaCaption("应用版本、设备与系统、离线词典与学习库的行数，以及本应用自己的最近日志。")
        TeaCaption("日志可能包含你查过的词；提交前请先读一遍。不读取其他应用的日志，也不需要额外权限。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    note = null
                    scope.launch {
                        report = withContext(Dispatchers.IO) { buildReport(context) }
                        busy = false
                        note = "报告已生成，可直接复制或分享"
                    }
                }
            ) { Text(if (report.isBlank()) "生成诊断报告" else "重新生成") }
            if (report.isNotBlank()) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(report))
                    note = "已复制到剪贴板"
                }) { Text("复制") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "茶词诊断报告")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(send, "分享诊断报告"))
                }) { Text("分享") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        note?.let { TeaCaption(it) }
    }
    if (report.isNotBlank()) {
        OutlinedTextField(
            value = report,
            onValueChange = {},
            readOnly = true,
            label = { Text("诊断报告") },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp)
        )
    }
}

private fun count(helper: DatabaseHelper, table: String): Long? = runCatching {
    helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }
}.getOrNull()

private fun buildReport(context: Context): String {
    val helper = DatabaseHelper(context)
    return try {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrElse { "未知" }
        val repository = DictionaryGateway.sourceOrNull()
        val dictionary = when {
            repository != null -> buildString {
                append("${repository.wordCount()} 词条")
                append(" · schema v${runCatching { repository.schemaVersion() }.getOrElse { -1 }}")
                runCatching { repository.meta("built_at") }.getOrNull()?.let { append(" · 构建 $it") }
            }
            DictionaryGateway.isReady() -> "已就绪，但读取词条数失败"
            else -> "未就绪 · ${DictionaryGateway.error() ?: "正在安装"}"
        }
        buildString {
            appendLine("茶词诊断报告")
            appendLine("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("应用版本: $version")
            appendLine("包名: ${context.packageName}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}")
            appendLine("离线词典: $dictionary")
            appendLine(
                "本地词库: " + runCatching { "${LocalDictionary(helper).count()} 词条" }.getOrElse { "读取失败" }
            )
            appendLine("学习库行数:")
            listOf(
                "learning_knowledge" to "知识",
                "learning_events" to "答题事件",
                "sense_units" to "义项",
                "user_sense_knowledge" to "义项知识",
                "test_records" to "测试记录",
                "vocabulary" to "生词本",
                "query_history" to "查询历史"
            ).forEach { (table, label) ->
                val rows = count(helper, table)
                appendLine("  - $label ($table): ${rows ?: "表不存在或读取失败"}")
            }
            appendLine("最近日志（仅本应用进程，最多 200 行）:")
            appendLine(readRecentLog())
        }
    } finally {
        helper.close()
    }
}

/** `logcat -v threadtime` 的第三列是 PID；只保留本进程的行，避免把系统日志一起贴进去。 */
private val LOGCAT_PID = Regex("""^\S+\s+\S+\s+(\d+)\s""")

private fun readRecentLog(): String = runCatching {
    val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "400")
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    val pid = android.os.Process.myPid().toString()
    val lines = text.lineSequence()
        .filter { line -> LOGCAT_PID.find(line)?.groupValues?.get(1) == pid || line.contains("AndroidRuntime") }
        .toList()
        .takeLast(200)
    if (lines.isEmpty()) "（本次进程还没有日志）" else lines.joinToString("\n")
}.getOrElse { "读取 logcat 失败：${it.message ?: it::class.simpleName}" }
