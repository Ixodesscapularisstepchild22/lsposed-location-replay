[English](BUILDING.md) | **简体中文**

# 构建与排错

## 依赖

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | AGP 9.x 拒绝 JDK 8 / 11 |
| Android SDK | `compileSdk 35` / `minSdk 24` | 需要 `platforms;android-35` 与 `build-tools` |
| Gradle | 9.x | 仓库自带 wrapper |
| Xposed API | `api-82.jar` | `compileOnly`，已随源码提供 |

## 构建

```bash
git clone https://github.com/BH4GMI/lsposed-location-replay.git
cd lsposed-location-replay/lsposed

# Linux / macOS
export JAVA_HOME=/path/to/jdk-21

# Windows (PowerShell)
# $env:JAVA_HOME = 'C:\path\to\jdk-21'

./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

调试构建用 `assembleDebug`，产物在 `app/build/outputs/apk/debug/`。

### Android SDK 路径

仓库不包含 `local.properties`。首次构建前需自行创建：

```properties
sdk.dir=/path/to/Android/Sdk
```

或设置环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。

### 签名

发布签名完全取自环境变量，仓库内不含 keystore 路径，也不含任何口令：

| 变量 | 含义 |
|---|---|
| `CE_KEYSTORE` | keystore 文件路径 |
| `CE_KEY_ALIAS` | keystore 内的别名 |
| `CE_KEY_PASS` | 口令（store 与 key 使用同一口令） |

```bash
export CE_KEYSTORE=/path/to/release.keystore
export CE_KEY_ALIAS=your-alias
export CE_KEY_PASS=your-password
./gradlew assembleRelease
```

三个变量缺任一，`assembleRelease` 会回落到 debug 签名，仅适用于本地自测。
**不要把 keystore 或口令提交到仓库。**

## 排错

### `Could not initialize native services` / `native-platform.dll` 加载失败

Gradle 无法写入 `~/.gradle`。常见原因：

- 该目录被安全软件或沙箱拦截；
- 目录权限损坏。

处理：确认当前用户对 `%USERPROFILE%\.gradle`（Linux/macOS 为 `~/.gradle`）有读写权限，
必要时删除该目录让 Gradle 重建。

### `Unsupported class file major version` / AGP 拒绝 JDK

`JAVA_HOME` 指向了 JDK 8/11。切换到 JDK 21：

```bash
java -version        # 应为 21.x
echo $JAVA_HOME
```

也可以在 `lsposed/gradle.properties` 里钉死：

```properties
org.gradle.java.home=/path/to/jdk-21
```

### 找不到 `de.robv.android.xposed`

确认 `lsposed/app/libs/api-82.jar` 存在。该文件随源码提供，
构建脚本中以 `compileOnly files('libs/api-82.jar')` 引用。

### 安装到设备后被系统拒绝

部分 ROM 会拦截通过 adb 安装的未验证包：

```bash
adb shell settings put global verifier_verify_adb_installs 0
```

### 模块装上了但目标 App 零注入

按顺序检查：

1. `lsposed/app/src/main/assets/xposed_init` 里的入口类名与包名一致
   （必须是 `com.locrec.app.MainHook`）。
2. `AndroidManifest.xml` 里的 `xposedmodule` / `xposedminversion` 元数据存在。
3. LSPosed 里模块已启用且勾选了作用域。
4. 看模块日志确认注入是否发生：

```bash
adb logcat -s fakeloc-hook:V
```

正常应能看到类似：

```
loaded pkg=<目标包名> datasetReady=...
app hooks installed in <目标包名> (...)
```

### 系统层 hook 没生效

系统层 hook 需要 LSPosed 作用域额外勾选「系统框架 / Android」，并且**重启设备**。
装完新版本模块后同样需要重启才会加载新代码。
