**English** | [简体中文](DATASET.zh-CN.md)

# Dataset Format (schema v2)

One location = one JSON file, at:

```
getExternalFilesDir(null)/datasets/<id>.json
```

`id` looks like `d1735689600000` (the `recordedAt` milliseconds at the end of the recording).

## Top-level fields

| Field | Type | Meaning |
|---|---|---|
| `v` | int | schema version, currently `2` |
| `durationMs` | long | recording duration |
| `recordedAt` | long | end timestamp (epoch ms) |
| `fixes` | array | fix track |
| `scans` | array | WiFi scan frames |
| `nmea` | string[] | NMEA sentences |
| `cells` | array | **optional** cell snapshot frames (v1.9+) |
| `gnss` | array | **optional** satellite status frames (v1.9+) |
| `diag` | string[] | collection diagnostic log |

Older files without `cells` / `gnss` parse into empty lists, and the corresponding hooks pass through the real values.

## fixes[]

| Key | Meaning |
|---|---|
| `off` | ms relative to the recording start |
| `p` | provider (`gps` / `network`…) |
| `la` `lo` | latitude, longitude |
| `al` `ac` `sp` `br` | altitude / accuracy / speed / bearing |
| `st` | number of satellites in use |

## scans[]

| Key | Meaning |
|---|---|
| `off` | relative offset in ms |
| `aps[]` | `b` BSSID, `s` SSID, `c` capabilities, `r` RSSI, `f` freq, `ts` ScanResult.timestamp |

## cells[]

| Key | Meaning |
|---|---|
| `off` | relative offset in ms |
| `list[]` | each entry is the JSON of one `CellInfo` frame (type `t` + identity/signal fields) |

## gnss[]

| Key | Meaning |
|---|---|
| `off` | relative offset in ms |
| `sats[]` | `sv` SVID, `cn` constellation, `c0` CN0, `az` `el` azimuth/elevation, `u` used, `al` `ep` ephemeris/almanac, `hc` `cf` carrier frequency |

## Index index.json

An array, each entry:

| Key | Meaning |
|---|---|
| `id` | location id |
| `name` | display name |
| `at` | recording time |
| `la` `lo` | center coordinates (`null` when there is no fix) |
| `fx` `sc` `nm` | fix / scan / NMEA counts |
| `note` | **optional** user note |

## active.json

A full JSON mirror of the currently selected location, so it can be checked directly over adb; identical to the content of the selected file.

## How the selection reaches the target process

1. The UI calls `DatasetStore.select` → writes `active.json` + prefs (`active_id` / `active_payload`).
2. In the target process, `Config.load` prefers `content://com.locrec.app.config` (`meta` + `chunk`).
3. Once it reads `enabled=true` with a non-empty payload, it parses lazily and caches the result.

`ensureDs` re-checks `activeId` every 3 seconds: as soon as it changes, it re-reads the dataset and re-anchors `replayStart` to the current moment.
Switching locations therefore **takes effect live (≤3 seconds); it requires neither force-stopping the target app nor rebooting the device**.
