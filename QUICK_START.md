# 茶词开发与构建

## 打开工程

用 Android Studio 打开 `D:/Projects/teaWords`。当前版本已位于最外层，旧工程与临时副本不再作为开发入口。

## 本机构建

工程使用 JDK 21、Gradle 9.4.1、Android SDK Platform 37，运行最低要求为 API 24。
本机 `local.properties` 指向 `D:/Android Studio/SDK`；该文件不提交到 Git。

在工程根目录运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline "-Dorg.gradle.java.home=C:\Program Files\Android\Android Studio\jbr"
```

`--offline` 使用已有依赖缓存；首次安装依赖时去掉此参数。工程保留 Google 仓库与 Maven 镜像配置。

产物与报告：

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`。
- 静态检查：`app/build/reports/lint-results-debug.html`。

## 模拟器验证

测试平台依赖可用时：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Dorg.gradle.java.home=C:\Program Files\Android\Android Studio\jbr"
```

本机离线缓存缺少 UTP 32.1.1，设备测试也可以先构建测试包，再由 Android 测试运行器执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline "-Dorg.gradle.java.home=C:\Program Files\Android\Android Studio\jbr"
& 'D:/Android Studio/SDK/platform-tools/adb.exe' install -r 'app/build/outputs/apk/debug/app-debug.apk'
& 'D:/Android Studio/SDK/platform-tools/adb.exe' install -r 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
& 'D:/Android Studio/SDK/platform-tools/adb.exe' shell am instrument -w -r -e class 'com.teameow.teawords.data.LearningPersistenceTest,com.teameow.teawords.data.SensePersistenceTest' 'com.teameow.teawords.test/androidx.test.runner.AndroidJUnitRunner'
```

## 离线词典工具

应用已经打包 `app/src/main/assets/dictionary.db`，正常编译不需要重新生成词典。

只读核查已打包词典：

```powershell
python tools/verify_dictionary.py
```

重新生成时，把 ECDICT 原始 CSV 放到 `tools/data/ecdict.csv`，然后执行：

```powershell
python tools/build_dictionary.py
python tools/add_token_stats.py
python tools/verify_dictionary.py tools/out/dictionary.db
```

原始 CSV 未随工程保留，需要自行提供。构建输出位于 `tools/out/`；核查完成后可将生成的数据库替换到应用资源目录。
原始 CSV 和临时输出均被 Git 忽略。
