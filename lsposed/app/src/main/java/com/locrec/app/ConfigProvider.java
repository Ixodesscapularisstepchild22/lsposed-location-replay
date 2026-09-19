package com.locrec.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 跨进程配置通道 (给被注入的目标进程读)。
 *
 * 为什么不用 XSharedPreferences: 它依赖 LSPosed 把模块 prefs 目录 chmod 成其它 uid 可读;
 * 实测这台机器上该机制没生效 —— 目标进程读到的永远是空值(enabled=false, payload=null),
 * 而且不抛异常、无法从返回值区分。ContentProvider 是 Android 原生的跨进程数据通道, 不需要任何
 * 权限花招, 也不受模块重装/inode 变化影响。
 *
 * 大对象: 数据集 JSON 可达数百 KB, Binder 事务上限约 1MB, 所以按块取(size/chunk)。
 * 暴露面: 只提供"当前选中地点"这一份数据(用户要伪装成的位置), 不暴露索引与原始录制,
 *          其它本地 App 理论上也能读到 —— 见 notes.md 的已知取舍。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.locrec.app.config";
    /** 单块大小 (字符): 留足余量避免 Binder 事务超限 */
    public static final int CHUNK = 200_000;

    private static final String TAG = "fakeloc-provider";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(Config.PREFS, android.content.Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            // 本 App 的 uid: system_server 里的系统层 hook 拿不到 Context 解析自家包名,
            // 由 provider(跑在本 App 进程) 自报, 用于把自家录制器从改写里放行。
            out.putInt("uid", android.os.Process.myUid());
            SharedPreferences p = prefs();
            String m = method == null ? "meta" : method;
            // system_server / shell 调用时 getCallingPackage 会抛 SecurityException, 不能拖垮 call()
            String caller = "?";
            try {
                caller = getCallingPackage();
            } catch (Throwable ignored) {
            }
            android.util.Log.i(TAG, "call=" + m + " arg=" + arg + " caller=" + caller);
            switch (m) {
                case "meta":
                    out.putBoolean("enabled", p.getBoolean(Config.K_ENABLED, false));
                    out.putString("activeId", p.getString(Config.K_ACTIVE_ID, null));
                    out.putLong("recordedAt", p.getLong(Config.K_RECORDED_AT, 0L));
                    out.putLong("anchor", p.getLong(Config.K_ANCHOR, 0L));
                    String payload = p.getString(Config.K_ACTIVE_PAYLOAD, null);
                    out.putInt("len", payload == null ? 0 : payload.length());
                    break;
                case "chunk": {
                    String full = p.getString(Config.K_ACTIVE_PAYLOAD, null);
                    int off = arg == null ? 0 : Integer.parseInt(arg);
                    if (full == null || off >= full.length()) {
                        out.putString("data", "");
                    } else {
                        out.putString("data", full.substring(off, Math.min(full.length(), off + CHUNK)));
                    }
                    break;
                }
                case "setEnabled": {
                    // BUG-08: 写操作必须鉴权 —— 只放行模块自身、root(0)、shell(2000)。
                    // 其它普通应用返回 error Bundle (不抛异常, call 的契约是错误走返回值)。
                    // 读方法 (meta/chunk) 保持开放: 那是被注入目标进程的既有读契约, 不在此改动。
                    int callerUid = android.os.Binder.getCallingUid();
                    if (callerUid != android.os.Process.myUid()
                            && callerUid != 0 /* root */ && callerUid != 2000 /* shell */) {
                        android.util.Log.w(TAG, "setEnabled denied for uid=" + callerUid
                                + " caller=" + caller);
                        out.putBoolean("error", true);
                        out.putString("message",
                                "setEnabled: uid " + callerUid + " not allowed (module/root/shell only)");
                        break;
                    }
                    boolean on = arg == null || "1".equals(arg) || "true".equalsIgnoreCase(arg);
                    p.edit().putBoolean(Config.K_ENABLED, on).apply();
                    out.putBoolean("enabled", on);
                    out.putString("activeId", p.getString(Config.K_ACTIVE_ID, null));
                    break;
                }
                default:
                    out.putBoolean("error", true);
                    out.putString("message", "unknown method " + m);
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "call failed", t);
            out.putBoolean("error", true);
            out.putString("message", String.valueOf(t));
        }
        return out;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
