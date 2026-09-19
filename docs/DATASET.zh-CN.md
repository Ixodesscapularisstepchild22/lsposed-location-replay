[English](DATASET.md) | **简体中文**

# 数据集格式 (schema v2)

单个地点 = 一个 JSON 文件，路径：

```
getExternalFilesDir(null)/datasets/<id>.json
```

`id` 形如 `d1735689600000`（录制结束的 `recordedAt` 毫秒）。

## 顶层字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | schema 版本，当前为 `2` |
| `durationMs` | long | 录制时长 |
| `recordedAt` | long | 结束时刻（epoch ms） |
| `fixes` | array | 定位点轨迹 |
| `scans` | array | WiFi 扫描帧 |
| `nmea` | string[] | NMEA 语句 |
| `cells` | array | **可选** 基站快照帧（v1.9+） |
| `gnss` | array | **可选** 卫星状态帧（v1.9+） |
| `diag` | string[] | 采集诊断日志 |

旧文件没有 `cells` / `gnss` 时解析为空列表，对应 hook 直通真值。

## fixes[]

| 键 | 含义 |
|---|---|
| `off` | 相对录制起点 ms |
| `p` | provider（`gps` / `network`…） |
| `la` `lo` | 纬度经度 |
| `al` `ac` `sp` `br` | 海拔 / 精度 / 速度 / 航向 |
| `st` | 使用中的卫星数 |

## scans[]

| 键 | 含义 |
|---|---|
| `off` | 相对偏移 ms |
| `aps[]` | `b` BSSID, `s` SSID, `c` capabilities, `r` RSSI, `f` freq, `ts` ScanResult.timestamp |

## cells[]

| 键 | 含义 |
|---|---|
| `off` | 相对偏移 ms |
| `list[]` | 每项为一帧 `CellInfo` 的 JSON（类型 `t` + identity/signal 字段） |

## gnss[]

| 键 | 含义 |
|---|---|
| `off` | 相对偏移 ms |
| `sats[]` | `sv` SVID, `cn` 星座, `c0` CN0, `az` `el` 方位仰角, `u` used, `al` `ep` 星历/历书, `hc` `cf` 载波频率 |

## 索引 index.json

数组，每项：

| 键 | 含义 |
|---|---|
| `id` | 地点 id |
| `name` | 显示名 |
| `at` | 录制时间 |
| `la` `lo` | 中心坐标（无 fix 为 `null`） |
| `fx` `sc` `nm` | fix / 扫描 / NMEA 数量 |
| `note` | **可选** 用户备注 |

## active.json

当前选中地点的全量 JSON 镜像，便于 adb 直读核对，与选中文件内容一致。

## 选中如何到达目标进程

1. UI 调用 `DatasetStore.select` → 写 `active.json` + prefs（`active_id` / `active_payload`）。
2. 目标进程 `Config.load` 优先 `content://com.locrec.app.config`（`meta` + `chunk`）。
3. 读到 `enabled=true` 且 payload 非空后懒解析并缓存。

`ensureDs` 每 3 秒复核一次 `activeId`：一旦变化就重读数据集并把 `replayStart` 重锚到当前时刻。
因此**切换地点是热生效的（≤3 秒），不需要强杀目标 App，也不需要重启设备**。
