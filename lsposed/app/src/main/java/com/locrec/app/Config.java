package com.locrec.app;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 模块配置。
 *
 * 多地点模型: 全量数据集存模块私有目录, prefs 只留索引 K_LIBRARY + 当前选中地点的镜像
 * K_ACTIVE_PAYLOAD。目标进程只能通过 XSharedPreferences 读 prefs。
 *
 * 注意: 跨进程读 prefs 依赖 LSPosed 让模块 prefs 可读。实测某些 ROM/LSPosed 组合下会读成空值
 * (enabled=false, payload=null), 因此这里把两端读数都打到 logcat(tag fakeloc-hook) —— 注入类问题
 * 只能靠这条通道定位。
 */
public final class Config {
    public static final String PKG = "com.locrec.app";
    public static final String PREFS = "cfg";

    public static final String K_ENABLED = "enabled";
    /** 当前选中的地点 id */
    public static final String K_ACTIVE_ID = "active_id";
    /** 当前选中地点的全量数据集 JSON (镜像, 供目标进程读) */
    public static final String K_ACTIVE_PAYLOAD = "active_payload";
    /** 地点索引 JSON (小, 供状态显示/排错) */
    public static final String K_LIBRARY = "library";

    /** 旧版单数据集键, 仅用于一次性迁移 */
    public static final String K_DATASET = "dataset";
    public static final String K_RECORDED_AT = "recorded_at";
    /**
     * 全局回放锚点 (elapsedRealtime 毫秒): select() 切地点时写入。
     * elapsedRealtime 同机全进程同源, 各被注入进程与 system_server 共用即可时间轴一致。
     */
    public static final String K_ANCHOR = "anchor_elapsed";

    private static final String TAG = "fakeloc-hook";

    public final boolean enabled;
    public final String datasetJson;
    public final String activeId;
    public final long recordedAt;
    /** 模块自身 App 的 uid (provider 自报); 0 = 未知。系统层 hook 用它放行自家录制器。 */
    public final int selfUid;
    /** 全局回放锚点 (elapsedRealtime); 0 = 未设置, 回落各进程本地懒锚。 */
    public final long anchorElapsed;
    /**
     * 该读数是否来自权威通道 (provider / binder service / LSPatch 宿主内直读):
     * 权威通道读到「无数据集 + 关闭」是用户真实操作 (如删除最后一个地点), 必须生效;
     * XSharedPreferences 读到同样内容则可能是权限/SELinux 读失败, 不能当真。
     */
    public final boolean authoritative;

    private Config(boolean enabled, String datasetJson, String activeId, long recordedAt,
                   int selfUid, long anchorElapsed, boolean authoritative) {
        this.enabled = enabled;
        this.datasetJson = datasetJson;
        this.activeId = activeId;
        this.recordedAt = recordedAt;
        this.selfUid = selfUid;
        this.anchorElapsed = anchorElapsed;
        this.authoritative = authoritative;
    }

    public static Config load(XSharedPreferences xp) {
        // 通道 0: 同进程 SharedPreferences 直读。LSPatch 模式下模块 UI 与 hook 都以宿主身份
        // 跑在同一进程, 读宿主自己的 cfg 文件即是权威配置 —— 不存在包可见性/跨进程问题。
        // LSPosed 模式下这里是"目标 App 自己的 cfg"(通常不存在), payload 为 null 自然跳过, 无害。
        Config viaHost = loadViaHostPrefs();
        if (viaHost != null && viaHost.datasetJson != null) {
            android.util.Log.i(TAG, "config source=host-prefs place=" + viaHost.activeId
                    + " payloadBytes=" + viaHost.datasetJson.length());
            return viaHost;
        }
        Config viaProvider = loadViaProvider();
        if (viaProvider != null && viaProvider.authoritative) {
            // 权威通道: 即使无数据集 (如删除最后一个地点) 也原样返回, 由调用方决定丢弃
            android.util.Log.i(TAG, "config source=provider place=" + viaProvider.activeId
                    + " payloadBytes=" + (viaProvider.datasetJson == null ? 0 : viaProvider.datasetJson.length()));
            return viaProvider;
        }
        Config viaService = loadViaService();
        if (viaService != null && viaService.authoritative) {
            android.util.Log.i(TAG, "config source=service place=" + viaService.activeId
                    + " payloadBytes=" + (viaService.datasetJson == null ? 0 : viaService.datasetJson.length()));
            return viaService;
        }
        Config viaPrefs = loadViaPrefs(xp);
        android.util.Log.i(TAG, "config source=prefs enabled=" + viaPrefs.enabled
                + " activeId=" + viaPrefs.activeId
                + " payloadBytes=" + (viaPrefs.datasetJson == null ? 0 : viaPrefs.datasetJson.length()));
        return viaPrefs;
    }

    /**
     * 通道 0: 宿主进程内 SharedPreferences 直读 (LSPatch 权威通道)。
     * 只读不建文件: 目标 App 若没有 cfg 文件 (LSPosed 模式的常态) 不会产生副作用。
     */
    private static Config loadViaHostPrefs() {
        try {
            // system_server 里 currentApplication() 返回系统 Application, 读到的是系统的
            // /data/system prefs —— 不是"宿主自己", 通道 0 在此语义不成立, 直接跳过
            if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID) return null;
            android.content.Context ctx = currentApp();
            if (ctx == null) return null;
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            // 权威通道加固 (LSPatch): 只认本模块 UI 写的 active_payload 镜像 + library 索引
            // 同时存在。不复用旧迁移键 K_DATASET ("dataset" 是目标 App 自己也可能用的通用键,
            // 单凭它一个字符串就采信会被宿主同名 prefs 劫持)。
            String payload = p.getString(K_ACTIVE_PAYLOAD, null);
            if (payload == null || !p.contains(K_LIBRARY)) return null;
            // selfUid 置 0: LSPatch 模式下模块与宿主同 uid, 若豁免"自己"就等于豁免目标 App,
            // 伪装会整体失效。代价是宿主内无法录制 (会被自家 hook 改写), 录制走独立 App + 导入。
            return new Config(p.getBoolean(K_ENABLED, false), payload,
                    p.getString(K_ACTIVE_ID, null), p.getLong(K_RECORDED_AT, 0L),
                    0, p.getLong(K_ANCHOR, 0L), true);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "host-prefs config read failed: " + t);
            return null;
        }
    }

    /**
     * 首选通道: ContentProvider。XSharedPreferences 依赖 LSPosed 的 prefs 可读机制,
     * 实测在本机读到的永远是空值(且不报错), 所以默认走原生通道。
     */
    private static Config loadViaProvider() {
        try {
            android.content.Context ctx = currentApp();
            if (ctx == null) return null;
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.net.Uri uri = android.net.Uri.parse("content://" + ConfigProvider.AUTHORITY + "/active");
            android.os.Bundle meta = cr.call(uri, "meta", null, null);
            if (meta == null || meta.getBoolean("error", false)) return null;
            int len = meta.getInt("len", 0);
            if (len <= 0) {
                // meta 读取成功 = provider 可达且鉴权通过, 空库是真实状态 (非读失败):
                // 带权威标记原样返回, 调用方据此丢弃缓存 (P0: 删除最后一个地点后必须能关掉)
                return new Config(meta.getBoolean("enabled", false), null,
                        meta.getString("activeId"), meta.getLong("recordedAt", 0L),
                        meta.getInt("uid", 0), meta.getLong("anchor", 0L), true);
            }
            StringBuilder sb = new StringBuilder(len);
            for (int off = 0; off < len; off += ConfigProvider.CHUNK) {
                android.os.Bundle b = cr.call(uri, "chunk", String.valueOf(off), null);
                if (b == null) return null;
                String part = b.getString("data");
                if (part == null || part.isEmpty()) break;
                sb.append(part);
            }
            if (sb.length() == 0) return null;
            return new Config(meta.getBoolean("enabled", false), sb.toString(),
                    meta.getString("activeId"), meta.getLong("recordedAt", 0L),
                    meta.getInt("uid", 0), meta.getLong("anchor", 0L), true);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "provider config read failed: " + t);
            return null;
        }
    }

    /** 供 hook 侧解析"本模块自身 uid"用: 系统层 hook 对全部调用方生效, 必须能认出自家进程。 */
    public static android.content.Context appContext() {
        return currentApp();
    }

    // ---- 通道二: 显式 bindService (Android 11+ 包可见性不限制显式 ComponentName 直连) ----
    // provider 通道在部分 ROM 上被包可见性拦死 ("Unknown authority", Android 16 实测),
    // 而显式 ComponentName 的 bindService 不经过解析/查询, 是平台保证可达的跨应用通道。

    private static final Object SERVICE_LOCK = new Object();
    private static android.os.IBinder serviceBinder;
    private static android.content.ServiceConnection serviceConn;

    private static Config loadViaService() {
        try {
            android.content.Context ctx = currentApp();
            if (ctx == null) return null;
            android.os.IBinder b = ensureServiceBinder(ctx);
            if (b == null) return null;
            android.os.Bundle meta = transact(b, ConfigService.TX_META, -1);
            if (meta == null || meta.getBoolean("error", false)) return null;
            int len = meta.getInt("len", 0);
            if (len <= 0) {
                // 同 provider: 绑定与 meta 都成功, 空库是真实状态, 权威返回
                return new Config(meta.getBoolean("enabled", false), null,
                        meta.getString("activeId"), meta.getLong("recordedAt", 0L),
                        meta.getInt("uid", 0), meta.getLong("anchor", 0L), true);
            }
            StringBuilder sb = new StringBuilder(len);
            for (int off = 0; off < len; off += ConfigService.CHUNK) {
                android.os.Bundle chunk = transact(b, ConfigService.TX_CHUNK, off);
                if (chunk == null) return null;
                String part = chunk.getString("data");
                if (part == null || part.isEmpty()) break;
                sb.append(part);
            }
            if (sb.length() == 0) return null;
            return new Config(meta.getBoolean("enabled", false), sb.toString(),
                    meta.getString("activeId"), meta.getLong("recordedAt", 0L),
                    meta.getInt("uid", 0), meta.getLong("anchor", 0L), true);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "service config read failed: " + t);
            return null;
        }
    }

    /** transact 一次并把应答打包成 Bundle (chunk 返回 {data}); 失败返回 null。 */
    private static android.os.Bundle transact(android.os.IBinder b, int code, int off)
            throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(ConfigService.ACTION);
            if (off >= 0) data.writeInt(off);
            if (!b.transact(code, data, reply, 0)) return null;
            reply.readException();
            if (code == ConfigService.TX_CHUNK) {
                android.os.Bundle out = new android.os.Bundle();
                out.putString("data", reply.readString());
                return out;
            }
            return reply.readBundle();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 持久连接: 绑一次常驻 (BIND_AUTO_CREATE, 模块进程本就常驻); 断连由框架自动重连。 */
    private static android.os.IBinder ensureServiceBinder(android.content.Context ctx) {
        synchronized (SERVICE_LOCK) {
            if (serviceBinder != null) return serviceBinder;
            if (serviceConn == null) {
                android.content.Intent it = new android.content.Intent()
                        .setClassName(Config.PKG, ConfigService.class.getName());
                serviceConn = new android.content.ServiceConnection() {
                    @Override
                    public void onServiceConnected(android.content.ComponentName name, android.os.IBinder ib) {
                        synchronized (SERVICE_LOCK) {
                            serviceBinder = ib;
                            SERVICE_LOCK.notifyAll();
                        }
                    }

                    @Override
                    public void onServiceDisconnected(android.content.ComponentName name) {
                        synchronized (SERVICE_LOCK) {
                            serviceBinder = null;
                        }
                    }
                };
                try {
                    if (!ctx.bindService(it, serviceConn, android.content.Context.BIND_AUTO_CREATE)) {
                        android.util.Log.w(TAG, "bindService returned false");
                        serviceConn = null;
                        return null;
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "bindService failed: " + t);
                    serviceConn = null;
                    return null;
                }
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
            while (serviceBinder == null) {
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) return null;
                try {
                    SERVICE_LOCK.wait(Math.min(left, 300));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return serviceBinder;
        }
    }

    /**
     * 可用的 Context: 应用进程取 Application; 取不到 (system_server / 早期初始化) 时
     * 回退 ActivityThread.getSystemContext() —— BUG-06 的关键路径: system_server 有了
     * Context 才能走 provider 读配置、用 PackageManager 解析模块 uid, 不再只能拿空 prefs。
     */
    private static android.content.Context currentApp() {
        for (String cls : new String[]{"android.app.AndroidAppHelper", "android.app.ActivityThread"}) {
            try {
                Class<?> c = Class.forName(cls);
                Object app = c.getMethod("currentApplication").invoke(null);
                if (app instanceof android.content.Context) return (android.content.Context) app;
            } catch (Throwable ignored) {
                // 试下一个来源
            }
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object ctx = at.getMethod("getSystemContext").invoke(thread);
                if (ctx instanceof android.content.Context) return (android.content.Context) ctx;
            }
        } catch (Throwable ignored) {
            // 没有 Context 可用 (理论不可达): 调用方按 null 处理
        }
        return null;
    }

    private static Config loadViaPrefs(XSharedPreferences xp) {
        try {
            xp.reload();
            boolean enabled = xp.getBoolean(K_ENABLED, false);
            String payload = xp.getString(K_ACTIVE_PAYLOAD, null);
            if (payload == null) payload = xp.getString(K_DATASET, null); // 未迁移的旧库
            return new Config(enabled, payload, xp.getString(K_ACTIVE_ID, null),
                    xp.getLong(K_RECORDED_AT, 0L), 0, xp.getLong(K_ANCHOR, 0L), false);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "prefs config read failed: " + t);
            de.robv.android.xposed.XposedBridge.log("[fakeloc] config load failed: " + t);
            return new Config(false, null, null, 0L, 0, 0L, false);
        }
    }
}
