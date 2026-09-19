# LocRec

**Android 定位实录 / 回放模块** —— 在目标地点实录设备真实接收到的定位环境，再按原节奏回放给作用域内的指定应用。

[English](README.md) | **简体中文**

[![Requires](https://img.shields.io/badge/requires-LSPosed-critical.svg)](https://github.com/LSPosed/LSPosed)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B%20(API%2024)-green.svg)](#运行环境)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](./LICENSE)

---

> [!WARNING]
> ## 严禁用途
>
> **严禁使用本项目伪造超星学习通（`com.chaoxing.mobile`）的打卡、签到、考勤，或任何形式的到场核验。**
>
> 超星学习通是本项目的主要测试对象，但测试范围仅限于定位功能的兼容性与行为分析。
> 将本项目用于伪造打卡、代签、代刷等行为，违反目标服务条款，并可能构成违法违规；
> 由此产生的账号封禁、学业处分及法律责任，全部由使用者自行承担。
> 完整条款见 [DISCLAIMER.zh-CN.md](./DISCLAIMER.zh-CN.md)。

> [!IMPORTANT]
> **运行环境**：LocRec 是一个 LSPosed 模块，依赖 LSPosed 提供的 Xposed API 运行时与作用域注入能力。
> **设备上没有安装并启用 LSPosed，模块完全无法工作。**
> 构建产物是模块 APK，**不是**可以独立运行的定位应用。

## 这是什么

LocRec 先从真实环境采集一份定位快照，之后把这份快照按原节奏回放给作用域内选定的应用。

它的关键点是**不伪造坐标**，而是「录下来、放回去」：坐标来自录制现场，
WiFi 扫描、基站、NMEA、卫星状态一并采集与回放，因此回放出的这组信号彼此自洽。

**它是什么**

- 定位信号的真实环境采集器与回放器（GPS / 网络定位解、WiFi 扫描、CellInfo、NMEA、GnssStatus）
- 基于 LSPosed 的进程级 + 系统级注入模块
- 用于定位相关兼容性测试、定位链路分析与隐私研究的工具

**它不是什么**

- 不是 mock location provider（不使用 `addTestProvider`）
- 不编造卫星星座，也不凭空生成信号
- 不是通用「一键改定位」应用：需要先到目标地点实地实录
- 不联网、不上报、不含任何遥测

## 测试范围

本项目的主要兼容性验证在**超星学习通**（`com.chaoxing.mobile`）上完成，覆盖两条定位路径：

- 百度定位 SDK（BDLocation）链路；
- Android 系统定位 API（`LocationManager` / `WifiManager` / `TelephonyManager`）。

验证内容为 hook 是否命中、回放数据是否自洽、目标应用行为是否稳定。
本项目不提供、也不支持任何规避到场合规校验的能力。

## 运行环境

| 项 | 要求 |
|---|---|
| **LSPosed** | **必需。** 模块由 LSPosed 加载，并使用其提供的 Xposed API（`de.robv.android.xposed`）与作用域注入能力。设备上需已安装 LSPosed，且模块处于启用状态 |
| Android | **7.0（API 24）及以上** |
| 权限 | `ACCESS_FINE_LOCATION`（实录时需要） |
| 模块作用域 | 至少勾选目标应用；需要全局生效时再勾选「系统框架 / Android」 |

安装前请先确认设备上的 LSPosed 工作正常。

## 安装

1. 从 [Releases](https://github.com/BH4GMI/lsposed-location-replay/releases) 下载 `app-release-*.apk`，或按[从源码构建](#从源码构建)自行编译。
2. 安装 APK（需允许安装未知来源）。
3. 在 **LSPosed 管理器**中启用 LocRec，并在「作用域」中勾选目标应用。
4. 打开 LocRec，授予定位权限。

勾选「系统框架 / Android」可让改写覆盖所有应用（包括未单独勾选作用域的）。
该作用域下，**安装新版本模块后需要重启设备**才会加载新代码：系统级 hook 在 `system_server`
启动时安装。切换地点、开关伪装不需要重启，3 秒内热生效。

## 使用

1. 到达需要复现的地点，**保持屏幕点亮**。息屏会使采集被系统冻结，整份数据作废；实录窗口内应用会自动保持亮屏。
2. 点击「开始实录」，等待结束。录制时长决定重复周期与采样密度：只关心坐标 30~60 秒即可，
   需要更长重复周期或更大覆盖范围时选择更长时长。
3. 录制完成后可写备注或重命名，并在列表中选中该地点。
4. 打开「启用伪装」，3 秒内热生效；关闭开关即停止，系统层立即恢复真实数据。

## 注入模式

| | LSPosed 模式 | LSPatch 模式 |
|---|---|---|
| 前提 | Root + LSPosed | 无需 Root，用 [LSPatch](https://github.com/LSPosed/LSPatch) 对目标 APK 打补丁 |
| 生效范围 | LSPosed 作用域内的任意应用 | 仅被补丁的宿主应用 |
| 配置通道 | ContentProvider / Binder 服务 / XSharedPreferences | 宿主进程内 SharedPreferences（同进程直读） |
| 实录 | 模块应用内录制 | 宿主内不支持录制：先在独立应用录制并导出，再导入宿主 |
| 系统级回放 | 支持（作用域含「系统框架」时） | 不支持（无 `system_server` 权限） |

LSPatch 模式流程：在独立应用录制并**导出**数据集 JSON → 用 LSPatch 对目标 APK 打补丁并嵌入模块 APK
→ 在补丁后的宿主内打开 LocRec 界面 → **导入** JSON、选择地点、开启伪装。

## 数据与隐私

- 实录数据只存在**设备本机**，模块不联网、不上报、不含遥测。
- 数据集包含真实坐标、WiFi 指纹与基站信息，属于**敏感个人数据**。请勿提交到公开仓库、
  请勿分享给他人，并在不再需要时及时删除。本仓库 `.gitignore` 已排除 `output/`、`dumps/`、
  `target/`、`decompiled/` 与签名文件。
- 本仓库不包含任何实录数据。

## 从源码构建

需要 **JDK 21** 与 **Android SDK**（`compileSdk 35`）。

```bash
git clone https://github.com/BH4GMI/lsposed-location-replay.git
cd lsposed-location-replay/lsposed
export JAVA_HOME=/path/to/jdk-21       # Windows: $env:JAVA_HOME = 'C:\path\to\jdk-21'
./gradlew assembleRelease              # 产物: app/build/outputs/apk/release/app-release.apk
```

- `local.properties`（Android SDK 路径）不入库，需本机自行生成。
- Xposed API 以 `compileOnly` 方式引用 `lsposed/app/libs/api-82.jar`，**不会**打包进 APK。
- 发布签名取自环境变量 `CE_KEYSTORE` / `CE_KEY_ALIAS` / `CE_KEY_PASS`，仓库内不含 keystore 与口令；
  三者缺任一时回落到 debug 签名，仅供本地自测。

构建与排错见 [docs/BUILDING.zh-CN.md](docs/BUILDING.zh-CN.md)。

## 项目结构

```
.
├── LICENSE                     # Apache-2.0
├── NOTICE                      # 第三方组件归属
├── README.md                   # 英文（默认）
├── README.zh-CN.md             # 本文件
├── DISCLAIMER.md               # 使用边界与免责（英文）
├── DISCLAIMER.zh-CN.md         #   中文
├── .editorconfig
├── docs/
│   ├── BUILDING.md             # 构建与排错
│   ├── BUILDING.zh-CN.md
│   ├── DATASET.md              # 数据集 JSON 格式
│   └── DATASET.zh-CN.md
└── lsposed/                    # 模块源码 (Gradle)
```

## 许可

[Apache License 2.0](./LICENSE)。第三方组件与归属见 [NOTICE](./NOTICE)。

---

> 使用本项目即表示你已阅读并同意 [DISCLAIMER.zh-CN.md](./DISCLAIMER.zh-CN.md)。
> 本项目仅供**自有设备**上的合法测试与研究使用。
