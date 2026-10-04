package com.teameow.teawords.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.*
import kotlinx.coroutines.*

@Composable
internal fun TranslationSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { TranslationPreferences(context) }
    var saved by remember { mutableStateOf(preferences.load()) }
    var engine by remember { mutableStateOf(saved.engine) }
    var email by remember { mutableStateOf(saved.email) }
    // Plain credentials are neither part of persisted Compose state nor restored instance state.
    var key by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var offlineAvailable by remember { mutableStateOf(false) }
    val device = remember { lazy { DeviceTranslationProvider() } }
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf<Job?>(null) }
    val uri = LocalUriHandler.current
    DisposableEffect(Unit) { onDispose { action?.cancel(); if (device.isInitialized()) device.value.release() } }
    BackHandler(busy) { }
    LaunchedEffect(engine) {
        if (engine == TranslationEngine.DEVICE) {
            checking = true
            try { offlineAvailable = withTimeoutOrNull(8_000) { device.value.isAvailable() } == true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "离线模型检查失败，可选择在线翻译。" }
            finally { checking = false }
        }
    }
    TeaListPage(title = "句子与长文翻译", containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        subtitle = "选择翻译方式，设置好再保存", onBack = onBack, backEnabled = !busy) {
        item {
            TeaCard {
            TranslationEngine.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().selectable(engine == choice, role = Role.RadioButton, onClick = { if (!busy) { engine = choice; error = null; message = null } }).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = engine == choice, onClick = null, enabled = !busy)
                    Text(choice.label)
                }
            }
            when (engine) {
                TranslationEngine.MYMEMORY -> {
                    TeaCaption("无需密钥，联网即可使用。免费匿名额度为每日 5,000 字符；提供本人有效邮箱后，服务商允许每日 50,000 字符。额度以服务商实际响应为准。")
                    OutlinedTextField(enabled = !busy, value = email, onValueChange = { email = it; error = null }, label = { Text("联系邮箱（可选）") }, singleLine = true)
                    TeaCaption("原文和所填邮箱会发送给 MyMemory；不会发送到其他翻译服务。")
                    TextButton(onClick = { uri.openUri("https://mymemory.translated.net/doc/usagelimits.php") }) { Text("查看额度与服务说明") }
                }
                TranslationEngine.DEEPSEEK -> {
                    TeaCaption("全文一起翻译以保留上下文。需要你自己的 DeepSeek API 密钥，费用由服务商从你的账户扣除。")
                    OutlinedTextField(enabled = !busy, value = key, onValueChange = { key = it; clearKey = false; error = null },
                        label = { Text(if (saved.hasKey && !clearKey) "替换 API 密钥（留空保留）" else "DeepSeek API 密钥") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                    TeaCaption("密钥加密保存在本机，不随备份导出。原文仅发送至 DeepSeek 官方接口。")
                    if (saved.hasKey && !clearKey) TextButton(onClick = { clearKey = true; key = "" }) { Text("清除已保存的密钥") }
                    if (clearKey) TeaCaption("保存后将清除密钥。")
                    TextButton(onClick = { uri.openUri("https://platform.deepseek.com/api_keys") }) { Text("前往 DeepSeek 获取密钥") }
                    TextButton(onClick = { uri.openUri("https://api-docs.deepseek.com/quick_start/pricing/") }) { Text("查看服务商计费说明") }
                }
                TranslationEngine.DEVICE -> {
                    TeaCaption("Google ML Kit 需要先通过 Wi-Fi 下载约 30 MB 模型。原文在本机处理；无法下载时可切回在线翻译。")
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (offlineAvailable) {
                        TeaCaption("离线模型已就绪。")
                        OutlinedButton(enabled = !busy, onClick = {
                            action = scope.launch {
                                busy = true; error = null
                                try { device.value.deleteModel(); offlineAvailable = false; message = "离线模型已删除" }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "删除模型失败，请重试。" }
                                finally { busy = false }
                            }
                        }) { Text("删除离线模型") }
                    } else {
                        Button(enabled = !busy, onClick = {
                            action = scope.launch {
                                busy = true; downloading = true; error = null; message = null
                                try {
                                    check(withTimeoutOrNull(60_000) { device.value.prepare(); true } == true) { "下载等待超时，可选择免下载的在线翻译。" }
                                    offlineAvailable = true; message = "离线模型已就绪，保存设置后即可使用。"
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "离线模型下载失败或超时，可选择免下载的在线翻译。" }
                                finally { busy = false; downloading = false }
                            }
                        }) { Text("通过 Wi-Fi 下载模型 · 约 30 MB") }
                    }
                    if (downloading) {
                        TeaCaption("正在等待 Wi-Fi 或下载模型，最多等待 60 秒。")
                        TextButton(onClick = { action?.cancel(); message = "已取消等待，系统已开始的下载可能继续。" }) { Text("取消等待") }
                    }
                    TextButton(onClick = { uri.openUri("https://developers.google.com/ml-kit/language/translation/translation-terms") }) { Text("查看离线服务说明") }
                }
            }
                error?.let { TeaCaption(it) }
                message?.let { TeaCaption(it) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = !busy && !checking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                    val contact = email.trim()
                    val credential = key.trim()
                    when {
                        engine == TranslationEngine.MYMEMORY && !TranslationSettingsPolicy.validEmail(contact) -> error = "请填写有效的本人邮箱，或留空。"
                        engine == TranslationEngine.DEEPSEEK && credential.isBlank() && !saved.hasKey && !clearKey -> error = "请先填写自己的 API 密钥。"
                        credential.any { it.isWhitespace() } -> error = "密钥不能包含空格或换行。"
                        else -> {
                            action = scope.launch {
                                busy = true; error = null; message = null
                                try {
                                    withContext(Dispatchers.IO) { preferences.save(engine, contact, if (engine == TranslationEngine.DEEPSEEK) credential else "", clearKey) }
                                    saved = preferences.load(); key = ""; clearKey = false
                                    message = "翻译设置已保存"
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "翻译设置保存失败，请重试。" }
                                finally { busy = false }
                            }
                        }
                    }
                }) { Text("保存设置") }
            }
        }
    }
}
