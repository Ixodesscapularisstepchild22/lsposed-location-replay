# LocRec

**Android location recording / replay module** — record the real positioning environment at a place, then replay it, in its original rhythm, to the applications in scope.

**English** | [简体中文](README.zh-CN.md)

[![Requires](https://img.shields.io/badge/requires-LSPosed-critical.svg)](https://github.com/LSPosed/LSPosed)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B%20(API%2024)-green.svg)](#runtime-requirements)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](./LICENSE)

---

> [!WARNING]
> ## Prohibited use
>
> **It is strictly forbidden to use this project to forge check-ins, attendance, clock-ins, or any other form of presence verification in Chaoxing Learning (超星学习通, `com.chaoxing.mobile`).**
>
> Chaoxing Learning is the primary test target of this project, but testing is limited to the
> compatibility and behaviour analysis of location functionality. Using this project to forge
> check-ins, proxy sign-ins, or similar conduct violates the target service's terms and may be
> unlawful. Account bans, academic penalties, and legal liability arising from such use are
> borne solely by the user. Full terms: [DISCLAIMER.md](./DISCLAIMER.md).

> [!IMPORTANT]
> **Runtime** — LocRec is an LSPosed module. It relies on the Xposed API runtime and the scope
> injection that LSPosed provides. **Without LSPosed installed and enabled on the device, the
> module does nothing at all.** The build output is a module APK, **not** a standalone location app.

## What it is

LocRec takes a snapshot of the real positioning environment first, then replays that snapshot,
in its original rhythm, to the applications you selected in scope.

The key idea is that it **does not fabricate coordinates** — it records and plays back:
coordinates come from the place where you recorded, and Wi-Fi scans, cell info, NMEA and
satellite status are captured and replayed together, so the replayed signals stay mutually
consistent.

**What it is**

- A real-environment recorder and replayer for positioning signals
  (GPS / network fixes, Wi-Fi scans, CellInfo, NMEA, GnssStatus)
- An LSPosed module using process-level and system-level injection
- A tool for location-feature compatibility testing, pipeline analysis and privacy research

**What it is not**

- Not a mock location provider (it never calls `addTestProvider`)
- Does not invent satellite constellations or synthesize signals out of thin air
- Not a one-tap "change my location" app: you must physically visit the place and record there first
- No network access, no reporting, no telemetry

## Testing scope

The primary compatibility verification of this project was carried out on **Chaoxing Learning**
(超星学习通, `com.chaoxing.mobile`), covering two location paths:

- the Baidu location SDK (BDLocation) pipeline;
- the Android system location APIs (`LocationManager` / `WifiManager` / `TelephonyManager`).

Verification covered whether hooks are hit, whether replayed data is self-consistent, and whether
the target application behaves stably. This project does not provide, and does not support, any
capability to circumvent presence verification.

## Runtime requirements

| Item | Requirement |
|---|---|
| **LSPosed** | **Required.** The module is loaded by LSPosed and uses the Xposed API (`de.robv.android.xposed`) it provides, together with scope injection. LSPosed must be installed on the device and the module must be enabled |
| Android | **7.0 (API 24) or newer** |
| Permission | `ACCESS_FINE_LOCATION` (needed for recording) |
| Module scope | At least the target app; also "System Framework / Android" for system-wide coverage |

Make sure LSPosed itself works on your device before installing.

## Installation

1. Download `app-release-*.apk` from [Releases](https://github.com/BH4GMI/lsposed-location-replay/releases), or build it yourself — see [Building from source](#building-from-source).
2. Install the APK (allow installation from unknown sources).
3. In the **LSPosed manager**, enable LocRec and select the target apps under "Scope".
4. Open LocRec and grant the location permission.

Selecting "System Framework / Android" makes the rewrite cover every app, including ones not
individually scoped. With that scope, **installing a new version of the module requires a device
reboot** before the new code is loaded: system-level hooks are installed when `system_server`
starts. Switching places and toggling spoofing do not need a reboot — they take effect within
3 seconds.

## Usage

1. Go to the place you want to reproduce and **keep the screen on**. A screen-off event freezes
   capture and invalidates the whole recording; the app keeps the screen on automatically for the
   recording window.
2. Tap "Start recording" and wait for it to finish. Duration determines the repeat period and the
   sampling density: 30–60 seconds is enough for coordinates alone; choose longer for a longer
   repeat period or wider coverage.
3. When it finishes you can add a note or rename it, and select the place in the list.
4. Turn on "Enable spoofing" — it takes effect within 3 seconds. Turn the switch off to stop; the
   system layer goes straight back to real data.

## Injection modes

| | LSPosed mode | LSPatch mode |
|---|---|---|
| Requires | Root + LSPosed | No root — patch the target APK with [LSPatch](https://github.com/LSPosed/LSPatch) |
| Effective scope | Any app in the LSPosed scope list | Only the patched host app |
| Config delivery | ContentProvider / Binder service / XSharedPreferences | Host-process SharedPreferences (same-process direct read) |
| Recording | In the module app | Not available inside the host: record in the standalone app, export, then import |
| System-level replay | Yes (with "System Framework" in scope) | No (no `system_server` access) |

LSPatch workflow: record in the standalone app and **Export** the dataset JSON → patch the target
APK with LSPatch, embedding the module APK → open the LocRec UI inside the patched host → **Import**
the JSON, select the place, enable spoofing.

## Data and privacy

- All recordings stay **on the device**. The module makes no network requests and has no telemetry.
- A dataset contains real coordinates, Wi-Fi fingerprints and cell information — **sensitive
  personal data**. Do not commit it to a public repository, do not share it with others, and delete
  it promptly when it is no longer needed. This repository's `.gitignore` excludes `output/`,
  `dumps/`, `target/`, `decompiled/` and signing files.
- This repository contains no recorded data.

## Building from source

You need **JDK 21** and the **Android SDK** (`compileSdk 35`).

```bash
git clone https://github.com/BH4GMI/lsposed-location-replay.git
cd lsposed-location-replay/lsposed
export JAVA_HOME=/path/to/jdk-21       # Windows: $env:JAVA_HOME = 'C:\path\to\jdk-21'
./gradlew assembleRelease              # output: app/build/outputs/apk/release/app-release.apk
```

- `local.properties` (Android SDK path) is not tracked; create it locally.
- The Xposed API is referenced as `compileOnly` from `lsposed/app/libs/api-82.jar` and is
  **not** bundled into the APK.
- Release signing is read from the `CE_KEYSTORE` / `CE_KEY_ALIAS` / `CE_KEY_PASS` environment
  variables; the repository contains neither the keystore nor its password. If any of the three is
  missing, the build falls back to debug signing, which is for local testing only.

See [docs/BUILDING.md](docs/BUILDING.md) for troubleshooting.

## Project layout

```
.
├── LICENSE                     # Apache-2.0
├── NOTICE                      # third-party attributions
├── README.md                   # English (default)
├── README.zh-CN.md            # 简体中文
├── DISCLAIMER.md               # usage boundaries and disclaimer
├── DISCLAIMER.zh-CN.md         # 简体中文
├── .editorconfig
├── docs/
│   ├── BUILDING.md             # build and troubleshooting
│   ├── BUILDING.zh-CN.md
│   ├── DATASET.md              # dataset JSON format
│   └── DATASET.zh-CN.md
└── lsposed/                    # module source (Gradle)
```

## License

[Apache License 2.0](./LICENSE). Third-party components and attributions: [NOTICE](./NOTICE).

---

> By using this project you confirm that you have read and accepted [DISCLAIMER.md](./DISCLAIMER.md).
> It is intended for lawful testing and research on **your own device** only.
