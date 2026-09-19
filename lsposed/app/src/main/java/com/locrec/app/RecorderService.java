package com.locrec.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实录前台服务: 在目标地点采集全量定位数据并落库。
 * 采集源 (全部普通权限):
 *  - LocationManager GPS + NETWORK fix 流 (1s)
 *  - GnssStatus (卫星数, 存入 fix.sats)
 *  - NMEA 原始语句
 *  - WiFi 扫描 (收到 SCAN_RESULTS_AVAILABLE_ACTION 即落库; 32s 触发一次, 避开
 *    Android 10+ 前台 4 次/2 分钟的扫描节流; 扫描被拒/无结果写入 diag)
 *  - 基站 CellInfo (5s 一帧, 与目标地一致的邻区结构)
 *  - GnssStatus 卫星快照 (约 1.5s 一帧, 供回放层重建)
 */
public class RecorderService extends Service {

    public static final long RECORD_MS = 60_000; // 默认实录 1 分钟(WiFi 2 批/基站 12 条, 够坐标回放)
    /** 扫描触发周期: 前台配额 4 次/2 分钟, 32s 留余量 */
    private static final long SCAN_PERIOD_MS = 32_000;
    public static final String EXTRA_DURATION_MS = "durationMs";
    /** UI 进度/完成广播 */
    public static final String ACTION_PROGRESS = "com.locrec.app.REC_PROGRESS";
    public static final String ACTION_DONE = "com.locrec.app.REC_DONE";
    public static final String EXTRA_ELAPSED_MS = "elapsedMs";
    public static final String EXTRA_TOTAL_MS = "totalMs";
    public static final String EXTRA_FIXES = "fixes";
    public static final String EXTRA_GPS = "gps";
    public static final String EXTRA_SCANS = "scans";
    public static final String EXTRA_CELLS = "cells";
    public static final String EXTRA_GNSS = "gnss";
    public static final String EXTRA_NMEA = "nmea";
    public static final String EXTRA_SAVED = "saved";

    private LocationManager lm;
    private WifiManager wm;
    /** 逐句暂存: off = 录制偏移, 回放才能按原始节奏与 fix 轨迹对齐 (旧版只有裸字符串)。 */
    private final java.util.ArrayList<Dataset.NmeaLine> nmeaBuf = new java.util.ArrayList<>();
    private final AtomicInteger nmeaCount = new AtomicInteger();
    private final AtomicInteger sats = new AtomicInteger(0);
    private long t0;
    private final Dataset data = new Dataset(0, 0);
    private final AtomicInteger fixCount = new AtomicInteger();
    private final AtomicInteger scanCount = new AtomicInteger();
    private final AtomicInteger gpsFixCount = new AtomicInteger();
    private final AtomicInteger scanCalls = new AtomicInteger();
    private final AtomicInteger scanBroadcasts = new AtomicInteger();
    private final AtomicInteger staleScans = new AtomicInteger();
    private final AtomicInteger cellSnaps = new AtomicInteger();
    private final AtomicInteger gnssSnaps = new AtomicInteger();
    private long lastCellOff = Long.MIN_VALUE;
    private long lastGnssOff = Long.MIN_VALUE;
    private static final long CELL_PERIOD_MS = 5_000;
    private static final long GNSS_SNAP_PERIOD_MS = 1_500;
    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean finished = false;
    /** BUG-10/11: 本次服务实例是否已成功开录。未 started 时禁止重复投递、禁止落盘保存。 */
    private boolean started = false;
    /** BUG-12: 供 UI 进程(同进程)对账: 回前台时服务是否仍在录, 弥补错过的 DONE 广播。 */
    public static volatile boolean running = false;
    private boolean scanReceiverRegistered = false;
    private long durationMs = RECORD_MS;
    private long lastBeatOff = Long.MIN_VALUE;

    private final LocationListener locL = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            Bundle ex = l.getExtras();
            int s = ex != null ? ex.getInt("satellites", sats.get()) : sats.get();
            data.fixes.add(new Dataset.Fix(
                    SystemClock.elapsedRealtime() - t0,
                    l.getProvider(), l.getLatitude(), l.getLongitude(), l.getAltitude(),
                    l.getAccuracy(), l.getSpeed(), l.getBearing(), s));
            fixCount.incrementAndGet();
            if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) gpsFixCount.incrementAndGet();
        }
    };

    private final GnssStatus.Callback gnssCb = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus st) {
            int used = 0;
            for (int i = 0; i < st.getSatelliteCount(); i++) {
                if (st.usedInFix(i)) used++;
            }
            sats.set(used);
            long off = SystemClock.elapsedRealtime() - t0;
            if (off - lastGnssOff >= GNSS_SNAP_PERIOD_MS) {
                lastGnssOff = off;
                try {
                    data.gnss.add(new Dataset.GnssSnap(off, SignalCodec.gnssToJson(st)));
                    gnssSnaps.incrementAndGet();
                } catch (Throwable t) {
                    diag("gnss snap failed: " + t);
                }
            }
        }
    };

    private final android.location.OnNmeaMessageListener nmeaL =
            (msg, timestamp) -> {
                if (msg == null) return;
                synchronized (nmeaBuf) {
                    nmeaBuf.add(new Dataset.NmeaLine(
                            SystemClock.elapsedRealtime() - t0, msg.trim()));
                }
                nmeaCount.incrementAndGet();
            };

    /** 扫描结果只在广播里取: 轮询 getScanResults 拿不到新结果, 扫描失败也无从判断。 */
    private final BroadcastReceiver scanRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean ok = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            scanBroadcasts.incrementAndGet();
            collectScanResults("broadcast(updated=" + ok + ")");
        }
    };

    private void collectScanResults(String why) {
        try {
            List<ScanResult> rs = wm.getScanResults();
            int n = rs == null ? 0 : rs.size();
            if (n == 0) { diag(why + " results=0"); return; }
            java.util.ArrayList<Dataset.Ap> aps = new java.util.ArrayList<>();
            long newestTs = 0;
            for (ScanResult r : rs) {
                aps.add(new Dataset.Ap(r.BSSID, r.SSID, r.capabilities, r.level, r.frequency, r.timestamp));
                newestTs = Math.max(newestTs, r.timestamp);
            }
            data.scans.add(new Dataset.Scan(SystemClock.elapsedRealtime() - t0, aps));
            scanCount.incrementAndGet();
            // 平台在息屏/省电时可能挂起真实扫描, 只回放缓存: 用时间戳把这件事记录下来,
            // 否则"WiFi 指纹"会在换地点后仍是上一个地点的, 数据看着正常其实是假的。
            long ageMs = newestTs > 0 ? (SystemClock.elapsedRealtimeNanos() / 1_000_000L - newestTs / 1000L) : -1;
            if (ageMs > 60_000) staleScans.incrementAndGet();
            diag(why + " results=" + n + (ageMs < 0 ? " (无时间戳)" : " newestAge=" + ageMs + "ms"
                    + (ageMs > 60_000 ? " STALE(>60s): 扫描被挂起, 结果来自旧缓存" : "")));
        } catch (SecurityException e) {
            diag(why + " SecurityException: " + e.getMessage());
        }
    }

    private final Runnable wifiScan = new Runnable() {
        @Override
        public void run() {
            try {
                boolean started = wm.startScan();
                scanCalls.incrementAndGet();
                diag("startScan=" + started + (started ? "" : " (被拒/节流)"));
                // startScan 返回 false 时也可能有旧缓存, 取一次不算错
                if (!started) collectScanResults("after-refused");
            } catch (SecurityException e) {
                diag("startScan SecurityException: " + e.getMessage());
            } catch (RuntimeException e) {
                diag("startScan " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            // 到点即停: 只看这一条延时消息不行 —— 息屏时系统会冻结进程, 冻结期间到期的
            // 回调会被推迟/吞掉, 录制既不落盘也不停止。周期 tick 里按截止时间自愈。
            if (deadlineReached()) { stopRecording(); return; }
            if (!finished) handler.postDelayed(this, SCAN_PERIOD_MS);
        }
    };

    private boolean deadlineReached() {
        return SystemClock.elapsedRealtime() - t0 >= durationMs;
    }

    private final Runnable cellPoll = new Runnable() {
        @Override
        public void run() {
            collectCells();
            if (deadlineReached()) { stopRecording(); return; }
            if (!finished) handler.postDelayed(this, CELL_PERIOD_MS);
        }
    };

    private void collectCells() {
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) { diag("telephony null"); return; }
            List<android.telephony.CellInfo> list = tm.getAllCellInfo();
            if (list == null || list.isEmpty()) { diag("cells=0"); return; }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (android.telephony.CellInfo ci : list) {
                try { arr.put(SignalCodec.cellToJson(ci)); } catch (Throwable ignored) {}
            }
            long off = SystemClock.elapsedRealtime() - t0;
            data.cells.add(new Dataset.CellSnap(off, arr));
            cellSnaps.incrementAndGet();
            lastCellOff = off;
        } catch (SecurityException e) {
            diag("cells SecurityException: " + e.getMessage());
        } catch (Throwable t) {
            diag("cells collect failed: " + t);
        }
    }

    private void diag(String s) {
        data.diag.add("off=" + (SystemClock.elapsedRealtime() - t0) + " " + s);
        android.util.Log.i("fakeloc-rec", s);
    }

    /**
     * 录制结束整条一次: 平台 Geocoder 反向编码录制中心点, 产出伪装地点的地址叙事 (Dataset.Addr)。
     * 鲁棒性约束: Geocoder 无后端/无结果/抛错/超时都不阻塞落盘 —— addr 缺失是合法状态,
     * hook 侧对无 addr 数据集按"清空地址叙事"收敛 (绝不留与伪装坐标矛盾的真地址)。
     * Geocoder 是同步 binder+网络调用, 放后台线程 + join(4s) 上限, 超时则本条不含地址。
     */
    private void geocodeInto(final Dataset ds) {
        // 注意: finish() 返回的是新 Dataset, 这里的诊断必须写进 ds.diag 才会随数据集落盘
        try {
            final Dataset.Fix f = ds.medianFix();
            if (f == null) return;
            if (!android.location.Geocoder.isPresent()) {
                ds.diag.add("addr: Geocoder 无后端, 本条不含地址叙事");
                return;
            }
            final java.util.concurrent.atomic.AtomicReference<android.location.Address> out =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicLong costMs = new java.util.concurrent.atomic.AtomicLong(-1);
            Thread g = new Thread(() -> {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    java.util.List<android.location.Address> r =
                            new android.location.Geocoder(this, java.util.Locale.CHINA)
                                    .getFromLocation(f.lat, f.lon, 1);
                    if (r != null && !r.isEmpty()) out.set(r.get(0));
                } catch (Throwable t1) {
                    ds.diag.add("addr: geocode failed: " + t1);
                } finally {
                    costMs.set(SystemClock.elapsedRealtime() - t0);
                }
            });
            g.setDaemon(true);
            g.start();
            try {
                g.join(4000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (g.isAlive()) {
                ds.diag.add("addr: geocode 超时(>4s), 本条不含地址叙事");
                return;
            }
            android.location.Address a = out.get();
            if (a == null) {
                ds.diag.add("addr: geocode 无结果 (" + costMs.get() + "ms), 本条不含地址叙事");
                return;
            }
            ds.addr = new Dataset.Addr(
                    nz(a.getCountryName()), nz(a.getCountryCode()),
                    nz(a.getAdminArea()), nz(a.getLocality()), nz(a.getSubLocality()),
                    nz(a.getThoroughfare()), nz(a.getSubThoroughfare()),
                    nz(a.getFeatureName()),
                    nz(a.getAddressLine(0)));
            ds.diag.add("addr: " + ds.addr.line + " (" + costMs.get() + "ms)");
            android.util.Log.i("fakeloc-rec", "addr: " + ds.addr.line);
        } catch (Throwable t) {
            ds.diag.add("addr: geocode failed: " + t);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private final Runnable stopper = new Runnable() {
        @Override
        public void run() { stopRecording(); }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (finished) return;
            long elapsed = SystemClock.elapsedRealtime() - t0;
            Intent i = new Intent(ACTION_PROGRESS)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_ELAPSED_MS, elapsed)
                    .putExtra(EXTRA_TOTAL_MS, durationMs)
                    .putExtra(EXTRA_FIXES, fixCount.get())
                    .putExtra(EXTRA_GPS, gpsFixCount.get())
                    .putExtra(EXTRA_SCANS, scanCount.get())
                    .putExtra(EXTRA_CELLS, cellSnaps.get())
                    .putExtra(EXTRA_GNSS, gnssSnaps.get())
                    .putExtra(EXTRA_NMEA, nmeaCount.get());
            sendBroadcast(i);
            handler.postDelayed(this, 1000);
        }
    };

    /** 进程被冻结/重启时, 位置与 WiFi 采集都会停: 每 10s 调度一次本任务, 实际每 ≥30s
     *  才写一条 diag —— 事后从 diag 时间轴的断层即可看出冻结区间
     *  (BUG-19: 旧注释把 10s 调度误写成记录频率)。 */
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            long off = SystemClock.elapsedRealtime() - t0;
            if (off - lastBeatOff >= 30_000) {
                diag("heartbeat off=" + off + " fixes=" + fixCount.get() + " scans=" + scanCount.get()
                        + " nmea=" + data.nmea.size());
            }
            lastBeatOff = off;
            if (!finished) handler.postDelayed(this, 10_000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** 前台通知 (录制中状态), 每次 startForegroundService 投递都要重新进入前台态。 */
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "rec", getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "rec") : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.rec_notif_title))
                .setContentText(getString(R.string.rec_notif_text))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true).build();
    }

    /** @return false = 进入前台失败 (已发失败 DONE 并 stopSelf), 调用方必须终止启动流程。 */
    private boolean enterForeground() {
        // BUG-14: startForeground 在部分 ROM/后台限制下会抛 SecurityException 等 ——
        // 必须整体捕获: 发失败 DONE 让 UI 复位, 记录原因, 停止服务; 不捕获会让进程直接崩溃
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, buildNotification());
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.e("fakeloc-rec", "startForeground failed: " + t, t);
            sendBroadcast(new Intent(ACTION_DONE)
                    .setPackage(getPackageName())
                    .putExtra(EXTRA_SAVED, false)
                    .putExtra("error", String.valueOf(t)));
            stopSelf();
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // BUG-10: 录制进行中收到重复 start 直接忽略 —— 重置 t0 会拉长时长轴, 重复 post
        // 会让 wifiScan/cellPoll/progressTick/heartbeat 双份投递、双份数据
        if (started) {
            android.util.Log.w("fakeloc-rec", "duplicate start ignored (recording in progress)");
            // startForegroundService 契约: 每次投递都要跟进 startForeground, 否则 5s 后
            // 系统抛异常杀服务 —— 重复 start 也要重新进入前台态 (幂等)
            enterForeground();
            return START_NOT_STICKY;
        }
        if (!enterForeground()) return START_NOT_STICKY;
        started = true;
        running = true;

        t0 = SystemClock.elapsedRealtime();
        durationMs = intent != null ? intent.getLongExtra(EXTRA_DURATION_MS, RECORD_MS) : RECORD_MS;
        if (durationMs <= 0) durationMs = RECORD_MS;
        diagEnv();
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0.1f, locL, getMainLooper());
            diag("requestLocationUpdates(gps) ok");
        } catch (SecurityException e) {
            diag("gps needs FINE permission: " + e.getMessage());
        } catch (RuntimeException e) {
            diag("gps request failed: " + e);
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0f, locL, getMainLooper());
            diag("requestLocationUpdates(network) ok");
        } catch (SecurityException e) {
            diag("network provider unavailable: " + e.getMessage());
        } catch (RuntimeException e) {
            diag("network request failed: " + e);
        }
        try {
            lm.registerGnssStatusCallback(gnssCb);
            lm.addNmeaListener(nmeaL);
            diag("gnss/nmea listeners ok");
        } catch (SecurityException e) {
            diag("gnss/nmea needs FINE permission: " + e.getMessage());
        }
        IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(scanRx, f);
        scanReceiverRegistered = true;
        handler.post(wifiScan);
        handler.post(heartbeat);
        handler.post(cellPoll);
        handler.post(progressTick);
        handler.postDelayed(stopper, durationMs);
        return START_NOT_STICKY;
    }

    /** 把决定成败的环境事实写进数据集, 让空数据集自己解释原因。诊断代码不得反过来搞崩录制。 */
    private void diagEnv() {
        diag("record start durationMs=" + durationMs);
        diag("FINE=" + granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
                + " COARSE=" + granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                + " NEARBY_WIFI=" + granted("android.permission.NEARBY_WIFI_DEVICES"));
        try {
            diag("providers: gps=" + lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    + " network=" + lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (RuntimeException e) {
            diag("provider probe failed: " + e);
        }
        try {
            diag("wifi enabled=" + (wm != null && wm.isWifiEnabled()));
        } catch (RuntimeException e) {
            diag("wifi probe failed: " + e);
        }
    }

    private String granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
    }

    private void stopRecording() {
        // BUG-11: 从未成功开录(如 startForeground 被拒后的 onDestroy)不得落盘,
        // 否则会在库里留下 duration≈0 的空数据集条目
        if (!started || finished) return;
        finished = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        lm.removeUpdates(locL);
        lm.unregisterGnssStatusCallback(gnssCb);
        lm.removeNmeaListener(nmeaL);
        if (scanReceiverRegistered) {
            try { unregisterReceiver(scanRx); } catch (IllegalArgumentException ignored) { }
            scanReceiverRegistered = false;
        }

        synchronized (nmeaBuf) {
            for (Dataset.NmeaLine line : nmeaBuf) {
                if (!line.text.isEmpty()) data.nmea.add(line);
            }
        }
        diag("record stop fixes=" + fixCount.get() + "(gps=" + gpsFixCount.get() + ")"
                + " scanCalls=" + scanCalls.get() + " scanBroadcasts=" + scanBroadcasts.get()
                + " scans=" + scanCount.get() + "(stale=" + staleScans.get() + ")"
                + " nmea=" + data.nmea.size()
                + " cells=" + cellSnaps.get() + " gnss=" + gnssSnaps.get());
        // 英文标签保持机器可读; 冒号后的行动建议走资源, 按系统语言出
        if (cellSnaps.get() == 0) diag("NO CELL SNAPSHOTS: " + getString(R.string.diag_no_cell));
        if (gnssSnaps.get() == 0) diag("NO GNSS STATUS SNAPSHOTS: " + getString(R.string.diag_no_gnss));
        if (gpsFixCount.get() == 0) {
            diag("NO GPS FIX in whole window: " + getString(R.string.diag_no_gps_fix));
        }
        if (scanCount.get() == 0) {
            diag("NO WIFI SCAN DATA: " + getString(R.string.diag_no_scan));
        } else if (staleScans.get() == scanCount.get()) {
            diag("WIFI FINGERPRINT STALE: " + getString(R.string.diag_scan_stale,
                    staleScans.get(), scanCount.get()));
        }
        // 反射写 final 字段不如重建: durationMs/recordedAt 通过重新序列化补齐
        Dataset done = finish();
        geocodeInto(done);
        // 存进多地点库并自动设为当前伪装地点; prefs/索引/磁盘的写入细节都在 DatasetStore
        boolean saved = false;
        String savedName = null;
        try {
            File base = getExternalFilesDir(null);
            if (base == null) throw new IOException("getExternalFilesDir null");
            DatasetStore.Entry e = DatasetStore.add(base, getSharedPreferences(Config.PREFS, MODE_PRIVATE), done, null);
            saved = true;
            savedName = e.name;
            android.util.Log.i("fakeloc-rec", "dataset saved: " + e.id + " " + e.name);
        } catch (Exception e) {
            android.util.Log.e("fakeloc-rec", "dataset save failed", e);
        }
        Intent doneI = new Intent(ACTION_DONE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SAVED, saved)
                .putExtra(EXTRA_FIXES, fixCount.get())
                .putExtra(EXTRA_GPS, gpsFixCount.get())
                .putExtra(EXTRA_SCANS, scanCount.get())
                .putExtra(EXTRA_CELLS, cellSnaps.get())
                .putExtra(EXTRA_GNSS, gnssSnaps.get())
                .putExtra(EXTRA_NMEA, data.nmea.size())
                .putExtra(EXTRA_TOTAL_MS, durationMs);
        if (savedName != null) doneI.putExtra("name", savedName);
        sendBroadcast(doneI);
        stopSelf();
    }

    private Dataset finish() {
        long dur = SystemClock.elapsedRealtime() - t0;
        Dataset done = new Dataset(dur, System.currentTimeMillis());
        done.fixes.addAll(data.fixes);
        done.scans.addAll(data.scans);
        done.nmea.addAll(data.nmea);
        done.cells.addAll(data.cells);
        done.gnss.addAll(data.gnss);
        done.diag.addAll(data.diag);
        return done;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
