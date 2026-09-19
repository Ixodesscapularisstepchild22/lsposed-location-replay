**English** | [简体中文](BUILDING.zh-CN.md)

# Building & Troubleshooting

## Requirements

| Item | Version | Notes |
|---|---|---|
| JDK | **21** | AGP 9.x rejects JDK 8 / 11 |
| Android SDK | `compileSdk 35` / `minSdk 24` | Requires `platforms;android-35` and `build-tools` |
| Gradle | 9.x | Wrapper bundled with the repository |
| Xposed API | `api-82.jar` | `compileOnly`, shipped with the source |

## Build

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

For a debug build use `assembleDebug`; the output lands in `app/build/outputs/apk/debug/`.

### Android SDK path

The repository does not include `local.properties`. Create it yourself before the first build:

```properties
sdk.dir=/path/to/Android/Sdk
```

Alternatively, set the `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variables.

### Signing

Release signing is taken entirely from environment variables — the repository contains no keystore path and no password:

| Variable | Meaning |
|---|---|
| `CE_KEYSTORE` | path to the keystore file |
| `CE_KEY_ALIAS` | key alias inside the keystore |
| `CE_KEY_PASS` | password (the same value for the store and the key) |

```bash
export CE_KEYSTORE=/path/to/release.keystore
export CE_KEY_ALIAS=your-alias
export CE_KEY_PASS=your-password
./gradlew assembleRelease
```

If any of the three is missing, `assembleRelease` falls back to the debug signing
configuration, which is suitable for local testing only. **Never commit a keystore or its
password to the repository.**

## Troubleshooting

### `Could not initialize native services` / `native-platform.dll` fails to load

Gradle cannot write to `~/.gradle`. Common causes:

- The directory is blocked by security software or a sandbox;
- The directory permissions are corrupted.

Fix: make sure the current user has read/write access to `%USERPROFILE%\.gradle` (`~/.gradle` on Linux/macOS),
and delete the directory if necessary so Gradle can recreate it.

### `Unsupported class file major version` / AGP rejects the JDK

`JAVA_HOME` points at JDK 8/11. Switch to JDK 21:

```bash
java -version        # should be 21.x
echo $JAVA_HOME
```

You can also pin it in `lsposed/gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk-21
```

### `de.robv.android.xposed` cannot be found

Make sure `lsposed/app/libs/api-82.jar` exists. The file ships with the source,
and the build script references it as `compileOnly files('libs/api-82.jar')`.

### The system rejects the package after it is installed on the device

Some ROMs block unverified packages installed over adb:

```bash
adb shell settings put global verifier_verify_adb_installs 0
```

### The module is installed but the target app shows zero injection

Check in this order:

1. The entry class name in `lsposed/app/src/main/assets/xposed_init` matches the package name
   (it must be `com.locrec.app.MainHook`).
2. The `xposedmodule` / `xposedminversion` metadata is present in `AndroidManifest.xml`.
3. The module is enabled in LSPosed and its scope is selected.
4. Check the module log to confirm whether injection happened:

```bash
adb logcat -s fakeloc-hook:V
```

You should normally see something like:

```
loaded pkg=<目标包名> datasetReady=...
app hooks installed in <目标包名> (...)
```

### System-level hooks do not take effect

System-level hooks require the additional 「系统框架 / Android」 scope in LSPosed, plus a **device reboot**.
After installing a new version of the module, a reboot is likewise required before the new code is loaded.
