package com.teameow.teawords.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 学习页的主操作卡。设计稿给的是 380×220 的 `primaryContainer` 大色块：标题在最上、计数在标题
 * 下方，96dp 的圆形「开始」按钮压在右下角，而不是被拉伸成一整条——拉伸后按钮会变成一条横杠，
 * 既不像按钮也丢掉了「一次点击就能开始」的视觉重点。
 */
@Composable
internal fun ExpressiveLearningPlan(due: Int, fuzzy: Int, unseen: Int, busy: Boolean, onStart: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth(0.62f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("开始学习", style = MaterialTheme.typography.displayMedium)
                Text(
                    "$due/$fuzzy/$unseen",
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp, fontWeight = FontWeight.Medium)
                )
                Text("到期 / 模糊 / 待排查", style = MaterialTheme.typography.labelMedium)
            }
            FilledIconButton(
                onClick = onStart,
                enabled = !busy,
                modifier = Modifier.align(Alignment.BottomEnd).size(96.dp)
            ) {
                Icon(AppSymbols.ArrowForward, "开始学习", Modifier.size(40.dp))
            }
        }
    }
}

/** Round size and a single toggle select what the main start button does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearningControls(limit: Int?, onLimitChange: (Int) -> Unit, quickMode: Boolean, onQuickModeChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("每轮目标", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp))
            Box {
                val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "目标菜单箭头")
                FilledTonalButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(limit?.let { "$it 个词" } ?: "请选择", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(AppSymbols.ExpandMore, null, Modifier.size(24.dp).rotate(angle))
                }
                TeaDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, sections = listOf(
                    TeaMenuSection(listOf(10, 15, 20).map { count ->
                        TeaMenuOption("$count 个词", AppSymbols.School, selected = count == limit) { onLimitChange(count); expanded = false }
                    })
                ))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("快速模式", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = quickMode, onCheckedChange = onQuickModeChange,
                modifier = Modifier.semantics { contentDescription = "快速模式" })
        }
    }
}
