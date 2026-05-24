# teaWords 快速入门指南

## 项目结构

```
teaWords/
├── app/
│   └── src/main/
│       ├── java/com/tea/teawords/
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   ├── Models.kt              ⭐ 数据类定义
│       │   │   ├── DatabaseHelper.kt      ⭐ 数据持久化
│       │   │   ├── DictionaryApi.kt       📡 在线词典
│       │   │   ├── WordLevelProvider.kt   🏷️ CET-4/6 标签
│       │   │   ├── ClozeGenerator.kt      🎲 题目生成
│       │   │   ├── OfflineQuestionBank.kt 📚 备用题库
│       │   │   ├── ReviewAnalytics.kt     📊 学习分析 (NEW)
│       │   │   └── TimerManager.kt        ⏰ 计时管理 (NEW)
│       │   └── ui/
│       │       ├── StatsDashboard.kt      📈 统计仪表板 (NEW)
│       │       ├── screens/
│       │       │   ├── MainScreen.kt      🎯 主屏幕
│       │       │   └── ReviewSessionScreenEnhanced.kt 🎯 复习会话 (NEW)
│       │       └── theme/
│       ├── assets/
│       │   ├── cet4.txt
│       │   └── cet6.txt
│       └── AndroidManifest.xml
├── build.gradle.kts
├── IMPLEMENTATION_SUMMARY.md             📋 完整文档
└── TESTING_CHECKLIST.md                  ✅ 测试清单
```

## 核心架构

### 数据流

```
用户界面 (UI Layer)
    ↓
业务逻辑 (Logic Layer)
    ├── ClozeGenerator (题目生成)
    ├── ReviewAnalytics (分析引擎)
    ├── TimerManager (计时管理)
    └── DictionaryApi (API 调用)
    ↓
数据层 (Data Layer)
    ├── DatabaseHelper (SQLite)
    ├── OfflineQuestionBank (本地备用)
    └── Assets (CET-4/6 数据)
```

## 主要功能模块

### 1️⃣ 翻译查词 (TRANSLATE)
- **输入**: 英文单词或中文句子
- **输出**: 中英双语释义
- **特性**: CET-4/6 标签、发音、词例

### 2️⃣ 复习系统 (REVIEW)
- **流程**:
  1. 选择难度和词汇范围
  2. 系统生成题目（离线/在线）
  3. 答题（单选）
  4. 计时与评分
  5. 统计保存

- **题目类型**:
  - 单词填空：从句子中挖空单词
  - 语义填空：根据句意选择合适的词

### 3️⃣ 学习统计 (STATS)
- **概览**：进度、Streak、等级
- **词库**：推荐复习词汇
- **错题**：高频错误分析

## 快速开始

### 编译项目

```bash
cd d:\Projects\teaWords
./gradlew build
```

### 运行应用

```bash
./gradlew installDebug
```

或在 Android Studio 中直接运行。

## 关键文件说明

### Models.kt (数据定义)

定义了所有核心数据类：

```kotlin
// 复习题目
data class ClozeProblem(
    val id: String,
    val originalWord: String,
    val sentence: String,
    val blankedSentence: String,  // 挖空后的句子
    val clozeWord: String,         // 正确答案
    val options: List<String>,     // 选项
    val difficulty: Int            // 1-5 难度
)

// 错题记录
data class ErrorRecord(
    val problemId: String,
    val word: String,
    val userAnswer: String,
    val correctAnswer: String,
    val lastAttemptTime: Long
)
```

### DatabaseHelper.kt (数据库)

管理 3 个新表：

- **error_records** - 错题记录
- **review_sessions** - 复习会话统计
- **vocab_stats** - 单词掌握度

**关键方法**:
```kotlin
// 记录错题
dbHelper.recordError(ErrorRecord(...))

// 更新单词统计
dbHelper.updateVocabStats(word, isCorrect)

// 获取推荐词汇
dbHelper.getWeakWords(limit = 10)
```

### ReviewAnalytics.kt (分析引擎)

提供所有统计分析：

```kotlin
val analytics = ReviewAnalytics(dbHelper)

// 学习进度
val progress = analytics.getLearningProgress()
println("准确率: ${progress.overallAccuracy}%")

// 错题分析
val errors = analytics.getErrorAnalysis()
println("高频错词: ${errors.frequentErrors}")

// 推荐难度
val nextDifficulty = analytics.recommendDifficulty()
```

### ClozeGenerator.kt (题目生成)

两层生成策略：

```kotlin
generator.generateClozeProblem("apple", "An apple a day") { problem ->
    // 第一优先级：离线题库
    if (offlineBank.hasQuestion("apple")) {
        return offlineBank.getQuestion("apple")
    }
    
    // 第二优先级：API 查询
    dictionaryApi.lookupWord("apple") { definition ->
        // 生成题目
    }
    
    // 第三优先级：语义填空
    createSemanticCloze("apple", definition)
}
```

### TimerManager.kt (计时)

管理倒计时：

```kotlin
val timer = TimerManager(coroutineScope)

// 计算推荐时间
val time = timer.calculateRecommendedTime(
    problemCount = 10,
    difficulty = 2  // 普通难度
)  // 返回 100 秒 (10 题 × 10秒 × 1.0 倍数)

// 启动计时
timer.startTimer(time)
timer.onTick = { secondsRemaining ->
    updateUI(secondsRemaining)
}
timer.onTimeout = {
    autoSubmit()
}
```

## UI 流程图

### 首次使用流程

```
启动 App
    ↓
[主屏幕] TRANSLATE 标签
    ↓
用户输入单词
    ↓
显示翻译结果 + CET-4/6 标签
    ↓
用户点击 ⭐ 加入生词本
    ↓
用户点击 REVIEW 开始复习
    ↓
[复习屏幕] 选择难度
    ↓
开始答题（计时开始）
    ↓
完成 → 显示成绩 → 保存到数据库
    ↓
用户点击 STATS 查看进度
    ↓
[统计仪表板] 显示学习分析
```

## 数据库架构

### Schema v2

```sql
-- 原有表
CREATE TABLE history (...)
CREATE TABLE vocabulary (...)

-- Phase 1-2 新增表
CREATE TABLE error_records (
    problemId TEXT,
    word TEXT,
    userAnswer TEXT,
    correctAnswer TEXT,
    attempt_count INTEGER,
    last_attempt_time LONG,
    PRIMARY KEY (problemId, word)
);

CREATE TABLE review_sessions (
    sessionId TEXT PRIMARY KEY,
    totalProblems INTEGER,
    correctCount INTEGER,
    accuracy FLOAT,
    timeSpentSeconds LONG,
    averageTimePerProblem FLOAT,
    difficulty INTEGER,
    timestamp LONG
);

CREATE TABLE vocab_stats (
    word TEXT PRIMARY KEY,
    accuracy FLOAT,
    reviewCount INTEGER,
    masteryLevel TEXT,
    lastReviewTime LONG
);
```

## 常见操作

### 添加新题目到离线题库

编辑 `OfflineQuestionBank.kt`:

```kotlin
val questions = mutableListOf(
    OfflineQuestion(
        id = "q001",
        word = "elephant",
        sentence = "An elephant is a large animal.",
        blankedSentence = "An ________ is a large animal.",
        correctAnswer = "elephant",
        options = listOf("elephant", "giraffe", "lion", "tiger"),
        difficulty = 2,
        category = "ANIMAL"
    )
    // ... 更多题目
)
```

### 查询学习统计

```kotlin
val analytics = ReviewAnalytics(dbHelper)

// 获取最近 7 天的学习趋势
val trend = analytics.getLearningTrend(7)
trend.forEach { dayStats ->
    println("${dayStats.date}: ${dayStats.accuracy}% (${dayStats.correctCount}/${dayStats.problemCount})")
}

// 获取推荐词汇
val weak = analytics.getRecommendedWords(10)
weak.forEach { word ->
    println("需要复习: $word")
}
```

## 性能优化建议

1. **数据库查询**
   - 为常用字段添加索引
   - 使用分页加载长列表

2. **题目生成**
   - 缓存已生成的题目
   - 预先加载离线题库

3. **UI 渲染**
   - 使用 `remember` 缓存分析结果
   - 图表使用 Canvas 代替 Compose 布局

4. **网络请求**
   - 实现连接池复用
   - 添加请求超时机制

## 故障排除

### 问题 1: 编译错误 "Cannot resolve symbol"

**解决**: 检查导入语句
```kotlin
// ❌ 错误
import com.tea.teawords.ReviewAnalytics

// ✅ 正确
import com.tea.teawords.data.ReviewAnalytics
```

### 问题 2: 数据库迁移失败

**解决**: 清除应用数据
```bash
adb shell pm clear com.tea.teawords
```

### 问题 3: 计时器不停止

**解决**: 确保在 `DisposableEffect` 中清理
```kotlin
DisposableEffect(Unit) {
    onDispose {
        timerManager.stopTimer()  // 必须调用
    }
}
```

## 贡献指南

如需添加新功能：

1. 在 `Models.kt` 中定义数据类
2. 在 `DatabaseHelper.kt` 中添加 CRUD 方法
3. 创建业务逻辑类（如 `XXXAnalytics.kt`）
4. 在 `ui/` 中创建相应的 Compose 组件
5. 在 `MainScreen.kt` 中集成
6. 更新 `TESTING_CHECKLIST.md`

## 相关资源

- [Kotlin 官方文档](https://kotlinlang.org/docs/)
- [Jetpack Compose](https://developer.android.com/develop/ui/compose)
- [SQLite 查询](https://www.sqlite.org/lang.html)
- [Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

---

**最后更新**: 2024年  
**维护者**: teaWords 开发团队
