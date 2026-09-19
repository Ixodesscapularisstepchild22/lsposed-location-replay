package com.locrec.app;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 实录回放引擎: 在目标 App 进程内, 把实录数据集按原始节奏回放到所有定位入口。
 *
 * 覆盖路径:
 *  1. LocationManager.getLastKnownLocation                       -> 实录中位 fix
 *  2. requestLocationUpdates / requestSingleUpdate (listener)    -> 代理回调喂实录 fix 流
 *     getCurrentLocation (Consumer)                              -> 同上
 *  3. addNmeaListener                                            -> 按录制节奏重放, GGA/RMC 随轴改写
 *  4. WifiManager.getScanResults                                 -> 反射构造实录 AP 列表
 *     (时间戳锚回放轴并保留录制散布; 无实录扫描/重建失败回空列表装穷)
 *  5. GMS LocationResult.getLastLocation/getLocations            -> 实录 fix
 *  6. Location.setLatitude/setLongitude 兜底 (高德等 SDK 子类)    -> 重写为实录坐标
 *  7. 高德/腾讯 (GCJ-02) / 百度 (BD-09) 契约                     -> CoordTransform 换算后写入
 *
 * 时间轴: sampleAt 环形插值 (循环边界无瞬移); 锚点 elapsedRealtime 由 select() 落盘,
 * App 进程与 system_server 共用 (anchorElapsed), 无锚时回落本地懒锚。
 * GNSS 卫星状态按 v1.9 快照回放; 无天空数据时 silent 演室内 network (吞真实星空/NMEA)。
 */
public class MainHook implements IXposedHookLoadPackage {

    /**
     * 配置状态 (BUG-44): 数据集 + 地点 id + 回放锚点收拢进一个不可变对象, 经
     * AtomicReference 单引用发布。hook 线程只做一次 volatile 读 —— 快照路径上
     * **零锁零 IO**。此前的实现 (DS_LOCK 内同步 Config.load: provider 分块读 +
     * bindService 等待) 让进入 hook 的系统关键线程在锁上排队数秒, 实测造成
     * system_server "Blocked in handler on PowerManagerService 30s" Watchdog。
     * 所有配置 IO 一律收敛到 REFRESH_EXECUTOR 单线程串行执行。
     */
    private static final class ConfigState {
        final Dataset ds;
        final String placeId;
        /** 全局回放锚点 (elapsedRealtime ms, select() 写入); <=0 = 未设置。 */
        final long anchor;

        ConfigState(Dataset ds, String placeId, long anchor) {
            this.ds = ds; this.placeId = placeId; this.anchor = anchor;
        }
    }

    /** null 态 = 伪装未启用/配置未装载; 用 EMPTY 区分「确认关闭」与「还没读到」。 */
    private static final ConfigState CONFIG_EMPTY = new ConfigState(null, null, -1);
    private static final java.util.concurrent.atomic.AtomicReference<ConfigState> CONFIG =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** 本进程是否 system_server (装载完成前 hook 放行真值, 绝不阻塞)。 */
    private static volatile boolean isSystemProcess;
    /** 首次装载尝试的信号: 只在「尝试完成」时 countDown; App 侧最多等 2s, system 侧不等。 */
    private static final java.util.concurrent.CountDownLatch FIRST_LOAD =
            new java.util.concurrent.CountDownLatch(1);
    private static volatile boolean firstLoadTried;
    /** 刷新节流: CAS 抢占本次调度窗口; 首装失败 1s 重试, 已装载 3s 复核。 */
    private static final AtomicLong lastRefreshAt = new AtomicLong(Long.MIN_VALUE / 2);
    private static final AtomicLong replayStart = new AtomicLong(-1);
    private static final AtomicLong fixSeq = new AtomicLong(0);
    /** 防止 rewriteSpatial/回放写入 调 setLatitude 触发 setter hook 再进自身 (栈溢出杀进程) */
    private static final ThreadLocal<Boolean> MUTATING = ThreadLocal.withInitial(() -> false);

    static Boolean enterMutating() {
        Boolean prev = MUTATING.get();
        MUTATING.set(true);
        return prev;
    }

    static void exitMutating(Boolean prev) {
        MUTATING.set(prev);
    }

    static boolean isMutating() {
        return Boolean.TRUE.equals(MUTATING.get());
    }

    /**
     * 本模块自身 App 的 uid。系统层 hook 装在 system_server 里, 对**所有**调用方生效,
     * 而自包排除只管应用层 —— 结果: 伪装开着时录制, 录制器读到的 WiFi 是 buildScanResults()
     * 重建的伪装批次、坐标是 rewriteSpatial 改过的, 录出来的"新地点"其实是当前伪装地点。
     * 这里用 calling uid 把自家放行。
     *
     * uid 解析来源优先级 (BUG-06):
     *  1. provider meta 自报 (Config.load 时 noteSelfUid 学习, 对 system_server 也可用)
     *  2. PackageManager: 应用进程 currentApplication / system_server 的 systemContext
     * 解析失败选择「按非自身处理」: 宁可漏改写(录制保护优先), 也不误放行陌生调用方;
     * 30s 限频重试, 日志区分「uid 未知」与「确认非自身」。
     */
    private static volatile int selfUid = -1;
    private static volatile long lastUidAttempt = Long.MIN_VALUE / 2;

    /** system_server 里多半没有 Application 上下文, 因此以 provider 自报的 uid 为准。 */
    private static void noteSelfUid(Config cfg) {
        if (cfg.selfUid > 0 && selfUid != cfg.selfUid) {
            selfUid = cfg.selfUid;
            XposedBridge.log("[fakeloc] self uid learned from provider = " + cfg.selfUid);
        }
    }

    /** system_server 侧的 Context: ActivityThread.currentActivityThread().getSystemContext()。 */
    private static android.content.Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Object ctx = at.getMethod("getSystemContext").invoke(thread);
            return ctx instanceof android.content.Context ? (android.content.Context) ctx : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用任意可用 Context 的 PackageManager 解析模块 uid; 都不可用返回 -1。 */
    private static int resolveSelfUid() {
        for (android.content.Context ctx : new android.content.Context[]{
                Config.appContext(), systemContext()}) {
            if (ctx == null) continue;
            try {
                int uid = ctx.getPackageManager().getPackageUid(Config.PKG, 0);
                if (uid > 0) return uid;
            } catch (Throwable t) {
                XposedBridge.log("[fakeloc] self uid resolve via " + ctx.getClass().getName()
                        + " failed: " + t);
            }
        }
        return -1;
    }

    private static boolean isSelfCaller() {
        int uid = selfUid;
        if (uid <= 0) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastUidAttempt < 30_000) {
                logOnce("selfUidUnknown", "self uid unknown yet, treat caller as non-self");
                return false;
            }
            lastUidAttempt = now;
            uid = resolveSelfUid();
            if (uid <= 0) {
                logOnce("selfUidFail", "self uid resolve failed (no context/pm), treat as non-self");
                return false;
            }
            selfUid = uid;
            XposedBridge.log("[fakeloc] self uid resolved = " + uid);
        }
        boolean self = android.os.Binder.getCallingUid() == uid;
        if (self) logOnce("selfCaller", "self caller uid=" + uid + ", 直通真实数据");
        return self;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(Config.PKG)) return;

        isSystemProcess = lpparam.packageName.equals("android");
        // 时序: handleLoadPackage 早于 Application 上下文就绪, 取数据集要走 ContentProvider,
        // 此刻必然拿不到。这里只调度后台首装 (BUG-44: 不在任何调用线程上做 IO),
        // hook 照常装, 数据在 REFRESH_EXECUTOR 上装载, 就绪后回调自然开始命中。
        scheduleRefresh(true);
        ConfigState st = CONFIG.get();
        log("loaded pkg=" + lpparam.packageName + " datasetReady=" + (st != null && st.ds != null)
                + (st != null && st.ds != null ? " place=" + st.placeId + " " + st.ds.summary()
                : " (lazy: 首次调用时再取)"));

        if (lpparam.packageName.equals("android")) {
            // system_server: 系统级回放, 覆盖所有 App/新装/分身用户
            installSystemHooks(lpparam);
            return;
        }
        // Android 11+ WiFi 逻辑在独立 wifi 进程 (包 com.android.wifi), 不在 system_server;
        // 基站 binder 入口 (PhoneInterfaceManager) 在 com.android.phone。这两个进程需要
        // 在 LSPosed 作用域里勾选本模块 (作用域选择器开启「显示系统应用」后可见),
        // 未勾选时这段代码不会被执行, 无副作用。
        if (lpparam.packageName.equals("com.android.wifi")) {
            installWifiProcessHooks(lpparam);
            return;
        }
        if (lpparam.packageName.equals("com.android.phone")) {
            installPhoneProcessHooks(lpparam);
            return;
        }

        hookLocationManager();
        hookNmea();
        hookWifiScan();
        hookWifiInfo();
        hookGmsLocationResult(lpparam);
        hookLocationParcel();
        hookCoordinateSetters();
        hookVendorSdk(lpparam);
        hookBaiduSdk(lpparam);
        hookCellAndGnss();
        hookCellLocation();
        hookMockFlags();
        hookGnssSideChannels();
        hookSensorAbsence();
        hookNetworkCaps();
        log("app hooks installed in " + lpparam.packageName
                + " (LM/NMEA/WifiScan/WifiInfo/GMS/Parcel/Setters/Vendor/Baidu/CellGnss/CellLoc/Mock/GnssSide/Sensor/NetCaps)");
    }

    /** 周期复核线程: 单守护线程串行执行, 不阻塞宿主退出; 刷新幂等, 无需取消。 */
    private static final java.util.concurrent.ExecutorService REFRESH_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fakeloc-config-refresh");
                t.setDaemon(true);
                return t;
            });

    /**
     * 无天空数据时演「室内 network 定位」: 不伪造卫星/NMEA, GPS 路径放空。
     * 与实录环境一致, 避免「坐标是假的、星图也是假的」双重穿帮。
     */
    static boolean silentGnss(Dataset d) {
        if (d == null) return true;
        boolean noGnss = d.gnss == null || d.gnss.isEmpty();
        boolean noNmea = d.nmea == null || d.nmea.isEmpty();
        return noGnss && noNmea;
    }

    /**
     * 回调入口统一入口: 一次 volatile 读 + 节流调度后台复核, 之后只允许使用返回的局部引用。
     * 返回 null = 伪装未启用/正被关闭/配置尚未装载 —— 调用方放行真实数据。
     * BUG-44: 此路径零锁零 IO; system_server 里装载完成前也直接放行 (绝不阻塞系统线程)。
     */
    static Dataset snapshot() {
        ConfigState s = CONFIG.get();
        if (s != null) {
            scheduleRefresh(false);
            return s.ds;
        }
        scheduleRefresh(true);
        if (!isSystemProcess && !firstLoadTried) {
            try {
                FIRST_LOAD.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            s = CONFIG.get();
            return s == null ? null : s.ds;
        }
        return null;
    }

    /**
     * 装载/复核触发器 (原 ensureDs 的无锁版): 首次装载也只在 REFRESH_EXECUTOR 上执行。
     * 返回 true = 当前有活跃数据集。普通 App 在首装窗口内允许等 2s (无锁等待);
     * system_server 永不等待 —— 最早一两个回调直通真值, 换取系统线程零阻塞。
     */
    private static boolean ensureDs() {
        ConfigState s = CONFIG.get();
        if (s != null && s.ds != null) {
            scheduleRefresh(false);
            return true;
        }
        scheduleRefresh(true);
        if (!isSystemProcess && !firstLoadTried) {
            try {
                FIRST_LOAD.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            s = CONFIG.get();
        }
        return s != null && s.ds != null;
    }

    /** 节流调度: 首装/失败重试 1s 窗口, 已装载 3s 复核窗口; CAS 抢占避免任务堆积。 */
    private static void scheduleRefresh(boolean force) {
        long now = SystemClock.elapsedRealtime();
        long window = force ? 1000 : 3000;
        long last = lastRefreshAt.get();
        if (now - last < window) return;
        if (!lastRefreshAt.compareAndSet(last, now)) return;
        REFRESH_EXECUTOR.execute(MainHook::refreshOnce);
    }

    /**
     * 唯一的配置 IO 执行点 (REFRESH_EXECUTOR 单线程串行, 免锁):
     * 读失败保旧快照 / 关开关丢弃 / 切地点热换 / 锚点前进 —— 与旧 refreshLocked 语义一致。
     */
    private static void refreshOnce() {
        try {
            Config cfg = Config.load(new XSharedPreferences(Config.PKG, Config.PREFS));
            noteSelfUid(cfg);
            // 非权威通道 (XSharedPreferences) 读到空 prefs (enabled=false, payload=null) 是
            // 「读失败」不是「用户关了」—— 保旧; 权威通道 (provider/service/宿主内直读) 读到
            // 同样内容则是真实状态 (如删除最后一个地点), 必须丢弃, 否则伪装永久卡在开启 (P0)
            if (cfg.datasetJson == null && !cfg.enabled) {
                if (cfg.authoritative) {
                    if (CONFIG.get() != CONFIG_EMPTY) {
                        log("authoritative empty (all places removed?), drop dataset");
                    }
                    CONFIG.set(CONFIG_EMPTY);
                } else {
                    log("config read empty, keep cached state");
                }
                return;
            }
            if (!cfg.enabled || cfg.datasetJson == null) {
                if (CONFIG.get() != CONFIG_EMPTY) log("disabled by switch, drop dataset");
                CONFIG.set(CONFIG_EMPTY);
                return;
            }
            ConfigState cur = CONFIG.get();
            if (cur == null || cur == CONFIG_EMPTY) {
                Dataset parsed = Dataset.parse(cfg.datasetJson);
                if (parsed.fixes.isEmpty()) {
                    log("dataset has no fixes, ignore");
                    return;
                }
                CONFIG.set(new ConfigState(parsed, cfg.activeId, cfg.anchorElapsed));
                log("config ok place=" + cfg.activeId + " " + parsed.summary());
                XposedBridge.log("[fakeloc] config ok place=" + cfg.activeId);
                return;
            }
            boolean placeChanged = cfg.activeId != null && !cfg.activeId.equals(cur.placeId);
            boolean anchorChanged = cfg.anchorElapsed != cur.anchor;
            if (placeChanged) {
                Dataset parsed = Dataset.parse(cfg.datasetJson);
                if (parsed.fixes.isEmpty()) {
                    log("place switch target has no fixes, keep old");
                    return;
                }
                CONFIG.set(new ConfigState(parsed, cfg.activeId, cfg.anchorElapsed));
                replayStart.set(-1); // 换地点 = 新时间轴
                log("place hot-switched to " + cfg.activeId + " " + parsed.summary());
                XposedBridge.log("[fakeloc] place hot-switched " + cfg.activeId);
            } else if (anchorChanged) {
                // 同地点重选/重开伪装: 数据不变, 锚点前进
                CONFIG.set(new ConfigState(cur.ds, cur.placeId, cfg.anchorElapsed));
            }
        } catch (Throwable t) {
            // 复核失败保留已加载数据, 但必须留痕, 不静默
            logOnce("refreshFail", "periodic refresh failed, keep cached state: " + t);
        } finally {
            if (!firstLoadTried) {
                firstLoadTried = true;
                FIRST_LOAD.countDown();
            }
        }
    }

    /** 同时写 LSPosed 私有日志与 logcat: 便于直接用 logcat 验证注入是否发生。 */
    private static void log(String msg) {
        android.util.Log.i("fakeloc-hook", msg);
    }

    private static final java.util.Set<String> LOGGED_ONCE = java.util.Collections.synchronizedSet(
            new java.util.HashSet<String>());

    /**
     * 每个 hook 点首次命中记一条: 用来判断目标 App 实际走的是哪条定位路径。
     * 上限 512 (BUG-20): 全是短字符串, 最坏 ~100KB 宿主堆常驻 —— 换来跨 ROM 的诊断覆盖, 预算可接受。
     */
    private static void logOnce(String tag, String msg) {
        if (LOGGED_ONCE.size() > 512) return;
        if (LOGGED_ONCE.add(tag)) log("hit " + msg);
    }

    // ---- 系统级: system_server 内拦 Location/WiFi 的总源头 ----
    // 防御原则: 每个点独立 try-catch, 失败只丢该点并留痕, 绝不让 system_server 抛异常 (防软砖)

    private static void installSystemHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        Dataset snap = snapshot();
        XposedBridge.log("[fakeloc] SYSTEM scope active, dataset="
                + (snap == null ? "lazy(首次调用时取)" : snap.summary()));

        // 0. 包可见性授予 (根因修复): Android 11+ 作用域 App 看不见未声明 <queries> 的模块包,
        //    provider 解析报 "Unknown authority"、显式 bindService 也被拦 (A16 实测均复现),
        //    LSPosed 的自动可见性授权在部分 ROM/LSPosed 组合上不生效。filterAppAccess 的
        //    语义是 "true = 对调用方隐藏" —— 这里只对本模块恒 false (对一切调用方可见),
        //    其它包的判定原样透传。热路径, 只做一次包名比对。
        try {
            int vHooked = 0;
            String[] visClasses = {"com.android.server.pm.ComputerEngine",
                    "com.android.server.pm.PackageManagerService"};
            for (String cn : visClasses) {
                Class<?> vc;
                try {
                    vc = Class.forName(cn, false, lpparam.classLoader);
                } catch (Throwable t) {
                    continue;
                }
                for (Method m : vc.getDeclaredMethods()) {
                    if (!"filterAppAccess".equals(m.getName())) continue;
                    boolean hasInt = false;
                    for (Class<?> pt : m.getParameterTypes()) if (pt == int.class) hasInt = true;
                    if (!hasInt) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object a0 = param.args[0];
                                if (a0 == null) return;
                                Object name = (a0 instanceof String)
                                        ? a0 : a0.getClass().getMethod("getPackageName").invoke(a0);
                                if (Config.PKG.equals(name)) param.setResult(false);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    vHooked++;
                }
                if (vHooked > 0) {
                    XposedBridge.log("[fakeloc] system: visibility filter hooks=" + vHooked + " on " + cn);
                    break;
                }
            }
            if (vHooked == 0) {
                XposedBridge.log("[fakeloc] system: WARN filterAppAccess not found, provider 可能仍不可见");
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] system visibility hook failed: " + t);
        }

        // 1. 所有 provider 的位置上报总入口: 改写 Location 空间字段, 时间/卫星/extras 留真。
        //    类路径两代: Android 13- 在 com.android.server.location, Android 14+ (AOSP main)
        //    移到 com.android.server.location.provider, 入口也从单 Location 变批量
        //    onReportLocation(LocationResult) —— 双路径都装, 命中哪个算哪个 (Android 16
        //    HyperOS 实测旧路径 CNFE, 见 notes 2026-09-16)。
        int reportHooked = hookLocationReport(lpparam, new String[]{
                "com.android.server.location.provider.LocationProviderManager",
                "com.android.server.location.LocationProviderManager"});
        if (reportHooked > 0) {
            XposedBridge.log("[fakeloc] system: " + reportHooked + " reportLocation path(s) hooked");
        } else {
            XposedBridge.log("[fakeloc] system: no reportLocation path found (LocationProviderManager)");
        }
        hookSystemLastKnown(lpparam);

        // 3 + 3b. WiFi 扫描/连接信息总源头: Android 11+ WifiServiceImpl 在独立 wifi 进程,
        //    system_server 里 CNFE 是预期 —— 两个进程都尝试, 互为旧 ROM 回退。
        installWifiHooks(lpparam.classLoader, "system_server");

        // 4. 系统侧基站 binder 入口: PhoneInterfaceManager 在 com.android.phone 进程
        //    (com.android.internal.telephony), system_server 里同样 CNFE。此处仅作旧 ROM
        //    回退; 主路径见 installPhoneProcessHooks。
        installPimCellHooks(lpparam.classLoader, "com.android.internal.telephony.PhoneInterfaceManager",
                "system_server");
    }

    /**
     * 位置上报总入口 hook: 对候选类逐个尝试, 方法名含 "reportlocation" 且参数为单个
     * Location (旧) 或单个 LocationResult (Android 14+) 都接住。LocationResult 分支
     * 取出其 Location 列表逐个就地改写 (Location 可变, 不重建容器)。
     */
    private static int hookLocationReport(XC_LoadPackage.LoadPackageParam lpparam, String[] candidates) {
        int hooked = 0;
        for (String cn : candidates) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, lpparam.classLoader);
            } catch (Throwable t) {
                continue; // 该版本无此类, 换下一个候选
            }
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1 || !m.getName().toLowerCase().contains("reportlocation")) continue;
                if (pts[0] == Location.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!ensureDs()) return; // 关闭开关 = 系统原样上报
                            if (param.args[0] instanceof Location) {
                                rewriteSpatial((Location) param.args[0]);
                            }
                        }
                    });
                    hooked++;
                } else if (pts[0].getName().endsWith("LocationResult")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!ensureDs()) return;
                            rewriteLocationResult(param.args[0]);
                        }
                    });
                    hooked++;
                }
            }
            if (hooked > 0) break; // 新路径命中即不再退回旧路径
        }
        return hooked;
    }

    /** LocationResult (framework 或 GMS 形态) 内的 Location 逐个就地改写; 反射失败整批放行。 */
    private static void rewriteLocationResult(Object result) {
        if (result == null) return;
        try {
            Object list;
            try {
                list = result.getClass().getMethod("getLocations").invoke(result);
            } catch (Throwable t1) {
                list = result.getClass().getMethod("asList").invoke(result);
            }
            if (!(list instanceof List)) return;
            for (Object o : (List<?>) list) {
                if (o instanceof Location) rewriteSpatial((Location) o);
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] system rewriteLocationResult failed: " + t);
        }
    }

    // 2. getLastKnownLocation 系统侧 (binder 入口)
    private static void hookSystemLastKnown(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> lms = Class.forName(
                    "com.android.server.location.LocationManagerService", false, lpparam.classLoader);
            for (Method m : lms.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains("lastlocation")) continue;
                if (m.getReturnType() != Location.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ensureDs()) return;
                        if (isSelfCaller()) return; // 自家录制器要真实值
                        if (param.getResult() instanceof Location) {
                            rewriteSpatial((Location) param.getResult());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] system LocationManagerService lastLocation hook failed: " + t);
        }
    }

    /** wifi 进程入口 (LSPosed 作用域需勾选 com.android.wifi)。 */
    private static void installWifiProcessHooks(XC_LoadPackage.LoadPackageParam lpparam) {        XposedBridge.log("[fakeloc] WIFI process scope active");
        installWifiHooks(lpparam.classLoader, "wifi");
    }

    /** phone 进程入口 (LSPosed 作用域需勾选 com.android.phone)。 */
    private static void installPhoneProcessHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[fakeloc] PHONE process scope active");
        installPimCellHooks(lpparam.classLoader, "com.android.internal.telephony.PhoneInterfaceManager",
                "com.android.phone");
    }

    /** WifiServiceImpl.getScanResults/getConnectionInfo hook (进程无关, 装到调用方给的 loader)。 */
    private static void installWifiHooks(ClassLoader cl, String where) {
        Class<?> wsm;
        try {
            wsm = Class.forName("com.android.server.wifi.WifiServiceImpl", false, cl);
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] " + where + ": WifiServiceImpl not present (预期于 system_server@A11+)");
            return;
        }
        int hooked = 0;
        for (Method m : wsm.getDeclaredMethods()) {
            if (!"getScanResults".equals(m.getName())) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 未开启伪装时绝不改写 —— 系统层误伤会影响全局 WiFi/定位
                    if (!ensureDs()) return;
                    if (isSelfCaller()) return; // 自家录制器要真实扫描
                    try {
                        Dataset d = snapshot();
                        if (d == null) return;
                        // WiFi 关闭/无扫描时真实结果为空: 如实镜像"无扫描",
                        // 不在关着的 WiFi 上凭空造扫描批次 (BUG-40)
                        Object r = param.getResult();
                        if (!(r instanceof List) || ((List<?>) r).isEmpty()) return;
                        param.setResult(buildScanResults(d));
                    } catch (Throwable t) {
                        // 系统层重建失败同样不许放行: 空列表 = "无扫描结果" (BUG-32)
                        XposedBridge.log("[fakeloc] " + where + " scanresult rebuild failed: " + t);
                        param.setResult(new ArrayList<ScanResult>());
                    }
                }
            });
            hooked++;
        }
        XposedBridge.log("[fakeloc] " + where + ": WifiServiceImpl.getScanResults hooks=" + hooked);
        int ci = 0;
        for (Method m : wsm.getDeclaredMethods()) {
            if (!"getConnectionInfo".equals(m.getName())) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ensureDs()) return;
                    if (isSelfCaller()) return; // 自家录制器要真实连接信息
                    try {
                        Dataset d = snapshot();
                        if (d == null) return;
                        Object wi = param.getResult();
                        if (wi == null) return;
                        rewriteConnectedWifiInfo(wi, d);
                    } catch (Throwable t) {
                        XposedBridge.log("[fakeloc] " + where + " WifiInfo rewrite failed: " + t);
                    }
                }
            });
            ci++;
        }
        XposedBridge.log("[fakeloc] " + where + ": WifiServiceImpl.getConnectionInfo hooks=" + ci);
    }

    /** PhoneInterfaceManager 基站 binder 入口 hook (进程无关, 装到调用方给的 loader)。 */
    private static void installPimCellHooks(ClassLoader cl, String className, String where) {
        Class<?> pim;
        try {
            pim = Class.forName(className, false, cl);
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] " + where + ": PhoneInterfaceManager not present");
            return;
        }
        int n = 0;
        for (Method m : pim.getDeclaredMethods()) {
            String ln = m.getName().toLowerCase();
            // getAllCellInfo 的 binder 入口同样在 PIM —— 只盖 cellLocation 会把
            // 真实基站列表漏给未勾作用域的 App (BUG-40)
            if (!ln.contains("celllocation") && !ln.contains("getallcellinfo")) continue;
            if (m.getReturnType() == void.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Dataset d = snapshot();
                    if (d == null) return;
                    if (isSelfCaller()) return; // 自家录制器要真实基站
                    String mn = param.method.getName().toLowerCase();
                    if (mn.contains("getallcellinfo")) {
                        List<android.telephony.CellInfo> built = buildCellInfos(d);
                        logOnce("sysCellInfo", "PIM.getAllCellInfo n=" + built.size());
                        param.setResult(built);
                        return;
                    }
                    Object fake = buildCellLocationFromDs(d);
                    if (fake != null) {
                        logOnce("sysCellLoc", "PhoneInterfaceManager." + param.method.getName());
                        param.setResult(fake);
                    } else {
                        // 无实录基站: 装不可用 (客户端对 null 的处理与无权限时一致), 不放行真实基站 (BUG-32)
                        param.setResult(null);
                    }
                }
            });
            n++;
        }
        XposedBridge.log("[fakeloc] " + where + ": PhoneInterfaceManager cell hooks=" + n);
    }

    /**
     * 就地改写 Location 空间字段: 按回放时间轴取轨迹点 (GPS 优先),
     * 再叠加与 accuracy 同量级的微抖动 —— 严检会看连续采样是否“死点”。
     * provider/时间戳保留原值; 卫星数写进 extras 与 GPS fix 自洽。
     * LM/Parcel 契约即 WGS-84, 此路径不做坐标系转换。
     */
    private static void rewriteSpatial(Location l) {
        Dataset d = snapshot();
        if (d == null) return;
        Dataset.Fix f = d.sampleAt(replayElapsed(d));
        if (f == null) f = d.medianFix();
        if (f == null) return;
        rewriteSpatialAt(l, f.lat, f.lon, f, d);
        logOnce("rewriteTraj", "rewriteSpatial traj off=" + f.off + " gps=" + Dataset.isGps(f));
    }

    /**
     * 把中心坐标 (须已按目标 SDK 契约换算好) 叠加微抖动后写到已有 Location 上;
     * 元数据 (alt/acc/spd/brg/sats) 取自 meta。vendor/百度路径共用。
     * 数据集一律来自参数 d: 这里曾直接读静态 ds, ensureDs 之后被另一线程清空会导致 NPE (BUG-26)。
     */
    private static void rewriteSpatialAt(Location l, double centerLat, double centerLon,
                                         Dataset.Fix meta, Dataset d) {
        if (d == null) return;
        double sigmaM = Math.max(meta.acc, 3f) * 0.12; // ~12% acc 作 1σ
        long seed = SystemClock.elapsedRealtimeNanos();
        double jlat = jitterDeg(centerLat, sigmaM / 111320.0, seed);
        double jlon = jitterDeg(centerLon, sigmaM / (111320.0 * Math.cos(Math.toRadians(centerLat))), seed * 31 + 7);
        Dataset.Fix shaken = Dataset.shaken(meta, jlat, jlon);
        boolean silent = silentGnss(d);
        if (silent) {
            shaken = new Dataset.Fix(shaken.off, shaken.provider,
                    shaken.lat, shaken.lon, shaken.alt, shaken.acc, shaken.spd, shaken.brg, 0);
        }
        d.applySpatial(l, shaken);
        if (silent) {
            try {
                android.os.Bundle b = l.getExtras();
                if (b != null) {
                    b.remove("satellites");
                    l.setExtras(b);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 近高斯抖动: 种子取调用时的 elapsedRealtimeNanos —— 同一纳秒结果可复现, 但纳秒时钟
     * 不会重复, 实际效果是每个采样时刻抖动都不同 (BUG-22: 旧注释「同时刻稳定」与实现不符;
     * 若需要同刻稳定, 应由调用方传入固定 seed)。
     */
    private static double jitterDeg(double center, double sigmaDeg, long seed) {
        if (sigmaDeg <= 0) return center;
        long x = seed * 6364136223846793005L + 1442695040888963407L;
        double u1 = ((x >>> 11) & 0xFFFFFFFFL) / 4294967296.0;
        x = x * 6364136223846793005L + 1442695040888963407L;
        double u2 = ((x >>> 11) & 0xFFFFFFFFL) / 4294967296.0;
        if (u1 < 1e-12) u1 = 1e-12;
        double z = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        return center + z * sigmaDeg;
    }

    /** 回放偏移: durationMs/锚点与取点同源 (时钟回绕时 % 可能为负)。 */
    private static long replayElapsed(Dataset d) {
        long now = SystemClock.elapsedRealtime();
        long s = replayStart.get();
        ConfigState st = CONFIG.get();
        long a = st != null ? st.anchor : -1;
        if (a > 0 && a <= now) {
            s = a; // 全局锚: 切地点时随 select 落盘, 各进程共用同一基准
        } else if (s < 0) {
            replayStart.compareAndSet(-1, now);
            s = replayStart.get();
        }
        long off = (now - s) % d.durationMs;
        return off >= 0 ? off : off + d.durationMs;
    }

    private static Location nextFix(String provider, Dataset d) {
        if (d == null) return null;
        Dataset.Fix f = d.sampleAt(replayElapsed(d));
        if (f == null) return null;
        boolean silent = silentGnss(d);
        // silent: 不声称 GPS, 不带 satellites extras —— 故事统一为 network
        if (silent) {
            if (provider == null || provider.length() == 0
                    || provider.toLowerCase().contains("gps")) {
                provider = LocationManager.NETWORK_PROVIDER;
            }
            f = new Dataset.Fix(f.off, LocationManager.NETWORK_PROVIDER,
                    f.lat, f.lon, 0, f.acc, f.spd, f.brg, 0);
        }
        // 调用方指定了 provider 时保留之 (GMS fused / gps), 但空间字段来自轨迹
        Location l = d.toLocation(f, SystemClock.elapsedRealtimeNanos());
        if (provider != null && provider.length() > 0) {
            Boolean prev = enterMutating();
            try {
                // Location.setProvider 不在 setter hook 内, 直接改
                l.setProvider(provider);
            } finally {
                exitMutating(prev);
            }
        }
        if (silent) {
            try {
                android.os.Bundle b = l.getExtras();
                if (b != null) {
                    b.remove("satellites");
                    l.setExtras(b);
                }
            } catch (Throwable ignored) {
            }
        }
        // 微抖动: 与 rewriteSpatial 同源, 避免 LM 路径与 Parcel 路径互相打脸
        double sigmaM = Math.max(f.acc, 3f) * 0.08;
        long seed = SystemClock.elapsedRealtimeNanos() + 17;
        double jlat = jitterDeg(l.getLatitude(), sigmaM / 111320.0, seed);
        double jlon = jitterDeg(l.getLongitude(),
                sigmaM / (111320.0 * Math.cos(Math.toRadians(l.getLatitude()))), seed * 13 + 3);
        Boolean prev = enterMutating();
        try {
            l.setLatitude(jlat);
            l.setLongitude(jlon);
        } finally {
            exitMutating(prev);
        }
        return l;
    }

    /** 高德/腾讯/百度等厂商 SDK: 进程内直接读 Location 的入口。 */
    private static void hookVendorSdk(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] clients = {
                "com.amap.api.location.AMapLocationClient",
                "com.baidu.location.LocationClient",
                "com.tencent.map.geolocation.TencentLocationManager",
        };
        XC_MethodHook fake = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Dataset d = snapshot();
                if (d == null) return;
                Object ret = param.getResult();
                if (!(ret instanceof Location)) return;
                Dataset.Fix f = d.sampleAt(replayElapsed(d));
                if (f == null) f = d.medianFix();
                if (f == null) return;
                logOnce("vendorSdk", param.method.getDeclaringClass().getSimpleName()
                        + "." + param.method.getName());
                // 高德/腾讯契约是 GCJ-02; 就地改写保留原对象 —— 返回值常是 AMapLocation 等
                // Location 子类, 换成基类对象会在调用点 checkcast 处 ClassCastException
                double[] g = CoordTransform.wgs84ToGcj02(f.lon, f.lat);
                rewriteSpatialAt((Location) ret, g[1], g[0], f, d);
            }
        };
        for (String cn : clients) {
            Class<?> cls;
            try {
                cls = XposedHelpers.findClass(cn, lpparam.classLoader);
            } catch (Throwable t) {
                continue; // 进程里没有该 SDK
            }
            int hooked = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!Location.class.isAssignableFrom(m.getReturnType())) continue;
                String n = m.getName().toLowerCase();
                if (!(n.contains("last") || n.contains("location") || n.contains("locate")
                        || n.contains("get"))) continue;
                try {
                    XposedBridge.hookMethod(m, fake);
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            if (hooked > 0) {
                logOnce("vendorSdkCls", cn + " hookedMethods=" + hooked);
            }
        }
    }

    // ---- 1/2: LocationManager ----

    private static void hookLocationManager() {
        XposedHelpers.findAndHookMethod(LocationManager.class, "getLastKnownLocation",
                String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Dataset d = snapshot();
                        if (d == null) return; // 无数据: 保持真实行为
                        String prov = String.valueOf(param.args[0]);
                        logOnce("getLastKnownLocation", "getLastKnownLocation(" + prov + ")");
                        // silent: GPS 有名无实, 返回 null 而不是假坐标冒充 gps
                        if (silentGnss(d) && LocationManager.GPS_PROVIDER.equals(prov)) {
                            param.setResult(null);
                            return;
                        }
                        param.setResult(nextFix(prov, d));
                    }
                });

        // 注册与注销成对 hook (BUG-03): 注册换上登记过的代理, 注销把原对象换回同一代理,
        // 否则系统按对象身份匹配移除会失效 (App 持有的是原对象), 监听器在 remove 后仍收回调。
        for (String name : new String[]{"requestLocationUpdates", "requestSingleUpdate",
                "removeUpdates"}) {
            for (Method m : LocationManager.class.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pts = m.getParameterTypes();
                int li = -1;
                for (int i = 0; i < pts.length; i++) {
                    if (LocationListener.class.isAssignableFrom(pts[i])) { li = i; break; }
                }
                if (li < 0) continue;
                final int idx = li;
                final boolean removing = name.equals("removeUpdates");
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (removing) {
                            // 未登记的对象原样转发, 不干扰正常注销
                            param.args[idx] = lookupProxyListener(param.args[idx]);
                            return;
                        }
                        logOnce(name, name + "(" + pts[idx].getSimpleName() + ")");
                        param.args[idx] = proxyListener((LocationListener) param.args[idx]);
                    }
                });
            }
        }

        for (Method m : LocationManager.class.getDeclaredMethods()) {
            if (!m.getName().equals("getCurrentLocation")) continue;
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (pts[i] == Consumer.class) {
                    final int idx = i;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            logOnce("getCurrentLocation", "getCurrentLocation(String, CancellationSignal, Consumer, Looper)");
                            String req = null;
                            for (int j = 0; j < pts.length && j < param.args.length; j++) {
                                if (pts[j] == String.class && param.args[j] != null) {
                                    req = String.valueOf(param.args[j]);
                                    break;
                                }
                            }
                            param.args[idx] = proxyConsumer((Consumer<Location>) param.args[idx], req);
                        }
                    });
                    break;
                }
            }
        }
    }

    // BUG-03: 原对象 -> 代理 的登记表。不能用 WeakHashMap 当"自动无泄漏": 代理(value)
    // 经闭包强引用原监听器(key), 表项永远不会被回收。显式 HashMap + 同一锁内完成查找/创建/
    // 删除; 表项随 removeUpdates/removeNmeaListener 的 hook 显式移除, 与系统对监听器的
    // 持有同生命周期, 不会额外泄漏。
    private static final Object LISTENER_LOCK = new Object();
    private static final java.util.HashMap<LocationListener, LocationListener> LISTENER_PROXIES =
            new java.util.HashMap<>();
    private static final java.util.HashMap<android.location.OnNmeaMessageListener,
            android.location.OnNmeaMessageListener> NMEA_PROXIES = new java.util.HashMap<>();

    /** 同一原对象永远返回同一代理: 重复注册不会产生重复回调。 */
    private static LocationListener proxyListener(final LocationListener real) {
        if (real == null) return null;
        synchronized (LISTENER_LOCK) {
            LocationListener p = LISTENER_PROXIES.get(real);
            if (p != null) return p;
            p = (LocationListener) Proxy.newProxyInstance(
                    MainHook.class.getClassLoader(), new Class[]{LocationListener.class},
                    (pr, method, args) -> {
                        if (method.getName().equals("onLocationChanged")
                                && args != null && args.length == 1
                                && args[0] instanceof Location) {
                            Dataset d = snapshot();
                            if (d != null) {
                                String prov = ((Location) args[0]).getProvider();
                                boolean silent = silentGnss(d);
                                if (silent && prov != null
                                        && prov.toLowerCase().contains("gps")) {
                                    // 室内故事: gps 监听器静默, 不拿 network fix 冒充 gps (BUG-37)。
                                    // 真机室内行为就是"无回调", 与 getLastKnownLocation(gps)=null 同口径
                                    logOnce("silentGpsDrop", "gps listener callback swallowed (silent)");
                                    return null;
                                }
                                logOnce("listenerCb",
                                        "LocationListener.onLocationChanged(" + prov + ")");
                                return invokeReal(real, method, new Object[]{nextFix(prov, d)});
                            }
                            // 快照恰被清空: 原样转发真实定位
                        }
                        return invokeReal(real, method, args);
                    });
            LISTENER_PROXIES.put(real, p);
            return p;
        }
    }

    /** 注销链路: 已登记则取出并移除表项后换回代理, 未登记原样返回。 */
    private static Object lookupProxyListener(Object real) {
        if (real == null) return null;
        synchronized (LISTENER_LOCK) {
            Object p = LISTENER_PROXIES.remove(real);
            return p != null ? p : real;
        }
    }

    private static Consumer<Location> proxyConsumer(final Consumer<Location> real,
                                                    final String requested) {
        return loc -> {
            // null 是 getCurrentLocation 的正常超时交付, 任何路径都必须原样转发:
            // 吞掉它宿主的 consumer 永远等不到回调, 且该代理包装与伪装开关无关 (BUG-39)
            if (loc == null) {
                real.accept(null);
                return;
            }
            Dataset d = snapshot();
            if (d == null) {
                real.accept(loc); // 没数据就把真实结果原样交回
                return;
            }
            String prov = loc.getProvider() != null ? loc.getProvider() : requested;
            if (silentGnss(d) && prov != null && prov.toLowerCase().contains("gps")) {
                // 室内故事: gps 请求不交付非空结果; 超时 null 已在顶部放行 (BUG-37)
                logOnce("silentGpsDropConsumer", "getCurrentLocation(gps) swallowed (silent)");
                return;
            }
            logOnce("consumerCb", "getCurrentLocation consumer");
            Location fixed = nextFix(prov != null && prov.length() > 0 ? prov
                    : LocationManager.GPS_PROVIDER, d);
            if (fixed != null) real.accept(fixed);
            else real.accept(loc);
        };
    }

    private static Object invokeReal(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    // ---- 3: NMEA ----

    private static void hookNmea() {
        // 注册与注销成对 hook (BUG-03), 覆盖全部重载: Executor 版与旧的单参版
        for (String name : new String[]{"addNmeaListener", "removeNmeaListener"}) {
            for (Method m : LocationManager.class.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pts = m.getParameterTypes();
                int li = -1;
                for (int i = 0; i < pts.length; i++) {
                    if (android.location.OnNmeaMessageListener.class.isAssignableFrom(pts[i])) {
                        li = i;
                        break;
                    }
                }
                if (li < 0) continue;
                final int idx = li;
                final boolean removing = name.equals("removeNmeaListener");
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[idx] == null) return;
                        if (removing) {
                            param.args[idx] = lookupProxyNmea(param.args[idx]);
                            return;
                        }
                        java.util.concurrent.Executor ex = null;
                        for (int i = 0; i < pts.length && i < param.args.length; i++) {
                            if (java.util.concurrent.Executor.class.isAssignableFrom(pts[i])) {
                                ex = (java.util.concurrent.Executor) param.args[i];
                                break;
                            }
                        }
                        param.args[idx] = proxyNmea(
                                (android.location.OnNmeaMessageListener) param.args[idx], ex);
                    }
                });
            }
        }
    }

    private static android.location.OnNmeaMessageListener proxyNmea(
            final android.location.OnNmeaMessageListener real,
            final java.util.concurrent.Executor ex) {
        if (real == null) return null;
        synchronized (LISTENER_LOCK) {
            android.location.OnNmeaMessageListener p = NMEA_PROXIES.get(real);
            if (p != null) return p;
            p = (message, timestamp) -> {
                Dataset d = snapshot();
                if (d == null || silentGnss(d) || d.nmea.isEmpty()) return; // 无星: 吞掉真 NMEA
                long e = replayElapsed(d);
                String next = nextNmea(d, e);
                if (next == null || next.isEmpty()) return;
                // 时间/坐标随回放轴改写, 与 fix 轨迹同时空
                final String out = rewriteNmea(next, System.currentTimeMillis(), d.sampleAt(e));
                run(ex, () -> real.onNmeaMessage(out, System.currentTimeMillis()));
            };
            NMEA_PROXIES.put(real, p);
            return p;
        }
    }

    /** 注销链路: 已登记则取出并移除表项后换回代理, 未登记原样返回。 */
    private static Object lookupProxyNmea(Object real) {
        if (real == null) return null;
        synchronized (LISTENER_LOCK) {
            Object p = NMEA_PROXIES.remove(real);
            return p != null ? p : real;
        }
    }

    private static void run(java.util.concurrent.Executor ex, Runnable r) {
        if (ex != null) ex.execute(r);
        else r.run();
    }

    /** 回放轴上的当前 NMEA 句: 有逐句时间轴按 off 取; 旧数据集 (无时间戳) 等比例循环兜底。 */
    private static String nextNmea(Dataset d, long elapsedMs) {
        if (d.nmea.isEmpty()) return null;
        if (d.nmeaTimed()) {
            return d.nmea.get(d.nmeaIndexAtOrBefore(elapsedMs)).text;
        }
        long total = Math.max(d.durationMs, 1);
        int idx = (int) (Math.floorMod(elapsedMs, total) * d.nmea.size() / total);
        return d.nmea.get(idx % d.nmea.size()).text;
    }

    /**
     * NMEA 坐标格式: 纬度 ddmm.mmmm / 经度 dddmm.mmmm (分保留 4 位, 与主流接收机一致)。
     * 分四舍五入满 60 必须向度进位 (BUG-27): 25.9999993° 应为 "2600.0000", 进位前是非法的 "2560.0000"。
     */
    private static String nmeaCoord(double deg, boolean lat) {
        double abs = Math.abs(deg);
        int d = (int) abs;
        double min = Math.round((abs - d) * 60.0 * 1e4) / 1e4;
        if (min >= 60.0) { min -= 60.0; d += 1; }
        return String.format(java.util.Locale.US, lat ? "%02d%07.4f" : "%03d%07.4f", d, min);
    }

    /**
     * 跨月回放时把 GGA/RMC 的 UTC 时间改成「现在」, 坐标/速度/航向/海拔改成当前回放点
     * (与 fix 轨迹同源), 最后重算校验和。NMEA 字面时间若还是录制日的旧值、坐标若是旧地点,
     * 比星图几何更容易被解析器抓到。
     */
    static String rewriteNmea(String sentence, long wallClockMs, Dataset.Fix fix) {
        if (sentence == null || sentence.length() < 8 || sentence.charAt(0) != '$') return sentence;
        int star = sentence.lastIndexOf('*');
        String body = star > 1 ? sentence.substring(1, star) : sentence.substring(1);
        String[] f = body.split(",", -1);
        if (f.length < 2) return sentence;
        String type = f[0];
        boolean gga = type.endsWith("GGA");
        boolean rmc = type.endsWith("RMC");
        if (!gga && !rmc) return sentence;
        try {
            java.util.Calendar c = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(wallClockMs);
            f[1] = String.format(java.util.Locale.US, "%02d%02d%02d.%02d",
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND),
                    c.get(java.util.Calendar.MILLISECOND) / 10);
            if (rmc && f.length > 9) {
                f[9] = String.format(java.util.Locale.US, "%02d%02d%02d",
                        c.get(java.util.Calendar.DAY_OF_MONTH),
                        c.get(java.util.Calendar.MONTH) + 1,
                        c.get(java.util.Calendar.YEAR) % 100);
            }
            if (fix != null) {
                if (rmc) {
                    // $..RMC,time,status,lat,N/S,lon,E/W,spd(节),course,date*
                    if (f.length > 6) {
                        f[3] = nmeaCoord(fix.lat, true);
                        f[4] = fix.lat >= 0 ? "N" : "S";
                        f[5] = nmeaCoord(fix.lon, false);
                        f[6] = fix.lon >= 0 ? "E" : "W";
                    }
                    if (f.length > 7) {
                        f[7] = String.format(java.util.Locale.US, "%.1f",
                                Math.max(fix.spd, 0) * 1.943844f); // m/s → 节
                    }
                    if (f.length > 8) {
                        f[8] = String.format(java.util.Locale.US, "%.1f", (fix.brg + 360f) % 360f);
                    }
                } else {
                    // $..GGA,time,lat,N/S,lon,E/W,quality,numsat,hdop,alt,M,...
                    if (f.length > 5) {
                        f[2] = nmeaCoord(fix.lat, true);
                        f[3] = fix.lat >= 0 ? "N" : "S";
                        f[4] = nmeaCoord(fix.lon, false);
                        f[5] = fix.lon >= 0 ? "E" : "W";
                    }
                    if (f.length > 9 && Dataset.isGps(fix) && fix.alt != 0) {
                        f[9] = String.format(java.util.Locale.US, "%.1f", fix.alt);
                    }
                }
            }
            StringBuilder sb = new StringBuilder("$");
            for (int i = 0; i < f.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(f[i]);
            }
            int cs = 0;
            String s = sb.toString();
            for (int i = 1; i < s.length(); i++) cs ^= s.charAt(i);
            return s + "*" + String.format(java.util.Locale.US, "%02X", cs);
        } catch (Throwable t) {
            return sentence;
        }
    }

    // ---- 4: WiFi 扫描 ----

    private static void hookWifiScan() {
        Class<?> wm = null;
        try {
            wm = XposedHelpers.findClass("android.net.wifi.WifiManager",
                    MainHook.class.getClassLoader());
        } catch (Throwable t) {
            return;
        }
        XC_MethodHook replay = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Dataset d = snapshot();
                if (d == null) return;
                logOnce("getScanResults", "WifiManager.getScanResults");
                // WiFi 关着不可能有扫描结果, 伪装时同样装穷 (BUG-32)
                try {
                    android.net.wifi.WifiManager self =
                            (android.net.wifi.WifiManager) param.thisObject;
                    if (!self.isWifiEnabled()) {
                        param.setResult(new ArrayList<ScanResult>());
                        return;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    param.setResult(buildScanResults(d));
                } catch (Throwable t) {
                    // 重建失败也不放行真实扫描: 放行 = 泄露真实位置指纹, 宁可装"无扫描"
                    XposedBridge.log("[fakeloc] scanresult rebuild failed: " + t);
                    param.setResult(new ArrayList<ScanResult>());
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(wm, "getScanResults", replay);
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] getScanResults hook failed: " + t);
        }
    }

    private static List<ScanResult> buildScanResults(Dataset d) throws Exception {
        List<ScanResult> out = new ArrayList<>();
        Dataset.Scan scan = latestScan(d);
        if (scan == null) return out; // 无实录扫描: 回空列表, 不放行真实批次 (BUG-32)
        Constructor<ScanResult> c = ScanResult.class.getDeclaredConstructor();
        c.setAccessible(true);
        // 批次基准锚在回放轴上: now - (replayElapsed - scan.off)。两个时钟同速推进,
        // 同一批录制扫描在多次 getScanResults 之间时间戳恒定 —— 真实平台在两次真实扫描
        // 之间返回的就是同一份缓存; 切到下一条录制 scan 时批次整体跳变, 节奏与真实扫描一致 (BUG-31)。
        long scanAgeMs = replayElapsed(d) - scan.off;
        if (scanAgeMs < 0) scanAgeMs = 0; // 环形首扫之前 (e < scans[0].off) 视为刚扫完
        long batchBaseUs = SystemClock.elapsedRealtime() * 1000L - scanAgeMs * 1000L;
        long maxTs = 0;
        for (Dataset.Ap a : scan.aps) if (a.ts > maxTs) maxTs = a.ts;
        for (Dataset.Ap a : scan.aps) {
            ScanResult r = c.newInstance();
            r.BSSID = a.bssid;
            r.SSID = a.ssid;
            r.capabilities = a.caps;
            r.level = a.rssi;
            r.frequency = a.freq;
            r.timestamp = apTimestampUs(batchBaseUs, a.ts, maxTs, a.bssid);
            out.add(r);
        }
        return out;
    }

    /**
     * 单 AP 回放时间戳 (µs since boot; 纯函数, JVM 用例直测)。
     * batchBaseUs = 本批次基准 (最新 AP 的时刻)。有录制 ts 的批次保留录制期散布
     * (maxTs - apTs): 整批同值是自首级指纹, 真实扫描各 AP 时间戳本就相差数秒;
     * 旧数据集 (ts=0) 用 BSSID 哈希稳定散布 (≤3s, 同一 BSSID 跨次读取不变, 与缓存
     * 扫描行为一致)。结果不允许早于开机 (负值钳 0)。
     */
    static long apTimestampUs(long batchBaseUs, long apTs, long maxTsInBatch, String bssid) {
        long back;
        if (apTs > 0 && maxTsInBatch > 0) {
            back = maxTsInBatch - apTs;
            if (back < 0) back = 0;
        } else {
            back = (bssid == null ? 0 : bssid.hashCode() & 0x7FFFFFFFL) % 3_000_000L;
        }
        long t = batchBaseUs - back;
        return t >= 0 ? t : 0;
    }

    /** 回放轴上最近的一条实录扫描; 无实录扫描返回 null (调用方装"无扫描", 不放行真实值)。 */
    private static Dataset.Scan latestScan(Dataset d) {
        if (d.scans.isEmpty()) return null;
        long e = replayElapsed(d);
        Dataset.Scan best = d.scans.get(0);
        for (Dataset.Scan s : d.scans) {
            if (s.off <= e) best = s;
        }
        return best;
    }

    /** 扫描批次中信号最强的 AP; 无实录扫描/AP 返回 null。WifiInfo 与 NetworkCapabilities 共用单一事实源。 */
    static Dataset.Ap bestApOf(Dataset d) {
        Dataset.Scan scan = latestScan(d);
        if (scan == null || scan.aps.isEmpty()) return null;
        Dataset.Ap best = scan.aps.get(0);
        for (Dataset.Ap a : scan.aps) if (a.rssi > best.rssi) best = a;
        return best;
    }

    // ---- 4b: 基站 + GnssStatus (v1.9; 旧数据集无 cells 块时基站装不可用, 无天空时吞回调) ----

    private static void hookCellAndGnss() {
        // TelephonyManager.getAllCellInfo
        try {
            Class<?> tm = android.telephony.TelephonyManager.class;
            XposedHelpers.findAndHookMethod(tm, "getAllCellInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Dataset d = snapshot();
                    if (d == null) return;
                    // 无实录基站回空列表 (真机无服务时即如此), 不放行真实基站 (BUG-32)
                    List<android.telephony.CellInfo> built = buildCellInfos(d);
                    logOnce("cellInfo", "TelephonyManager.getAllCellInfo n=" + built.size());
                    param.setResult(built);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] getAllCellInfo hook failed: " + t);
        }

        // GnssStatus.Callback 是抽象类, 不能动态代理 —— hook 基类方法。
        // 注意边界 (BUG-02): 这只覆盖走到基类实现(或 super 链)的回调; 子类完全重写且不调
        // super 时 hook 不触发, 该残留只能在目标 ROM 上逐一验证, 不能声称"自动全覆盖"。
        // 有实录帧且能重建时替换参数; 无天空(silent)或重建失败时吞掉真回调 ——
        // 假坐标配真星空比没有星空更容易被识别。
        try {
            XposedBridge.hookMethod(
                    android.location.GnssStatus.Callback.class.getDeclaredMethod(
                            "onSatelliteStatusChanged", android.location.GnssStatus.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Dataset d = snapshot();
                            // silent / 无 gnss 块 / 快照被清: 吞掉真实星空(真点天空≠假点故事)
                            if (d == null || silentGnss(d) || d.gnss.isEmpty()) {
                                param.setResult(null);
                                return;
                            }
                            Dataset.GnssSnap snap = d.gnssAtTime(replayElapsed(d));
                            if (snap == null) {
                                param.setResult(null);
                                return;
                            }
                            android.location.GnssStatus fake = SignalCodec.gnssFromJson(snap.sats);
                            if (fake != null) {
                                logOnce("gnssReplay", "GnssStatus n=" + fake.getSatelliteCount());
                                param.args[0] = fake;
                            } else {
                                // 有实录但重建失败 (API 24-29 无 Builder / 未来签名再变):
                                // 必须吞真回调, 不能放行真星空
                                logOnce("gnssRebuildFail", "gnss rebuild failed, swallow real status");
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] GnssStatus.Callback hook failed: " + t);
        }
    }

    // ---- 5: GMS FusedLocation ----

    private static void hookGmsLocationResult(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass("com.google.android.gms.location.LocationResult",
                    lpparam.classLoader);
        } catch (Throwable t) {
            return; // 无 GMS location API
        }
        XC_MethodHook fake = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Dataset d = snapshot();
                if (d == null) return;
                Object ret = param.getResult();
                logOnce("gmsLocationResult", "GMS LocationResult." + param.method.getName());
                if (ret instanceof Location) {
                    Location fixed = nextFix(((Location) ret).getProvider(), d);
                    if (fixed != null) param.setResult(fixed);
                } else if (ret instanceof List && !((List<?>) ret).isEmpty()) {
                    Location fixed = nextFix("fused", d);
                    if (fixed != null) {
                        param.setResult(new ArrayList<>(java.util.Collections.singletonList(fixed)));
                    }
                }
            }
        };
        // 按方法名枚举而非 findAndHookMethod 定死签名: 宿主打包的 play-services 版本
        // 可能没有 getLastLocation()/getLocations() 的教科书签名 (Android 16 实测
        // NoSuchMethodError), 枚举天然兼容任意签名, 找不到只是少一条路径。
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            String mn = m.getName();
            if (!"getLastLocation".equals(mn) && !"getLocations".equals(mn)) continue;
            try {
                XposedBridge.hookMethod(m, fake);
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n == 0) {
            XposedBridge.log("[fakeloc] GMS LocationResult: no getLastLocation/getLocations method found");
        }
    }

    // ---- 6: Parcel 反序列化入口 (GMS/其它进程通过 Binder 送进来的 Location) ----

    /**
     * Chrome 这类 App 的定位走 GMS fused provider: Location 在 GMS 进程里产出,
     * 以 Parcel 反序列化进入目标进程, 全程不碰 LocationManager —— 上面所有 hook 都不会命中。
     * 构造器是唯一必经之路, 所以在这一层改写。
     * 只挂钩 Parcel 构造器: 程序内 new Location(provider) 造出来的对象不受影响,
     * 避免把我们自己回放的轨迹也压平成一个点。
     */
    private static void hookLocationParcel() {
        // 诊断: 把 Location 的真实构造器清单打出来, 便于跨 ROM/版本定位
        StringBuilder sb = new StringBuilder();
        for (Constructor<?> c : Location.class.getDeclaredConstructors()) sb.append(c).append(" | ");
        logOnce("parcelCtors", "Location ctors: " + sb);

        // 主钩点: Parcelable.Creator.createFromParcel —— 所有 Binder 反序列化必经此处
        int hooked = 0;
        try {
            Object creator = XposedHelpers.getStaticObjectField(Location.class, "CREATOR");
            Method from = creator.getClass().getDeclaredMethod(
                    "createFromParcel", android.os.Parcel.class);
            XposedBridge.hookMethod(from, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ensureDs()) return;
                    Object r = param.getResult();
                    if (r instanceof Location) {
                        logOnce("creatorFromParcel", "Location.CREATOR.createFromParcel");
                        rewriteSpatial((Location) r);
                    }
                }
            });
            hooked++;
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] CREATOR.createFromParcel hook failed: " + t);
        }

        // 备钩点: 带 Parcel 的构造器 (老版本 AOSP 直接走构造器)
        for (Constructor<?> c : Location.class.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length != 1 || pts[0] != android.os.Parcel.class) continue;
            try {
                c.setAccessible(true);
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ensureDs()) return;
                        logOnce("parcelCtor", "Location(Parcel) deserialized");
                        rewriteSpatial((Location) param.thisObject);
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XposedBridge.log("[fakeloc] Location(Parcel) ctor hook failed: " + t);
            }
        }
        logOnce("parcelHooks", "Location parcel hooks installed=" + hooked);
    }

    // ---- 7: 坐标 setter 兜底 ----

    private static void hookCoordinateSetters() {
        XC_MethodHook rewrite = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isMutating()) return;
                Dataset d = snapshot();
                if (d == null) return;
                Location self = (Location) param.thisObject;
                Dataset.Fix target = d.medianFix();
                if (target == null) return;
                String name = param.method.getName();
                logOnce("setter", "Location." + name);
                Boolean prev = MUTATING.get();
                MUTATING.set(true);
                try {
                    // 只重写本方法对应轴; 旧实现用 lat||lon 判断却只 set 一边, 会无限重入
                    if ("setLatitude".equals(name)) {
                        if (self.getLatitude() != target.lat) self.setLatitude(target.lat);
                    } else if ("setLongitude".equals(name)) {
                        if (self.getLongitude() != target.lon) self.setLongitude(target.lon);
                    }
                } finally {
                    MUTATING.set(prev);
                }
            }
        };
        try {
            XposedBridge.hookMethod(
                    Location.class.getDeclaredMethod("setLatitude", double.class), rewrite);
            XposedBridge.hookMethod(
                    Location.class.getDeclaredMethod("setLongitude", double.class), rewrite);
        } catch (NoSuchMethodException e) {
            XposedBridge.log("[fakeloc] Location setter hook failed: " + e);
        }
    }

    // ---- 8: 当前连接 WiFi (WifiInfo) —— 扫描列表假、连接 BSSID 真会穿帮 ----

    private static void hookWifiInfo() {
        try {
            Class<?> wm = XposedHelpers.findClass("android.net.wifi.WifiManager",
                    MainHook.class.getClassLoader());
            XposedHelpers.findAndHookMethod(wm, "getConnectionInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Dataset d = snapshot();
                    if (d == null) return;
                    Object wi = param.getResult();
                    if (wi == null) return;
                    try {
                        rewriteConnectedWifiInfo(wi, d);
                    } catch (Throwable t) {
                        XposedBridge.log("[fakeloc] WifiInfo rewrite failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] getConnectionInfo hook failed: " + t);
        }
    }

    /**
     * 统一的 WifiInfo 改写入口 (app 层与系统层共用, BUG-40): 设备实际未连接
     * (真实 BSSID 为空, 含 WiFi 关闭) 时如实呈现未连接, 不虚构"连着假 AP" (R6);
     * 已连接则改写为实录最强 AP, 无实录扫描时匿名化。
     */
    private static void rewriteConnectedWifiInfo(Object wi, Dataset d) throws Exception {
        String realBssid = null;
        try {
            realBssid = ((android.net.wifi.WifiInfo) wi).getBSSID();
        } catch (Throwable ignored) {
        }
        if (realBssid == null || realBssid.isEmpty()) return; // 未连接/WiFi 关: 真实状态即答案
        Dataset.Ap best = bestApOf(d);
        if (best == null) {
            // 无实录扫描: 匿名化 (与系统对"无定位权限 App"的口径一致), 不放行真实 BSSID (BUG-32)
            anonymizeWifiInfo(wi);
            logOnce("wifiInfoAnon", "WifiInfo anonymized (no scans)");
            return;
        }
        rewriteWifiInfo(wi, best);
        logOnce("wifiInfo", "WifiInfo->" + best.bssid + " " + best.ssid);
    }

    /** 系统对"无定位权限 App"的匿名口径: SSID 用 UNKNOWN_SSID、BSSID 用 02:00:00:00:00:00 (BUG-32)。 */
    private static final String ANON_BSSID = "02:00:00:00:00:00";

    /** 无实录 WiFi 数据时的"假装不可用": 只匿名 SSID/BSSID; RSSI 不定位, 保留真值。 */
    private static void anonymizeWifiInfo(Object wi) {
        String q = android.net.wifi.WifiManager.UNKNOWN_SSID; // 自带引号
        setFieldQuiet(wi, "mSSID", q);
        setWifiSsidField(wi, q);
        setFieldQuiet(wi, "mBSSID", ANON_BSSID);
    }

    /** 反射改 WifiInfo 内部字段: 构造器多为 @hide, 改字段兼容面最大。 */
    private static void rewriteWifiInfo(Object wi, Dataset.Ap ap) throws Exception {
        String q = ap.ssid != null && ap.ssid.length() >= 2
                && ap.ssid.charAt(0) == '"' ? ap.ssid : "\"" + (ap.ssid == null ? "" : ap.ssid) + "\"";
        setFieldQuiet(wi, "mSSID", q);
        setWifiSsidField(wi, q);
        setFieldQuiet(wi, "mBSSID", ap.bssid);
        setFieldQuiet(wi, "mRssi", ap.rssi);
        try {
            setFieldQuiet(wi, "mFrequency", ap.freq);
        } catch (Throwable ignored) {
        }
    }

    /**
     * API 31+: SSID 存在 mWifiSsid (WifiSsid 对象) 而非 mSSID (String), 单写 mSSID 会
     * 静默失败漏真实 SSID (BUG-41)。两处都写; 老版本无 WifiSsid 类时自然跳过。
     */
    private static void setWifiSsidField(Object wi, String quotedSsid) {
        try {
            Class<?> cls = Class.forName("android.net.wifi.WifiSsid");
            Object sid = null;
            try {
                java.lang.reflect.Method from = cls.getDeclaredMethod("fromString", String.class);
                from.setAccessible(true);
                sid = from.invoke(null, quotedSsid);
            } catch (Throwable noFrom) {
                // 兜底: 隐藏构造器 WifiSsid(byte[])
                java.lang.reflect.Constructor<?> k = cls.getDeclaredConstructor(byte[].class);
                k.setAccessible(true);
                sid = k.newInstance((Object) quotedSsid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            setFieldQuiet(wi, "mWifiSsid", sid);
        } catch (Throwable ignored) {
        }
    }

    private static void setFieldQuiet(Object o, String name, Object v) {
        try {
            Field f = findField(o.getClass(), name);
            if (f == null) return;
            f.setAccessible(true);
            if (v instanceof Integer) f.setInt(o, (Integer) v);
            else f.set(o, v);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    // ---- 9: 百度 BDLocation (学习通主路径; 不是 android.location.Location 子类) ----

    private static void hookBaiduSdk(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> bd;
        try {
            bd = XposedHelpers.findClass("com.baidu.location.BDLocation", lpparam.classLoader);
        } catch (Throwable t) {
            return; // 进程无百度定位
        }
        XC_MethodHook rewrite = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // 地址/POI 叙事 setter: 在 SDK 写入前就换掉参数 (构造期即干净), 免去事后对拍。
                // 我们自己的 invokeQuiet 回写发生在 enterMutating 区间内, 这里会直接放行, 不递归。
                if (isMutating()) return;
                Dataset d = snapshot();
                if (d == null || param.args == null || param.args.length != 1) return;
                if (currentFixForBd(d) == null) return;
                String n = param.method.getName();
                try {
                    if ("setAddrStr".equals(n)) {
                        param.args[0] = bdAddrLine(d);
                    } else if ("setLocationDescribe".equals(n)) {
                        param.args[0] = bdAddrDescribe(d);
                    } else if ("setAddr".equals(n)) {
                        Object rebuilt = bdAddressOf(((Method) param.method).getParameterTypes()[0].getClassLoader(), d);
                        if (rebuilt != null) param.args[0] = rebuilt;
                    } else if ("setPoiList".equals(n) || "setPoiRegion".equals(n)) {
                        param.args[0] = null; // 真实 POI 锚定真实位置, 必须清掉
                    }
                } catch (Throwable t) {
                    logOnce("bdAddrSet", "addr setter rewrite: " + t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isMutating()) return;
                Dataset d = snapshot();
                if (d == null) return;
                Object self = param.thisObject;
                Dataset.Fix f = currentFixForBd(d);
                if (f == null || self == null) return;
                double[] bd = bd09Of(f); // {lat, lon} in BD-09
                String n = param.method.getName();
                Boolean prev = enterMutating();
                try {
                    if ("setLatitude".equals(n)) {
                        invokeQuiet(self, "setLatitude", new Class[]{double.class}, bd[0]);
                    } else if ("setLongitude".equals(n)) {
                        invokeQuiet(self, "setLongitude", new Class[]{double.class}, bd[1]);
                    } else if ("getLatitude".equals(n)) {
                        param.setResult(bd[0]);
                    } else if ("getLongitude".equals(n)) {
                        param.setResult(bd[1]);
                    } else if ("getRadius".equals(n) || "getAccuracy".equals(n)) {
                        if (f.acc > 0) param.setResult(f.acc);
                    } else if ("getAltitude".equals(n)) {
                        // 坐标系换算不涉及海拔, 直用 WGS-84 记录值; silent 按"室内无海拔"写 0 (BUG-38)
                        param.setResult(silentGnss(d) ? 0d : f.alt);
                    } else if ("getSpeed".equals(n)) {
                        param.setResult(Math.max(f.spd, 0));
                    } else if ("setAltitude".equals(n) && param.args != null && param.args.length == 1) {
                        invokeQuiet(self, "setAltitude", new Class[]{double.class},
                                silentGnss(d) ? 0d : f.alt);
                    } else if ("setSpeed".equals(n) && param.args != null && param.args.length == 1) {
                        invokeQuiet(self, "setSpeed", new Class[]{float.class}, Math.max(f.spd, 0));
                    }
                    // ---- 地址族 getter (防御纵深): Parcel 构造等不经 setter 的路径也收敛到这里 ----
                    else if ("getAddrStr".equals(n)) {
                        param.setResult(bdAddrLine(d));
                    } else if ("getAddress".equals(n)) {
                        Object rebuilt = bdAddressOf(self.getClass().getClassLoader(), d);
                        if (rebuilt != null) param.setResult(rebuilt);
                    } else if ("getProvince".equals(n)) {
                        param.setResult(bdAddrField(d, "province"));
                    } else if ("getCity".equals(n)) {
                        param.setResult(bdAddrField(d, "city"));
                    } else if ("getDistrict".equals(n)) {
                        param.setResult(bdAddrField(d, "district"));
                    } else if ("getCountry".equals(n)) {
                        param.setResult(bdAddrField(d, "country"));
                    } else if ("getCountryCode".equals(n)) {
                        param.setResult(bdAddrField(d, "countryCode"));
                    } else if ("getStreet".equals(n)) {
                        param.setResult(bdAddrField(d, "street"));
                    } else if ("getStreetNumber".equals(n)) {
                        param.setResult(bdAddrField(d, "streetNo"));
                    } else if ("getLocationDescribe".equals(n)) {
                        param.setResult(bdAddrDescribe(d));
                    } else if ("getAdCode".equals(n) || "getCityCode".equals(n) || "getTown".equals(n)) {
                        // Geocoder 拿不到这三个百度专有字段: 留空, 不留与伪装坐标矛盾的真值
                        param.setResult("");
                    } else if ("getPoiList".equals(n) || "getPoiRegion".equals(n)) {
                        // POI 锚定真实位置 (服务端按真坐标下发), setter 清不掉的路径这里兜底
                        param.setResult(null);
                    }
                    // ---- mock 字段 (A3): 服务端回填, 对象层钳制到 LocObs 实测的干净基线
                    //      (strategy=0, probability=-1, reall=null) —— 打卡拦截判
                    //      getMockGnssProbability()>1, 签到上报 mockData{strategy,probability} ----
                    else if ("getMockGnssStrategy".equals(n) || "getMockGpsStrategy".equals(n)) {
                        param.setResult(0);
                    } else if ("getMockGnssProbability".equals(n) || "getMockGpsProbability".equals(n)) {
                        param.setResult(-1);
                    } else if ("getReallLocation".equals(n)) {
                        param.setResult(null);
                    } else if ("getDisToRealLocation".equals(n)) {
                        param.setResult(-1.0); // 无 reall 时的 SDK 默认值
                    }
                } finally {
                    exitMutating(prev);
                }
            }
        };
        int hooked = 0;
        for (String mn : new String[]{"setLatitude", "setLongitude", "getLatitude",
                "getLongitude", "getRadius", "getAccuracy",
                "setAltitude", "getAltitude", "setSpeed", "getSpeed",
                // 地址叙事 + mock 字段: setter 构造期改写, getter 防御纵深 (A2/A3)
                "setAddr", "setAddrStr", "setLocationDescribe", "setPoiList", "setPoiRegion",
                "getAddress", "getAddrStr", "getProvince", "getCity", "getDistrict",
                "getCountry", "getCountryCode", "getStreet", "getStreetNumber",
                "getLocationDescribe", "getAdCode", "getCityCode", "getTown",
                "getPoiList", "getPoiRegion",
                "getMockGnssStrategy", "getMockGpsStrategy",
                "getMockGnssProbability", "getMockGpsProbability",
                "getReallLocation", "getDisToRealLocation"}) {
            for (Method m : bd.getDeclaredMethods()) {
                if (!m.getName().equals(mn)) continue;
                try {
                    XposedBridge.hookMethod(m, rewrite);
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
        }
        // locType: silent 走网络定位 161, 有天空数据时像 GPS 61
        try {
            Method st = bd.getDeclaredMethod("setLocType", int.class);
            XposedBridge.hookMethod(st, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Dataset d = snapshot();
                    if (d == null) return;
                    Boolean prev = enterMutating();
                    try {
                        invokeQuiet(param.thisObject, "setLocType",
                                new Class[]{int.class}, silentGnss(d) ? 161 : 61);
                    } finally {
                        exitMutating(prev);
                    }
                }
            });
            hooked++;
        } catch (Throwable ignored) {
        }
        // LocationClient.getLastKnownLocation 返回 BDLocation
        try {
            Class<?> lc = XposedHelpers.findClass("com.baidu.location.LocationClient", lpparam.classLoader);
            for (Method m : lc.getDeclaredMethods()) {
                if (!"getLastKnownLocation".equals(m.getName())) continue;
                if (!bd.isAssignableFrom(m.getReturnType()) && m.getReturnType() != Object.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Dataset d = snapshot();
                        if (d == null) return;
                        Object ret = param.getResult();
                        if (ret == null) return;
                        if (currentFixForBd(d) == null) return;
                        rewriteBdObject(ret, d); // 坐标 + 地址叙事 + mock 字段与回调路径同源
                        logOnce("baiduLast", "LocationClient.getLastKnownLocation rewritten");
                    }
                });
                hooked++;
            }
        } catch (Throwable ignored) {
        }
        // 位置监听器: 不用 Proxy —— BDAbstractLocationListener 是抽象类, Proxy 只支持
        // 接口 (学习通上实测抛 "not visible from class loader")。改为在注册点拿到真实
        // 监听器对象, 直接 hook 其类 (含基类链) 的 onReceiveLocation, 改写 BDLocation 参数。
        // 覆盖抽象类/接口/匿名子类三种形态; 同一监听器类只 hook 一次。
        final java.util.Set<Class<?>> hookedLsn =
                java.util.Collections.synchronizedSet(new java.util.HashSet<Class<?>>());
        try {
            Class<?> lc = XposedHelpers.findClass("com.baidu.location.LocationClient", lpparam.classLoader);
            for (Method m : lc.getDeclaredMethods()) {
                if (!m.getName().equals("registerLocationListener")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        Class<?> cls = param.args[0].getClass();
                        if (!hookedLsn.add(cls)) return;
                        int n = 0;
                        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                            for (Method m2 : c.getDeclaredMethods()) {
                                if (!"onReceiveLocation".equals(m2.getName())) continue;
                                Class<?>[] pts = m2.getParameterTypes();
                                if (pts.length != 1 || !bd.isAssignableFrom(pts[0])) continue;
                                try {
                                    XposedBridge.hookMethod(m2, new XC_MethodHook() {
                                        @Override
                                        protected void beforeHookedMethod(MethodHookParam p2) {
                                            Dataset d = snapshot();
                                            if (d == null) return;
                                            if (p2.args != null && p2.args.length == 1
                                                    && p2.args[0] != null) {
                                                rewriteBdObject(p2.args[0], d);
                                            }
                                        }
                                    });
                                    n++;
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        if (n > 0) logOnce("baiduLsn", "onReceiveLocation hooks=" + n
                                + " on " + cls.getName());
                    }
                });
                hooked++;
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] baidu listener hook failed: " + t);
        }
        // A4: 百度 SDK 内部 mock 评分 (com.baidu.location.f.d.f(Location) -> int,
        // isFromMockProvider→100, 时间窗/卫星组合→200~500), 结果作为 is_mock 随 GPS JSON
        // 上报百度服务器。伪装期间恒 0 —— 上行叙事与对象层一致。签名已对照学习通
        // 3.x 脱壳源码核实 (private int f(Location)); 类名混淆, 换 SDK 版本可能漂移,
        // findClass/方法匹配失败仅记日志, 不影响其它 hook。
        try {
            Class<?> fd = XposedHelpers.findClass("com.baidu.location.f.d", lpparam.classLoader);
            for (Method m : fd.getDeclaredMethods()) {
                if (!"f".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1 || pts[0] != android.location.Location.class) continue;
                if (m.getReturnType() != int.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (snapshot() == null) return;
                        param.setResult(0);
                    }
                });
                hooked++;
            }
        } catch (Throwable t) {
            logOnce("baiduFdf", "f.d.f hook unavailable: " + t);
        }
        if (hooked > 0) {
            logOnce("baiduSdk", "Baidu BDLocation hooks=" + hooked);
        }
    }

    /** 回放轴上的当前 fix (WGS-84 元数据); BD-09 转换发生在各写入点 (见 bd09Of)。 */
    private static Dataset.Fix currentFixForBd(Dataset d) {
        if (d == null) return null;
        Dataset.Fix f = d.sampleAt(replayElapsed(d));
        if (f == null) f = d.medianFix();
        return f;
    }

    /** 数据集 fix (WGS-84) → BD-09 {lat, lon}: BDLocation 契约是 BD-09, 不转换偏移数百米。 */
    private static double[] bd09Of(Dataset.Fix f) {
        double[] bd = CoordTransform.wgs84ToBd09(f.lon, f.lat);
        return new double[]{bd[1], bd[0]};
    }

    private static void rewriteBdObject(Object bd, Dataset d) {
        if (d == null) return;
        Dataset.Fix f = currentFixForBd(d);
        if (f == null) return;
        double[] bdc = bd09Of(f); // {lat, lon} in BD-09; 抖动叠加在 BD-09 空间 (量级 ~0.5m)
        double sigmaM = Math.max(f.acc, 3f) * 0.08;
        long seed = SystemClock.elapsedRealtimeNanos() + 29;
        double jlat = jitterDeg(bdc[0], sigmaM / 111320.0, seed);
        double jlon = jitterDeg(bdc[1],
                sigmaM / (111320.0 * Math.cos(Math.toRadians(bdc[0]))), seed * 17 + 5);
        Boolean prev = enterMutating();
        try {
            invokeQuiet(bd, "setLatitude", new Class[]{double.class}, jlat);
            invokeQuiet(bd, "setLongitude", new Class[]{double.class}, jlon);
            invokeQuiet(bd, "setRadius", new Class[]{float.class}, f.acc);
            // 海拔不受坐标系影响, 直用记录值; silent 按"室内无海拔"写 0; 速度直用记录值 (BUG-38)
            invokeQuiet(bd, "setAltitude", new Class[]{double.class}, silentGnss(d) ? 0d : f.alt);
            invokeQuiet(bd, "setSpeed", new Class[]{float.class}, Math.max(f.spd, 0));
            invokeQuiet(bd, "setLocType", new Class[]{int.class}, silentGnss(d) ? 161 : 61);
            // 地址叙事 (A2): 数据集有 addr 换成录制地, 没有 → 全空 Address (清空叙事不留真值);
            // Builder 反射失败时 getter 侧 hook (getAddress/getAddrStr/...) 仍会兜底改写
            Object rebuilt = bdAddressOf(bd.getClass().getClassLoader(), d);
            if (rebuilt != null) {
                invokeQuiet(bd, "setAddr", new Class[]{rebuilt.getClass()}, rebuilt);
            }
            invokeQuiet(bd, "setAddrStr", new Class[]{String.class}, bdAddrLine(d));
            invokeQuiet(bd, "setLocationDescribe", new Class[]{String.class}, bdAddrDescribe(d));
        } catch (Throwable t) {
            logOnce("bdRewriteFail", "rewriteBdObject: " + t);
        } finally {
            exitMutating(prev);
        }
    }

    // ---- A2 辅助: BDLocation 地址族的数据源 ----

    /** 数据集 addr → 目标 classLoader 的 com.baidu.location.Address (Builder 反射重建)。
     *  无地址数据时返回全空 Address —— "清空叙事"而非回落真地址; Builder 反射失败返回 null,
     *  此时依赖 getter 侧 hook 兜底。 */
    private static Object bdAddressOf(ClassLoader cl, Dataset d) {
        try {
            Class<?> abCls = Class.forName("com.baidu.location.Address$Builder", true, cl);
            Object b = abCls.getDeclaredConstructor().newInstance();
            Dataset.Addr a = d.addr;
            if (a != null) {
                bdBuilderSet(abCls, b, "country", a.country);
                bdBuilderSet(abCls, b, "countryCode", a.countryCode);
                bdBuilderSet(abCls, b, "province", a.province);
                bdBuilderSet(abCls, b, "city", a.city);
                bdBuilderSet(abCls, b, "district", a.district);
                bdBuilderSet(abCls, b, "street", a.street);
                bdBuilderSet(abCls, b, "streetNumber", a.streetNo);
            }
            return abCls.getMethod("build").invoke(b);
        } catch (Throwable t) {
            logOnce("bdAddrBuild", "bdAddressOf: " + t);
            return null;
        }
    }

    private static void bdBuilderSet(Class<?> abCls, Object builder, String method, String value)
            throws Exception {
        if (value == null || value.isEmpty()) return;
        abCls.getMethod(method, String.class).invoke(builder, value);
    }

    private static String bdAddrLine(Dataset d) {
        return (d.addr != null && d.addr.line != null) ? d.addr.line : "";
    }

    private static String bdAddrDescribe(Dataset d) {
        return (d.addr != null && d.addr.describe != null) ? d.addr.describe : "";
    }

    private static String bdAddrField(Dataset d, String field) {
        if (d.addr == null) return "";
        try {
            Object v = Dataset.Addr.class.getField(field).get(d.addr);
            return v == null ? "" : (String) v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void invokeQuiet(Object target, String name, Class<?>[] pts, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, pts);
            m.invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    // ---- 10: getCellLocation / 邻区 (风控监控列表里有) ----

    private static void hookCellLocation() {
        try {
            XposedHelpers.findAndHookMethod(android.telephony.TelephonyManager.class,
                    "getCellLocation", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Dataset d = snapshot();
                            if (d == null) return;
                            Object fake = buildCellLocationFromDs(d);
                            if (fake != null) {
                                logOnce("cellLoc", "TelephonyManager.getCellLocation");
                                param.setResult(fake);
                            } else {
                                // 真机上无数据时本就常返回 null, 装不可用无穿帮风险 (BUG-32)
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] getCellLocation hook failed: " + t);
        }
        try {
            for (Method m : android.telephony.TelephonyManager.class.getDeclaredMethods()) {
                if (!m.getName().equals("getNeighboringCellInfo")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ensureDs()) return;
                        // 实录邻区未单独存; 返回空列表避免真邻区穿帮
                        param.setResult(new ArrayList<>());
                        logOnce("neighCell", "getNeighboringCellInfo cleared");
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] getNeighboringCellInfo hook failed: " + t);
        }
    }

    /**
     * 回放轴上的基站列表 (app 层与系统层 PIM 共用, BUG-40): 无实录 cells 回空列表。
     * 时间戳锚定快照时刻 (elapsedNow - (replayElapsed - snap.off), 与 F1 同型, BUG-43):
     * 同一快照内多次调用恒定、切快照前进 —— 真机注册态基站在信息未变化时 ts 稳定。
     */
    private static List<android.telephony.CellInfo> buildCellInfos(Dataset d) {
        List<android.telephony.CellInfo> out = new ArrayList<>();
        long e = replayElapsed(d);
        Dataset.CellSnap snap = d.cellAtTime(e);
        if (snap == null || snap.cellsJson == null || snap.cellsJson.length() == 0) return out;
        long ageMs = e - snap.off;
        if (ageMs < 0) ageMs = 0; // cellAtTime 取最近帧, 帧在"未来"时视作刚更新
        long tsNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L;
        return SignalCodec.cellsFromJson(snap.cellsJson, tsNanos >= 0 ? tsNanos : 0);
    }

    /** 从实录 CellSnap 造 GsmCellLocation (LAC/CID); 无 cells 时返回 null 直通。 */
    private static Object buildCellLocationFromDs(Dataset d) {
        try {
            Dataset.CellSnap snap = d.cellAtTime(replayElapsed(d));
            if (snap == null || snap.cellsJson == null || snap.cellsJson.length() == 0) return null;
            List<android.telephony.CellInfo> built = SignalCodec.cellsFromJson(snap.cellsJson);
            if (built.isEmpty()) return null;
            for (android.telephony.CellInfo ci : built) {
                if (!ci.isRegistered()) continue;
                Object id = ci.getClass().getMethod("getCellIdentity").invoke(ci);
                Integer lac = (Integer) callOpt(id, "getTac");
                if (lac == null) lac = (Integer) callOpt(id, "getLac");
                Integer cid = (Integer) callOpt(id, "getCi");
                if (cid == null) cid = (Integer) callOpt(id, "getCid");
                if (lac == null || cid == null) continue;
                Object gl = Class.forName("android.telephony.gsm.GsmCellLocation")
                        .getDeclaredConstructor().newInstance();
                gl.getClass().getMethod("setLacCid", int.class, int.class).invoke(gl, lac, cid);
                return gl;
            }
        } catch (Throwable t) {
            logOnce("cellLocBuildFail", "buildCellLocation: " + t);
        }
        return null;
    }

    private static Object callOpt(Object o, String name) {
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 11: mock 标志强制干净 ----

    private static void hookMockFlags() {
        XC_MethodHook notMock = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ensureDs()) return;
                param.setResult(false);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", notMock);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(Location.class, "isMock", notMock);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(Location.class, "getMock", notMock);
        } catch (Throwable ignored) {
        }
    }

    // ---- 12: GNSS 旁路 (getGpsStatus 恒不可用 / GnssMeasurements 拒绝注册) ----

    private static void hookGnssSideChannels() {
        try {
            for (Method m : LocationManager.class.getDeclaredMethods()) {
                if (!m.getName().equals("getGpsStatus")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Dataset d = snapshot();
                        if (d == null) return;
                        // legacy GpsStatus 无法重建 (无 Builder), 恒假装不可用 (BUG-33):
                        // 与重建回放的 GnssStatus 不产生双口径, 也堵死真星空从旧接口漏出
                        param.setResult(null);
                        logOnce("gpsStatusHidden", "getGpsStatus->null");
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] getGpsStatus hook failed: " + t);
        }
        // GnssMeasurements: GnssMeasurementsEvent.Callback 是抽象类, 不能动态代理 ——
        // 旧实现的 Proxy.newProxyInstance 在注册时就会抛 IllegalArgumentException, 反而
        // 打断宿主的定位链 (BUG-02, 已删除)。改为在注册链路阻止: 伪装生效期间所有
        // registerGnssMeasurementsCallback 重载直接返回 false (框架语义内的失败, 应用按
        // "不支持"处理), onGnssMeasurementsReceived/onStatusChanged 因此不再派发,
        // 真实伪距与星图不会泄露给假坐标。未启用伪装时原样注册。
        try {
            for (Method m : LocationManager.class.getDeclaredMethods()) {
                if (!m.getName().equals("registerGnssMeasurementsCallback")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (snapshot() == null) return;
                        param.setResult(false);
                        logOnce("gnssMeasSilent", "registerGnssMeasurementsCallback refused");
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] GnssMeasurements hook failed: " + t);
        }
    }

    // ---- 13: 硬件旁路: 气压计暴露真实海拔 (BUG-35) ----

    /**
     * 气压计读数换算的海拔是真实地点的, 无法跨地点伪造 —— 按"该机无气压计"收敛:
     * 大量机型本就无气压计, 缺失完全可信。只隐藏 TYPE_PRESSURE, 不动加速度计/
     * 磁力计等运动传感器 (隐藏它们会破坏 App 正常功能, 且与位置无关)。
     * 仅装在应用层: 系统级隐藏会波及所有进程, 绝不在 system_server 安装。
     *
     * 不 hook getFullSensorList (BUG-42, 复审回退): 它在基类 SensorManager 上是
     * protected abstract, hook 基类抽象方法拦不住 SystemSensorManager 具体实现的
     * 调用 (虚分派走子类); 且"清空全量列表"若生效会隐藏全部传感器, 与设计约束相反。
     * 公开 API 面 (getDefaultSensor 两重载 / getSensorList(int)) 已完整覆盖,
     * 全量枚举是框架内部路径, App 拿不到。
     */
    private static void hookSensorAbsence() {
        XC_MethodHook noPressure = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (snapshot() == null) return;
                if (param.args == null || param.args.length == 0
                        || !(param.args[0] instanceof Integer)
                        || (Integer) param.args[0] != android.hardware.Sensor.TYPE_PRESSURE) {
                    return;
                }
                if ("getSensorList".equals(param.method.getName())) {
                    param.setResult(java.util.Collections.emptyList());
                } else {
                    param.setResult(null); // getDefaultSensor 契约: 无此传感器返回 null
                }
                logOnce("pressureHidden", "SensorManager pressure -> absent");
            }
        };
        try {
            int hooked = 0;
            for (Method m : android.hardware.SensorManager.class.getDeclaredMethods()) {
                if (!"getDefaultSensor".equals(m.getName()) && !"getSensorList".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 0 || pts[0] != int.class) continue;
                XposedBridge.hookMethod(m, noPressure);
                hooked++;
            }
            if (hooked > 0) logOnce("sensorHook", "SensorManager pressure hooks=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log("[fakeloc] SensorManager hook failed: " + t);
        }
    }

    // ---- 14: NetworkCapabilities.getSignalStrength (API 29+, BUG-36) ----

    /**
     * WiFi transport 的信号强度与 WifiManager.getConnectionInfo().getRssi() 是同一事实
     * 的两种读法, 必须同源 —— 否则"扫描列表/连接信息是假 AP、caps 里却是真 RSSI"。
     * 只改 WIFI transport (蜂窝信号无假数据源, 动了必穿帮); 无实录 AP 时保持系统值,
     * 与 hookWifiInfo 匿名分支"RSSI 留真"同口径 (RSSI 单值不构成位置指纹)。
     */
    private static void hookNetworkCaps() {
        try {
            XposedHelpers.findAndHookMethod(android.net.NetworkCapabilities.class,
                    "getSignalStrength", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Dataset d = snapshot();
                            if (d == null) return;
                            boolean wifi;
                            try {
                                wifi = Boolean.TRUE.equals(android.net.NetworkCapabilities.class
                                        .getMethod("hasTransport", int.class)
                                        .invoke(param.thisObject,
                                                android.net.NetworkCapabilities.TRANSPORT_WIFI));
                            } catch (Throwable t) {
                                return;
                            }
                            if (!wifi) return;
                            Dataset.Ap best = bestApOf(d);
                            if (best == null) return;
                            param.setResult(best.rssi);
                            logOnce("capsRssi", "NetworkCapabilities.getSignalStrength -> " + best.rssi);
                        }
                    });
        } catch (Throwable t) {
            // API < 29 无此方法属预期, 留痕即可
            logOnce("capsHookSkip", "NetworkCapabilities.getSignalStrength hook skipped: " + t);
        }
    }
}
