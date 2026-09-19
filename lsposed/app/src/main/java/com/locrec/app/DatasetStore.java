package com.locrec.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 多地点数据集库。
 *
 * 分层理由: 被 hook 的目标进程只能用 XSharedPreferences 读 prefs(XML), 不能读模块私有文件,
 * 所以"选中项"必须镜进 prefs; 但把每一次实录都塞进 prefs 会让每个被注入的 App 都去解析
 * 整份 XML。故: 全量数据集落 App 私有外部目录, prefs 只放索引 + 当前选中项的镜像。
 *
 * 目录: getExternalFilesDir(null)/datasets/<id>.json + index.json + active.json(镜像, 供 adb 直读)
 */
public final class DatasetStore {

    public static final String DIR = "datasets";

    public static final class Entry {
        public final String id;
        public final String name;
        public final long recordedAt;
        public final double lat, lon;
        public final int fixes, scans, nmea;
        /** 用户备注, 可空 */
        public final String note;

        Entry(String id, String name, long recordedAt, double lat, double lon,
              int fixes, int scans, int nmea) {
            this(id, name, recordedAt, lat, lon, fixes, scans, nmea, null);
        }

        Entry(String id, String name, long recordedAt, double lat, double lon,
              int fixes, int scans, int nmea, String note) {
            this.id = id; this.name = name; this.recordedAt = recordedAt;
            this.lat = lat; this.lon = lon;
            this.fixes = fixes; this.scans = scans; this.nmea = nmea;
            this.note = (note == null || note.trim().isEmpty()) ? null : note.trim();
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject().put("id", id).put("name", name).put("at", recordedAt)
                    .put("la", numOrNull(lat)).put("lo", numOrNull(lon))
                    .put("fx", fixes).put("sc", scans).put("nm", nmea);
            if (note != null) o.put("note", note);
            return o;
        }

        /** NaN/Inf 不是合法 JSON 数字 (org.json 会抛 Forbidden numeric value), 用 null 表示"没有坐标"。 */
        private static Object numOrNull(double v) {
            return (Double.isNaN(v) || Double.isInfinite(v)) ? JSONObject.NULL : (Object) v;
        }

        static Entry from(JSONObject o) {
            return new Entry(o.optString("id"), o.optString("name"), o.optLong("at"),
                    o.optDouble("la"), o.optDouble("lo"),
                    o.optInt("fx"), o.optInt("sc"), o.optInt("nm"),
                    o.optString("note", null));
        }

        /** 列表/状态栏显示用: 名字 + 备注 + 数据量 + 是否有定位。文案片段由调用方按语言传入。 */
        public String label(String noteTag, String warnNoFix, String warnNoWifi) {
            StringBuilder sb = new StringBuilder(name);
            if (note != null && !note.isEmpty()) sb.append("\n    ").append(noteTag).append(note);
            sb.append("\n    ").append(fixes).append(" fix / ").append(scans)
                    .append(" WiFi / ").append(nmea).append(" nmea");
            if (fixes == 0) sb.append("  ").append(warnNoFix);
            else if (scans == 0) sb.append("  ").append(warnNoWifi);
            return sb.toString();
        }
    }

    private DatasetStore() { }

    // ---- 磁盘路径 ----

    private static File dir(File base) {
        File d = new File(base, DIR);
        if (!d.exists() && !d.mkdirs()) {
            android.util.Log.e("fakeloc-store", "mkdir failed: " + d);
        }
        return d;
    }

    /** 数据集全量 JSON 的落盘位置 (导出功能也从这里读源文件)。 */
    public static File fileOf(File base, String id) {
        return new File(dir(base), id + ".json");
    }

    public static File indexFile(File base) { return new File(dir(base), "index.json"); }

    public static File activeFile(File base) { return new File(dir(base), "active.json"); }

    // ---- 索引 ----

    public static List<Entry> list(File base) {
        List<Entry> out = new ArrayList<>();
        File f = indexFile(base);
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONArray(read(f));
            for (int i = 0; i < a.length(); i++) out.add(Entry.from(a.getJSONObject(i)));
        } catch (Exception e) {
            android.util.Log.e("fakeloc-store", "index parse failed", e);
        }
        return out;
    }

    private static void writeIndex(File base, List<Entry> entries) throws IOException, org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Entry e : entries) a.put(e.toJson());
        writeAtomic(indexFile(base), a.toString());
    }

    // ---- 增删选 ----

    /** 保存一次实录为新地点, 并设为当前选中项。返回新条目。 */
    public static Entry add(File base, SharedPreferences p, Dataset d, String name) throws Exception {
        String id = "d" + d.recordedAt;
        String json = d.toJson();
        writeAtomic(fileOf(base, id), json);

        Entry e = new Entry(id, name != null ? name : autoName(d), d.recordedAt,
                centerLat(d), centerLon(d), d.fixes.size(), d.scans.size(), d.nmea.size());

        List<Entry> all = list(base);
        // 同一毫秒重复保存时覆盖旧条目, 不留孤儿行
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) all.remove(i--);
        all.add(0, e);
        writeIndex(base, all);
        select(base, p, id);
        return e;
    }

    /** 无 fix 的录制没有中心点, 用 NaN 表示"无坐标"而不是伪造 0,0。 */
    private static double centerLat(Dataset d) {
        Dataset.Fix f = d.medianFix();
        return f == null ? Double.NaN : f.lat;
    }

    private static double centerLon(Dataset d) {
        Dataset.Fix f = d.medianFix();
        return f == null ? Double.NaN : f.lon;
    }

    /** 切换当前伪装地点: 全量数据进 prefs 镜像, 目标进程据此回放。 */
    public static void select(File base, SharedPreferences p, String id) throws Exception {
        File f = fileOf(base, id);
        if (!f.exists()) throw new IOException("dataset file missing: " + f);
        String json = read(f);
        writeAtomic(activeFile(base), json);
        // K_LIBRARY 镜像: 通道 0 (LSPatch 宿主内直读) 靠 "active_payload + library 双键
        // 同时存在" 判定配置出自本模块 UI (防宿主同名 prefs 劫持), select 是镜像的唯一
        // 写入漏斗 (add/migrate 都经它), 必须同步维护
        String lib = null;
        try {
            File idx = indexFile(base);
            if (idx.exists()) lib = read(idx);
        } catch (Exception e) {
            android.util.Log.w("fakeloc-store", "library mirror read failed: " + e);
        }
        android.content.SharedPreferences.Editor ed = p.edit()
                .putString(Config.K_ACTIVE_ID, id)
                .putString(Config.K_ACTIVE_PAYLOAD, json)
                // 回放锚点: 切地点即重锚。elapsedRealtime 同机全进程同源,
                // 被注入进程与 system_server 读同一值, 回放时间轴才一致。
                .putLong(Config.K_ANCHOR, android.os.SystemClock.elapsedRealtime());
        if (lib != null) ed.putString(Config.K_LIBRARY, lib);
        ed.apply();
    }

    public static void remove(File base, SharedPreferences p, String id) throws Exception {
        List<Entry> all = list(base);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) { all.remove(i); break; }
        }
        //noinspection ResultOfMethodCallIgnored
        fileOf(base, id).delete();
        writeIndex(base, all);
        if (id.equals(p.getString(Config.K_ACTIVE_ID, null))) {
            if (all.isEmpty()) {
                p.edit().remove(Config.K_ACTIVE_ID).remove(Config.K_ACTIVE_PAYLOAD)
                        .remove(Config.K_LIBRARY)
                        .putBoolean(Config.K_ENABLED, false).apply();
                //noinspection ResultOfMethodCallIgnored
                activeFile(base).delete();
            } else {
                select(base, p, all.get(0).id);
            }
        }
    }

    public static Entry activeEntry(File base, SharedPreferences p) {
        String id = p.getString(Config.K_ACTIVE_ID, null);
        if (id == null) return null;
        for (Entry e : list(base)) if (e.id.equals(id)) return e;
        return null;
    }

    /** 更新备注 (空串清除)。不改动数据集本体, 只改索引。 */
    public static void updateNote(File base, String id, String note) throws Exception {
        List<Entry> all = list(base);
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (!e.id.equals(id)) continue;
            all.set(i, new Entry(e.id, e.name, e.recordedAt, e.lat, e.lon,
                    e.fixes, e.scans, e.nmea, note));
            writeIndex(base, all);
            return;
        }
        throw new IOException("entry not found: " + id);
    }

    /** 重命名显示名 (不改 id)。 */
    public static void updateName(File base, String id, String name) throws Exception {
        List<Entry> all = list(base);
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            if (!e.id.equals(id)) continue;
            String n = (name == null || name.trim().isEmpty()) ? e.name : name.trim();
            all.set(i, new Entry(e.id, n, e.recordedAt, e.lat, e.lon,
                    e.fixes, e.scans, e.nmea, e.note));
            writeIndex(base, all);
            return;
        }
        throw new IOException("entry not found: " + id);
    }

    /**
     * 旧版单数据集(K_DATASET)迁移进库: 只搬一次, 迁移后删除旧键避免两份真相。
     */
    public static void migrateLegacy(File base, SharedPreferences p) {
        String legacy = p.getString(Config.K_DATASET, null);
        if (legacy == null) return;
        try {
            if (p.getString(Config.K_ACTIVE_PAYLOAD, null) == null) {
                Dataset d = Dataset.parse(legacy);
                String id = "d" + d.recordedAt;
                writeAtomic(fileOf(base, id), legacy);
                List<Entry> all = list(base);
                all.add(0, new Entry(id, autoName(d), d.recordedAt, centerLat(d), centerLon(d),
                        d.fixes.size(), d.scans.size(), d.nmea.size()));
                writeIndex(base, all);
                select(base, p, id);
                android.util.Log.i("fakeloc-store", "legacy dataset migrated as " + id);
            }
            p.edit().remove(Config.K_DATASET).remove(Config.K_RECORDED_AT).apply();
        } catch (Exception e) {
            android.util.Log.e("fakeloc-store", "legacy migrate failed", e);
        }
    }

    /** 地点名: 时间 + 坐标 + 数据量 (持久化数据, 保持语言中立)。 */
    public static String autoName(Dataset d) {
        String when = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(d.recordedAt));
        if (d.fixes.isEmpty()) return when + " · 0fix/0AP";
        Dataset.Fix mf = d.medianFix();
        return String.format(Locale.US, "%s · %.5f,%.5f · %dfix/%dAP",
                when, mf.lat, mf.lon, d.fixes.size(), d.scans.size());
    }

    // ---- IO ----

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 先写临时文件再改名: 断电/被杀不会留下半个索引或半个数据集。 */
    private static void writeAtomic(File f, String content) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(content.getBytes(StandardCharsets.UTF_8));
            o.flush();
            o.getFD().sync();
        }
        if (f.exists() && !f.delete()) {
            android.util.Log.w("fakeloc-store", "old file delete failed: " + f);
        }
        if (!tmp.renameTo(f)) {
            // 少数文件系统上 rename 覆盖不可靠: 退回复制。
            // 显式 UTF-8 (BUG-21): FileWriter 用平台默认字符集, 换设备/ROM 会写坏非 ASCII 内容
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(content.getBytes(StandardCharsets.UTF_8));
                o.flush();
                o.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
