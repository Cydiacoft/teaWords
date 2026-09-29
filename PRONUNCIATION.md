# 英式 / 美式双发音：实现、数据来源与扩展接口

本轮交付：Android 系统 TTS 的 UK / US 双口音发音，覆盖词典详情页、单词学习与快速排雷，并把音标与音频的数据来源、许可证和真人录音扩展点独立出来。

相关代码：`data/pronunciation/`（4 个文件）、`ui/screens/PronunciationComponents.kt`；接入点 `LookupScreen`、`LearningScreen`、`MainScreen`、`SettingsScreen`、`MainActivity`、`ProcessTextActivity`。

---

## 1. 结论速览

| 需求 | 状态 | 证据 |
|---|---|---|
| ① 词典详情页同时显示 UK / US 两种发音 | 已完成 | 截图 `validation/pronunciation/dictionary-dual-tts.png`；UI dump 中同时存在「UK 发音」「US 发音」 |
| ② 分别保存英式 / 美式 IPA，不用复制同一音标伪造 | 已完成 | `WordPronunciationEntity.region`；`PronunciationPersistenceTest` 断言 UK/US/未标注三条记录各自独立 |
| ③ 用 Android TextToSpeech 实现基础发音 | 已完成 | `AndroidSpeechBackend` |
| ④ 用 `Locale.UK` / `Locale.US` 区分口音 | 已完成 | `PronunciationDialect.locale` |
| ⑤ 检查实际可用 Voice，优先精确地区匹配 | 已完成 | `matchingVoices()`；每次播放前重新查询 |
| ⑥ 资源不可用时明确提示，不静默换口音 | 已完成 | `missingRegionDoesNotSpeakThroughOtherVoice`（UK 缺失时不调用 US） |
| ⑦ 可复用的 PronunciationManager | 已完成 | 初始化 / 播放 / 停止 / 错误 / 释放统一在一个类 |
| ⑧ 词典页两个独立发音按钮 | 已完成 | `DualPronunciation` 内两个 `FilledTonalButton` |
| ⑨ DataStore 保存默认发音偏好 | 已完成 | `PronunciationPreferences` + 旧 SharedPreferences 迁移 |
| ⑩ 学习 / 快速排雷 / 词典搜索复用 | 已完成 | 三处都走 `PronunciationManager` |
| ⑪ 预留真人录音数据源与本地缓存 | 已完成（接口层，未接入真实音频） | `WordPronunciationSource`、`LicensedPronunciationCache` |
| ⑫ 发音资源与知识状态分离 | 已完成 | 独立 `pronunciations.db`；测试断言学习库 `lastModified` 不变 |

**音标数据的真实状态（重要）**：ECDICT 只提供**未标注口音**的单一音标，不能假定它含英美双音标。因此英美音标卡目前显示「暂无经来源确认的英式/美式音标」，而不是拿 ECDICT 音标去填充其中一边或两边。这是设计选择，不是缺陷 —— 详见第 4 节。

---

## 2. 界面

`DualPronunciation(word, basicPhonetic, basicSource)` 渲染两张并列卡片：

```
tomato
英式与美式发音
┌──────────────────────────────────────┐
│ UK · 英音                    [UK 发音] │
│ 暂无经来源确认的英音音标               │
│ en-GB · 本地系统合成                   │
└──────────────────────────────────────┘
┌──────────────────────────────────────┐
│ US · 美音                    [US 发音] │
│ 暂无经来源确认的美音音标               │
│ en-US · 本地系统合成                   │
└──────────────────────────────────────┘
基础音标（来源未标注口音）· təˈmɑːtəʊ · ECDICT
发音由系统 TTS 合成；音标来自独立词典数据，合成语音不等于真人录音。
```

要点：

- **两个按钮互相独立**：各自绑定 `PronunciationDialect.UK` / `US`，不存在「一个按钮循环切换口音」。
- **音标按地区过滤**：只展示 `region == UK/US` 且 `ipa` 非空的记录；ECDICT 的基础音标单独一行并明确标注「来源未标注口音」，不会被当作英音或美音。
- **语音可用状态逐地区显示**：`en-GB · 本地系统合成` / `en-US · 需要网络` / `UK 语音不可用 · 请安装 en-GB 语音资源`。
- **底部声明**明确「合成语音不等于真人录音」，避免把 TTS 当成真人发音。
- 单词学习与快速排雷用 `DefaultPronunciationButton`，只播放默认口音，**不显示单词拼写**（避免在拼写测试里泄露答案），且只在词可见时出现。

---

## 3. 语音选择规则（核心：绝不跨口音回退）

`matchingVoices()` 的匹配条件与排序：

| 规则 | 实现 |
|---|---|
| 只接受**精确地区**匹配 | `locale.language == "en"` **且** `locale.country == "GB"`/`"US"` |
| 通用英语 / 澳洲 / 另一地区语音一律不算匹配 | 语言或国家任一不同即排除 |
| 排除未安装的语音包 | `KEY_FEATURE_NOT_INSTALLED` |
| 离线优先 | 先按 `networkRequired` 升序，再按 quality 降序、latency 升序、name |
| 播放前**重新查询** | 资源可能在两次播放之间被卸载或下载 |
| `setVoice` 后**回读校验** | `tts.voice` 的 name + language + country 必须与请求一致，否则视为失败 |
| 无匹配则**不播放** | 直接进入 `ERROR` 并提示需安装对应 `toLanguageTag()` 语音 |

> 这里刻意不用 `TextToSpeech.setLanguage(Locale.UK)`：它会在缺少英式语音时**静默退回**通用英语或另一种口音，这正是需求 ⑥ 禁止的行为。

播放链路还有这些保障：

- 只使用地区匹配的 voice；`select()` 返回 false 时立即失败，**不会沿用上一次选中的 voice**。
- 播放请求带自增 id，`UtteranceProgressListener` 回调会校验 id；**过期回调不会影响新的播放**（`staleCallbacksCannotCompleteNewPlayback`）。
- `stop()` 会作废旧 id，离开页面 / 切换单词时调用；`ON_STOP` 生命周期也会停。
- 音频焦点用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，结束或停止时归还；被其他音频抢占时给出「发音已被其他音频中断」。
- 看门狗：初始化 15 秒、播放 30 秒，超时给出明确文案而不是永久转圈。
- 错误分类可见：引擎初始化失败 / 无法读取语音资源 / 地区语音缺失 / 启动失败 / 合成失败（带错误码）/ 被中断 / 超时。

`PronunciationManager` 是主线程持有的 `AutoCloseable`：`close()` 停止播放、取消看门狗、`shutdown()` 引擎、进入 `RELEASED`，且**幂等**（`stopInvalidatesCallbacksAndReleaseIsIdempotent`）。它依赖 `SpeechBackend` 接口，因此上述行为可在无语音资源的环境里确定性测试。

---

## 4. 为什么音标大多显示「暂无」，以及如何接入真实英美音标

### 4.1 不伪造、不转换

- ECDICT 的 `phonetic` 字段是**单一且未标注口音**的（例如 `təˈmɑːtəʊ`）。把它同时填进 UK 和 US 两张卡，就是需求 ② 明确禁止的「复制同一个音标伪造双音标」。
- **没有任何字符串替换式的英音→美音转换**：已核查 `app/src/main` 全部代码，`.replace(...)` 仅用于换行处理（`\\n`），不存在 IPA 音位改写逻辑。
- 所以 `region = UNSPECIFIED` 只用于 ECDICT 基础音标，UI 把它单独成行并标注来源；英美列只认真实来源。

### 4.2 接入真实英美音标（数据层已就绪，无需改 UI）

`WordPronunciationRepository.save()` 写入后，词典页会自动按 `region` 展示。示例：

```kotlin
val repo = WordPronunciationRepository(context)
repo.save(
    WordPronunciationEntity(
        word = "tomato",
        region = PronunciationRegion.UK,
        sourceRecordId = "wiktionary-tomato-uk",
        ipa = "/təˈmɑːtəʊ/",
        ipaAttribution = PronunciationAttribution(
            sourceId = "wiktionary",
            sourceUrl = "https://en.wiktionary.org/wiki/tomato",
            licenseId = "CC BY-SA 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
            attribution = "Wiktionary contributors",
            retrievedAt = System.currentTimeMillis()
        )
    )
)
```

`validate()` 会强制以下约束，写入不合格的数据会直接失败：

- `word` 与 `sourceRecordId` 非空；
- `ipa` 与 `recording` **至少有一个**；
- 有 `ipa` 就必须有 `ipaAttribution`；
- `recording` 的 URL 必须是 `https://`，`sha256` 必须是 64 位十六进制；
- `attribution` 必须给出 `sourceId` / `sourceUrl` / `licenseId` / `attribution` 四项。

**一个词在同一地区可以有多条记录**（多来源、多变体），互不覆盖；`lookup()` 按 `region, source_record_id` 排序返回全部。

---

## 5. 数据模型与「来源 / 许可证」归属

独立于 `lex_words` 与 `learning_knowledge`，存放在**单独数据库** `pronunciations.db`：

```sql
CREATE TABLE word_pronunciations (
  word TEXT NOT NULL,            -- normalizePronunciationWord() 归一化
  region TEXT NOT NULL,          -- UK / US / UNSPECIFIED
  source_record_id TEXT NOT NULL,
  payload TEXT NOT NULL,         -- WordPronunciationEntity 的 JSON
  PRIMARY KEY (word, region, source_record_id)
)
```

- **音标与音频各自的来源可以不同**，因此 `ipaAttribution` 与 `recording.attribution` 是**两个独立字段**，不是共用一个来源。
- 不同 provider 的本地 record id 可能重名：存储主键用「两个资产来源 + 原始 record id」拼出的命名空间，原始 id 仍完整保留在 payload 里，便于回溯。
- 词形归一化 `normalizePronunciationWord()` **与口音无关**，所以英音、美音不会产生两个不同的词身份。

---

## 6. 真人录音扩展接口（需求 ⑪）

### 6.1 数据源端口

```kotlin
interface WordPronunciationSource {
    suspend fun lookup(word: String): List<WordPronunciationEntity>
}
```

`WordPronunciationRepository` 是当前唯一实现（本地库）。后续真人录音适配器实现同一接口即可接入，UI 无需改动。

### 6.2 录音资产与合规门禁

```kotlin
data class RecordedPronunciation(
    val url: String,
    val attribution: PronunciationAttribution,
    val sha256: String,
    val playbackPermitted: Boolean = false,   // 默认关闭
    val cachingPermitted: Boolean = false     // 默认关闭
)
```

`LicensedPronunciationCache` 的约束：

| 约束 | 行为 |
|---|---|
| 播放 / 缓存许可 | `playbackPermitted && cachingPermitted` 缺一不可，否则拒绝 |
| 许可证标识 | `licenseId` 不能为空 |
| 完整性 | 文件名即 `sha256`，读取与写入都重新计算摘要比对 |
| 体积上限 | 单文件 5 MB |
| 写入原子性 | 先写临时 `.part` 再改名，避免半成品被当成有效缓存 |
| **不自动联网** | 只接受适配器传入的**已授权** `InputStream`；库本身**绝不按 URL 抓取** |

使用示例（抓取与授权由接入方负责）：

```kotlin
val cache = LicensedPronunciationCache(File(context.cacheDir, "pronunciation"))
val file = cache.find(asset) ?: cache.store(asset, authorizedAdapter.openStream(asset))
```

> 当前**没有打包任何真人音频，也没有接入任何外部录音源**。这是刻意的：音频授权未逐条核实之前不引入。TTS 也永远不会去下载录音。

---

## 7. 默认口音偏好（DataStore）

- `preferencesDataStore(name = "pronunciation")`，键 `pronunciation_dialect`。
- **迁移**：`SharedPreferencesMigration(context, "teawords_settings", setOf("pronunciation_dialect"))`。旧实现把该键作为 `PronunciationDialect.ordinal` 存在 SharedPreferences（`US=0`、`UK=1`），新实现按 `1 → UK，其余 → US` 解读，语义完全对应，迁移后旧键被删除、同文件的其它键保留。
- 设置页「发音设置」页提供 US / UK 选择，并注明「默认口音用于学习和快速排雷；词典页仍可分别播放 UK 与 US」，下方是 `PronunciationResourcePanel`（逐地区显示可用性 + 「试听 · tomato」）。
- 读取失败 / 保存失败都有可见错误与「重试」，并且**不会在磁盘写入完成前就宣称保存成功**。

---

## 8. 复用点

| 场景 | 组件 | 口音 |
|---|---|---|
| 词典详情（含应用外划词 `ProcessTextActivity`） | `DualPronunciation` | UK + US 两个独立按钮 |
| 单词学习 | `DefaultPronunciationButton` | 默认口音（DataStore） |
| 快速排雷 | `DefaultPronunciationButton` | 默认口音 |
| 设置页 | `PronunciationResourcePanel` | 两地区试听 + 可用性 |
| 生命周期 | `ProvidePronunciation` + `ON_STOP` | 离开即停止 |

所有入口共用同一个 `PronunciationServices`（`PronunciationManager` + `PronunciationPreferences`），没有第二份 TTS 实例或第二套偏好读取逻辑。

---

## 9. 本轮验证（全部在 Pixel 9 Pro / API 36 模拟器与当前代码上重跑）

| 项目 | 命令 | 结果 |
|---|---|---|
| 单元测试 | `gradlew testDebugUnitTest` | **112 项，0 失败**（其中 `VoiceSelectionTest` 6 项） |
| 发音设备测试 | `gradlew connectedDebugAndroidTest -P...class=Pronunciation*Test,DualPronunciationTest` | **12 项，0 失败** |
| 全量设备测试 | `gradlew connectedDebugAndroidTest` | 67 项中 **5 项失败，均与发音无关**（根因见第 11 节） |
| 真实引擎审计 | `PronunciationDeviceTest` | UK → `en-GB-language` / `en-US` 无；US → `en-US-language`；两者 `networkRequired=false`、`phase=READY`（合成完成回调） |
| 界面 | 截图 + UI dump | `validation/pronunciation/dictionary-dual-tts.png`、`dictionary.xml` |

真实引擎审计原文（`validation/pronunciation/device-audit.json`）：

```json
[{"dialect":"UK","phase":"READY","voice":"en-GB-language","locale":"en-GB","networkRequired":false},
 {"dialect":"US","phase":"READY","voice":"en-US-language","locale":"en-US","networkRequired":false}]
```

这说明两次播放都命中了**地区精确匹配**的语音并收到完成回调，没有发生跨口音回退。

关键回归测试：

- `accentsRequireExactRegion` / `oppositeAccentIsNeverFallback` / `missingVoicePacksAreExcluded` —— 只有精确地区才匹配。
- `missingRegionDoesNotSpeakThroughOtherVoice` —— 只有 US 语音时点 UK，`speak` **零调用**且提示含 `en-GB`。
- `rejectedVoiceDoesNotSpeakUsingPreviousVoice` —— 选音被拒时不会沿用上一个 voice。
- `staleCallbacksCannotCompleteNewPlayback` —— 过期回调不干扰新请求。
- `ukAndUsSelectSeparateExactVoices` —— 两个口音选中各自的 `Locale`。
- `twoButtonsDoNotInventIpaOrFallbackAndWordChangeStopsPlayback` —— 两个按钮、不臆造音标、切词即停。
- `independentIpaVariantsKeepAttributionAndDoNotTouchLearningDatabase` —— 三条独立记录、归属保留、**学习库文件未被改动**。

---

## 10. 已知边界

1. **未接入真实英美音标数据源**：词典页因此显示「暂无经来源确认的…音标」。这是不伪造的必然结果，不是渲染缺陷。要显示音标，必须提供带许可证的 UK / US 分开的音标数据（见 4.2）。
2. **未接入真人录音**：只完成了接口、许可证门禁与缓存校验；无任何音频资产。
3. **音质取决于系统 TTS 引擎**，应用只负责选对地区和正确播放/报错；某些设备可能两个地区都缺资源，此时两个按钮都会给出安装提示。
4. **`PronunciationDeviceTest` 依赖模拟器已安装 TTS 资源**：本机命中本地 `en-GB`/`en-US`；在缺少资源的设备上该测试会走「明确不可用」分支（断言仍成立，但审计内容不同）。
5. **发音不参与任何学习记录**：刻意不把「听过发音」写成知识状态，避免无意义的进度膨胀。

---

## 11. 附录：全量设备测试中 5 项失败的根因（与本功能无关）

全量 67 项中有 5 项失败，**全部位于词典/义项模块，与发音代码无关**；单独运行这 5 项所在的测试类时 **13/13 全部通过**。

失败项：`LocalDictionaryTest.offlineLookupReverseFormsAndTypoShareAnIdentity`、`SensePersistenceTest`（`senseUnitsAreDerivedFromTheGloss`、`aReviewWritesBothTheMemoryStateAndTheEvidence`、`aRevealedAnswerIsRecordedButDoesNotVerify`、`knowledgeSurvivesReopeningTheDatabase`）。

**根因（已用二分实验证实）**：`LearningBooksTest.setUp()` 调用了 `DictionaryGateway.open(context)`。`DictionaryGateway` 是**进程级单例**（`@Volatile repository` / `engine`），而它的 `@After` 只关闭并删除自己的 `learning-books-test.db`，**从不释放网关**。instrumentation 的所有测试类共享一个进程，于是网关一旦被打开，后续测试就都「装着一部 57,841 词的参照词典」：

- `SensePersistenceTest.seedWord()` 用 `LocalDictionary(helper)`，其默认来源是 `ShippedDictionarySource.processWide`。`exact("issue")` 因此解析到参照词典的词条（`old.zh` 非空），而 `upsert` 只在 `old.zh.isBlank() || source.startsWith("ECDICT")` 时写入 `zh`——测试用的 `source = "测试"` 两条都不满足，**本地 `lex_words.zh` 始终为空**，`deriveMissingSenseUnits()` 扫描 `WHERE zh <> ''` 便一无所获 → 断言「至少这个词要产出义项」失败，随后 `sensesFor(wordId).first()` 抛 `NoSuchElementException`。
- `LocalDictionaryTest` 虽然注入了 `localOnly` 规避检索泄漏，但 `LearningRepository.save()` 内部**自己新建** `LocalDictionary(helper)`（`LearningRepository.kt:58`），用的是默认的 `processWide`，于是 `dictionary.exact(state.word)?.id`（第 59 行）取到参照词典的**全局词条 ID 27101** 并写进本地 `learning_knowledge.word_id`，断言 `expected:<1> but was:<27101>`。

复现实验（决定性）：

| 运行的测试类 | 结果 |
|---|---|
| `SensePersistenceTest, LocalDictionaryTest, LearningPersistenceTest` | 13/13 通过 |
| `DictionaryAcceptanceTest, SensePersistenceTest` | 20/20 通过 |
| `LearningBooksTest, SensePersistenceTest, LocalDictionaryTest` | **同样这 5 项失败** |

**建议修复（测试侧，未擅自改动）**：给 `DictionaryGateway` 增加测试用 `reset()` 并在 `LearningBooksTest.@After` 调用；或让 `SensePersistenceTest` 也显式注入 `ShippedDictionarySource.localOnly`（项目在查词模块已经采用并记录过这一模式，见 `FEATURE_DELIVERY.md` 的「旧内联索引会抢答新词典」补记）。这属于既有测试隔离缺陷，本轮发音改动未触及相关代码。
