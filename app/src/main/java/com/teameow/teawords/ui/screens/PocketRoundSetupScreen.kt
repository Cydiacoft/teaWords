package com.teameow.teawords.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.teameow.teawords.data.PocketPlanner

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PocketRoundSetupScreen(
    initialWordLimit: Int, busy: Boolean, error: String?, onBack: () -> Unit, onStart: (Int) -> Unit
) {
    var input by rememberSaveable { mutableStateOf(initialWordLimit.toString()) }
    val count = input.toIntOrNull()?.takeIf { it in 1..PocketPlanner.MAX_WORD_LIMIT }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun start() {
        if (!busy && count != null) {
            focus.clearFocus()
            keyboard?.hide()
            onStart(count)
        }
    }
    DisposableEffect(Unit) { onDispose { focus.clearFocus(force = true); keyboard?.hide() } }
    TeaListPage(title = "准备小练习", subtitle = "按你现在的空闲时间，选一轮的词数",
        onBack = onBack, backEnabled = !busy, loading = busy) {
        item {
            TeaCard {
                Text("这轮想练几个词？", style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 20).forEach { preset ->
                        FilterChip(selected = count == preset, onClick = {
                            input = preset.toString()
                            focus.clearFocus()
                            keyboard?.hide()
                        }, enabled = !busy, label = { Text("$preset 个词") })
                    }
                }
                OutlinedTextField(value = input, onValueChange = {
                    input = it.filter { char -> char in '0'..'9' }.take(3)
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text("自定义词数") }, isError = input.isNotEmpty() && count == null,
                    supportingText = { Text("可输入 1–${PocketPlanner.MAX_WORD_LIMIT} 个词") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { start() }))
                TeaCaption("优先练到期的词。实际词数受可用词条和每日新词额度影响。")
                TeaCaption("词数会记住，练到一半也可以暂停，下次接着来。")
                error?.let { TeaCaption(it) }
                Button(onClick = ::start, enabled = !busy && count != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("开始这一轮") }
            }
        }
    }
}
