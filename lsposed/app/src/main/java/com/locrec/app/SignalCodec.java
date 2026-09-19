package com.locrec.app;

import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * CellInfo / GnssStatus 序列化与重建 (纯反射, 不依赖隐藏 API 的编译期可见性)。
 * 旧数据集无这些字段时 parse 出空列表, 回放层跳过 —— 向后兼容。
 */
public final class SignalCodec {

    private SignalCodec() {}

    // ---------- CellInfo ----------

    public static JSONObject cellToJson(CellInfo ci) throws Exception {
        JSONObject o = new JSONObject();
        o.put("reg", ci.isRegistered());
        o.put("ts", ci.getTimeStamp());
        o.put("t", ci.getClass().getSimpleName());
        Object id = call(ci, "getCellIdentity");
        Object ss = call(ci, "getCellSignalStrength");
        putId(o, id);
        putSs(o, ss);
        return o;
    }

    public static List<CellInfo> cellsFromJson(JSONArray arr) {
        return cellsFromJson(arr, SystemClock.elapsedRealtimeNanos());
    }

    /** tsNanos: 本批快照的统一时间戳 (由调用方锚定回放轴, 快照内恒定); 默认重载用"此刻"。 */
    public static List<CellInfo> cellsFromJson(JSONArray arr, long tsNanos) {
        List<CellInfo> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            try {
                CellInfo ci = cellFromJson(arr.getJSONObject(i), tsNanos);
                if (ci != null) out.add(ci);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static CellInfo cellFromJson(JSONObject o) throws Exception {
        return cellFromJson(o, SystemClock.elapsedRealtimeNanos());
    }

    public static CellInfo cellFromJson(JSONObject o, long tsNanos) throws Exception {
        String t = o.optString("t", "CellInfoLte");
        Object id = buildIdentity(t, o);
        Object ss = buildSignal(t, o);
        if (id == null || ss == null) return null;

        Object rawCi = newInstance(CellInfo.class.getName().replace(".CellInfo", "." + t));
        if (!(rawCi instanceof CellInfo)) return null;
        CellInfo ci = (CellInfo) rawCi;

        // setCellIdentity / setCellSignalStrength 多为 @hide
        if (!invoke(ci, "setCellIdentity", id)) {
            Field fi = findField(ci.getClass(), "mCellIdentity");
            if (fi != null) { fi.setAccessible(true); fi.set(ci, id); }
        }
        if (!invoke(ci, "setCellSignalStrength", ss)) {
            Field fs = findField(ci.getClass(), "mCellSignalStrength");
            if (fs != null) { fs.setAccessible(true); fs.set(ci, ss); }
        }
        try {
            invoke(ci, "setRegistered", o.optBoolean("reg", false));
        } catch (Throwable ignored) {
        }
        try {
            Field f = CellInfo.class.getDeclaredField("mTimeStamp");
            f.setAccessible(true);
            // 时间戳由调用方锚定 (BUG-34/43): 默认"此刻"; 回放路径传入快照锚定值 ——
            // 真机同帧 CellInfo 即此刻, 且注册态基站在信息未变化时 ts 稳定,
            // 逐调用刷新"此刻"反而构成指纹。录制 "ts" 保留在 JSON 里仅供取证对照。
            f.setLong(ci, tsNanos);
        } catch (Throwable ignored) {
        }
        // 写后复核 (BUG-13): setter/字段写入可能静默失败, 半成品 CellInfo (缺 identity 或
        // strength) 会以畸形数据暴露给宿主 —— 任一读不回来就整条丢弃。
        if (call(ci, "getCellIdentity") == null) return null;
        if (call(ci, "getCellSignalStrength") == null) return null;
        return ci;
    }

    private static void putId(JSONObject o, Object id) throws Exception {
        if (id == null) return;
        String cls = id.getClass().getSimpleName();
        o.put("idt", cls);
        putStr(o, id, "getMccString", "mcc");
        putStr(o, id, "getMncString", "mnc");
        putInt(o, id, "getCi", "ci");
        putInt(o, id, "getPci", "pci");
        putInt(o, id, "getTac", "tac");
        putInt(o, id, "getEarfcn", "earfcn");
        putInt(o, id, "getLac", "lac");
        putInt(o, id, "getCid", "cid");
        putInt(o, id, "getArfcn", "arfcn");
        putInt(o, id, "getBsic", "bsic");
        putInt(o, id, "getPsc", "psc");
        putLong(o, id, "getNci", "nci");
        putInt(o, id, "getNrarfcn", "nrarfcn");
        putInt(o, id, "getNetworkId", "nid");
        putInt(o, id, "getSystemId", "sid");
        putInt(o, id, "getBasestationId", "bid");
        putInt(o, id, "getCpid", "cpid");
        // 旧 API 数字 MCC/MNC
        Integer mcc = (Integer) call(id, "getMcc");
        if (mcc != null && !o.has("mcc")) o.put("mcc", String.valueOf(mcc));
        Integer mnc = (Integer) call(id, "getMnc");
        if (mnc != null && !o.has("mnc")) o.put("mnc", String.valueOf(mnc));
    }

    private static void putSs(JSONObject o, Object ss) throws Exception {
        if (ss == null) return;
        putInt(o, ss, "getRsrp", "rsrp");
        putInt(o, ss, "getRsrq", "rsrq");
        putInt(o, ss, "getRssi", "rss");
        putInt(o, ss, "getCqi", "cqi");
        putInt(o, ss, "getTimingAdvance", "ta");
        putInt(o, ss, "getBitErrorRate", "ber");
        putInt(o, ss, "getDbm", "dbm");
        putInt(o, ss, "getEcio", "ecio");
        putInt(o, ss, "getSsRsrp", "ssRsrp");
        putInt(o, ss, "getSsRsrq", "ssRsrq");
        putInt(o, ss, "getSsSinr", "ssSinr");
    }

    private static Object buildIdentity(String cellType, JSONObject o) {
        String pkg = "android.telephony.";
        if ("CellInfoLte".equals(cellType)) return ctor(
                pkg + "CellIdentityLte",
                new Class[]{String.class, String.class, int.class, int.class, int.class, int.class},
                o.optString("mcc", "0"), o.optString("mnc", "0"),
                o.optInt("ci", CellInfo.UNAVAILABLE), o.optInt("pci", CellInfo.UNAVAILABLE),
                o.optInt("tac", CellInfo.UNAVAILABLE), o.optInt("earfcn", CellInfo.UNAVAILABLE));
        if ("CellInfoGsm".equals(cellType)) return ctor(
                pkg + "CellIdentityGsm",
                new Class[]{String.class, String.class, int.class, int.class, int.class, int.class},
                o.optString("mcc", "0"), o.optString("mnc", "0"),
                o.optInt("lac", CellInfo.UNAVAILABLE), o.optInt("cid", CellInfo.UNAVAILABLE),
                o.optInt("arfcn", CellInfo.UNAVAILABLE), o.optInt("bsic", CellInfo.UNAVAILABLE));
        if ("CellInfoWcdma".equals(cellType)) return ctor(
                pkg + "CellIdentityWcdma",
                new Class[]{String.class, String.class, int.class, int.class, int.class},
                o.optString("mcc", "0"), o.optString("mnc", "0"),
                o.optInt("lac", CellInfo.UNAVAILABLE), o.optInt("cid", CellInfo.UNAVAILABLE),
                o.optInt("psc", CellInfo.UNAVAILABLE));
        if ("CellInfoNr".equals(cellType) && Build.VERSION.SDK_INT >= 29) return ctor(
                pkg + "CellIdentityNr",
                new Class[]{String.class, String.class, long.class, int.class, int.class, int.class, int[].class, int.class, int.class},
                o.optString("mcc", ""), o.optString("mnc", ""),
                (long) o.optLong("nci", Long.MIN_VALUE),
                o.optInt("pci", CellInfo.UNAVAILABLE), o.optInt("tac", CellInfo.UNAVAILABLE),
                o.optInt("nrarfcn", CellInfo.UNAVAILABLE), new int[0],
                parseMcc(o), parseMnc(o));
        if ("CellInfoCdma".equals(cellType)) return ctor(
                pkg + "CellIdentityCdma",
                new Class[]{int.class, int.class, int.class, int.class, int.class},
                o.optInt("nid", CellInfo.UNAVAILABLE), o.optInt("sid", CellInfo.UNAVAILABLE),
                o.optInt("bid", CellInfo.UNAVAILABLE), o.optInt("lat", CellInfo.UNAVAILABLE),
                o.optInt("lon", CellInfo.UNAVAILABLE));
        if ("CellInfoTdscdma".equals(cellType) && Build.VERSION.SDK_INT >= 29) return ctor(
                pkg + "CellIdentityTdscdma",
                new Class[]{String.class, String.class, int.class, int.class, int.class},
                o.optString("mcc", ""), o.optString("mnc", ""),
                o.optInt("lac", CellInfo.UNAVAILABLE), o.optInt("cid", CellInfo.UNAVAILABLE),
                o.optInt("cpid", CellInfo.UNAVAILABLE));
        return null;
    }

    private static Object buildSignal(String cellType, JSONObject o) {
        String pkg = "android.telephony.";
        if ("CellInfoLte".equals(cellType)) return ctor(
                pkg + "CellSignalStrengthLte",
                new Class[]{int.class, int.class, int.class, int.class, int.class},
                o.optInt("rss", CellInfo.UNAVAILABLE), o.optInt("rsrp", CellInfo.UNAVAILABLE),
                o.optInt("rsrq", CellInfo.UNAVAILABLE), o.optInt("ta", CellInfo.UNAVAILABLE),
                o.optInt("cqi", CellInfo.UNAVAILABLE));
        if ("CellInfoGsm".equals(cellType)) return ctor(
                pkg + "CellSignalStrengthGsm",
                new Class[]{int.class, int.class, int.class},
                o.optInt("rss", CellInfo.UNAVAILABLE), o.optInt("ber", CellInfo.UNAVAILABLE),
                o.optInt("ta", CellInfo.UNAVAILABLE));
        if ("CellInfoWcdma".equals(cellType)) return ctor(
                pkg + "CellSignalStrengthWcdma",
                new Class[]{int.class, int.class},
                o.optInt("rss", CellInfo.UNAVAILABLE), o.optInt("ber", CellInfo.UNAVAILABLE));
        if ("CellInfoNr".equals(cellType) && Build.VERSION.SDK_INT >= 29) return ctor(
                pkg + "CellSignalStrengthNr",
                new Class[]{int.class, int.class, int.class, int.class},
                o.optInt("ssRsrp", CellInfo.UNAVAILABLE), o.optInt("ssRsrq", CellInfo.UNAVAILABLE),
                o.optInt("ssSinr", CellInfo.UNAVAILABLE), CellInfo.UNAVAILABLE);
        if ("CellInfoCdma".equals(cellType)) return ctor(
                pkg + "CellSignalStrengthCdma",
                new Class[]{int.class, int.class},
                o.optInt("dbm", CellInfo.UNAVAILABLE), o.optInt("ecio", CellInfo.UNAVAILABLE));
        if ("CellInfoTdscdma".equals(cellType) && Build.VERSION.SDK_INT >= 29) return ctor(
                pkg + "CellSignalStrengthTdscdma",
                new Class[]{int.class, int.class},
                o.optInt("rss", CellInfo.UNAVAILABLE), o.optInt("ber", CellInfo.UNAVAILABLE));
        // 兜底: 无参构造 (部分 ROM 可见)
        return ctor(pkg + "CellSignalStrengthLte", new Class[]{}, (Object[]) null);
    }

    // ---------- GnssStatus ----------

    public static final class Sat {
        public final int svid, constell;
        public final float cn0, az, el;
        public final boolean used, almanac, ephemeris;
        public final boolean hasCf;
        public final float cfHz;

        public Sat(int svid, int constell, float cn0, float az, float el,
                   boolean used, boolean almanac, boolean ephemeris, boolean hasCf, float cfHz) {
            this.svid = svid; this.constell = constell; this.cn0 = cn0;
            this.az = az; this.el = el; this.used = used;
            this.almanac = almanac; this.ephemeris = ephemeris;
            this.hasCf = hasCf; this.cfHz = cfHz;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("sv", svid).put("cn", constell)
                    .put("c0", (double) cn0).put("az", (double) az).put("el", (double) el)
                    .put("u", used).put("al", almanac).put("ep", ephemeris)
                    .put("hc", hasCf).put("cf", (double) cfHz);
        }

        public static Sat fromJson(JSONObject o) {
            return new Sat(o.optInt("sv"), o.optInt("cn"),
                    (float) o.optDouble("c0", 0), (float) o.optDouble("az", 0),
                    (float) o.optDouble("el", 0), o.optBoolean("u"),
                    o.optBoolean("al"), o.optBoolean("ep"),
                    o.optBoolean("hc"), (float) o.optDouble("cf", 0));
        }
    }

    public static List<Sat> gnssToJson(android.location.GnssStatus st) {
        List<Sat> out = new ArrayList<>();
        if (st == null) return out;
        int n = st.getSatelliteCount();
        for (int i = 0; i < n; i++) {
            boolean hasCf = false;
            float cf = 0;
            try {
                hasCf = st.hasCarrierFrequencyHz(i);
                if (hasCf) cf = st.getCarrierFrequencyHz(i);
            } catch (Throwable ignored) {
            }
            out.add(new Sat(
                    st.getSvid(i), st.getConstellationType(i),
                    st.getCn0DbHz(i), st.getAzimuthDegrees(i), st.getElevationDegrees(i),
                    st.usedInFix(i), st.hasAlmanacData(i), st.hasEphemerisData(i),
                    hasCf, cf));
        }
        return out;
    }

    /**
     * 重建 GnssStatus。GnssStatus.Builder 仅 API 30+, 且只有一个 addSatellite ——
     * 签名已用 javap 核实 API 30/35 完全一致 (旧实现调用的 setSatelliteCount/setSvid 等
     * setter 不存在, 必抛 NoSuchMethod, BUG-09):
     *   addSatellite(int svid, int constellationType, float cn0DbHz, float azimuthDegrees,
     *       float elevationDegrees, boolean usedInFix, boolean hasAlmanacData,
     *       boolean hasEphemerisData, boolean hasBasebandCn0DbHz, float basebandCn0DbHz,
     *       boolean hasCarrierFrequencyHz, float carrierFrequencyHz)
     * 注意 boolean 顺序是 almanac 在 ephemeris 之前 (与 getter 相反)。基带 CN0 录制未采集,
     * 恒写 has=false/0.0。API 24-29 无 Builder, 返回 null —— 调用方 (hook 层) 必须
     * 吞掉真实状态, 不放行真星空。
     */
    public static android.location.GnssStatus gnssFromJson(List<Sat> sats) {
        if (sats == null || sats.isEmpty()) return null;
        try {
            Class<?> builderCls = Class.forName("android.location.GnssStatus$Builder");
            Object b = builderCls.getDeclaredConstructor().newInstance();
            Method add = builderCls.getMethod("addSatellite",
                    int.class, int.class, float.class, float.class, float.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, float.class,
                    boolean.class, float.class);
            for (Sat s : sats) {
                add.invoke(b, s.svid, s.constell, s.cn0, s.az, s.el,
                        s.used, s.almanac, s.ephemeris,
                        false, 0f, // baseband CN0 未录制
                        s.hasCf, s.cfHz);
            }
            return (android.location.GnssStatus) builderCls.getMethod("build").invoke(b);
        } catch (Throwable t) {
            // API 24-29 无 Builder / 未来签名变化: 返回 null, 由调用方决定吞回调
            return null;
        }
    }

    // ---------- reflection utils ----------

    private static Object ctor(String cls, Class<?>[] pts, Object... args) {
        try {
            Class<?> c = Class.forName(cls);
            Constructor<?> k = c.getDeclaredConstructor(pts);
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newInstance(String cls) {
        try {
            Class<?> c = Class.forName(cls);
            Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        } catch (Throwable t) {
            // 再试 (CellIdentity, CellSignalStrength) 双参构造
            try {
                Class<?> c = Class.forName(cls);
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    Class<?>[] p = k.getParameterTypes();
                    if (p.length == 2
                            && p[0].getName().contains("CellIdentity")
                            && p[1].getName().contains("CellSignalStrength")) {
                        k.setAccessible(true);
                        Object id = p[0].getDeclaredConstructor().newInstance();
                        Object ss = p[1].getDeclaredConstructor().newInstance();
                        return k.newInstance(id, ss);
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    private static Object call(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = findMethod(o.getClass(), name);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean invoke(Object o, String name, Object arg) {
        try {
            Method m = findMethod(o.getClass(), name);
            if (m == null) return false;
            m.setAccessible(true);
            m.invoke(o, arg);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method findMethod(Class<?> c, String name) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length <= 1) return m;
            }
            c = c.getSuperclass();
        }
        return null;
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

    private static void putStr(JSONObject o, Object src, String getter, String key) throws Exception {
        Object v = call(src, getter);
        if (v instanceof String && !((String) v).isEmpty()) o.put(key, v);
    }

    private static void putInt(JSONObject o, Object src, String getter, String key) throws Exception {
        Object v = call(src, getter);
        if (v instanceof Integer) {
            int iv = (Integer) v;
            if (iv != CellInfo.UNAVAILABLE && iv != Integer.MAX_VALUE) o.put(key, iv);
        }
    }

    private static void putLong(JSONObject o, Object src, String getter, String key) throws Exception {
        Object v = call(src, getter);
        if (v instanceof Long) {
            long lv = (Long) v;
            if (lv != Long.MIN_VALUE && lv != 0) o.put(key, lv);
        }
    }

    private static int parseMcc(JSONObject o) {
        try {
            return Integer.parseInt(o.optString("mcc", "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseMnc(JSONObject o) {
        try {
            return Integer.parseInt(o.optString("mnc", "0"));
        } catch (Exception e) {
            return 0;
        }
    }
}
