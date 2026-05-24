# teaWords (茶词)

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/android)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-orange.svg)](https://developer.android.com/jetpack/compose)

**teaWords (茶词)** 是一款极致简洁、专注单词学习与翻译的 Android 应用。旨在为你提供纯净的查词体验，并在不经意间通过 Bing 壁纸和一言名句，带给你一丝片刻的宁静。

## ✨ 主要功能

*   **🔍 智能查词与翻译**：支持单词深度查询（释义、音标、例句）及长句在线翻译。
*   **📖 沉浸式学习**：
    *   **单词本 (Vocabulary)**：一键收藏生词，支持 3D 翻转卡片复习模式。
    *   **智能练习 (Review)**：基于词书或搜索历史自动生成完形填空（Cloze）练习。
*   **📊 数据统计与分析**：跟踪你的学习进度，分析薄弱词汇，推荐最适合的学习难度。
*   **🎨 优雅的 UI/UX 设计**：
    *   **Bing 每日壁纸**：主页自动更换精美背景，支持自适应文字配色。
    *   **一言 (Hitokoto)**：动态展示人心文字，支持自定义刷新间隔。
    *   **悬浮透明 Dock**：现代化的导航栏设计，支持沉浸式视觉体验。
*   **⚙️ 深度定制**：自定义主页标题、口音偏好（美音/英音）、多版本词书管理（CET4/6, TEM4/8）。

## 🛠️ 技术栈

*   **UI 框架**：Jetpack Compose (Material 3)
*   **网络请求**：OkHttp, Free Dictionary API
*   **图片加载**：Coil
*   **数据存储**：SQLite (SQLiteOpenHelper)
*   **架构模式**：响应式 UI 驱动

## 🚀 快速开始

### 环境要求
*   Android Studio Jellyfish | 2023.3.1 或更高版本
*   Kotlin 2.0+
*   Android SDK 24 (Android 7.0) 及以上

### 编译运行
1.  克隆仓库：
    ```bash
    git clone https://github.com/Cydiacoft/teaWords.git
    ```
2.  在 Android Studio 中打开项目。
3.  等待 Gradle 同步完成。
4.  点击 **Run** 运行到你的设备或模拟器上。

## 📜 致谢

*   **Jetpack Compose** - 现代原生 Android UI 工具包
*   **Material 3** - Google 的新一代设计语言
*   **Hitokoto 一言** - 提供温暖的人心文字
*   **Bing Wallpaper** - 提供每日精美壁纸
*   **Free Dictionary API** - 基础词典数据支持

---

Developed by **teaMeow Technology**
