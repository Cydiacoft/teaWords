package com.teameow.teawords.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.teameow.teawords.R
import com.teameow.teawords.data.UpdateChecker

@Composable
fun AboutIdentity() {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.teawords_logo), "茶词图标", Modifier.size(80.dp))
        Text("茶词 · teaWords", style = MaterialTheme.typography.headlineMedium)
        Text("版本 ${installedVersionName()}", style = MaterialTheme.typography.bodyMedium)
        TeaCaption("查词、翻译，慢慢记住。")
    }
}

@Composable
fun AboutCredits() {
    val uri = LocalUriHandler.current
    var libraries by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TeaCard {
            Text("用茶词做什么", style = MaterialTheme.typography.titleLarge)
            Text("查词 · 离线查英文单词、中文释义和词形。")
            Text("翻译 · 中英句子与长文翻译，支持复制译文。")
            Text("回望 · 巩固查过的生词，暂不包含句子。")
            Text("学习 · 选择词书，快速排雷，再通过回忆练习巩固。")
        }
        TeaCard {
            Text("数据与翻译服务", style = MaterialTheme.typography.titleLarge)
            AboutSource("ECDICT", "内置离线词典 · MIT 许可证", "https://github.com/skywind3000/ECDICT")
            AboutSource("MyMemory", "免配置在线翻译，有每日免费额度", "https://mymemory.translated.net/doc/usagelimits.php")
            AboutSource("DeepSeek", "可选长文翻译 · 需本人 API 密钥并按用量计费", "https://api-docs.deepseek.com/quick_start/pricing/")
            AboutSource("Google ML Kit", "可选设备端翻译 · 需先下载语言模型", "https://developers.google.com/ml-kit/language/translation/translation-terms")
            TeaCaption("查词使用本地词典。在线翻译会把原文发送给所选服务商；设备端离线翻译在本机处理。")
            TeaCaption("查阅记录与学习进度保存在本机，可在查询历史中关闭记录。外部应用分享的输入不保存查询历史。")
        }
        TeaCard {
            Text("项目与反馈", style = MaterialTheme.typography.titleLarge)
            Text("遇到问题或有新想法，欢迎到项目仓库反馈。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uri.openUri(UpdateChecker.PAGE_URL) }, modifier = Modifier.weight(1f)) { Text("项目源码") }
                OutlinedButton(onClick = { uri.openUri("${UpdateChecker.PAGE_URL}/issues") }, modifier = Modifier.weight(1f)) { Text("反馈问题") }
            }
            TextButton(onClick = { uri.openUri("${UpdateChecker.PAGE_URL}/blob/main/LICENSE") }) { Text("应用开源许可 · GPL-3.0") }
        }
        TeaCard {
            TextButton(onClick = { libraries = !libraries }) { Text(if (libraries) "收起开源组件与致谢" else "开源组件与致谢") }
            if (libraries) {
                Text("Jetpack Compose / Material 3 · 界面与交互")
                Text("OkHttp / Gson · 网络请求与数据解析")
                Text("Coil · 图片加载")
                AboutSource("Bing 每日壁纸", "可选的首页背景", "https://www.bing.com")
                AboutSource("Hitokoto 一言", "可选的首页文字", "https://hitokoto.cn")
            }
        }
        TeaCaption("teaMeow Technology")
    }
}

@Composable
private fun AboutSource(name: String, summary: String, url: String) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { uri.openUri(url) }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
