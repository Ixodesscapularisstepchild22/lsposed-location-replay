package com.locrec.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 录制 / 备注 / 切换 地点。
 * 流程: 实录 → 完成弹窗可写备注 → 列表选中 → 开伪装。
 * 切换地点/开关伪装都是热生效(≤3s), 不需要重启设备或强杀目标 App。
 */
public class MainActivity extends Activity {

    private static final int C_BG = 0xFF12141A;
    private static final int C_CARD = 0xFF1C1F28;
    private static final int C_ACCENT = 0xFF3DDC84;
    private static final int C_WARN = 0xFFFFB020;
    private static final int C_DANGER = 0xFFFF5C5C;
    private static final int C_TEXT = 0xFFE8EAED;
    private static final int C_DIM = 0xFF9AA0A6;

    private TextView status;
    private TextView progressLabel;
    private ProgressBar progressBar;
    private LinearLayout progressWrap;
    private Button recordBtn;
    private RadioGroup places;
    private Switch spoof;
    private SharedPreferences prefs;
    private boolean recording = false;
    private BroadcastReceiver recRx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        datasetBase();
        buildUi();
        refreshAll();
        maybeAutoRecord();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerRecRx();
        // BUG-12: 页面离开期间 receiver 已注销, DONE 广播会错过 —— 回前台与服务状态对账
        if (RecorderService.running) {
            // 服务还在录 (如录制中切走再回来): 恢复录制中的 UI
            recording = true;
            styleRecordBtn(true);
            recordBtn.setText(R.string.btn_recording);
            progressWrap.setVisibility(View.VISIBLE);
        } else if (recording) {
            // 服务已结束但 UI 还停在录制中 (错过了 DONE): 按结束处理
            finishRecordingUi();
        }
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recRx != null) {
            try { unregisterReceiver(recRx); } catch (Exception ignored) {}
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.app_sub);
        sub.setTextSize(13);
        sub.setTextColor(C_DIM);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // 伪装开关卡片
        LinearLayout spoofCard = card();
        spoof = new Switch(this);
        spoof.setText(R.string.enable_spoof);
        spoof.setTextColor(C_TEXT);
        spoof.setTextSize(16);
        spoof.setChecked(prefs.getBoolean(Config.K_ENABLED, false));
        spoof.setOnCheckedChangeListener((b, on) -> {
            if (on && prefs.getString(Config.K_ACTIVE_PAYLOAD, null) == null) {
                spoof.setChecked(false);
                toast(R.string.toast_no_place);
                return;
            }
            prefs.edit().putBoolean(Config.K_ENABLED, on).apply();
            renderStatus();
        });
        spoofCard.addView(spoof);
        root.addView(spoofCard, lpMatch());

        // 录制区
        LinearLayout recCard = card();
        TextView recTitle = new TextView(this);
        recTitle.setText(R.string.section_record);
        recTitle.setTextColor(C_ACCENT);
        recTitle.setTextSize(13);
        recTitle.setTypeface(Typeface.DEFAULT_BOLD);
        recCard.addView(recTitle);

        recordBtn = new Button(this);
        recordBtn.setText(R.string.btn_record);
        recordBtn.setTextSize(16);
        recordBtn.setAllCaps(false);
        styleRecordBtn(false);
        recordBtn.setOnClickListener(v -> {
            if (recording) {
                toast(R.string.toast_recording_keep_screen);
                return;
            }
            if (!hasFinePerm()) { requestPerm(); return; }
            startRecording(RecorderService.RECORD_MS);
        });
        recCard.addView(recordBtn, lpMatch());

        progressWrap = new LinearLayout(this);
        progressWrap.setOrientation(LinearLayout.VERTICAL);
        progressWrap.setPadding(0, dp(12), 0, 0);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressWrap.addView(progressBar, lpMatch());
        progressLabel = new TextView(this);
        progressLabel.setTextColor(C_DIM);
        progressLabel.setTextSize(13);
        progressLabel.setPadding(0, dp(6), 0, 0);
        progressWrap.addView(progressLabel);
        progressWrap.setVisibility(View.GONE);
        recCard.addView(progressWrap);

        TextView hint = new TextView(this);
        hint.setText(R.string.hint_keep_screen);
        hint.setTextColor(C_DIM);
        hint.setTextSize(12);
        hint.setPadding(0, dp(10), 0, 0);
        recCard.addView(hint);
        root.addView(recCard, lpMatch());

        // 状态卡
        LinearLayout stCard = card();
        TextView stTitle = new TextView(this);
        stTitle.setText(R.string.section_status);
        stTitle.setTextColor(C_WARN);
        stTitle.setTextSize(13);
        stTitle.setTypeface(Typeface.DEFAULT_BOLD);
        stCard.addView(stTitle);
        status = new TextView(this);
        status.setTextColor(C_TEXT);
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, 0);
        stCard.addView(status);
        root.addView(stCard, lpMatch());

        // 地点列表
        LinearLayout listCard = card();
        TextView listTitle = new TextView(this);
        listTitle.setText(R.string.section_places);
        listTitle.setTextColor(C_ACCENT);
        listTitle.setTextSize(13);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listCard.addView(listTitle);

        places = new RadioGroup(this);
        places.setOrientation(LinearLayout.VERTICAL);
        places.setPadding(0, dp(8), 0, 0);
        listCard.addView(places);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        Button noteBtn = smallBtn(getString(R.string.btn_note), C_ACCENT);
        noteBtn.setOnClickListener(v -> editNoteForSelected());
        Button renameBtn = smallBtn(getString(R.string.btn_rename), C_WARN);
        renameBtn.setOnClickListener(v -> renameForSelected());
        Button delBtn = smallBtn(getString(R.string.btn_delete), C_DANGER);
        delBtn.setOnClickListener(v -> confirmDelete());
        row.addView(noteBtn, lpWeight());
        row.addView(renameBtn, lpWeight());
        row.addView(delBtn, lpWeight());
        listCard.addView(row);

        // 导入/导出行: SAF 原生文件选择 (ACTION_CREATE_DOCUMENT / OPEN_DOCUMENT), 无需存储权限
        LinearLayout ioRow = new LinearLayout(this);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        ioRow.setPadding(0, dp(8), 0, 0);
        Button expBtn = smallBtn(getString(R.string.btn_export), C_ACCENT);
        expBtn.setOnClickListener(v -> startExport());
        Button impBtn = smallBtn(getString(R.string.btn_import), C_DIM);
        impBtn.setOnClickListener(v -> startImport());
        ioRow.addView(expBtn, lpWeight());
        ioRow.addView(impBtn, lpWeight());
        listCard.addView(ioRow);
        root.addView(listCard, lpMatch());

        setContentView(scroll);
    }

    private void styleRecordBtn(boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(on ? C_DANGER : C_ACCENT);
        recordBtn.setBackground(d);
        recordBtn.setTextColor(on ? Color.WHITE : 0xFF0B1A10);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(14));
        d.setColor(C_CARD);
        c.setBackground(d);
        LinearLayout.LayoutParams p = lpMatch();
        p.topMargin = dp(12);
        c.setLayoutParams(p);
        return c;
    }

    private Button smallBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(0, dp(8), 0, dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setColor(color);
        b.setBackground(d);
        b.setTextColor(Color.WHITE);
        return b;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private File datasetBase() {
        File base = getExternalFilesDir(null);
        if (base != null) DatasetStore.migrateLegacy(base, prefs);
        return base;
    }

    private void maybeAutoRecord() {
        long ms = getIntent() != null
                ? getIntent().getLongExtra(RecorderService.EXTRA_DURATION_MS, 0) : 0;
        if (ms <= 0 || !hasFinePerm()) return;
        startRecording(ms);
    }

    /** 等待 hook 进程复核开关的时长: ensureDs 复核节流 3s, 留足余量 */
    private static final long SPOOF_SETTLE_MS = 4000;
    /** 本次录制是否由我们自动关掉了伪装(结束后要还原) */
    private boolean autoSpoofOff = false;

    /**
     * 实录 = 录当前真实环境, 与「启用伪装」语义互斥。
     *
     * 伪装开着时录制, 系统层 hook 会把 WiFi 整包换成 buildScanResults() 重建的伪装批次、
     * 把坐标 rewriteSpatial 成伪装地点, 于是"新地点"录出来还是老地点
     * (实测指纹: 扫描批次 ts 恒等于 录制起点+offset, 即读取时刻而非芯片扫描时刻)。
     * 所以录制前强制关伪装, 并等 hook 进程复核生效后再真正开录。
     */
    private void startRecording(long ms) {
        if (prefs.getBoolean(Config.K_ENABLED, false)) {
            autoSpoofOff = true;
            prefs.edit().putBoolean(Config.K_ENABLED, false).apply();
            if (spoof != null) spoof.setChecked(false);
            renderStatus();
            toast(R.string.toast_spoof_auto_off);
            recording = true;
            styleRecordBtn(true);
            recordBtn.setText(R.string.btn_recording_preparing);
            recordBtn.postDelayed(() -> beginRecording(ms), SPOOF_SETTLE_MS);
            return;
        }
        beginRecording(ms);
    }

    private void beginRecording(long ms) {
        recording = true;
        styleRecordBtn(true);
        recordBtn.setText(R.string.btn_recording);
        progressBar.setProgress(0);
        progressLabel.setText(getString(R.string.progress_simple, "0:00", fmtMs(ms)));
        progressWrap.setVisibility(View.VISIBLE);
        startForegroundService(new Intent(this, RecorderService.class)
                .putExtra(RecorderService.EXTRA_DURATION_MS, ms));
        keepScreenOnFor(ms);
    }

    private void registerRecRx() {
        if (recRx != null) {
            try { unregisterReceiver(recRx); } catch (Exception ignored) {}
        }
        recRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null) return;
                if (RecorderService.ACTION_PROGRESS.equals(i.getAction())) {
                    long el = i.getLongExtra(RecorderService.EXTRA_ELAPSED_MS, 0);
                    long total = i.getLongExtra(RecorderService.EXTRA_TOTAL_MS, 1);
                    int pct = (int) Math.min(100, el * 100 / Math.max(total, 1));
                    progressBar.setProgress(pct);
                    progressLabel.setText(getString(R.string.progress_fmt,
                            fmtMs(el), fmtMs(total),
                            i.getIntExtra(RecorderService.EXTRA_FIXES, 0),
                            i.getIntExtra(RecorderService.EXTRA_SCANS, 0)));
                    recording = true;
                    styleRecordBtn(true);
                    recordBtn.setText(R.string.btn_recording);
                    progressWrap.setVisibility(View.VISIBLE);
                } else if (RecorderService.ACTION_DONE.equals(i.getAction())) {
                    finishRecordingUi();
                    boolean saved = i.getBooleanExtra(RecorderService.EXTRA_SAVED, false);
                    int fixes = i.getIntExtra(RecorderService.EXTRA_FIXES, 0);
                    int gps = i.getIntExtra(RecorderService.EXTRA_GPS, 0);
                    int scans = i.getIntExtra(RecorderService.EXTRA_SCANS, 0);
                    int cells = i.getIntExtra(RecorderService.EXTRA_CELLS, 0);
                    int gnss = i.getIntExtra(RecorderService.EXTRA_GNSS, 0);
                    int nmea = i.getIntExtra(RecorderService.EXTRA_NMEA, 0);
                    if (saved) {
                        showDoneDialog(fixes, gps, scans, cells, gnss, nmea);
                    } else {
                        String err = i.getStringExtra("error");
                        toast(err != null
                                ? getString(R.string.toast_save_failed) + "\n" + err
                                : getString(R.string.toast_save_failed));
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(RecorderService.ACTION_PROGRESS);
        f.addAction(RecorderService.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recRx, f);
        }
    }

    /**
     * 录制结束的 UI 复位 (DONE 广播与 onResume 对账共用)。
     * 若本次录制前是我们自动关掉的伪装, 这里负责还原开启。
     */
    private void finishRecordingUi() {
        recording = false;
        styleRecordBtn(false);
        recordBtn.setText(R.string.btn_record);
        progressWrap.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (autoSpoofOff) {
            autoSpoofOff = false;
            prefs.edit().putBoolean(Config.K_ENABLED, true).apply();
            if (spoof != null) spoof.setChecked(true);
            toast(R.string.toast_spoof_restored);
        }
        refreshAll();
    }

    private void showDoneDialog(int fixes, int gps, int scans, int cells, int gnss, int nmea) {
        String msg = getString(R.string.dlg_done_msg, fixes, gps, scans, cells, gnss, nmea);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_done_title)
                .setMessage(msg)
                .setPositiveButton(R.string.dlg_btn_write_note, (d, w) -> editNoteForSelected())
                .setNegativeButton(R.string.dlg_btn_done, null)
                .show();
    }

    private void keepScreenOnFor(long ms) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> { if (!isFinishing()) getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); },
                ms + 10_000L);
    }

    private void onPlaceSelected(String id) {
        File base = datasetBase();
        if (base == null) return;
        try {
            DatasetStore.select(base, prefs, id);
        } catch (Exception e) {
            toast(getString(R.string.toast_switch_failed, e.getMessage()));
        }
        renderStatus();
    }

    private String selectedId() {
        String id = prefs.getString(Config.K_ACTIVE_ID, null);
        return id;
    }

    private void editNoteForSelected() {
        String id = selectedId();
        if (id == null) { toast(R.string.toast_pick_place_first); return; }
        File base = datasetBase();
        DatasetStore.Entry e = null;
        if (base != null) e = DatasetStore.activeEntry(base, prefs);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.hint_note);
        if (e != null && e.note != null) input.setText(e.note);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_note_title)
                .setView(box)
                .setPositiveButton(R.string.dlg_btn_save, (d, w) -> {
                    try {
                        DatasetStore.updateNote(datasetBase(), id, input.getText().toString());
                        toast(R.string.toast_note_saved);
                        refreshAll();
                    } catch (Exception ex) {
                        toast(getString(R.string.toast_note_save_failed, ex.getMessage()));
                    }
                })
                .setNeutralButton(R.string.dlg_btn_clear, (d, w) -> {
                    try {
                        DatasetStore.updateNote(datasetBase(), id, "");
                        toast(R.string.toast_note_cleared);
                        refreshAll();
                    } catch (Exception ex) {
                        toast(ex.getMessage());
                    }
                })
                .setNegativeButton(R.string.dlg_btn_cancel, null)
                .show();
    }

    private void renameForSelected() {
        String id = selectedId();
        if (id == null) { toast(R.string.toast_pick_place_first); return; }
        File base = datasetBase();
        DatasetStore.Entry e = base == null ? null : DatasetStore.activeEntry(base, prefs);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (e != null) input.setText(e.name);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_rename_title)
                .setView(box)
                .setPositiveButton(R.string.dlg_btn_save, (d, w) -> {
                    try {
                        DatasetStore.updateName(datasetBase(), id, input.getText().toString());
                        refreshAll();
                    } catch (Exception ex) {
                        toast(getString(R.string.toast_rename_failed, ex.getMessage()));
                    }
                })
                .setNegativeButton(R.string.dlg_btn_cancel, null)
                .show();
    }

    private void confirmDelete() {
        String id = selectedId();
        if (id == null) { toast(R.string.toast_no_place_selected); return; }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_delete_title)
                .setMessage(getString(R.string.dlg_delete_msg, id))
                .setNegativeButton(R.string.dlg_btn_cancel, null)
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    try {
                        DatasetStore.remove(datasetBase(), prefs, id);
                        toast(R.string.toast_deleted);
                    } catch (Exception e) {
                        toast(getString(R.string.toast_delete_failed, e.getMessage()));
                    }
                    refreshAll();
                })
                .show();
    }

    private void refreshAll() {
        renderStatus();
        renderPlaces();
    }

    private void renderStatus() {
        File base = datasetBase();
        String activeId = prefs.getString(Config.K_ACTIVE_ID, null);
        List<DatasetStore.Entry> all = base == null
                ? java.util.Collections.<DatasetStore.Entry>emptyList() : DatasetStore.list(base);

        StringBuilder sb = new StringBuilder();
        sb.append(getString(prefs.getBoolean(Config.K_ENABLED, false)
                ? R.string.status_spoof_on : R.string.status_spoof_off));
        if (activeId == null) {
            sb.append(getString(R.string.status_no_place));
        } else {
            String payload = prefs.getString(Config.K_ACTIVE_PAYLOAD, null);
            if (payload == null) {
                sb.append(getString(R.string.status_payload_missing, activeId));
            } else {
                try {
                    Dataset d = Dataset.parse(payload);
                    long ageMin = (System.currentTimeMillis() - d.recordedAt) / 60000;
                    DatasetStore.Entry ae = DatasetStore.activeEntry(base, prefs);
                    if (ae != null && ae.note != null) {
                        sb.append(getString(R.string.status_note_fmt, ae.note));
                    }
                    sb.append("\n").append(d.summary())
                            .append(getString(R.string.status_recorded_ago, ageMin));
                    if (d.fixes.isEmpty()) sb.append(getString(R.string.status_no_fix));
                    else if (d.scans.isEmpty()) sb.append(getString(R.string.status_no_wifi));
                } catch (Exception e) {
                    sb.append(getString(R.string.status_parse_failed));
                }
            }
        }
        sb.append(getString(R.string.status_place_count, all.size()));
        status.setText(sb.toString());
    }

    private void renderPlaces() {
        File base = datasetBase();
        String activeId = prefs.getString(Config.K_ACTIVE_ID, null);
        List<DatasetStore.Entry> all = base == null
                ? java.util.Collections.<DatasetStore.Entry>emptyList() : DatasetStore.list(base);

        places.setOnCheckedChangeListener(null);
        places.removeAllViews();
        for (DatasetStore.Entry e : all) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(e.id);
            rb.setText(e.label(getString(R.string.tag_note),
                    getString(R.string.warn_no_fix), getString(R.string.warn_no_wifi)));
            rb.setTextColor(C_TEXT);
            rb.setTextSize(14);
            rb.setPadding(dp(4), dp(10), dp(4), dp(10));
            rb.setChecked(e.id.equals(activeId));
            places.addView(rb);
        }
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.places_empty);
            empty.setTextColor(C_DIM);
            empty.setPadding(0, dp(8), 0, 0);
            places.addView(empty);
        }
        places.setOnCheckedChangeListener((g, checkedId) -> {
            View v = g.findViewById(checkedId);
            if (v != null && v.getTag() instanceof String) onPlaceSelected((String) v.getTag());
        });
    }

    private boolean hasFinePerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerm() {
        java.util.ArrayList<String> ps = new java.util.ArrayList<>();
        ps.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ps.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            ps.add("android.permission.NEARBY_WIFI_DEVICES");
            ps.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(ps.toArray(new String[0]), 1);
    }

    private static String fmtMs(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---- 数据集导入 / 导出 (SAF) ----

    private static final int REQ_EXPORT = 41;
    private static final int REQ_IMPORT = 42;

    /** 导出当前选中的地点: 全量数据集 JSON 落到用户选择的任意位置。 */
    private void startExport() {
        File base = datasetBase();
        DatasetStore.Entry e = base == null ? null : DatasetStore.activeEntry(base, prefs);
        if (e == null) {
            toast(R.string.toast_no_place_to_export);
            return;
        }
        exportId = e.id;
        String fname = "locrec-" + e.name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".json";
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/json");
        it.putExtra(Intent.EXTRA_TITLE, fname);
        startActivityForResult(it, REQ_EXPORT);
    }

    private void startImport() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        String[] mimes = {"application/json", "text/plain", "application/octet-stream"};
        it.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        startActivityForResult(it, REQ_IMPORT);
    }

    private String exportId;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_EXPORT) {
            doExport(data.getData());
        } else if (requestCode == REQ_IMPORT) {
            doImport(data.getData());
        }
    }

    private void doExport(android.net.Uri uri) {
        File base = datasetBase();
        if (base == null || exportId == null) {
            toast(R.string.toast_export_failed);
            return;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(
                DatasetStore.fileOf(base, exportId));
             java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("openOutputStream null");
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            toast(R.string.toast_export_ok);
        } catch (Exception ex) {
            toast(getString(R.string.toast_export_failed, ex.getMessage()));
        }
    }

    /** 导入上限: 正常数据集 (含 gnss/nmea 全量) 远小于此; 防 SAF 选到超大/损坏文件拖垮内存 */
    private static final long IMPORT_MAX_BYTES = 32L * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService IMPORT_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 静态: Activity 旋转/重建后防重入仍有效 (executor 是静态单线程) */
    private static volatile boolean importing;

    /** 超限专用信号: 走独立的"文件过大"文案, 不落进通用读取失败 */
    private static final class ImportTooLargeException extends RuntimeException {
        ImportTooLargeException(String msg) { super(msg); }
    }

    private void doImport(android.net.Uri uri) {
        if (importing) return; // 正在导入中, 忽略重复点击
        importing = true;
        IMPORT_EXECUTOR.execute(() -> {
            String err = null, ok = null;
            try {
                Long declared = querySize(uri);
                if (declared != null && declared > IMPORT_MAX_BYTES) {
                    throw new ImportTooLargeException(getString(R.string.toast_import_too_large));
                }
                String json;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("openInputStream null");
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    long total = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        total += n;
                        if (total > IMPORT_MAX_BYTES) {
                            throw new ImportTooLargeException(getString(R.string.toast_import_too_large));
                        }
                        bo.write(buf, 0, n);
                    }
                    json = bo.toString("UTF-8");
                }
                Dataset d = Dataset.parse(json); // 解析失败抛 JSONException
                if (d.fixes.isEmpty()) throw new org.json.JSONException("no fixes");
                File base = datasetBase();
                if (base == null) throw new java.io.IOException("external storage unavailable");
                DatasetStore.Entry e = DatasetStore.add(base, prefs, d, null);
                ok = getString(R.string.toast_import_ok, e.name);
            } catch (org.json.JSONException je) {
                // 格式不是本模块的数据集: 明确告知, 不落半条数据 (add 前解析整份 JSON)
                err = getString(R.string.toast_import_failed);
            } catch (ImportTooLargeException tl) {
                err = tl.getMessage();
            } catch (final Throwable t) {
                // Throwable 兜底 (含 OOM Error): importing 必须能复位, 不能让一次失败锁死导入
                err = getString(R.string.toast_import_read_failed, String.valueOf(t));
            }
            final String errMsg = err, okMsg = ok;
            runOnUiThread(() -> {
                importing = false;
                if (okMsg != null) {
                    if (!isFinishing() && !isDestroyed()) {
                        renderPlaces();
                        renderStatus();
                    }
                    toast(okMsg);
                } else {
                    toast(errMsg);
                }
            });
        });
    }

    /** SAF 声明的大小 (无元数据时返回 null, 由流式累计上限兜底)。 */
    private Long querySize(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.getColumnIndex(android.provider.OpenableColumns.SIZE) >= 0) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Throwable ignored) {
            // 查不到大小不算错误, 走流式上限
        }
        return null;
    }
}
