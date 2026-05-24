# teaWords - 茶词 - 即刻翻译查词应用

## 项目概述

teaWords 是一个专注于**即开即搜**的 Android 翻译应用，采用创新的交互设计和强大的学习功能。应用通过以下特性帮助用户快速查词和深化学习：

1. **即刻翻译** - 搜索区域位于屏幕中下部，适配单手操作
2. **双语词典** - 专业、克制的排版，一目了然的翻译结果
3. **词汇等级标签** - 显示单词属于 CET-4 或 CET-6 词汇等级
4. **智能复习系统** - 随机挖空、语义填空、自适应难度
5. **完整学习分析** - 错题追踪、学习进度、个性化推荐

---

## 第 1-2 阶段完成的功能

### 第 1 阶段：核心复习系统 + 数据持久化

#### 1.1 数据模型 (`Models.kt`)

新增核心数据类：

- **`ClozeType`** - 枚举类型：`WORD_CLOZE`（单词填空）、`SEMANTIC_CLOZE`（语义填空）
- **`ClozeProblem`** - 单个挖空题目
  - `id`: 题目唯一标识
  - `originalWord`: 原始单词
  - `sentence`: 完整句子
  - `blankedSentence`: 挖空后的句子
  - `clozeWord`: 正确答案
  - `options`: 选项列表（含干扰项）
  - `difficulty`: 难度等级 (1-5)
  
- **`ReviewAnswer`** - 用户答题记录
  - `problemId`: 题目 ID
  - `userAnswer`: 用户选择
  - `isCorrect`: 是否正确
  - `timeSpentSeconds`: 耗时

- **`ReviewSession`** - 一次复习会话
  - `sessionId`: 会话 ID
  - `vocabItems`: 参与词汇
  - `problems`: 题目列表
  - `answers`: 答题记录
  - `difficulty`: 会话难度等级
  - `startTime`: 开始时间
  
- **`ErrorRecord`** - 错题记录
  - `problemId`: 题目 ID
  - `word`: 错误单词
  - `userAnswer` / `correctAnswer`: 用户答案与正确答案
  - `attempt_count`: 尝试次数
  - `last_attempt_time`: 最后尝试时间

- **`ReviewSessionRecord`** - 复习会话统计
  - `sessionId`, `totalProblems`, `correctCount`
  - `accuracy`: 准确率百分比
  - `timeSpentSeconds`: 总耗时
  - `difficulty`: 题目难度

- **`VocabularyStatistics`** - 单词学习统计
  - `word`: 单词
  - `accuracy`: 准确率 (0-1)
  - `reviewCount`: 复习次数
  - `masteryLevel`: 掌握等级

- **`OfflineQuestion`** - 离线备用题目结构

#### 1.2 数据库升级 (`DatabaseHelper.kt`)

- **版本升级**: v1 → v2
- **新表结构**:
  - `error_records` - 记录每个单词的错题
  - `review_sessions` - 追踪每次复习会话的统计数据
  - `vocab_stats` - 存储单词的掌握度统计

- **新增 CRUD 方法** (~250 行):
  - `recordError()` / `getErrorRecords()` / `getErrorWords()`
  - `recordReviewSession()` / `getReviewSessions()` / `getReviewStats()`
  - `updateVocabStats()` / `getVocabStats()` / `getWeakWords()`

#### 1.3 题目生成引擎 (`ClozeGenerator.kt`)

- **两层生成策略**:
  1. 优先从**离线题库**获取（快速、可靠）
  2. 若无则从**字典 API** 获取例句并自动生成
  
- **智能干扰项生成**（4 层策略）:
  - 从同义词库选择
  - 从相同词性选择
  - 从相同难度选择
  - 从任意词库备选

#### 1.4 离线题库 (`OfflineQuestionBank.kt`)

- **25+ 精选题目**，覆盖多个难度等级 (1-3)
- **8 大分类**: 水果、物体、人物、地点、情感、形容词、动词、名词
- **包含完整题目信息**:
  - 句子模板
  - 正确答案
  - 干扰选项
  - 难度级别

#### 1.5 复习 UI 组件

**核心组件** (`MainScreen.kt`):
- `ReviewView` - 复习主屏幕
- `ReviewStartScreen` - 复习开始选择界面
- `ReviewSessionScreen` - 题目展示与答题交互
- `ReviewCompleteScreen` - 复习完成统计

**底部导航扩展**:
- 新增 `REVIEW` 标签到底部导航栏

#### 1.6 计时管理 (`TimerManager.kt`)

- **动态计时**：基于题目数量和难度自动计算推荐时间
  - 公式：`题数 × 10秒 × (难度/2)`
- **实时监控**：每 100ms 刷新一次剩余时间
- **超时处理**：时间到自动提交、显示 10 秒警告
- **中断/恢复**：支持暂停和继续计时

---

### 第 2 阶段：智能学习分析 + 自适应推荐

#### 2.1 学习分析引擎 (`ReviewAnalytics.kt`)

提供全面的学习数据分析：

**核心统计**:
- `getLearningProgress()` - 总体学习进度
  - 总体准确率 / 最近 7 天准确率
  - 连续复习天数（Streak）
  - 用户等级 (1-6 级)
  - 总学习时长

- `getErrorAnalysis()` - 错题分析
  - 总错误数、错误单词数、错误率
  - 高频错词排行 Top 10

- `getLearningTrend()` - 学习趋势（7 天）
  - 每日的题数、正确数、准确率
  - 适合绘制趋势图表

- `getDifficultyDistribution()` - 难度分布
  - 各难度等级的准确率分析
  - 帮助评估学习水平

**个性化推荐**:
- `getVocabularyMastery()` - 单词掌握度分析
  - 掌握等级: 未学 / 学习中 / 基本熟悉 / 熟练 / 完全掌握
  - 标记需要复习的词汇

- `getRecommendedWords()` - 推荐需要复习的单词（限制数量）

- `recommendDifficulty()` - 推荐下一题难度
  - 准确率 > 85% → 增加难度
  - 准确率 < 60% → 降低难度
  - 否则保持当前难度

#### 2.2 统计仪表板 UI (`StatsDashboard.kt`)

**三大标签页**:

1. **概览（Overview）**
   - 用户等级、连续复习天数、连续火焰图标
   - 总体准确率 / 最近 7 天准确率 / 学习时长
   - 学习趋势迷你图表（7 天柱状图）
   - 难度分布折线
   - 智能建议（下次推荐难度）

2. **词库（Vocabulary）**
   - 推荐复习列表（Top 5）
   - 每个单词的准确率、复习次数、掌握等级
   - 颜色编码：绿色 (≥80%) / 橙色 (≥60%) / 红色 (<60%)

3. **错题（Error）**
   - 错题统计卡片（总错误、错误单词、错误率）
   - 高频错词排行 Top 10
   - 快速定位需要加强的词汇

#### 2.3 增强的复习会话屏幕 (`ReviewSessionScreenEnhanced.kt`)

**新增功能**:
- 实时计时器显示（左上角）
  - 绿色 (正常) → 橙色 (10秒警告) → 红色 (时间到)
  - 自动格式化为 MM:SS
  
- 难度指示器（5 星难度）
- 题型标签（单词填空 / 语义填空）
- 进度条与题号显示
- 自动提交机制（时间到自动提交）
- 完成屏幕统计
  - 准确率百分比
  - 正确数/总数
  - 耗时显示

---

## 架构设计

### 分层架构

```
UI Layer (Compose Components)
├── StatsDashboard (统计仪表板)
├── ReviewSessionScreenEnhanced (复习会话)
├── MainScreen (主屏幕集成)
└── HomeView, ResultView, etc.

Business Logic Layer
├── ReviewAnalytics (学习分析)
├── TimerManager (计时管理)
└── ClozeGenerator (题目生成)

Data Layer
├── DatabaseHelper (持久化)
├── Models (数据定义)
├── OfflineQuestionBank (备用题库)
└── DictionaryApi (在线字典)
```

### 数据流

```
用户答题 → ReviewSessionScreen → onAnswerSubmit 
  → 记录 ErrorRecord（若错误）
  → 更新 VocabularyStatistics
  → 更新 ReviewSessionRecord
  
查看统计 → StatsDashboard 
  → ReviewAnalytics.getLearningProgress()
  → 读取数据库 (error_records, review_sessions, vocab_stats)
  → 计算趋势、推荐、分析
```

---

## 文件清单

### 新增文件

| 文件 | 行数 | 功能 |
|------|------|------|
| `ReviewAnalytics.kt` | 295 | 学习分析引擎 |
| `TimerManager.kt` | 95 | 计时管理 |
| `StatsDashboard.kt` | 565 | 统计仪表板 UI |
| `ReviewSessionScreenEnhanced.kt` | 290 | 增强复习会话 UI |
| `OfflineQuestionBank.kt` | 180 | 离线备用题库 |

### 修改文件

| 文件 | 变更 | 说明 |
|------|------|------|
| `Models.kt` | +150 行 | 新增 5 个数据类 + ClozeType 枚举 |
| `DatabaseHelper.kt` | +250 行 | 3 新表 + CRUD 方法 |
| `ClozeGenerator.kt` | 更新 | 集成离线题库备用 |
| `MainScreen.kt` | +20 行 | 新增 STATS 标签、导入 StatsDashboard |

---

## 使用流程

### 复习流程

1. 用户点击底部导航 **REVIEW** 标签
2. 进入 ReviewView，选择难度或词汇范围
3. 点击开始复习
4. 系统计算推荐时间，启动计时器
5. 用户逐题答题
   - 每次错误自动记录到 `error_records` 表
   - 每次答题更新 `vocab_stats`
6. 时间到或全部完成时显示完成屏幕
7. 点击返回，会话统计保存到 `review_sessions` 表

### 查看统计流程

1. 用户点击底部导航 **STATS** 标签
2. 打开 StatsDashboard
3. 在三个标签页切换查看：
   - **概览** - 学习进度、趋势、建议
   - **词库** - 推荐复习词汇
   - **错题** - 高频错误分析
4. 点击词汇项可跳转回翻译页面快速复习

---

## 关键实现细节

### 离线备用策略

```kotlin
// ClozeGenerator.kt
fun generateClozeProblem(...) {
    // 第一步：尝试离线题库
    val offlineQ = offlineBank.getQuestionByWord(word)
    if (offlineQ != null) return offlineQ  // 快速返回
    
    // 第二步：API 查询
    dictionaryApi.lookupWord(word) { ... }
    
    // 第三步：生成语义填空
    if (noExampleFound) createSemanticCloze(word, definition)
}
```

### 自适应难度推荐

```kotlin
// ReviewAnalytics.kt
fun recommendDifficulty(currentDifficulty: Int): Int {
    val avgAccuracy = recentSessions.avgAccuracy
    return when {
        avgAccuracy > 85f && currentDifficulty < 5 → currentDifficulty + 1
        avgAccuracy < 60f && currentDifficulty > 1 → currentDifficulty - 1
        else → currentDifficulty
    }
}
```

### 计时机制

```kotlin
// TimerManager.kt
fun calculateRecommendedTime(problemCount: Int, difficulty: Int): Int {
    val baseTime = 10  // 秒/题
    val multiplier = difficulty / 2.0f  // 难度 2 为基准
    return (problemCount * baseTime * multiplier).toInt()
}
```

---

## 未来计划

### 第 3 阶段
- [ ] 拼写练习模式
- [ ] 听力理解题
- [ ] 高级数据导出（CSV、PDF）
- [ ] 社交分享成绩

### 性能优化
- [ ] 数据库查询优化（索引）
- [ ] 题目生成缓存
- [ ] 图表渲染优化

### 用户体验
- [ ] 深色模式适配
- [ ] 音频发音
- [ ] 题目收藏功能
- [ ] 学习提醒通知

---

## 技术栈

- **语言**: Kotlin
- **UI 框架**: Jetpack Compose
- **数据库**: SQLite (DatabaseHelper)
- **网络**: OkHttp + Gson
- **异步**: Coroutines
- **导航**: Compose Navigation (预计)

---

## 总结

teaWords 已完成从**核心翻译功能**到**智能学习分析**的完整升级。通过多层架构、离线备用、数据持久化和个性化分析，应用现已具备专业语言学习工具的基本能力。所有功能均已集成到主 UI 中，可以无缝运行。

---

**构建日期**: 2024  
**版本**: 1.0 (Phase 1-2 Complete)
