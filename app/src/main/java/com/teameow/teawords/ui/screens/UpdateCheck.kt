package com.teameow.teawords.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.UpdateChecker
import com.teameow.teawords.data.UpdateInfo
import com.teameow.teawords.data.isNewerVersion
import com.teameow.teawords.data.isComparableVersion
import kotlinx.coroutines.launch

private sealed interface UpdateOutcome {
    data object Checking : UpdateOutcome
    /** 仓库里没有任何 release / tag，无从比较。 */
    data object NoRelease : UpdateOutcome
    data class Found(val info: UpdateInfo, val newer: Boolean) : UpdateOutcome
    data class Failed(val reason: String) : UpdateOutcome
}

/**
 * 设置 → 关于App 里的「检查更新」。
 *
 * 视觉上跟「回望」「学习」的主卡一致：一整块 `secondaryContainer` 大圆角卡 + 前导圆形图标 +
 * 一个主操作，状态用一行 pill 表达。读 GitHub 公开 API 只做版本比较：不下载、不自动安装、
 * 不上传设备或账号信息；没有 release 也没有 tag 时如实说明，不谎报「已是最新」。
 */
@Composable
internal fun UpdateCheckCard(currentVersion: String, currentVersionCode: Long) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker() }
    var outcome by remember { mutableStateOf<UpdateOutcome?>(null) }
    val checking = outcome is UpdateOutcome.Checking

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(AppSymbols.Update, null, Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 页面顶栏已经写了「检查更新」，卡片里不再重复标题，只说当前装的是什么。
                    Text("当前版本 $currentVersion ($currentVersionCode)", style = MaterialTheme.typography.titleLarge)
                    Text("GitHub 公开仓库 Cydiacoft/teaWords", style = MaterialTheme.typography.bodyMedium)
                }
            }

            when (val state = outcome) {
                null -> Unit
                UpdateOutcome.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取公开仓库的发布信息…", style = MaterialTheme.typography.bodyMedium)
                }
                UpdateOutcome.NoRelease -> StatusPill(
                    icon = AppSymbols.Info,
                    text = "仓库里暂无可比较的发布版本或标签，暂时无法比较版本号。",
                    container = MaterialTheme.colorScheme.surfaceContainerLow,
                    content = MaterialTheme.colorScheme.onSurface
                )
                is UpdateOutcome.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        icon = AppSymbols.Close,
                        text = state.reason,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "仓库本身可以直接在浏览器里打开。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is UpdateOutcome.Found -> if (state.newer) {
                    NewVersionBlock(state.info) { uri.openUri(state.info.pageUrl) }
                } else {
                    StatusPill(
                        icon = AppSymbols.Check,
                        text = if (isComparableVersion(currentVersion)) "未发现更新版本 · 仓库版本为 ${state.info.tag}"
                            else "当前版本号无法比较 · 仓库版本为 ${state.info.tag}",
                        container = MaterialTheme.colorScheme.surfaceContainerLow,
                        content = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    enabled = !checking,
                    onClick = {
                        outcome = UpdateOutcome.Checking
                        scope.launch {
                            outcome = checker.latest().fold(
                                onSuccess = { info ->
                                    if (info == null) UpdateOutcome.NoRelease
                                    else UpdateOutcome.Found(info, isNewerVersion(info.tag, currentVersion))
                                },
                                onFailure = { UpdateOutcome.Failed(it.message ?: "未知错误") }
                            )
                        }
                    }
                ) { Text(if (outcome == null) "检查更新" else "重新检查") }
                TextButton(onClick = { uri.openUri(UpdateChecker.RELEASES_URL) }) { Text("打开发布页") }
            }

            Text(
                "只比较版本号：不下载安装包、不自动安装、不上传任何设备信息。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            )
        }
    }
}

/**
 * 「项目仓库与反馈」入口。放在设置 → 日志获取页：那一页就是为了提交 BUG，仓库/issue 才是报告的去处。
 */
@Composable
internal fun RepositoryCard() {
    val uri = LocalUriHandler.current
    Card(
        onClick = { uri.openUri(UpdateChecker.PAGE_URL) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(AppSymbols.Feedback, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("项目仓库与反馈", style = MaterialTheme.typography.titleMedium)
                Text(
                    UpdateChecker.PAGE_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(AppSymbols.ChevronRight, null)
        }
    }
}

/** 一行状态：图标 + 一句话，用于「已是最新」「没有发布」「检查失败」。 */@Composable
private fun StatusPill(icon: ImageVector, text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = container, contentColor = content) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 发现新版本：版本号给足字号，说明最多四行，动作只有一个「打开发布页」。 */
@Composable
private fun NewVersionBlock(info: UpdateInfo, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("发现新版本 ${info.tag}", style = MaterialTheme.typography.headlineSmall)
            info.name.takeIf { it.isNotBlank() && it != info.tag }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall)
            }
            info.publishedAt?.take(10)?.let {
                Text("发布于 $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (info.fromTag) {
                Text(
                    "该版本来自 git 标签，没有发布说明。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (info.notes.isNotBlank()) {
                Text(
                    info.notes.trim().take(600),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onOpen) { Text("打开发布页") }
            Text(
                "更新需要你手动下载安装，应用不会自动替换已安装的版本。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
