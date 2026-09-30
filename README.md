# teaWords（茶词）

<img src="docs/brand/teawords-logo.png" alt="茶词图标：茶杯与书页" width="96" />

茶词是一款 Android 单词查询、中英翻译与自适应学习应用，使用 Kotlin、Jetpack Compose 和 Material 3。

## 下载

当前版本 **1.1.0**（versionCode 2），适用于 Android 7.0 及以上。

从 [GitHub Releases](https://github.com/Cydiacoft/teaWords/releases/latest) 下载已签名的 `teaWords-1.2.0.apk`。无需下载翻译模型即可使用默认在线翻译；更新记录见 [CHANGELOG](CHANGELOG.md)。

1.2.0 起，设置中的“检查更新”支持直接下载 APK，显示后台下载进度并在完成后打开系统安装确认。首次安装需允许茶词安装应用；发布包会校验版本、校验和及签名，使用相同发布签名更新可保留数据。

学习页新增“随手练一小轮”：点开始后可选词数或自定义 1–100 个词，混合词义、听音、配对、拼写填空及现有例句的语境题。实际词数受可用词条和每日新词额度影响；暂停后继续原轮次。

## 开发入口

当前工程直接位于仓库根目录，使用 Android Studio 打开克隆后的仓库即可。

## 当前功能

- 离线词典：英文查询、中文反查、词形还原、拼写建议与词书范围选择。
- 句子与长文翻译：默认 MyMemory 联网即译，无需下载；可选 DeepSeek（本人 API 密钥）或 Google 设备端离线翻译。支持系统文本选择、分享与复制译文。
- 回望：独立复习查过的单词（暂不包含句子），保留生词本入口；查阅记录在首页查看。
- 学习：词书选择、快速模式开关、自适应诊断、回忆练习、复习计划和学习统计。
- 发音：英音 / 美音偏好及设备语音支持，详见 [发音说明](PRONUNCIATION.md)。
- 外观：Bing 每日壁纸、一言、自定义首页、浅色 / 深色 / 跟随系统，Android 12 及以上支持动态取色。
- 设置：统一管理句子与长文翻译偏好、外观、发音与学习策略；保留独立更新页、诊断日志和反馈入口。“关于茶词”暂不提供检查更新按钮。
- 菜单：统一 Material 3 Expressive 圆角菜单；查词选项以简洁主菜单和二级选项呈现，查询范围与每轮目标使用相同的选中反馈。

词典采用 ECDICT 数据，共 57,841 个词条，包含中考、高考、四级、六级、考研、雅思、托福和 GRE 标签词书。考试标签不代表官方最新完整大纲；诊断难度和义项拆分具有启发式限制，详见 [交付记录](FEATURE_DELIVERY.md)。

## 构建与验证

需要 JDK 21 与 Android SDK Platform 37；最低运行版本为 Android 7.0（API 24）。Gradle 使用工程自带的 wrapper。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

本机 JDK 路径、离线构建和模拟器验证方法见 [快速入门](QUICK_START.md)。

调试安装包生成于 `app/build/outputs/apk/debug/app-debug.apk`。

发布构建使用 `:app:assembleRelease`；通过环境变量 `TEAWORDS_KEYSTORE`、`TEAWORDS_STORE_PASSWORD`、`TEAWORDS_KEY_ALIAS`、`TEAWORDS_KEY_PASSWORD` 提供自己的发布签名。未提供时输出未签名 APK，不可直接安装。请保留签名文件和密码，后续更新必须使用同一签名。

GitHub 发布包使用独立发布签名，无法直接覆盖 Android Studio 的调试签名安装。切换前请保留数据；卸载调试版会清除其应用数据。也可以继续使用本地同签名调试构建。

## 翻译方式

在「设置 → 句子与长文翻译」中选择翻译方式：

- **MyMemory**：无需密钥，默认在线翻译；匿名服务额度为 5,000 字符/日，提供本人有效联系邮箱可提升至 50,000 字符/日，实际限制以服务商响应为准。长文按 UTF-8 字节拆分，保留换行；失败、取消时保留部分译文，本次会话内重试复用已完成段落。
- **DeepSeek**：填写自己的 API 密钥后，将全文一起发送至官方接口以保留上下文。服务商按用量计费；密钥通过 Android Keystore 加密，存于不参与系统备份的私有目录。未提供密钥时不发请求。
- **设备端离线**：可选 Google ML Kit，先通过 Wi-Fi 下载约 30 MB 模型。模型下载最多等待 60 秒，网络不可达时可切换在线翻译。

单次翻译上限 20,000 字符；超限明确提示，不截断原文。在线翻译会把原文发送给所选服务，失败不会自动切到其他服务。外部应用分享的文本先展示，再由用户点击翻译，不保存查询历史。

接口与额度：[MyMemory API](https://mymemory.translated.net/doc/spec.php)、[额度说明](https://mymemory.translated.net/doc/usagelimits.php)、[DeepSeek API](https://api-docs.deepseek.com/guides/harness)、[DeepSeek 计费](https://api-docs.deepseek.com/quick_start/pricing/)。免配置服务受网络与配额限制，不承诺无限使用。

## 工程结构

- `app/src/main/`：应用源码、界面、离线词典和资源。
- `app/src/test/`：JVM 单元测试。
- `app/src/androidTest/`：设备数据库与界面测试。
- `gradle/`：版本目录和 wrapper。
- `tools/`：词典构建、升级、核查工具及 ECDICT 来源说明。
- `FEATURE_DELIVERY.md`：功能交付、验证结果和已知边界。

## 许可证

应用沿用仓库 [GPL-3.0 许可证](LICENSE)。ECDICT 的 MIT 许可证保留在 `tools/data/ecdict-LICENSE`。完整来源与处理说明见交付记录。

Developed by teaMeow Technology.
