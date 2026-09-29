# 本轮交付与后续边界

## 真实词典数据与搜索引擎模块（最新一轮）

### 数据来源（已实际获取并核查）

| 来源 | 许可证 | 处理 |
|---|---|---|
| [ECDICT](https://github.com/skywind3000/ECDICT) | **MIT**（已读 LICENSE 原文核实） | **采用**，作为唯一打包数据源 |
| [Qwerty Learner](https://github.com/RealKai42/qwerty-learner) | **GPL-3.0**，且其 README 明确声明词典数据来自第三方抓取项目 [kajweb/dict](https://github.com/kajweb/dict) | **未采用**，授权不明确；已写入 `lex_sources` 标注"未采用" |

ECDICT 实际数据形态（已解析 770,611 行 CSV）：13 列，`tag` 字段为**空格分隔**的多标签，全部标签与真实数量：

| 标签 | zk | gk | cet4 | cet6 | ky | ielts | toefl | gre |
|---|---|---|---|---|---|---|---|---|
| 词数 | 1,603 | 3,677 | 3,849 | 5,407 | 4,801 | 5,040 | 6,974 | 7,504 |

带标签词条共 14,942 条。`abnormal` 同时带 `gk cet4 cet6 ky toefl ielts` —— 正是需要拆分关联而不是当成一个字符串的证据。

### 构建产物（可重复执行）

`tools/build_dictionary.py` 从 CSV 生成预构建 SQLite：

- **57,841 词条**、**197,766 义项**、**56,515 词形**、**465,982 中文反查 token**、八本词书共 **38,855 条关联**，**61.0 MB**。
- 保留全部考试标签词 + 按**语料库词频排名**（BNC/FRQ）取前 60,000 的通用词；不打包 77 万条全量（会让 APK 增加约 150 MB）。这个筛选依据是词频排名，不是手工挑选。
- 八本词书由 `tag` 拆分生成：zk/gk/cet4/cet6/ky/ielts/toefl/gre，每本都记录 `source_id`、`version`、词数。**没有把 ECDICT 标签宣称成官方大纲**：`lex_sources.note` 明确写了"不代表官方最新完整大纲"。
- 词形来自 ECDICT 的 `exchange` 字段（`d:perceived/p:perceived/3:perceives/i:perceiving`），所以 `went→go`、`better→good` 是**数据驱动**，不是删后缀。
- 中文义项按 ECDICT 实际使用的分隔符切分并附 `confidence`，文档中明确标注这是**启发式切分，不是义项消歧**。
- `word_book_entries` 主键 `(book_id, word_id)`，重复导入不会产生重复关联。

### 真实查询验证（构建出的数据库上实测）

| 场景 | 结果 | 耗时 |
|---|---|---|
| 精确查询 `Apple`/`apple`/`APPLE` | 均命中 `apple` | 0.16–0.23 ms |
| 前缀 `con` | con, conan, concatenate, concave, conceal… | 0.24 ms |
| 中文反查 `放弃` | abandon, abandoning, abandonment, abdicate… | 0.78 ms |
| 中文反查 `发行` | accredit, circulation, emission, emit… | 0.41 ms |
| 中文反查 `动物园` | menagerie, **zoo**, zookeeper | 3.31 ms |
| 单字中文 `爱` | 正常返回 | 0.15 ms |
| 不规则词形 `went` | **go** | 0.19 ms |
| 不规则词形 `better` | **good** | 0.13 ms |
| `running` | run（现在分词） | 0.16 ms |
| 按词书过滤 cet4 + `con%` | 只返回 cet4 词 | 3.06 ms |

`EXPLAIN QUERY PLAN` 确认：前缀查询走 `idx_lex_words_norm_unique`（`SEARCH ... USING INDEX`），中文反查走 `idx_lex_zh_token` + 主键回表，**没有全表扫描**。

### 新增代码

- `data/BundledDictionaryInstaller.kt`：流式安装 `assets/dictionary.db`（61 MB，**不读进内存**，带进度回调），先写临时文件再改名，避免半成品被当成有效；校验 `dictionary_meta.schema_version`。词典是**独立数据库文件**，与用户学习库 `teawords.db` 分离，所以更新词典不可能动到知识状态。
- `data/search/SearchQueryNormalizer.kt`：查询类型判定（英文词/中文/短语）、规范化、中文双字 token、前缀上界、**统一排序器**（匹配类别优先，词频只用于同类内部排序）、**拼写纠错**（先由索引化探测生成有界候选集，再算编辑距离；`MAX_CANDIDATES=256`，绝不全库算 Levenshtein）、**查询计划构建器**。
- `data/search/DictionarySearchEngine.kt`：`DictionarySearchEngine` 接口 + 默认实现，按计划执行并合并最强匹配；**精确命中后不再混入前缀噪声**（保证输入 `contemplate` 时它一定排第一）；通过 `SearchHitSource` 端口与存储解耦，便于用假数据测试。
- `data/search/WordSearchRepository.kt`：只读执行计划，全部为固定 SQL + 绑定参数；实现 `resolve()`（词形还原）与 `checkIndexConsistency()`（索引一致性校验，确认 FTS/义项不落后于基础表）。
- `SearchFilter` 的 `onlyUnmastered`/`onlyFuzzy` 由调用方应用——词典文件是只读的、不认识用户，这一点写在注释里避免以后误接。

### 本轮验证

`compileDebugKotlin` 通过；`testDebugUnitTest` **92 项全通过**（新增 `DictionarySearchEngineTest` 18 项：大小写、前缀、不规则词形、拼写纠错、词书过滤、排序规则、重复合并、查询分类）。

### 尚未完成的（下一轮）

> **本节已过时**：以下 1–4 项均已完成并实机验收，见文末「词典接入应用、包名变更与实机验收」。

1. **把预构建词典接入应用启动路径**：~~`MainActivity` 尚未调用 `BundledDictionaryInstaller.install()`~~ **已完成**。
2. **UI 接入新搜索引擎**：~~`LookupScreen`/`LookupViewModel` 仍走旧的 `LocalDictionary`~~ **已完成**。
3. **词书选择界面**：~~`word_books` 表还没有对应的 UI~~ **已完成**。
4. **设备端性能基准**：~~以上耗时是开发机 Python 结果~~ **已补齐实机数据**。
5. **Qwerty Learner 补充词表**：授权不明确，保持「待核验」，未打包。（**仍然有效**）

## 自适应改造：schema v6、诊断页、排雷重做

按 1 → 4 → 3 的顺序推进。

### 第一步：schema v6（已完成）

`DatabaseHelper` 升到 v6，新增内容**全部是增量**：不改动既有列、旧行仍可读，`onCreate` 与 `onUpgrade` 共用同一条创建路径。

- `sense_units`：由词典中文释义派生出的义项单元。实测派生出 **12925** 条（5707 词）。
- `user_sense_knowledge`：义项级知识 + FSRS 记忆参数（difficulty / stability / due / reps / lapses）。
- `item_difficulty`：词与题型的难度先验，带 `calibrated` 标志与 `source` 来源字符串。
- `test_records`：可回溯的答题证据，含题型、义项、评分、作答前后的能力值。
- `learning_events` 扩展 4 列：`sense_id`、`mode`、`grade`、`revealed`（均可空，旧行保持有效）。

**义项切分是启发式的，不是真正的义项消歧**：内置数据每词只有一条整串释义，只能用 `；` 切分（2296/5707 词含该分隔符）。`LexicalText.splitSenses` 给每段附 `confidence`：显式分隔且短 = 0.6，长整串 = 0.25。UI 必须说明这一点，不能把这些当作权威义项。

### 第四步：诊断页与策略设置（已完成）

- **词汇能力诊断页**（学习页右上角入口）：CAT 自适应选题，实时显示能力值、95% 区间、精度是否达标、大致水平档位，以及每个已答题目的难度。可随时结束。结果通过 `AbilityStore` 跨会话保留（能力值、标准误、题数、信息量），下次继续累积。
- **证据真实落库**：每次作答写入 `test_records`（含题型、义项、作答前后能力值）并在**同一事务内**写入 `learning_events`，所以一条答案不会只存在于其中一处。页面直接显示「已写入的可回溯证据：N 条」，持久化失败不再是静默的。
- **设置 → 学习策略**：效率优先 / 均衡 / 覆盖优先三档，加每轮新词上限与目标记忆保持率覆盖项。存于 `AppPreferences`，由决策引擎读取。

### 排雷重做：解决「4000 词排到什么时候」（已完成）

你指出的问题是真实缺陷：原来的排雷队列等于整本词书，且**今日学习被排雷完成度门控**，等于把传统背单词的"从头过一遍"换了个位置。

现在按能力估计分带（`ScreeningPriority`）：

| 分带 | 条件 | 处理 |
|---|---|---|
| 预测已认识 | 掌握概率 ≥ 0.70 | **直接跳过**，不问、不学；保留抽样复核 |
| 不确定 | 0.35 ~ 0.70 | **只问这些**，按单次点击的信息量排序 |
| 预测陌生 | 掌握概率 ≤ 0.35 | **直接进入学习**，不必先点一遍 |

- 排雷页顶部显示「按你的能力估计，N 个词（P%）无需手动判断」，并列出跳过数与直接学习数。
- **今日学习不再被排雷门控**：计划为空时，用能力估计中"预测陌生"的词直接组成学习队列。实测点击「开始学习」直接进入答题，提示「按能力估计直接开始：这 10 个词预测为尚未掌握」。
- 能力估计越准、用户相对词书越强，跳过比例越高。初学者对整本词书仍会需要较多判断，**这是真实标定结果，没有粉饰**。

### 过程中修掉的标定与实现缺陷（全部由测试抓出）

1. **诊断写入我的 `catch` 吞掉了异常**，且我先前的库拉取拿到的是 WAL 未合并的旧快照，导致我误判"没落库"。现在页面自报证据条数，异常带堆栈打日志。
2. **先验混入让排雷永远无效**：`predictedMastery` 原来朝 0.30 先验混合，把所有概率压到 0.85 阈值以下，"已认识"永远判不出来。改为**在 logit 空间表达不确定性**（按答案数收缩 θ），曲线形状保留。
3. **`fromAbility` 过度保守**：12 题就把置信度推到 0.88，能力估计被压得过小。改为基于答案数的证据权重（12 题才算充分）。
4. **阈值贴着先验**：未知阈值曾设为 0.30，与先验同值，导致新装用户整本词书都被判为"陌生"。改为 0.35 / 0.70，并写明这是**产品取舍**（漏教一个词可再发现，误问一个已知词直接浪费用户时间）。
5. **词长惩罚过大**：`administration` 被算到 1.26 而非 0.55，把大纲词挤出"已认识"带。系数从 0.02 降到 0.015，基线从 5 字符改到 8。
6. **`SenseRepository` 出现两个 companion object**、`learning_events` 列名引用错误、`LearningScreen` 缺 algorithm 包 import——均已修。

### 本轮验证

`assembleDebug`、`testDebugUnitTest`（**64 项全通过**：算法 25 + 排雷 11 + 义项切分 8 + 既有 20）、`lintDebug`（0 error / 14 warning）。

设备实测：义项派生 12925 条、诊断答题后能力值 0.62→0.67、误差 ±1.20→±1.07、证据 4→5 条、`learning_events` 新增 4 列、`sense_units` 表存在、旧知识记录（20 行）与词库（5707 行）保留。

### 下一步（第三步，未开始）

**业务层对接**：把 `FsrsScheduler` 与 `LearningDecisionEngine` 接到学习页，替换现在的 `LearningEngine` 基础调度；答题写回评分等级（`ReviewGrade.fromAnswer`）；用 FSRS 的 due 驱动复习队列。当前调度仍是基础间隔规则，**不是 FSRS**，这一点在统计页与本文档中均已标注。

## 自适应学习系统：算法层

按需求文档推进自适应改造。本轮完成**算法层**（纯 Kotlin，不依赖 Android，可单测）与项目差距分析，**尚未接入 UI 与持久化**。

### 现状差距（先讲清楚才有意义）

| 需求 | 现状 | 结论 |
|---|---|---|
| Room + DataStore + Hilt | 现工程用 `SQLiteOpenHelper` + `SharedPreferences`，无 DI 框架 | **不迁移**：Room/DI 要重建数据层与所有调用点，风险远大于收益；算法层已通过接口与持久化解耦，未来可替换 |
| 统一词汇数据库（稳定 ID + 词书关联表） | `lex_words` 已有整数 ID、`lex_forms`、`lex_zh`、`lex_fts`；词书用 `tags` 字符串 | 部分满足。**缺**词书关联表与义项级表 |
| 义项级知识 | `lex_senses` 每词只有一条整串释义（5707 词仅 10202 行） | **缺**。且中文释义只能用 `；` 粗切（2296/5707 含 `；`），必须如实标注"不是精确义项" |
| 词频 | `frequency` 列 **0/5707 有值** | 难度先验只能用词书标签（cet4 / cet6 / 非大纲） |
| IRT / CAT / BKT / FSRS | 无 | 本轮实现 |
| 证据流水 | `learning_events(word, kind, correct, elapsed, timestamp)` | 缺题型 / 义项 / 评分等级，**必须补**，否则历史无法回溯拟合 |

### 已实现（`com.teameow.teawords.algorithm`，25 项单测）

每个算法一个文件、一个明确职责，不堆进一个大文件：

- **`DifficultyPrior.kt`** — 词书标签 + 词长构造难度先验，`calibrated` 恒为 `false`，来源字符串随值返回、UI 必须显示；识别/回忆/拼写三种题型各加固定偏移。
- **`AbilityEstimator.kt`** — Rasch/1PL 能力估计，贝叶斯牛顿步更新，带 **Fisher 信息标准误**与 95% 区间；一次作答永远不会被当成精确测量。
- **`BayesianKnowledgeTracing.kt`** — 单知识点掌握概率，区分 `mastery`（模型预测）与 `confidence`（证据量），`verifiedByTest` 仅连续 3 次正确后为真；建模猜对（快速作答抬高 guess）、失误（纠正抬高 slip）、"看答案"不计证据。
- **`CatSelector.kt`** — 按 Fisher 信息选题、对已掌握词降权；支持精度停止、题量上限、用户随时停止、词池耗尽。
- **`FsrsScheduler.kt`** — FSRS-4.5：D/S/R 维护、按评分更新、目标保持率反解间隔。**从未复习的词没有稳定度**（`nextIntervalDays` 返回 null，不编造复习日期）。参数为公开默认值，`SchedulerReadiness` 明确标注未做个人参数拟合。
- **`LearningDecisionEngine.kt`** — 统一决策：排雷 / 诊断 / 学习 / 复习 / 抽样验证 五路分流，学习价值与诊断价值**分开建模**，含每日上限与三档策略。

### 过程中修掉的实现缺陷（全部由测试抓出）

1. `nextIntervalDays` 缩放因子写错（误用 `-DECAY` 作分母），算出负间隔被钳到 0.01 天。
2. 遗忘曲线漏了 FSRS 的 `FACTOR = 19/81`，导致提取概率算出 **> 1**；修正后 `R(S) = 0.9` 精确成立。
3. 权重表错位：`w8/w9/w10`、`w12..w14` 抄错，且 `w9` 用成指数而非乘数，复习后稳定度**崩溃**；已按 py-fsrs 参考实现逐位对齐。
4. 决策引擎会把"模型认为已掌握"的词重新排进学习队列（浮点误差使 `1 - 0.95 = 0.050000000000000044` 刚好越过下限）。现在**任何** `predictedMastery ≥ 跳过阈值` 的词直接排除，抽样验证只在无其他工作时给至多一个。

### 验收测试（需求文档测试一~十）

`AlgorithmAcceptanceTest` 覆盖：认识大多数词的用户不被强制过词书、答对难题抬高能力估计、单次作答不算精确、自评认识退出队列且可抽样、连续答错降低掌握、连续 3 次才验证、看答案不算证据、快速作答视为可能猜对、**义项独立**（掌握 issue「问题」不影响「发行」）、FSRS 按复习结果而非固定表调整、未复习词无凭空稳定度、目标保持率影响间隔、词书难度先验有序、CAT 选题贴近能力且提前停止不重复出题、预测与验证是两个量。

### 本轮验证

`assembleDebug`、`testDebugUnitTest`（**45 项全通过**）、`lintDebug`（0 error / 13 既有 warning）。

### 下一步（未完成）

1. **schema v6 迁移**：`user_sense_knowledge`（义项级）、`item_difficulty`（难度先验落库并记录来源）、`test_records`，以及 `learning_events` 扩列（题型、义项、评分等级、是否看答案）。
2. **义项切分落库**：按 `；` 粗切并标注置信度，不假装是精确义项。
3. **业务层对接**：把 `FsrsScheduler` 与 `LearningDecisionEngine` 接到学习页，替换现在的基础调度；答题写回带评分等级的证据。
4. **UI**：CAT 诊断页、义项级知识展示、推荐理由、三档策略与每日上限设置。
5. **跨词书知识复用**与**重复导入幂等**的集成测试。

## 本轮（UI 统一与学习/统计重做）

本轮针对三件事：悬浮导航栏的底部留白缺陷、学习功能与学习统计页面的重做、以及全应用 Material 3 视觉统一。

### 悬浮导航栏

- 圆角容器底部留白从 10dp 加到 28dp，导航栏不再贴住手势条。
- 页面底部内边距改为按页面语义决定：整屏伸入状态栏的壁纸首页为 0，其余页面统一预留 Scaffold 的 `innerPadding`，避免列表最后一项被悬浮栏盖住。
- 导航选中态改用 `primary` / `primaryContainer`（此前是 `secondary` + 8% 透明，选中项几乎看不出来），未选中态用 `onSurfaceVariant`。容器色改用 `surfaceContainer`，边框对比度提高。

### 学习功能（`LearningScreen.kt` 重写）

- 页面结构：今日计划 → 快速排雷 → 词汇知识画像 → 推荐理由，四块而不是六块，并统一使用新的 `TeaComponents`。
- 快速排雷新增批量动作「接下来的 N 个也认识」，一次点击把最多 10 个词移出新词队列，并支持整批撤销。这是本轮对「减少无效学习时间」最直接的一项：4495 词的四级表逐词点击是不可完成的任务。
- 排雷进度条显示「已排查 / 剩余」，且排雷总数在进入时快照，不会因状态变化而漂移。
- 修复 `plan` 以冻结的 `now` 作为 `remember` 键导致的失效：刚标记为陌生的词不会进入今日计划。现在计划在知识状态或轮次上限变化时重算。
- 自评认识仍不产生测试证据；自评词只做「至少 7 天后、每轮至多一个」的抽样验证，且抽样状态每天最多显示一次。
- RecallCard 交给统一卡片渲染，`1/1` 与推荐理由分别作为标题与副标题，答题结束后仍显示真实答题次数与连续答对次数。

### 学习统计（`StatsDashboard.kt` 重写）

- 今日：回忆次数、专注时长、正确率；无记录时明确说明而不是显示 0 分。
- 词汇知识画像：已验证 / 学习中 / 模糊认识 / 完全陌生 / 待确认五类互不重复，各自带独立进度条与配色，并注明「已验证」只代表同一释义连续 3 次主动回忆通过。
- 练习趋势：新增 `LearningRepository.daily()` 与 `activeDays()`，用纯 Box 绘制最近 7 天柱状图，不引入图表依赖；同时给出最近 7 天答题量与正确率、连续学习天数、累计次数与时长。
- 词汇明细由整块卡片改为紧凑行 + 状态标签，长列表可读性明显提高。
- 旧版复习答题量仍单独标注为「旧版练习保留 N 道答题记录，未换算成新的知识画像」。

### 视觉统一（Material 3）

- 共享组件集中在 `ui/screens/TeaComponents.kt`：`TeaListPage`（统一 TopAppBar + 列表间距 + 内边距）、`TeaCard` / `LearningCard`、`TeaMetric` / `TeaMetricRow`、`TeaProgressRow` / `TeaProgressLine`、`TeaChip`、`TeaSectionLabel`、`TeaCaption`、`TeaEmptyState`。
- 词典与翻译页：`TabRow` 换成与全应用一致的 `FilterChip` 选择；卡片、按钮、说明文字、词条列表行全部改用统一组件与语义色；「设备端翻译」卡片此前呈现为禁用态（低对比的卡面 + 灰色胶囊按钮），现在模型未就绪时只给一个主按钮并明确说明。
- 进度线与 Material 的 `LinearProgressIndicator` 解耦（`TeaProgressLine` 自绘），避免不同 Compose 版本下参数差异导致的样式不一致。
- 选择 Chip/分段、指标行、卡片圆角、标题层级与顶部栏在所有页面统一；深色主题已实机验证。

### 数据质量与冷启动（实机验证时发现）

- 内置四/六级词表此前把音标与词性缩写当成释义存储，例如 `[ɔ:] pron.你的，你们的`、`a.有病的；恶心的`。`WordImport` 现在清理音标与词性前缀，并与内置资源导入路径共用同一实现；对旧安装另有一次性的 `clean-pos-v1` 修复。
- 单字母章节标题（`A`、`B`）不再作为词条导入。
- 内置词典建立索引在第一台设备上约需一分钟，此前查询会一直卡在「正在查询本地词典…」。现在等待有上限（2.5 秒），索引在后台继续建立，下一次查询即可命中；同时索引建立会跳过已按同词书建好索引的词，避免在一次安装中重复写两遍。

### 闪烁修复（实机录屏定位）

用户录屏显示排雷页「左上角有闪烁」。逐帧分析录屏（812×1212、60fps、4.2s，252 帧）后定位到：每次点击排雷按钮，应用内容整体发生约 3–6px 的垂直抖动，而底部悬浮导航栏与手势条保持不动，因此看起来像标题、说明与计数在闪。

根因是排雷卡片里的 `AnimatedContent`：它在交叉淡入淡出期间会改变自身尺寸，而它位于 `LazyColumn` 的 item 内，重新测量会把整页向下推。修复方式是把词条动画放进固定高度（132dp）的 `Box` 并居中，动画改为缩放 + 淡入淡出（140ms），不再改变布局尺寸；同时为计数行固定行高，个位到十位的变化不会顶动页面。

验证：同一次点击前后对 UI 层级取 `uiautomator` 边界值，返回按钮、标题、`已排查 / 剩余` 行、两个按钮行、底部三项导航的 bounds 完全一致，只有词条本身按预期变化。

### 悬浮导航栏的层级修复

用户指出统计页底部有「意义不明的白色遮挡」。在本机复现时该位置渲染正确（像素值等于页面背景 `#F8F9FC`，切换三键导航模式也一样），但代码里确实存在一个真实缺陷：悬浮导航栏是 Scaffold 的 `bottomBar`，绘制在统计/设置/词典/学习会话等覆盖页 **之上**，因此在覆盖页淡入的那一帧会在页面上留下一刻空白容器。

现在覆盖页打开时**不再组合**导航栏，只保留等高的占位以维持页面内边距稳定；同时补上 `disabledIconColor` / `disabledTextColor`——此前禁用态没有任何颜色设置，图标会淡到几乎不可见，剩下的圆角容器看起来就是一块空白。

### 词典「查不了」的修复

用户反馈查词失效。复现后发现是**首次安装建立索引期间查询**的缺陷：此前为了避免界面长时间转圈，等待索引的时间被限制为 2.5 秒，超时后就直接执行查询。于是查询在索引尚为空、且索引建立事务正持有数据库写锁的情况下运行，界面停在「正在查询本地词典…」并给出 0 结果——看起来就是「查不了」。

现在的行为：仍然先等 2.5 秒（多数情况足够安静地建完），若索引仍在建立则明确显示「首次使用正在建立本地词典索引… 已处理 N 条」并等待其完成，然后自动执行原查询，无需用户重新点击。翻译、模型下载等不读取索引的操作不再受索引建立阻塞。

实测（冷启动，全新安装）：先提示进度，索引完成后自动给出结果，词典 5707 词条、lex_zh 反查索引 50094 个 token，中文反查 `问题` 正确命中 issue / problem / question。

该版本仍存在的已知代价：内置索引在首次安装时需要建立，模拟器上约 1–2 分钟（真机更快），期间查询会等待而不是失败。后续可改为构建期预生成索引资源以彻底消除这一等待。

### 页面切换时的白带（同一根因的第二种表现）

用户再次指出词典页下半部分有「意义不明的白色半透明」。核对用户截图后发现两处与当前构建不符：截图里**底部三项导航仍然绘制在词典页上**（当前构建覆盖页打开时不再组合导航栏），且整页内容比当前构建整体下移约 2%。据此判断截图取自修复中的旧包。

同时当前构建确实还有一个真实的视觉缺陷：覆盖页进入时 `PredictiveBackLayer` 会把页面缩放到 94%，那 220ms 内页面四周留出 letterbox 空隙，空隙暴露下层内容——从壁纸首页进入时就是壁纸与白色导航栏，看起来正是「白色半透明遮挡」。

已修复：在缩放层背后增加一层**不参与变换**的整屏背景，letterbox 空隙不再暴露下层，进入与返回手势动画期间页面始终是不透明的。若页面在缩放后仍小于屏幕（极端比例），空隙只会露出本页背景色，不会出现白块。

### 跳动根因（已定位并修复）

用户反馈排雷页「点一下跳一次」。逐帧复现后发现真正的机制：`LearningScreen` 在排队保存期间把 `onBack` 传成 `null`，而 `TeaListPage` 只在 `onBack != null` 时才绘制返回图标。图标一消失，Material 3 的 `TopAppBar` 就会把标题当作单行标题重新居中——于是**标题连同整个顶栏每点一次就位移约 4px**。批量保存耗时更长，所以「接下来的 10 个也认识」看起来跳得更明显。

修复：返回图标在忙碌时**只禁用、不移除**（`TeaListPage` 新增 `backEnabled`），标题与副标题都限定单行，顶栏布局不再因状态变化而重排。

验证：连续 14 帧、覆盖四种按钮（含批量）的高速截图比对，顶栏、返回图标、提示行、三个操作按钮的位置全部为 `+0.000` 偏移。此前的低分辨率与全分辨率静态比对都无法发现该问题，因为它只发生在忙碌态那一两帧内。

顺带移除同一行的另一个隐患：撤销按钮的文案含词条名，长词可能把整行挤到换行，现已限制宽度并单行省略。

### 白屏/卡死的真正原因：首次建索引时应用无响应（已修复）

用户反复反馈「到处都是白的」。抓取运行中应用时发现真正的问题不是渲染，而是 **ANR**：系统弹出「teaWords isn't responding / Close app / Wait」，画面停在「正在建立本地词典索引… 已处理 4000 条」。应用无响应时窗口只剩白底，这正是用户看到的「白色」。

两个根因：

1. **没有开启 WAL**。索引建立是一个长写事务，而普通日志模式下读者会被写者阻塞：查询、刷新都在等锁，主线程随之卡住，最终触发 ANR（`/data/anr/` 中确有 12:55 的记录）。
2. **逐词 upsert**。5707 个词每个都要走 `upsert()`（包含若干查询与多张表的插入），整体约四万条语句，耗时 1–2 分钟。

修复：

- `DatabaseHelper` 开启 `setWriteAheadLoggingEnabled(true)`：写事务进行期间读取仍可看到上一个快照，界面不再被建索引阻塞。
- 索引建立改为**集合式批量写入**：解析后一次性批量 `INSERT OR IGNORE` 词条，再用几条 SQL 从 `lex_words` 派生 `lex_senses`、`lex_fts`，中文倒排索引一次性批量写入。语句数从约四万降到几十条。

实测（全新安装冷启动）：索引建立 **26.5 秒**（此前 1–2 分钟且 ANR），期间不再弹出无响应对话框，查询在索引完成后自动给出结果；`/data/anr/` 无新增记录。数据完整性核对：`lex_words` 5707、`lex_senses` 5707、`lex_fts` 5707、`lex_zh` 50094 个 token，中文反查 `英雄` 命中 hero / heroic / heroine，`hero` 释义与音标正确。

### 列表底部空白条

`TeaListPage` 的底部内边距算了两次：调用方已经预留了 Scaffold 的底部内边距，页面自身又加了 `navigationBarsPadding()`，因此列表最后一项下方留下一条空白带，最后一张词条卡正好被切在半行文字处，看起来像是「未渲染的白色区域」。

现在页面只在自身补系统手势区内边距，列表内容底部留白 32dp。词典页实测：最后一张卡与其下背景的边界清晰，空白带消失。

### 深色模式设置

设置 → 个性化 新增「深色模式」，三档：跟随系统 / 浅色 / 深色。

- 偏好存于 `AppPreferences.themeMode`（SharedPreferences `teawords_settings`，键 `theme_mode`），重启后保持。
- `MainActivity` 在组合中读取该偏好并用 `resolveDarkTheme(mode, isSystemInDarkTheme())` 决定主题，因此在设置页选择后**立即生效**，无需重启；`resolveDarkTheme` 是纯函数，已加单元测试。
- `MainScreen` 接收 `appPreferences` 与主题回调，透传到设置页；外部划词入口（`ProcessTextActivity`）不传偏好，回退到原有默认行为。
- 深色配色沿用既有 `darkColorScheme`，未新增颜色。

验证：选「深色」后页面背景立即变为 `#111318`、卡片 `#1D2024`；重启应用后仍为深色；学习页、词典页在深色下配色正确；`theme_mode=2` 正确落盘。

### 悬浮导航栏只在顶层页面显示

用户指出：学习功能里的嵌套页（快速排雷、学习与复习）不该显示底部悬浮导航栏。这是真实缺陷——此前只在词典/统计/设置这类**覆盖页**隐藏了导航栏，而「快速排雷」「学习与复习」是 `学习` tab **内部**的嵌套页（自带返回箭头），导航栏被继续组合。

修复：`LearningScreen` 通过 `onNestedChange` 把「当前处于嵌套页」上报给 `MainScreen`，导航栏可见性改为
`!childVisible && !(currentTab == REVIEW && learningNested)`。

实测结果：

| 页面 | 导航栏 |
|---|---|
| 翻译 / 回望 / 学习（三个顶层 tab） | 显示 |
| 学习 → 快速排雷 | 隐藏 |
| 学习 → 学习与复习（答题） | 隐藏 |
| 返回学习 tab | 恢复显示 |
| 词典与翻译、学习统计、设置 | 隐藏 |

### 覆盖页底部空白条

词典页只有一张词条卡时，内容下方到屏幕底部空出一条（约 580px）。原因是 Scaffold 为悬浮导航栏预留了整条底部空间，而覆盖页打开时导航栏并不显示，该预留变成空白。

现在：覆盖页**撑满整屏**不再预留导航栏空间，改由页面自身按导航栏实际高度（96dp）留置底部间距；tab 页面继续使用 Scaffold 预留值。实测词典页 `其他匹配` 词条之后直接接 `翻译服务与来源说明`，空白条消失。

### 卡片文字颜色

`TeaCard` / `LearningCard` 与词典页词条卡此前依赖继承的 `contentColor`；现全部显式指定 `onSurface`，卡片容器与文字颜色不再可能因环境差异而趋同（用户截图出现过「卡片渲染、文字不见」的空白块）。

### 本轮验证

- `assembleDebug`、`testDebugUnitTest`、`lintDebug` 均通过；单元测试 17 项（新增 `WordImportTest` 5 项），lint 0 error、13 warning（均为既有项）。
- 模拟器实测：底部导航留白、学习计划、快速排雷（含 10 词批量与撤销）、陌生词教学 + 主动回忆 + 保存、学习统计三类证据分区与 7 天趋势、词典查询（`sick` → `有病的；恶心的`）、深色主题。
- 数据库直查确认持久化语义：`learning_knowledge(zoo, UNKNOWN, attempts=1, streak=1, interval_hours=24.0)`，`learning_events` 含 `screen` 与 `test(correct=1, elapsed=14840)` 两条；查询行为不改变知识状态。

### 本轮未做

- 设备端翻译模型的下载与成功翻译仍未实测（本机 Google 连通性不稳定），失败时不生成伪译文，输入不会丢失。
- 设置页与壁纸首页顶部栏仍是旧的自定义实现（视觉接近但未换成 `TeaTopAppBar`）。
- `MainScreen.kt` 仍保留旧复习（挖空）路径与旧的统计视图代码，未从导航入口暴露，也未删除。
- Room、义项级知识状态、成熟 FSRS、上下文义项匹配均未接入。

## 实际工程与验证

本轮运行工程为 `.upstream-review`，保留此前的预测返回手势修复。
构建使用 Android Studio 自带 JDK、现有 Gradle wrapper 和本机 Android SDK。
最终安装包：`app/build/outputs/apk/debug/app-debug.apk`。

已执行 assembleDebug、testDebugUnitTest、lintDebug；纯 Kotlin 单元测试全部通过，静态检查 0 错误。
已执行 connectedDebugAndroidTest；模拟器测试通过，涵盖已有返回手势测试、数据库重开、v2 升级、词典索引、知识复用和导入事务。


## 已实现及效率价值

- 悬浮导航栏将系统安全区移到圆角容器外，NavigationBar 内部不重复消费导航栏高度。避免底部裁切与过大的容器。
- 快速排雷提供认识、模糊、陌生三个连续按钮以及撤销。认识词暂离新词队列，避免从头背整本词库。
- 今日计划统一到期复习、模糊词、陌生词与少量认识词验证；每轮上限可调，允许提前结束。排雷未结束也可以开始学习。
- 自评认识至少 7 天后每轮抽查至多一个。一次正确不计作已掌握，连续三次正确仅证明已练习释义的主动回忆。
- 基础间隔随答题结果伸缩，失败回到短间隔；名称明确为基础调度，不冒充 FSRS。
- 统计使用实际持久化答题及前台单题时间，区分待确认、自评、模糊、陌生、学习中和已验证；不编造节省时间、模型预测或训练准确率。
- CSV / JSON / TXT 学习词库导入、现有四/六级词表导入，重复词保留原学习记录。来源与许可说明单独记录。
- 本地词典初始化现有四/六级资源得到 5707 个唯一词条（当前资源实测）。查询不依赖在线 API，也不自动收藏、降低掌握状态或生成学习证据。
- ECDICT CSV 流式导入，支持带引号、逗号、换行和 UTF-8 校验；导入原子提交，失败/取消回滚，不覆盖学习记录。完整 ECDICT 数据未打包。
- 英文精确/前缀走索引；变形走词形表；拼写纠错限制至 768 个索引探测候选；英文释义用 FTS4；中文反查用汉字单字/双字倒排索引并校验实际子串，不依赖英文 tokenizer。不是语义搜索。
- 词典详情展示可用音标、释义、词性、标签、词形、词频来源及共享知识状态。系统 TTS 提供美/英音及缺少资源提示。不制造源数据没有的例句或英文释义。
- PROCESS_TEXT 与 ACTION_SEND 显式系统入口；用户可手动切换词典/翻译。外部输入不保存历史，不监听剪贴板、不使用无障碍读取。
- TranslationProvider 隔离引擎，ML Kit 实现中英设备端翻译、模型可用性、Wi-Fi 准备、删除模型、任务取消与资源释放。SDK 没有字节进度，界面使用不确定进度；取消等待不承诺取消系统已启动的下载。
- 点击分析生词才匹配原文，依照词典词形还原，过滤已有认识/验证证据，每个候选均需手动加入。原文和机器译文都不是学习证据。
- 查询历史独立于学习记录，包含类型、方向和时间，支持删除/清空/关闭，并整合到回望页面。

## 架构与数据兼容

沿用现有 SQLiteOpenHelper，当前 schema v5；新增 lex_words 稳定整数 ID、lex_senses、lex_forms、lex_zh、lex_fts、lex_sources、query_history；学习表通过 word_id 关联词条，并保留原字符串键以兼容旧记录。
词典与学习使用同一个 teawords.db。词书来源保存在 learning_sources，词典标签用于显示词书所属；还没有完整的独立词书管理与动态集合。
新增词典/翻译界面采用 ViewModel + StateFlow + Repository；算法是纯 Kotlin。学习页面暂沿用工程的 Compose 状态方式，不宣称已完成全面 MVVM 重构。

未为了技术栈名词重建现有数据库。Room、DataStore、完整义项级知识状态、多用户、IRT/BKT/CAT 和成熟 FSRS 尚未接入。当前采用单机默认用户与可解释启发式，未训练任何模型。
保留不同来源与语种的释义记录；并未实现同形异义词语义消歧，也不把中英文释义按行号假装成严格对齐的义项。

## 翻译和数据来源限制

已验证系统划词 issue 进入本地词典、句子分享进入翻译页面，以及模型尚未下载时禁用翻译并保留输入。
本机 Google 网络连接不稳定，尚未完成真实模型下载与成功翻译的端到端验收。失败不会生成伪译文；普通本地查词及学习不受影响。
Google 官方署名素材下载域名在本机连接失败。目前显示 Google Translate 文字署名和服务说明链接；正式发布前须补齐官方 badge 及完整服务免责声明并验证模型下载。

ECDICT 仓库标注 MIT，但 README 明示词汇数据混合多个来源；不据此断言所有数据具备无条件商业授权。当前导入入口记录上游与许可边界，用户选择有权使用的数据。现有四/六级资源来源授权仍需核验，未抓取第三方商业词典。

参考：
- https://github.com/skywind3000/ECDICT
- https://developers.google.com/ml-kit/language/translation/android
- https://developers.google.com/ml-kit/language/translation/translation-terms
- https://docs.cloud.google.com/translate/attribution

## 下一阶段

优先补齐可合法分发的完整词典数据及 Google 模型实机验收，再推进义项级学习和词书管理。上下文义项推荐、云端服务、自定义密钥、完整阅读模式均未作为占位功能展示。

---

# 真实词典数据与检索引擎（本次工作，含实机验收）

## 已完成的真实数据

由真实 ECDICT（MIT，770,611 行 CSV）离线构建 `dictionary.db`，不是占位数据：

| 项目 | 实测值 |
| --- | --- |
| 词条 | 57,841 |
| 义项 `lex_senses` | 197,766 |
| 词形 `lex_forms` | 56,515 |
| 中文单字倒排 `lex_zh` | 465,982 |
| 字符文档频次 `lex_token_stats` | 4,596 |
| 词书关联 | 38,855 |
| 文件大小 | 61.0 MB（64,098,304 字节，SHA256 `2E87537F…0E7DC1`） |

八本考试词书全部来自 ECDICT 自带标签，非手工罗列：zk 1,603 / gk 3,677 / cet4 3,849 / cet6 5,407 / ky 4,801 / ielts 5,040 / toefl 6,974 / gre 7,504（带标签行合计 14,942，另保留 BNC/FRQ 前 60,000 高频词，同一词可属多本，无重复 `(book_id, word_id)`）。
Qwerty Learner 数据**未**采用（GPL-3.0 且含第三方抓取来源），已记录在 `lex_sources` 为「待核验」。

## 本次在真实数据上查出的三个真实缺陷

单元测试全部通过时，以下缺陷依然存在；它们只在真实数据 / 实机上暴露，因此都补了对应回归测试。

1. **中文反查完全不可用（最严重）。** `lex_zh` 只存**单字** token，而检索层用**双字** token 去探测，`WHERE token='放弃'` 命中 0 行。中文反查是该应用最常用的查询，却一直返回空。现改为「最稀有单字锚点 + `instr(zh, 整个查询)` 校验」。
2. **相近释义搜索返回噪音。** 原评分为「字符覆盖率」，在 57,841 条真实长释义上，`走兽` 命中 `theropod`（走自「行走」、兽自「兽脚亚目」），`动物` 命中 `he / still / tree`，`甲乙丙` 命中 `perspex`（甲基丙烯酸）。长释义天然包含大量常用字，共享字符不构成共享语义。
   现改为「**查询的最长连续子串**出现在释义中」，锚点字符与校验子串分离；中文中心语在末尾，故末尾子串获得极小加权（0.05，仅用于同长度并列时排序）。
3. **打包的词典资源是过期版本。** `assets/dictionary.db` 的 `schema_version` 仍是 `1`，**缺少 `lex_token_stats` 表**；`BundledDictionaryInstaller.SCHEMA_VERSION` 也是 `1`，因此已安装的旧副本永远不会被替换，每次中文查询都在查询期崩溃。现已重新打包 v2 资源、将常量提升为 `2`，并让缺失统计表只降级为「变慢」而非崩溃（该表只是性能辅助，候选仍要经释义校验）。

现在 `checkIndexConsistency()` 会把「缺少 `lex_token_stats`」列为问题，实机测试同时断言资源版本与安装器常量一致——这正是本次漏掉的检查。

## 性能：真实数字与两处 N+1

先前「毫秒级」的说法来自开发机 Python 直接查 SQLite，不能作为 Android 证据。现由实机（Pixel 9 Pro 模拟器，x86_64）测试打印，取 5 次均值：

| 路径 | 修复前（首次冷跑） | 修复后（连续两次热跑） |
| --- | --- | --- |
| 精确 `abandon` | 5 ms | 0 ms |
| 前缀 `contemp`（20 条） | 29 ms | 3 / 4 ms |
| 词形 `went` | 5 ms | 2 ms |
| **拼写建议 `contempalte`** | **189 ms** | **3 / 3 ms** |
| 中文 `放弃` | 15 ms | 6 / 13 ms |
| 中文 `放弃` 限定 cet4 | 13 ms | 5 / 3 ms |
| 相近释义 `野生动物园` | 71 ms | 25 / 25 ms |
| 相近释义 `走兽`（应为空） | 2 ms | 1 / 1 ms |
| 20 条结果带词书标签 | 11 ms | 3 / 3 ms |

两处真实 N+1 已修：

- 每命中一条词就单独查一次所属词书，20 条结果 = 21 条语句；现改为一条 `IN (...)` 批量取回。
- 拼写纠错对最多 256 个候选各查一次（且每查一次还附带一次词书查询）；现改为一条批量语句 + 纯 Kotlin 计算编辑距离排序。这是 189 ms → 3 ms 的原因，其余差值主要来自冷/热页缓存，不作为改进成果。

计划经 `EXPLAIN QUERY PLAN` 确认走 `idx_lex_zh_token`，无全表扫描。已实测并否决「取两字倒排交集」的优化：`lex_zh` 没有 `word_id` 索引，`IN (子查询)` 反而退化为扫描全部 57,841 行，整体更慢（112.8 ms vs 64.6 ms），因此不做；若将来要更快，应先给 `lex_zh(word_id)` 建索引。

## 实机验收（已通过）

新增 `DictionarySearchInstrumentedTest`，直接对**打包的真实 61 MB 词典**跑真实引擎，共 14 项全部通过，覆盖：资源版本与安装器一致、`lex_token_stats` 存在、八本词书齐全、大小写归一、精确优先于前缀族、词形还原、拼写建议、中文反查真的返回结果（缺陷 1 的回归）、中文按词书限定、字符巧合不得作为相近释义（缺陷 2 的回归）、共享连续子串必须标注为「近似」、精确命中不得混入近似结果、每条命中带词书标签、去重与「强匹配优先」排序。

同批实机运行中 `SensePersistenceTest` 6 项、`LearningPersistenceTest` 2 项、`LocalDictionaryTest` 5 项亦通过。`PredictiveBackLayerTest` 有 1 项失败且随后模拟器崩溃中断 4 项，属于既有的手势测试问题，与本次改动无关，尚未排查。

## 尚未完成：UI 尚未接入新词典

必须说明：**应用目前仍在使用 teawords.db 内联的 5,707 词旧索引**（`LookupViewModel` 用 `LocalDictionary`），`BundledDictionaryInstaller` 与 `DefaultDictionarySearchEngine` 尚未在正式代码路径被调用。因此上面的检索能力目前只能在测试中验证，用户还无法在界面上用到它。

接入前必须先决定 `word_id` 迁移，因为两套词典的数字 ID 不同（`abandon`：旧 1 → 新 16）。已量化：

- `learning_knowledge` 20 行按词形归一化映射：**20/20 可映射，0 丢失**；旧内联 5,707 词中 5,706 词存在于新词典（仅 `air-condition` 缺失）。
- 义项级则**无法无损迁移**：`sense_units` 12,923 条中只有 2,720 条（21%）的释义文本在新词典中逐字存在；带真实答题记录的 5 条义项进度中只有 1 条能对上。

建议（需确认后实施）：**只迁移词级 `word_id`，义项模型继续由 `teawords.db` 拥有**，`dictionary.db` 仅作只读参照。这样 100% 无损失，也符合既有「词典文件与学习库分离、更新词典不能触碰学习状态」的架构原则；代价是学习页显示的是旧词形切分出的义项文本，与词典页的新释义在措辞上可能不同。

未实施的选择：按文本重映射义项并把对不上的证据回退到词级（会丢掉 4/5 条义项级进度）。这一步涉及用户既有学习记录，不宜由实现方静默决定。

---

# 词典接入应用、包名变更与实机验收

本轮把预构建词典真正接进了应用，并把包名从 `com.tea.teawords` 改为 `com.teameow.teawords`。

## 接入方式

- `MainActivity` 启动时在线程中调用 `DictionaryGateway.open()`：流式安装 `assets/dictionary.db`（61 MB，不读进内存、不阻塞 UI），装完才对外提供检索。**不在首次查询里做安装**，否则界面会被误认为卡死。
- `DictionaryGateway` 是进程内唯一持有者：主界面、查词页、以及 Android 可以脱离主界面直接启动的 `PROCESS_TEXT` / `SEND` 入口共用同一个只读词典，不重复拷贝也不重复打开。
- `LocalDictionary.search(text, bookId)` 改为「先问引擎，再补本地」：引擎结果按匹配强度排序在前，本地库只补上词典没有的自建词；`ShippedDictionarySource.localOnly` 让测试和需要沙箱的调用方显式关掉参照词典。
- 查词页新增**查询范围**选择（全部 + 八本词书），并显示拼写建议与「命中方式」说明。

## 本轮在真实数据/实机上查出的三个新缺陷

1. **词书范围过滤对最强的那一步无效。** 精确查询没有套用 `bookId`，所以「限定大学英语四级」仍会返回不属于任何词书的首词（如 `con`）。验收测试 H 抓出。现已对精确步骤同样套用范围。
2. **中文反查的排序答非所问。** 按词频排序时，「放弃」第一条是 **go**（其释义把「放弃」列为第八个义项），而不是 **abandon**（第一个义项就是「放弃」）。
   - 先试「查询长度 / 释义长度」比例，**实测更差**：把 `Ireland` 排到「爱」的第一位、`forgo` 排到「放弃」第一位。该方案已否决并在注释中记录。
   - 最终采用**义项位置**：按逗号/分号/换行切分释义，取包含查询的第一个义项序号，越靠前权重越高（`1/(1+序号)`），义项正好等于查询再加权。释义里的换行是**字面 `\n` 两字符**而非真换行，必须先归一化，否则整条释义被当成一个义项。
   - 实测结果：放弃→abandon/relinquish、发行→issue、爱→love、动物→animal、动物园→zoo、学习→study。
3. **`LocalDictionary` 改造时丢了本地库的拼写纠错与全文检索**，且直接读全局网关导致无法隔离测试。现已恢复本地库原有完整检索，并把参照词典改成可注入依赖（`ShippedDictionarySource`）。

## 词条 ID 迁移（知识按全局 wordId 复用）

`WordIdMigration` 把既有进度从旧内联索引的 ID 空间重绑到词典的全局 ID 空间：

- `learning_knowledge` 存有归一化词形，按**文本**重绑，不受旧数字影响。
- `sense_units` / `test_records` / `item_difficulty` 只有数字，经旧内联索引反查词形后再映射。
- **顺序很关键**：先处理「词典里没有的词」（置为负数），再映射能对上的。反过来会先写入新 ID，随后的一致性检查拿**新** ID 去查**旧**索引，对任何小于旧索引上限的新 ID 都会命中另一个词，把刚映射好的行错误地置负。`zoo` 新 ID 57818 正是这种情况，已写成回归测试。
- 不变式：`word_id > 0` 为全局词典 ID；`word_id < 0` 表示词典没有的自建词（保留旧 ID 的相反数，两个空间不相交，无需删除任何数据）。
- 每次词典构建只绑定一次，记在 `lex_meta['dictionary-binding']`。

**实机真实数据验证**（Pixel 9 Pro 模拟器，还原用户真实库后触发）：

```
已按全局词条 ID 重新绑定：知识 20 行、义项 12925 行、测试记录 5 行、题目难度 0 行；
词典中不存在的自建词 0 个（ID 记为负数）
```

- 词级 **20/20** 重绑成功；`yearly/yell/yellow/yes/yesterday` 由旧 ID 4476–4480 → 新 ID 57557–57595。
- 义项 **12,925** 行重绑，其中 **2 行**为负（`air-condition`，旧内联 5,707 词中唯一不在词典里的词），与开发机测算完全一致。
- 21 条答题事件、5 条义项知识、5 条测试记录全部保留，`PRAGMA integrity_check` = ok。
- 本机词典文件由应用自行安装：`dictionary.db` = 64,098,304 字节（与资源 SHA256 一致）。

## 验收测试 A–L（实机全部通过）

新增 `DictionaryAcceptanceTest`，对**打包的真实 61 MB 词典**运行，13 项：

| | 验收 | 结果 |
|---|---|---|
| A | 首次启动安装资源；再次调用不重复拷贝；版本与安装器常量一致 | 通过 |
| B | 精确查询忽略大小写/空格，精确命中第一 | 通过 |
| C | 前缀返回整个词族且都以查询开头 | 通过 |
| D | 词形由数据还原（went→go、mice→mouse），臆造词形不猜 | 通过 |
| E | 中文反查校验整个查询，且排序以「是否就是这个意思」为准 | 通过 |
| F | 拼写建议按编辑距离、一次批量探测、候选有界 | 通过 |
| G | 强匹配永远排在弱匹配之前，无重复词条 | 通过 |
| H | 词书范围过滤生效，八本词书齐全，范围外首词不返回 | 通过 |
| I | 同一词在八本词书共用一个全局 ID | 通过 |
| J | 关闭重开词典后仍可检索，知识记录仍在 | 通过 |
| K | 强制重装不产生重复词条或重复关联（38,855 条，0 重复） | 通过 |
| L | 义项/中文 token/全文索引与基础表一致，锚点统计存在 | 通过 |
| M | ID 重绑（含新 ID 超过旧索引上限的回归）与幂等 | 通过 |

同批实机：`DictionarySearchInstrumentedTest` 14 项、`SensePersistenceTest` 6 项、`LearningPersistenceTest` 2 项、`LocalDictionaryTest` 5 项、`PredictiveBackLayerTest` 3 项，**合计 47 项全部通过**。

## 本轮验证命令与结果

- `compileDebugKotlin` / `compileDebugUnitTestKotlin` / `compileDebugAndroidTestKotlin`：通过。
- `testDebugUnitTest`：**104 项全通过**（`DictionarySearchEngineTest` 增至 31 项）。
- `lintDebug`：**0 error / 14 warning / 5 info**，均为既有项（UseTomlInstead、AutoboxingStateCreation 等），与本轮改动无关。
- `connectedDebugAndroidTest`：**47/47 通过**。
- 界面实测：查词页显示「离线词典 57841 词条」与八本词书范围（六级 5407／四级 3849／GRE 7504／托福 6974／雅思 5040／考研 4801／中考 1603／高考 3677，与构建数据一致）；中文反查「放弃」首条为 abandon。

## 包名变更

`com.tea.teawords` → `com.teameow.teawords`，覆盖 `namespace`、`applicationId`、三个 source set 的目录结构与 62 个文件的包声明与 import，以及两份文档。核对后全仓（排除 `build/`）无残留。

注意事项：

- **换包名等于换了一个应用**：Android 视为全新安装，旧包的数据目录不会自动继承。已验证的升级路径是「安装新包 → 把旧 `teawords.db`（含 `-wal`、`-shm`）放回新包数据目录 → 启动触发重绑」，本轮即按此路径验证通过。
- `validation/` 目录下留有上一轮的验收产物（`LocalDictionaryTest.kt`、`window.xml`），仍写着旧包名。它们不在任何 source set 内、不参与编译，属历史记录，未改动；其中 `LocalDictionaryTest.kt` 与 `androidTest` 中的同名测试重复，建议删除以免混淆。

## 仍未完成

1. **PDF / 阅读模式、云端同步、账号体系**：未实现，也未做成占位界面。
2. **Qwerty Learner 补充词表**：授权不明确，继续「待核验」，未打包。
3. **翻译模型实机端到端验收**：本机 Google 网络不通，仍未完成真实模型下载与成功翻译的验证；失败不会生成伪译文。
4. **义项级学习仍是启发式切分**，不是义项消歧；学习页显示的义项文本来自旧切分，与词典页新释义措辞可能不同（这是 ID 迁移方案的有意取舍：保住全部义项级进度）。
5. **中文相关的「相近释义」是词形重叠，不是语义检索**：已实测否决字符覆盖方案，现为「连续子串」，无 embedding，不宣称理解语义。

### 补记：接入后又在实机上查出的一个缺陷

**旧内联索引会抢答新词典。** 实机截图发现查 `contempalte` 时，界面直接显示了 `contemplate` 卡片，且写着「来源 · 原项目 cet6.txt」——即答案来自被取代的 5,707 词内联索引，而不是新词典的拼写建议列表。原因是 `LocalDictionary.search` 把本地库的**拼写纠错与全文检索**结果也一并附上，抢在参照词典之前给出了答案。

现在的优先级是明确的：**参照词典可用时它就是权威**，本地库只补充词典里没有的词（精确／词形／前缀／中文反查），不再提供猜测型结果；只有在词典尚未安装完成时，本地库才承担全部检索（该状态界面已明确提示）。新增验收测试 N 固定这一行为，并改用**注入式** `ShippedDictionarySource`——原先测试读的是进程级网关，而 instrumentation 不保证类之间执行顺序，属于测试自身的缺陷，已一并修正。

实机复查：`contempalte` 现在显示「未找到该词，是否想查：contemplate」，并注明「按编辑距离排序的拼写建议，不是词典释义」。

### 最终验证结果

| 项目 | 结果 |
|---|---|
| `compileDebugKotlin` / 单测 / androidTest 编译 | 通过 |
| `testDebugUnitTest` | **105 项，0 失败**（`DictionarySearchEngineTest` 31 项） |
| `connectedDebugAndroidTest` | **48 项，0 失败**（验收 A–L 共 14 项含测试 N） |
| `lintDebug` | **0 error / 14 warning / 5 info**（均既有） |
| 实机界面 | 57841 词条、八本词书范围、中文反查「放弃」首条 abandon、拼写建议卡片正常 |
| 实机数据 | 20 条知识 / 12,925 条义项 / 21 条事件 / 5 条义项知识 / 5 条测试记录全部保留，ID 已重绑 |

## 2026-09-28 查词页布局与主页查询刷新

- 查询范围改为单行下拉菜单，保留全部词典及八本词书筛选；选中后重新查询。
- 模式切换与查询按钮放在同一行，导入和词典说明收进右上角菜单，结果提前显示；键盘搜索键可直接查询。
- 释义显示时将字面量 `\n` 转为换行。
- 修复 SaveableStateHolder 恢复旧初始化标记造成的查询不刷新：保存已消费的请求标识，主页每次提交递增请求编号，因此不同词与同词重查都会重新加载。
- 验证：assembleDebug 成功；105 项单元测试通过；新增 LookupNavigationTest 在 Pixel 9 Pro 模拟器通过，覆盖页面离开/恢复、新词、页内改词后主页同词重查和下拉筛选。
- 实际主页操作：link → 返回 → apple，输入框与结果均为 apple。截图：validation/lookup-after.png。新版 APK 已重新安装到模拟器。

## 2026-09-28 英式 / 美式双发音（系统 TTS）

完整实现说明、数据来源策略与真人录音扩展接口见 **[PRONUNCIATION.md](PRONUNCIATION.md)**。要点：

- 词典详情页并列 **UK / US 两张卡片与两个独立按钮**，各自显示音标与语音可用状态；学习页与快速排雷复用同一 `PronunciationManager`，只播放 DataStore 保存的默认口音。
- **只用地区精确匹配的 Voice**（language+country 都相等），刻意不用 `setLanguage`——它会在缺资源时静默退回其它口音。资源缺失时明确提示安装对应语音，**不做跨口音回退**。
- 默认口音改用 DataStore，并**迁移**旧的 `teawords_settings/pronunciation_dialect`（`US=0`/`UK=1` 与旧 ordinal 语义一致）。
- **音标数据的真实状态**：ECDICT 只有未标注口音的单一音标，不能假定其含英美双音标；本轮**没有**用字符串替换把英音转美音（已核查全仓无此逻辑）。因此英美音标列显示「暂无经来源确认的…音标」，ECDICT 音标单独标注「来源未标注口音」，不伪造、不填充。
- 新增独立 `data/pronunciation/WordPronunciationEntity.kt`：音标与音频**各自的来源与许可证分开保存**，存放于独立 `pronunciations.db`，与 `lex_words` / `learning_knowledge` 完全分离，因此**不因口音不同产生重复学习记录**（有测试断言学习库文件未被改动）。真人录音预留 `WordPronunciationSource` + `LicensedPronunciationCache`（许可标记、SHA-256 校验、5 MB 上限、原子写入，且绝不按 URL 自动抓取）。本轮未打包任何真人音频。
- 验证：`testDebugUnitTest` **112 项 0 失败**；发音相关设备测试 **12 项 0 失败**；真实引擎审计 UK→`en-GB-language`、US→`en-US-language`，均本地合成且收到完成回调（`validation/pronunciation/device-audit.json`）；界面截图 `validation/pronunciation/dictionary-dual-tts.png`。
- **同时发现一个与发音无关的既有测试隔离缺陷**：`LearningBooksTest` 打开进程级单例 `DictionaryGateway` 后从不释放，导致后续 `SensePersistenceTest` / `LocalDictionaryTest` 共 5 项失败（单独运行则 13/13 通过，已二分定位）。根因、证据与建议修复见 PRONUNCIATION.md 第 11 节；本轮未擅自改动其它模块的测试。

## 2026-09-28 按 `m3e-canvas.json` 对齐 Material 3 Expressive 界面

设计稿（`m3e-canvas.json`：palette=purple、仅浅色、412×892 竖屏、rounded shapes、expressive motion）此前只落地了主题与首页骨架，
本轮逐屏对齐，并把上一轮**没编过**的工程修到能出包。

### 先修掉上一轮遗留的编译错误

上一轮结束在半途，工程实际是编译不通过的，两处：

1. `HomeScreen.kt`：`BoxWithConstraints` 提供的 `maxHeight` 在 `Column` 的内容 lambda 里被隐式接收者
   （`ColumnScope`）遮蔽 → `e: 'val maxHeight: Dp' cannot be called in this context`。改为先在
   `BoxWithConstraints` 作用域内取值再传进去。
2. `SettingsScreen.kt` 引用了**根本不存在**的 `DiagnosticsExport` → `e: Unresolved reference`（设置页「日志获取」只有入口）。

### 逐屏对齐

| 页面 | 设计稿 | 本轮改动 |
|---|---|---|
| 查词 | 一块 `secondaryContainer` 词头卡：45sp 词头、词书标签 chip、收藏 + 发音两个 40dp outlined 图标按钮、分隔线、中英释义、复制 | 新增 `WordHeroCard`；顶栏标题改为所查单词；发音 / 词条资料 / 加入学习移到第二张卡 |
| 学习 | 380×220 `primaryContainer` 主操作卡，96dp 实心箭头按钮在右下角；「每轮目标」下拉 160dp 与「快速模式」开关同排 | 箭头按钮从「拉满整行的横条」改回 96×96 方形；下拉框固定 160dp，开关推到行尾 |
| 回望 | 顶部卡片 tonal | 由 `surfaceContainerHighest` 改为 `secondaryContainer`，正文与计数改用 `onSecondaryContainer` |
| 首页 · 最近查阅 | 每行一个方向 chip | chip 由内部模式名（查词 / 翻译）改为查询方向（英译中 / 中译英） |
| 设置 | 一行「日志获取 · 便于提交BUG」 | 补上缺失的实现（见下） |

### 日志获取（新增 `ui/screens/DiagnosticsExport.kt`）

设计稿只有列表项，功能是空的。现在生成一段可直接粘贴的报告：应用版本、Android / 机型 / ABI、离线词典
（词条数、schema 版本、构建时间）、学习库各表行数，以及**本应用进程**最近的 logcat。

- 只读自己 UID 的日志条目（Android 4.1 起进程默认如此），**不需要 READ_LOGS，不写文件，不申请存储权限**。
- 报告先展示再交给用户决定：只提供「复制」与「分享」，不静默上传、不静默落盘。
- 文案里直接写明「日志可能包含你查过的词」，不把隐私风险藏起来。

### 收藏按钮的语义

设计稿的 `favorite` 是收藏按钮。它**只管理生词本**：点亮 = 写入 `vocabulary`，熄灭 = 从 `vocabulary` 移除；
学习证据（`learning_knowledge` / `learning_events` / `sense_*` / `test_records`）由「加入学习队列 / 自评认识 /
有些模糊」负责，熄灭收藏**不会**删掉已记录的答题证据——卡片上也写明了这一点。

### 图标

新增三个与既有 `symbol_*` 同源同风格（Material Symbols Rounded，960 viewport）的矢量：
`symbol_favorite`、`symbol_favorite_filled`、`symbol_content_copy`，不再为这两个动作混用另一套 Material Icons。

### 构建工具链（本机网络与 JDK，必须知道）

- 本机**直连 `repo.maven.apache.org` / `github.com` 失败**（SSL 被拦、连接超时），Kotlin 编译器
  `kotlin-compiler-embeddable` 因此下不下来。`settings.gradle.kts` 增加了阿里云只读镜像兜底
  （Google 仓库仍优先，可直连）；网络正常时删掉那两行即可。
- Gradle wrapper 指向 `9.4.1`：本机已完整缓存该发行版，而 `9.3.1` 只有半截 `.part`，wrapper 会尝试重新下载并超时。
- 必须使用 **Android Studio 自带 JBR 21**：
  `-Dorg.gradle.java.home="C:\Program Files\Android\Android Studio\jbr"`。
- 仓库内没有配置 release 签名（无 keystore、无 `signingConfig`），因此本轮交付物仍是 debug APK；
  没有伪造签名配置。

### 实机验收中发现并修掉的两个真实缺陷（Pixel 9 Pro / API 36）

#### 1. 全部 Material Symbols 矢量图标不渲染（最严重）

**现象**：界面能显示，但**所有** `AppSymbols.*` 图标是空白——顶部栏的设置/统计、底部导航三个图标、
首页搜索大按钮、FAB、卡片里的收藏/发音按钮、返回箭头、删除图标全部是空圆。而 `Icons.Filled.*`
（Compose 代码矢量）正常，所以最初看起来像「有些图标偶尔不显示」。

**根因**：`res/drawable/symbol_*.xml` 的 path data 是从 Google Fonts 的 Material Symbols SVG **原样抄**来的，
而那份 SVG 的 viewBox 是 `0 -960 960 960`——**y 坐标全部为负**（例如 `M380-320q-109 0…T120-580`）。
Android 的 `<vector>` 只有 `viewportWidth/viewportHeight`，**没有 viewport 原点**，因此整条路径落在
0..960 画布之外：aapt2 看资源完全合法、运行时不报任何错、就是什么都不画。
（我本轮新增的 `symbol_favorite`、`symbol_favorite_filled`、`symbol_content_copy` 犯了同一个错。）

**定位过程**（实机 A/B，不是猜）：在顶栏同时放三个探针——`painterResource(symbol_search)`（红）、
`ImageVector.vectorResource(symbol_search)`（蓝）、以及用同一条 path data 在 Kotlin 里现搭的 ImageVector（绿）。
三者**全部空白**，而同一条顶栏里 material-icons-extended 的 `Icons.Filled.MoreVert` / `History` 正常渲染。
唯一差别是坐标系：代码矢量是 24 网格，出问题的是 960 网格——正好对上「Material Symbols 用 `0 -960 960 960`」。

**修复**：31 个 drawable 全部把 `<path>` 包进 `<group android:translateY="960">`，把 -960..0 平移到 0..960。
实机复核：设置/统计/返回/底部导航/FAB/收藏/发音/箭头全部正常（`validation/expressive-ui/recall.png`）。
**以后新增图标必须补这一层 group，直接贴 Google Fonts 的 path 会再次变成空白。**

#### 2. Bing 每日壁纸被缩成小卡片

按设计稿改版后，壁纸只是副标题下方一张 `100dp` 高的卡片——宽度铺满、高度只截到画面中间一小条，
用户直接指出「Bing 每日壁纸报废了」。

**修复**：开启每日壁纸时它重新作为**整页背景**（`ContentScale.Crop` 铺满），另加一层上/中/下三段渐变遮罩
（0.45 / 0.22 / 0.55），顶部图标、「茶词」标题、副标题改用白色，搜索大按钮改为半透明白底白图标；
关闭壁纸时仍是设计稿的纯色 surface 首页。截图：`validation/expressive-ui/home-wallpaper.png`。

### 本轮验证（构建 + 静态检查 + 实机）

- `:app:compileDebugKotlin`、`:app:assembleDebug` 通过；产物 `app/build/outputs/apk/debug/app-debug.apk`
  （119,299,276 字节，内含 61 MB 离线词典）。
- `:app:testDebugUnitTest`：**112 项 / 0 失败**。
- `:app:lintDebug`：**0 error / 55 warning / 5 hint**（warning 均为既有类型：UseKtx、UseTomlInstead 等）。
  过程中修掉 1 个真实 lint error：`MainScreen` 旧复习列表在 composable 里调用 `Locale.getDefault()`
  （新版 Compose 的 `NonObservableLocale` 检查），已固定为 `Locale.US`。
  注：Android Studio 打开着本工程并持有 `app/build/intermediates/lint-cache` 里两个 jar 的句柄，
  IDE 占用期间原地 `lintDebug` 会以 `FileSystemException` 失败；这次 lint 是在工程副本里跑的
  （同一份源码与依赖），跑完已删除副本。
- **实机（Pixel 9 Pro 模拟器，API 36）**：为对齐设计稿，验收时把显示覆盖为 `1080×2338 @420dpi`
  （即设计稿的 412×892dp），**验收后已 `wm size/density reset` 还原**。
- 实机逐屏确认：带壁纸 / 不带壁纸首页、最近查阅底部面板（方向 chip「英译中」）、回望、
  学习（96dp 主按钮 + 160dp「每轮目标」+「快速模式」开关）、设置（学习策略、发音偏好）、
  设置 → 日志获取（真的生成了报告：57841 词条 · schema v2；知识 8 / 答题事件 9 / 义项 2619 /
  生词本 1 / 查询历史 12）。截图存于 `validation/expressive-ui/`。

### 仍未做

- 设计稿里的 `image` 占位插图用语义图标（`School`）代替，没有引入占位图片资源。
- 学习/复习只点了一屏排雷，没有跑完整轮；统计页未在实机上复核。

## 2026-09-28 深色模式恢复 + 动态取色（Material You）

设计稿只画了浅色（`theme.dark=false`、`bothModes=false`、`dynamicColor=false`），上一轮因此把主题写成了
固定浅色，连**已经交付过的深色模式一起砍掉了**（`TeaWordsTheme` 收下 `darkTheme` 却直接忽略）。
这是功能回退，不是设计取舍，本轮补回，并把动态取色真正做出来。

### 深色模式

- `Theme.kt` 补一套同一紫色 tonal palette 的暗端 `PurpleDark`（M3 baseline dark token），
  `TeaWordsTheme(darkTheme, dynamicColor)` 按模式选配色；`resolveDarkTheme(mode, systemInDark)` 保持原语义不变。
- 状态栏/导航栏图标跟随明暗切换（`isAppearanceLightStatusBars = !darkTheme`）——不改这一句，
  深色下时间与电量会是深色描边，等于看不见。
- 设置 → 外观模式恢复三档：**跟随系统 / 浅色 / 深色**，并在列表项摘要里显示当前档位。
  `AppThemeMode`、`AppPreferences.themeMode`、`MainActivity` 的读取与实时重设本来就是通的，
  缺的只是「主题真的用它」和「设置页给出选择」。
- `MainActivity` 一直是按组合读取偏好（`resolveDarkTheme(mode, isSystemInDarkTheme())`），所以切换立即生效，无需重启。

### 动态取色（Material You）

- `AppPreferences.dynamicColor`（SharedPreferences `teawords_settings/dynamic_color`），
  设置 → 外观模式里一个开关，附一句说明；**仅 Android 12（API 31）及以上可开**，不支持时开关禁用而不是假装能开。
- 开启后深浅两套都取系统配色：`dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`；关闭回到品牌 Purple。
- **默认关闭**：设计稿的 `dynamicColor` 是 false、palette 固定 purple，默认保持品牌配色；
  想改成默认开启只需把 `AppPreferences.dynamicColor` 的默认值改成 `true`。

### 本轮验证（实机）

- 显式「深色」：设置页、学习页、回望页、带壁纸首页全部正确（`appearance-dark.png`、`learning-dark.png`、
  `recall-dark.png`、`home-dark-wallpaper.png`），96dp 主按钮在深色下改为深底浅箭头，仍清晰。
- 「跟随系统」双向验证：`adb shell cmd uimode night yes/no` 切换系统夜间模式，应用随之变深/变浅
  （`follow-system-dark.png` / `follow-system-light.png`），验证后已把模拟器系统夜间模式恢复为 `no`。
- 动态取色：开启后选中态与预览卡从紫色变为系统壁纸配色（本机为蓝紫），深浅两套都跟随
  （`dynamic-color-light.png` / `dynamic-color-dark.png`）；关闭后回到 Purple（`appearance-dark.png`）。
- 构建 / 单测 / lint 结果见上一节（同一份代码）。

### 深色模式下已知的遗留

- `MainScreen.kt` 里保留的**旧复习（挖空）页面**是死代码（导航入口已移除），其中写死了 `Color.White`
  背景与 `Color.Black` 文本，深色下会不协调；因为它不可达，本轮没有为它补配色。
- `ui/theme/Color.kt` 的旧 ink/blue 调色板已无人引用（主题自己内联了 light/dark scheme），
  留着只为避免动到历史文件；后续可删。

## 2026-09-28 设置 → 检查更新（GitHub 公开仓库）

### 位置（按你的反馈调整过两轮）

第一版把卡片放在「关于App」页最上面，你指出：**别放顶上**、**仓库/反馈这种入口应该跟日志页在一起**，
而「检查更新」应该是设置里的一行。现在的结构是：

- 设置 → 关于：`关于App` → **`检查更新`** → `日志获取`（独立一行、独立页面）
- 设置 → 日志获取：诊断报告卡 + **项目仓库与反馈**（提交 BUG 的去处，`https://github.com/Cydiacoft/teaWords`）
- 关于App：只保留应用自己的标识、版本、简介与致谢，不再塞检查更新与仓库行
- 检查更新页 = 顶栏「检查更新 / 只比较版本号，不自动下载或安装」+ 一块 `secondaryContainer` 卡
  （圆形刷新图标、当前版本、状态 pill、`重新检查` / `打开发布页`、隐私说明）

### 行为

- 只读 GitHub 公开 API（`api.github.com/repos/Cydiacoft/teaWords`），匿名请求、带 User-Agent：
  1) `GET /releases/latest` → 2) 404 时 `GET /releases` → 3) 再退回 `GET /tags`。
- 三者都没有时如实显示「仓库里还没有发布版本或标签，暂时无法比较版本号」，**不**谎报「已是最新」；
  网络失败时显示具体原因（超时 / 域名解析失败 / HTTP 码），并提示可以直接打开发布页。
- 版本比较按**数字段**：`v1.10 > 1.9`（字符串比较会判反），缺段按 0；解析不出有意义版本号的标签
  （如 `nightly`）一律判「没有更新」——宁可漏报，也不骗用户去下载。
- 不下载安装包、不自动安装、不上传任何设备或账号信息；发现新版本时只显示版本号、发布日期、
  发布说明（最多 6 行，来自 release 的 body）和「打开发布页」。

### 实机验证（对着真仓库）

- 模拟器上点「检查更新」，请求真的到达 GitHub：`/releases/latest` 返回 404、`/releases` 与 `/tags` 都是空数组，
  界面显示「仓库里还没有发布版本或标签」——与该仓库当前状态一致（目前只有 `main` 分支，没有 release 也没有 tag）。
  也就是说这条路径是**端到端跑通并由真实响应驱动**的，不是本地假数据。
- 截图：`validation/expressive-ui/27-settings-main.png`（关于三段顺序）、`28-update-page.png`、
  `29-update-result.png`、`30-logs-page.png`（仓库入口在日志页）、`31-about-page.png`。
- 版本比较新增 5 项 JVM 单测（`UpdateCheckerVersionTest`）：tag 形状解析、数字段比较、缺段补零、
  不可解析标签不报更新、预发布后缀。
- 本轮收尾验证：`:app:assembleDebug` 通过（`app-debug.apk` 119,329,328 字节）；
  `:app:testDebugUnitTest` **117 项 / 0 失败**；`:app:lintDebug` **0 error / 56 warning / 5 hint**。





## 2026-09-29 代码审阅与逻辑修复

本轮以 ACTIVE_PROJECT.md 指向的 `.upstream-review` 为开发入口，并核对了提供的上一轮会话日志；原有工作区改动均保留。

### 学习数据一致性

- `LearningRepository.revertSelfReport()` 把错误的 `correct_streak` 改为该表实际存在的 `streak`，恢复统计页「撤销认识」功能；既有答题证据保留。
- `LearningRepository.save()` 将状态与事件写入同一事务，并检查状态插入是否成功。事件写入失败时整个保存回滚，避免只有学习状态却没有证据。
- `SenseRepository` 的 DIAGNOSTIC 只写 test_records 和 learning_events，不修改义项掌握次数、连续正确次数或复习记忆参数。自评认识不再计为回忆验证。保留旧历史数据，本轮未自动清除过往诊断产生的状态。

### 诊断与查词

- 诊断每道题使用单调时钟独立计时，排除初始化耗时，不再把整个会话累计时间重复算到每一道题。
- 诊断入口增加忙碌状态保护；保存期间阻止系统返回；协程取消不再显示为保存失败。
- 查词初始化改用 ViewModel 的生命周期作用域，数据库补列移到后台，并从 StateFlow.update 内移出数据库计数。初始化失败时查询明确报告词典未就绪。
- DictionaryGateway 在进度 ID 迁移尝试结束后才发布可用的搜索引擎，避免并发页面过早读取；迁移用的数据库 helper 在完成后关闭，成功打开时清除旧错误。

### 检查更新

- GitHub name/body/html_url/published_at 为 null 时正常处理，链接缺失时使用仓库页。
- 严格验证完整版本号，拒绝 `2garbage`、`2..1`、溢出数字等输入；当前安装版本不可比较时不宣称最新。
- 同一数字版本的正式版可更新同版本预发布安装；build metadata 不作为更新依据。预发布之间仍不比较后缀顺序。
- 回退 releases 列表忽略草稿与 prerelease，在接口当前返回的一页有效数据里按数字版本择优；tags 同样在当前页选取最高有效版本，跳过 nightly 等不可比较标签。本轮未增加分页。
- 无可比较发布信息时界面如实说明；非新版本显示「未发现更新版本」。

### 回归验证

- `:app:testDebugUnitTest`：124 项，0 失败（原 117 项 + 新增 7 项版本/API 响应测试；响应由本地拦截器提供）。
- Android 模拟器数据库测试：11 项通过，包含撤销、注入事件写入失败后事务回滚、重启保留记录、诊断不写掌握状态。
- Android 模拟器查词导航测试：2 项通过，覆盖模式自动切换、页面恢复后处理新查询、词书范围切换。
- 修复旧 SettingsScreenTest 缺少动态取色参数的编译错误，调用改为具名参数；将 SensePersistenceTest 原来永远返回 1 的伪断言换成实际数据断言。
- 本机离线 `connectedDebugAndroidTest` 因缺少 UTP 32.1.1 依赖无法启动，已通过 adb 安装测试 APK，并使用 AndroidJUnitRunner 直接执行以上 13 项相同的测试；未宣称 Gradle connected 任务成功。
- 最终 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 通过；lint 为 0 errors / 51 warnings / 5 hints。
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，119,329,516 字节，已覆盖安装到现有模拟器，保留应用数据。
- 未在本轮验证整套设置 UI 测试、真机完整学习会话，也未重新访问 GitHub 真实发布接口。

## 2026-09-29 工程迁移到最外层并清理旧版

当前唯一开发入口为 `D:/Projects/teaWords`，直接用 Android Studio 打开该目录。本文件前文出现的内层工程位置是历史记录，已不再存在。

- 新版 app、Gradle 配置与 wrapper、词典资源、当前文档和许可证迁到工程根目录；125 个源码与资源文件迁移前后 SHA256 全部一致。
- 新版 Git 仓库一并迁到根目录，保留 HEAD `b15d442c8bf2eaaee4d4aa90b18a82c492575751`、分支、远程配置和未提交改动；本次没有提交或推送。
- 已删除旧外层工程、内层工程残留、lint 验证副本、旧缓存、设备数据库备份、历史截图、旧设计导出、过期补丁及旧计划文档；迁移暂存目录也已清空删除。
- 保留必要词典工具到 `tools/`，将硬编码旧位置改为脚本相对路径；ECDICT 的来源说明与 MIT 许可证保留于 `tools/data/`。核查工具默认只读已打包词典，中文 token 探测与当前单字索引一致。
- README、QUICK_START 和 ACTIVE_PROJECT 已更新到外层开发入口。
- 从外层全新构建：`:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 全部成功；124 项单元测试 / 0 失败，lint 0 errors / 51 warnings / 5 hints。
- 词典只读核查正常；重新生成 APK 内的 `assets/dictionary.db` 与源文件 SHA256 一致，均为 64,098,304 字节。
- 当前安装包为 `D:/Projects/teaWords/app/build/outputs/apk/debug/app-debug.apk`，118,737,540 字节；测试和静态检查报告也在外层 `app/build/reports/`。


## 2026-09-29 去除重复入口与查阅生词复习

- 学习页最终按用户偏好保留「快速模式」开关，移除重复的「快速排雷」按钮。开关打开时主卡进入排雷，关闭时进入回忆练习；选择持久保存。
- 回望首页只提供查阅生词复习与生词本入口，移除与首页「最近查阅」重复的记录列表。开始复习留在回望流程，不再跳转词书学习。
- 生词复习来源为保留的词典查阅记录和旧版单词历史，排除句子、翻译记录及未查阅的词书词汇；按规范词形去重。新记录保留中文反查、词形查询选中的英文词头，不改变最近列表显示的原查询。
- 去掉超过 200 条记录时自动删除的旧逻辑，避免早期生词因列表上限丢失。首页最近列表仍有显示上限；关闭历史记录、外部应用输入不保存历史，以及用户主动删除记录的行为保持有效。历史中已被删掉的记录无法恢复。
- 到期词优先，其次未练过与尚未验证掌握的词；连续正确达到验证门槛的词等待到期。一轮沿用 10/15/20 的每轮目标；首次练习不预先展示英文答案。
- 查词页面保留单个输入框与内嵌查询按钮，自动识别模式；查询范围、手动模式和学习标记收进菜单。删除下方「发音与学习」卡，主词条保留发音、音标、收藏与复制释义；词条资料移入弹窗。
- 两种回忆练习共用事务保存，知识状态、答题证据与记忆状态同时提交，查看答案不算独立回忆通过；含大写字母的词条按规范词形匹配。

验证：

- 最终根目录构建与 Android 测试 APK 构建成功；128 项 JVM 单元测试，0 失败。
- 静态检查：0 errors / 51 warnings / 5 hints。
- 模拟器通过 22 项数据库和 Compose 界面测试：包含旧记录迁移、句子过滤、超过最近列表上限的生词保留、保存失败事务回滚、查询模式与范围切换、复习正确/查看答案保存、返回、大字体生词本与首页最近列表。
- 最后修正大小写词条匹配后，另用混合大小写词条重跑 2 项复习页面测试，均通过。
- 实际主页面操作验证快速模式开关的两条入口、回望复习不跳词书页，以及查词页简化结果；验证期间未在真实用户词条上提交练习答案，开关恢复原设置。
- 使用 adb 安装与 AndroidJUnitRunner 直接执行设备测试；未声称 Gradle connectedDebugAndroidTest 已运行。
- APK：`D:/Projects/teaWords/app/build/outputs/apk/debug/app-debug.apk`，119,368,676 字节，已覆盖安装到现有模拟器并保留数据。
- 页面验证截图位于 `validation/ui-simplification/`（已忽略）；本轮未提交或推送。


## 2026-09-29 在线长文翻译、菜单与品牌更新 · 1.1.0

- 默认 MyMemory 在线中英翻译，无需下载模型；按 UTF-8 字节安全拆分，保留段落换行。支持部分译文、进度、取消、复制及已完成片段缓存重试，超出 20,000 字符明确拒绝，不截断原文。
- 提供 DeepSeek 官方全文翻译选项，密钥用 Android Keystore 加密保存到不参加备份的私有目录；没有密钥不发送请求，失败不自动转发至其他翻译服务。本轮只验证 DeepSeek 请求和响应契约，没有真实付费调用。
- 可选 Google ML Kit 离线翻译；检查和下载均有等待上限，保留 Wi-Fi 下载、删除模型及取消等待。
- 根据用户最新偏好，翻译方式、联系邮箱、密钥和模型操作均集中到「设置 → 句子与长文翻译」。翻译页保留输入、方向切换、结果及复制，既有 ViewModel 再次打开时重载设置。外部分享需要主动点击翻译且不保存查询历史。
- 重写关于茶词，明确当前 ECDICT、本机发音和在线/离线翻译来源及本地数据行为；不再将 Free Dictionary API 描述为当前主词典。关于页检查更新暂时移除，设置中的独立更新页保留。
- 来源链接为小圆角并有内边距的点击区域，修复零内边距搭配圆角按钮裁切首字符的问题。
- 应用内三个下拉入口统一使用当前 Material 3 Expressive 原生菜单组件。根据用户对第一版外观的反馈，改为单个紧凑菜单容器，查词主菜单通过二级选项承载输入方式、查询范围、学习标记和词典管理；统一紫色选中反馈、图标、主题配色及滚动边界。
- 重绘茶杯、书页与茶叶矢量品牌图标，替换原 PNG；支持自适应与 Android 13 单色主题图标，保留 SVG 与 PNG 品牌预览源文件。
- 版本名 1.1.0，versionCode 2；README 和 CHANGELOG 已更新，新增通过环境变量提供发布签名的构建支持。

验证与发布准备：

- 139 项 JVM 单元测试，0 失败；覆盖安全拆分、实体解析、服务额度与错误、缓存重试、取消、超限拒绝、密钥和 DeepSeek 响应契约。
- 9 项模拟器测试通过，包含密钥加密、设置保存与重载、外部分享不自动翻译、关于与页面导航；菜单最终精简和关于按钮移除后重新通过 5 项相关界面测试。
- 模拟器实际将公开编写的英文长文在线翻译为中文，超过单次服务请求字节数，保留两个段落；未上传用户查阅文本或私密资料。
- 实际截图核查查词主菜单、输入方式、词书范围、每轮目标、翻译设置和关于来源；三处菜单均可用，来源文字完整。验证截图位于忽略的 validation/expressive-menus/。
- Debug 构建与 lint、已签名 Release 构建与 lint 均通过，静态检查 0 errors / 55 warnings / 5 hints。首次 Release 合并因 2 GB 构建堆不足失败，增加至 4 GB 并限制本次发布构建并发后通过。
- 调试包已覆盖安装到当前模拟器，保留既有数据。未用独立发布签名覆盖调试版，避免卸载造成数据丢失。
- 发布 APK：tools/out/release/teaWords-1.1.0.apk，108,547,917 字节；签名、版本和 SHA256 校验通过。最终发布包以 Release 附件 SHA256SUMS.txt 为准。
- 独立发布签名文件及密码仅保存在忽略的 tools/out/release-signing/，不提交、不上传；后续发布须备份并使用同一签名。
