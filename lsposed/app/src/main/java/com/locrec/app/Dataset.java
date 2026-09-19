package com.locrec.app;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 实录数据集: 录制期内的定位流 + 环境指纹, 单位为相对录制起点的毫秒偏移 (off)。
 * 回放时把 off 重锚到当前时钟, 保持相对节奏循环。
 */
public final class Dataset {

    public static final class Fix {
        public final long off;
        public final String provider;
        public final double lat, lon, alt;
        public final float acc, spd, brg;
        public final int sats;

        Fix(long off, String provider, double lat, double lon, double alt,
            float acc, float spd, float brg, int sats) {
            this.off = off; this.provider = provider; this.lat = lat; this.lon = lon;
            this.alt = alt; this.acc = acc; this.spd = spd; this.brg = brg; this.sats = sats;
        }
    }

    public static final class Ap {
        public final String bssid, ssid, caps;
        public final int rssi, freq;
        /** ScanResult.timestamp: 该 AP 被看到的开机微秒时刻; 0 = 老数据没有这个字段。
         *  有了它才能判断 Platform 给的是新扫结果还是陈旧缓存 (息屏/省电时 WiFi 扫描会被挂起)。 */
        public final long ts;

        Ap(String bssid, String ssid, String caps, int rssi, int freq) {
            this(bssid, ssid, caps, rssi, freq, 0L);
        }

        Ap(String bssid, String ssid, String caps, int rssi, int freq, long ts) {
            this.bssid = bssid; this.ssid = ssid; this.caps = caps;
            this.rssi = rssi; this.freq = freq; this.ts = ts;
        }
    }

    public static final class Scan {
        public final long off;
        public final List<Ap> aps;

        Scan(long off, List<Ap> aps) { this.off = off; this.aps = aps; }
    }

    /** 一帧 getAllCellInfo 结果 */
    public static final class CellSnap {
        public final long off;
        public final org.json.JSONArray cellsJson;

        CellSnap(long off, org.json.JSONArray cellsJson) {
            this.off = off;
            this.cellsJson = cellsJson;
        }
    }

    /** 一帧 GnssStatus 卫星列表 */
    public static final class GnssSnap {
        public final long off;
        public final List<SignalCodec.Sat> sats;

        GnssSnap(long off, List<SignalCodec.Sat> sats) {
            this.off = off;
            this.sats = sats;
        }
    }

    /**
     * 伪装地点的地址叙事 (可选, 旧数据集为 null): 录制结束时平台 Geocoder 反向编码一次。
     * 供 BDLocation 地址族 (getAddrStr/getCity/...) 改写 —— 否则"坐标在 A 地、地址文案在
     * 真实位置"是服务端可直接交叉的矛盾。与 cells/gnss 同契约: 缺失 = hook 侧清空地址
     * 叙事 (全空 Address), 绝不回落真值。Geocoder 拿不到百度 adcode/cityCode/town, 留空。
     */
    public static final class Addr {
        public final String country, countryCode, province, city, district;
        public final String street, streetNo, describe, line;

        public Addr(String country, String countryCode, String province, String city,
                    String district, String street, String streetNo, String describe, String line) {
            this.country = country; this.countryCode = countryCode;
            this.province = province; this.city = city; this.district = district;
            this.street = street; this.streetNo = streetNo;
            this.describe = describe; this.line = line;
        }

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject()
                    .put("country", country).put("countryCode", countryCode)
                    .put("province", province).put("city", city).put("district", district)
                    .put("street", street).put("streetNo", streetNo)
                    .put("describe", describe).put("line", line);
        }

        /** 全空 = 无有效地址 (Geocoder 无后端/空结果), 解析侧还原为 null。 */
        private static boolean isEmpty(Addr a) {
            return a == null || (
                    (a.line == null || a.line.isEmpty())
                            && (a.city == null || a.city.isEmpty())
                            && (a.district == null || a.district.isEmpty()));
        }

        static Addr fromJson(JSONObject o) {
            Addr a = new Addr(o.optString("country"), o.optString("countryCode"),
                    o.optString("province"), o.optString("city"), o.optString("district"),
                    o.optString("street"), o.optString("streetNo"),
                    o.optString("describe"), o.optString("line"));
            return isEmpty(a) ? null : a;
        }
    }

    /** 一句 NMEA; off = 录制起点毫秒偏移, -1 = 旧数据集无时间轴 (回放走等比例兜底)。 */
    public static final class NmeaLine {
        public final long off;
        public final String text;

        NmeaLine(long off, String text) {
            this.off = off;
            this.text = text;
        }
    }

    /** 地址叙事 (可选, 旧数据集为 null); 缺失时 BDLocation 地址族按"清空"收敛。 */
    public Addr addr;

    public final List<Fix> fixes = new ArrayList<>();
    public final List<Scan> scans = new ArrayList<>();
    public final List<NmeaLine> nmea = new ArrayList<>();
    /** v1.9+: 基站快照 (可选, 旧数据集为空) */
    public final List<CellSnap> cells = new ArrayList<>();
    /** v1.9+: GnssStatus 快照 (可选, 旧数据集为空) */
    public final List<GnssSnap> gnss = new ArrayList<>();
    /** 采集过程诊断: 扫描是否被拒/被节流、GNSS 是否无 fix —— 空数据集必须自证原因 */
    public final List<String> diag = new ArrayList<>();
    public final long durationMs;
    public final long recordedAt;
    /** 数据集坐标基准 (wgs84); 回放写入 BD-09/GCJ-02 契约的 SDK 前由 CoordTransform 换算。 */
    public String cs = "wgs84";

    public Dataset(long durationMs, long recordedAt) {
        this.durationMs = Math.max(durationMs, 1);
        this.recordedAt = recordedAt;
    }

    /**
     * 环形时间轴取点 (代替旧的最近邻 fixAtTime):
     *  2s 窗口内有 GPS fix 直接用之 (保持 GPS 时刻原真); 否则在相邻两 fix 间线性插值。
     *  循环边界 (尾→首) 也是普通一段, 不再产生跳回起点的瞬移。
     */
    public Fix sampleAt(long elapsedMs) {
        if (fixes.isEmpty()) return null;
        if (fixes.size() == 1) return fixes.get(0);
        long total = Math.max(durationMs, 1);
        long e = Math.floorMod(elapsedMs, total);
        Fix gps = nearestGpsWithin(e, 2000);
        if (gps != null) return gps;
        // fixes 按 off 递增 (录制单调追加); 二分找 off <= e 的最后一段
        int lo = 0, hi = fixes.size() - 1, i = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (fixes.get(mid).off <= e) { i = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        Fix a = fixes.get(i);
        Fix b = fixes.get((i + 1) % fixes.size());
        long bOff = (i + 1 == fixes.size()) ? b.off + total : b.off;
        long span = Math.max(bOff - a.off, 1);
        double x = Math.min(Math.max((double) (e - a.off) / span, 0.0), 1.0);
        return interpolate(a, b, x);
    }

    private Fix nearestGpsWithin(long e, long windowMs) {
        Fix best = null;
        long bestD = windowMs;
        for (Fix f : fixes) {
            if (!isGps(f)) continue;
            long d = Math.abs(f.off - e);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    /**
     * 两 fix 间线性插值: 空间/精度/海拔线性; 速度/航向用记录值插值而非位移推导 ——
     * 记录值是真值, 静点数据的米级抖动若折算成位移会得出每秒几十米的假速度。
     * 跨 provider 段 (gps↔network) 不插值 (BUG-28): provider/sats/alt 是同一叙事,
     * 混插会造出 "network 坐标配 GPS 卫星数" 的自相矛盾, 直接取占时更长的端点。
     */
    private static Fix interpolate(Fix a, Fix b, double x) {
        if (a.provider == null || !a.provider.equals(b.provider)) {
            return x < 0.5 ? a : b;
        }
        double lat = a.lat + (b.lat - a.lat) * x;
        double lon = a.lon + (b.lon - a.lon) * x;
        double alt = a.alt + (b.alt - a.alt) * x;
        float acc = (float) (a.acc + (b.acc - a.acc) * x);
        float spd = (float) (a.spd + (b.spd - a.spd) * x);
        float brg = (float) (a.brg + shortestArc(a.brg, b.brg) * x);
        int sats = x < 0.5 ? a.sats : b.sats;
        return new Fix(a.off, a.provider, lat, lon, alt, acc, spd, brg, sats);
    }

    private static double shortestArc(float from, float to) {
        double d = to - from;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    public static boolean isGps(Fix f) {
        return f.provider != null && f.provider.toLowerCase().contains("gps");
    }

    /** 基于已有 fix 生成微抖动副本 (严检会看连续采样是否重合)。 */
    public static Fix shaken(Fix f, double lat, double lon) {
        if (f == null) return null;
        return new Fix(f.off, f.provider, lat, lon, f.alt, f.acc, f.spd, f.brg, f.sats);
    }

    public Location toLocation(Fix f, long elapsedNanosBase) {
        Location l = new Location(f.provider != null ? f.provider : "gps");
        Boolean prev = MainHook.enterMutating();
        try {
            l.setLatitude(f.lat);
            l.setLongitude(f.lon);
            if (f.alt != 0) l.setAltitude(f.alt);
            if (f.acc > 0) l.setAccuracy(f.acc);
            if (f.spd > 0) l.setSpeed(f.spd);
            if (f.brg != 0) l.setBearing(f.brg);
        } finally {
            MainHook.exitMutating(prev);
        }
        long now = System.currentTimeMillis();
        l.setTime(now);
        // 回放点代表"现在"的定位: 事件时间 = 当前时刻。旧实现 now+回放偏移会把
        // elapsedRealtimeNanos 推到未来 (最多超前整个录制周期), 是经典 mock 检测特征。
        l.setElapsedRealtimeNanos(elapsedNanosBase);
        android.os.Bundle b = new android.os.Bundle();
        if (f.sats > 0) b.putInt("satellites", f.sats);
        l.setExtras(b);
        return l;
    }

    /** 全部 NMEA 均带录制偏移时为 true (旧数据集 off=-1 → 回放走等比例兜底)。 */
    public boolean nmeaTimed() {
        if (nmea.isEmpty()) return false;
        for (NmeaLine n : nmea) if (n.off < 0) return false;
        return true;
    }

    /** 回放时间轴上的"当前句": off <= e 的最后一句 (e 落在首句之前则环形取末句)。 */
    public int nmeaIndexAtOrBefore(long elapsedMs) {
        long total = Math.max(durationMs, 1);
        long e = Math.floorMod(elapsedMs, total);
        int lo = 0, hi = nmea.size() - 1, i = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (nmea.get(mid).off <= e) { i = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return i >= 0 ? i : nmea.size() - 1;
    }

    /** 把空间字段写到已有 Location 上 (Parcel/GMS 路径), 保留原 provider/时间。 */
    public void applySpatial(Location l, Fix f) {
        if (l == null || f == null) return;
        Boolean prev = MainHook.enterMutating();
        try {
            l.setLatitude(f.lat);
            l.setLongitude(f.lon);
            // 海拔: 必须覆盖, 否则会漏出「人在这、海拔却像真地点」
            if (isGps(f) && f.alt != 0) {
                l.setAltitude(f.alt);
            } else {
                // network 无有效海拔: 清掉 hasAltitude, 避免沿用原 Location 的真实 alt
                try {
                    java.lang.reflect.Field has = Location.class.getDeclaredField("mHasAltitude");
                    has.setAccessible(true);
                    has.setBoolean(l, false);
                } catch (Throwable ignored) {
                    l.setAltitude(0);
                }
            }
            if (f.acc > 0) l.setAccuracy(f.acc);
            if (f.spd > 0) l.setSpeed(f.spd);
            if (f.brg != 0) l.setBearing(f.brg);
        } finally {
            MainHook.exitMutating(prev);
        }
        if (f.sats > 0) {
            android.os.Bundle b = l.getExtras();
            if (b == null) b = new android.os.Bundle();
            else b = new android.os.Bundle(b);
            b.putInt("satellites", f.sats);
            l.setExtras(b);
        }
    }

    /** 无 fix 的录制 (室内无星) 是合法结果, 返回 null 而不是抛异常。 */
    public Fix medianFix() {
        return fixes.isEmpty() ? null : fixes.get(fixes.size() / 2);
    }

    // ---- 序列化 ----

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 2);
            o.put("durationMs", durationMs);
            o.put("recordedAt", recordedAt);
            o.put("cs", cs);
            JSONArray fx = new JSONArray();
            for (Fix f : fixes) fx.put(new JSONObject()
                    .put("off", f.off).put("p", f.provider)
                    .put("la", f.lat).put("lo", f.lon).put("al", f.alt)
                    .put("ac", (double) f.acc).put("sp", (double) f.spd).put("br", (double) f.brg)
                    .put("st", f.sats));
            o.put("fixes", fx);
            JSONArray sc = new JSONArray();
            for (Scan s : scans) {
                JSONArray ap = new JSONArray();
                for (Ap a : s.aps) ap.put(new JSONObject()
                        .put("b", a.bssid).put("s", a.ssid).put("c", a.caps)
                        .put("r", a.rssi).put("f", a.freq).put("ts", a.ts));
                sc.put(new JSONObject().put("off", s.off).put("aps", ap));
            }
            o.put("scans", sc);
            // NMEA: "nmea" 保持字符串数组 (旧版本解析兼容), 时间轴放平行数组 "nmeaOff"
            // (仅数字, 体积小)。长度一致才写; 旧解析器忽略未知键, 新解析器对旧文件回落等比例。
            JSONArray nm = new JSONArray();
            JSONArray no = new JSONArray();
            boolean timed = !nmea.isEmpty();
            for (NmeaLine n : nmea) {
                nm.put(n.text);
                if (n.off < 0) timed = false; else no.put(n.off);
            }
            o.put("nmea", nm);
            if (timed) o.put("nmeaOff", no);
            JSONArray dg = new JSONArray();
            for (String d : diag) dg.put(d);
            o.put("diag", dg);
            // v2 可选块: 旧解析器忽略未知键, 新解析器旧文件 opt 出 null → 空列表
            JSONArray cl = new JSONArray();
            for (CellSnap c : cells) cl.put(new JSONObject().put("off", c.off).put("list", c.cellsJson));
            o.put("cells", cl);
            JSONArray gn = new JSONArray();
            for (GnssSnap g : gnss) {
                JSONArray sa = new JSONArray();
                for (SignalCodec.Sat s : g.sats) sa.put(s.toJson());
                gn.put(new JSONObject().put("off", g.off).put("sats", sa));
            }
            o.put("gnss", gn);
            // 地址叙事 (可选): null 不写键, 旧版本解析器忽略未知键
            if (addr != null) o.put("addr", addr.toJson());
        } catch (Exception e) {
            throw new IllegalStateException("dataset serialize failed", e);
        }
        return o.toString();
    }

    public static Dataset parse(String json) throws org.json.JSONException {
        JSONObject o = new JSONObject(json);
        Dataset d = new Dataset(o.getLong("durationMs"), o.getLong("recordedAt"));
        d.cs = o.optString("cs", "wgs84");
        JSONArray fx = o.optJSONArray("fixes");
        if (fx != null) for (int i = 0; i < fx.length(); i++) {
            JSONObject f = fx.getJSONObject(i);
            d.fixes.add(new Fix(f.getLong("off"), f.getString("p"),
                    f.getDouble("la"), f.getDouble("lo"), f.getDouble("al"),
                    (float) f.getDouble("ac"), (float) f.getDouble("sp"), (float) f.getDouble("br"),
                    f.optInt("st", 0)));
        }
        JSONArray sc = o.optJSONArray("scans");
        if (sc != null) for (int i = 0; i < sc.length(); i++) {
            JSONObject s = sc.getJSONObject(i);
            List<Ap> aps = new ArrayList<>();
            JSONArray ap = s.optJSONArray("aps");
            if (ap != null) for (int j = 0; j < ap.length(); j++) {
                JSONObject a = ap.getJSONObject(j);
                aps.add(new Ap(a.getString("b"), a.optString("s"), a.optString("c"),
                        a.optInt("r", -100), a.optInt("f", 0), a.optLong("ts", 0L)));
            }
            d.scans.add(new Scan(s.getLong("off"), aps));
        }
        JSONArray nm = o.optJSONArray("nmea");
        if (nm != null) {
            JSONArray no = o.optJSONArray("nmeaOff");
            for (int i = 0; i < nm.length(); i++) {
                d.nmea.add(new NmeaLine(
                        no != null && no.length() == nm.length() ? no.optLong(i, -1) : -1,
                        nm.getString(i)));
            }
        }
        JSONArray dg = o.optJSONArray("diag");
        if (dg != null) for (int i = 0; i < dg.length(); i++) d.diag.add(dg.getString(i));
        JSONArray cl = o.optJSONArray("cells");
        if (cl != null) for (int i = 0; i < cl.length(); i++) {
            JSONObject c = cl.getJSONObject(i);
            d.cells.add(new CellSnap(c.getLong("off"), c.optJSONArray("list")));
        }
        JSONArray gn = o.optJSONArray("gnss");
        if (gn != null) for (int i = 0; i < gn.length(); i++) {
            JSONObject g = gn.getJSONObject(i);
            List<SignalCodec.Sat> sats = new ArrayList<>();
            JSONArray sa = g.optJSONArray("sats");
            if (sa != null) for (int j = 0; j < sa.length(); j++) {
                sats.add(SignalCodec.Sat.fromJson(sa.getJSONObject(j)));
            }
            d.gnss.add(new GnssSnap(g.getLong("off"), sats));
        }
        JSONObject ad = o.optJSONObject("addr");
        if (ad != null) d.addr = Addr.fromJson(ad);
        return d;
    }

    /** 按回放时间取最近一帧基站快照; 无数据返回 null (调用方放行真实值)。 */
    public CellSnap cellAtTime(long elapsedMs) {
        if (cells.isEmpty()) return null;
        long total = Math.max(durationMs, 1);
        long e = elapsedMs % total;
        CellSnap best = cells.get(0);
        long bestD = Math.abs(best.off - e);
        for (int i = 1; i < cells.size(); i++) {
            long d = Math.abs(cells.get(i).off - e);
            if (d < bestD) { best = cells.get(i); bestD = d; }
        }
        return best;
    }

    /** 按回放时间取最近一帧卫星快照。 */
    public GnssSnap gnssAtTime(long elapsedMs) {
        if (gnss.isEmpty()) return null;
        long total = Math.max(durationMs, 1);
        long e = elapsedMs % total;
        GnssSnap best = gnss.get(0);
        long bestD = Math.abs(best.off - e);
        for (int i = 1; i < gnss.size(); i++) {
            long d = Math.abs(gnss.get(i).off - e);
            if (d < bestD) { best = gnss.get(i); bestD = d; }
        }
        return best;
    }

    public String summary() {
        Fix mf = fixes.isEmpty() ? null : medianFix();
        return "fixes=" + fixes.size()
                + " scans=" + scans.size()
                + " nmea=" + nmea.size()
                + " cells=" + cells.size()
                + " gnss=" + gnss.size()
                + " dur=" + (durationMs / 1000) + "s"
                + (mf != null ? " center=" + mf.lat + "," + mf.lon : "");
    }
}
