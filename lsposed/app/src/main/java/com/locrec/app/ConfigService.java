package com.locrec.app;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

/**
 * 跨进程配置通道之二: exported Binder 服务 (ConfigProvider 的可见性免疫替代通道)。
 *
 * 为什么有了 Provider 还要这个: Android 11+ 包可见性会拦 "按 authority 解析 provider" ——
 * 作用域 App 看不见未声明 <queries> 的模块包, resolveContentProvider 直接失败
 * ("Unknown authority"), LSPosed 的自动可见性授权在部分 ROM/LSPosed 组合上没生效
 * (Android 16 实测)。而包可见性**不限制显式 ComponentName 的 bindService** ——
 * 这是平台明文保证的跨应用直连通道, 不经过任何解析/查询, 因此在这类 ROM 上依然可达。
 *
 * 读取链: Provider (首选, 老路径) → 本服务 (可见性受限 ROM) → XSharedPreferences (死路, 兜底)。
 *
 * 协议与 ConfigProvider.meta/chunk 同语义 (onTransact 手写, 不引 AIDL):
 *   TX_META  → Bundle{enabled,activeId,recordedAt,anchor,uid,len}
 *   TX_CHUNK → String data (调用方先 writeInt(off))
 * 暴露面与 Provider 一致: 只提供"当前选中地点", 无调用方校验 (见 notes.md 已知取舍)。
 */
public final class ConfigService extends Service {

    public static final String ACTION = "com.locrec.app.CONFIG";
    /** 与 ConfigProvider.CHUNK 保持一致 */
    public static final int CHUNK = 200_000;
    /** 客户端 transact 动作码 */
    public static final int TX_META = 1;
    public static final int TX_CHUNK = 2;

    private static final String TAG = "fakeloc-service";

    private final IBinder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            try {
                if (code == TX_META) {
                    reply.writeNoException();
                    reply.writeBundle(metaBundle());
                    return true;
                }
                if (code == TX_CHUNK) {
                    data.enforceInterface(ACTION);
                    int off = data.readInt();
                    reply.writeNoException();
                    reply.writeString(chunkAt(off));
                    return true;
                }
            } catch (Throwable t) {
                // 服务端异常不往调用方抛 (跨进程后就是 DeadObject), 记录后走空应答
                android.util.Log.e(TAG, "onTransact failed: " + t);
                reply.writeException(new IllegalStateException(String.valueOf(t)));
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Config.PREFS, MODE_PRIVATE);
    }

    private android.os.Bundle metaBundle() {
        SharedPreferences p = prefs();
        String payload = p.getString(Config.K_ACTIVE_PAYLOAD, null);
        android.os.Bundle out = new android.os.Bundle();
        out.putInt("uid", Process.myUid());
        out.putBoolean("enabled", p.getBoolean(Config.K_ENABLED, false));
        out.putString("activeId", p.getString(Config.K_ACTIVE_ID, null));
        out.putLong("recordedAt", p.getLong(Config.K_RECORDED_AT, 0L));
        out.putLong("anchor", p.getLong(Config.K_ANCHOR, 0L));
        out.putInt("len", payload == null ? 0 : payload.length());
        return out;
    }

    private String chunkAt(int off) {
        String full = prefs().getString(Config.K_ACTIVE_PAYLOAD, null);
        if (full == null || off >= full.length()) return "";
        return full.substring(off, Math.min(full.length(), off + CHUNK));
    }
}
