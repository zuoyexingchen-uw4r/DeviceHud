package io.github.zuoyexingchen.devicehud;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.app.Dialog;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // —— 调色板
    private static final int BG_PAGE = 0xFF08080A;
    /** 弹窗面板底色 */
    private static final int BG_PANEL = 0xFF101013;
    private static final int BG_CARD = 0xFF121114;
    private static final int BG_CHIP = 0xFF1A181C;
    private static final int BG_CHIP_ON = 0xFF241A1E;
    private static final int STROKE_CARD = 0x1AFFFFFF;
    private static final int STROKE_CHIP = 0x1FFFFFFF;
    private static final int TEXT_MAIN = 0xFFF2E6EA;
    private static final int TEXT_DIM = 0xFF6B6168;
    private static final int TEXT_SOFT = 0xFFB9AEB4;
    private static final int ACCENT = 0xFFE58BA6;

    /** 方向键每帧 1px，摇杆推满 6px/帧 */
    private static final float STEP_BASE = 1f;
    private static final float STEP_JOY_EXTRA = 5f;

    private float density;
    private int screenW;
    private int screenH;
    private int statusBarPx = 120;

    private HudView preview;
    private LinearLayout cardsBox;
    private TextView stateText;
    private TextView toggleBtn;
    private TextView posText;
    private final List<Runnable> stepperRefreshers = new ArrayList<>();

    private TextView autoBtn;
    private TextView shizukuBtn;
    /** 蓝牙电量（权限 + 轮询） */
    private TextView btBtn;
    /** 实际像素尺寸说明 */
    private TextView sizeText;
    /** 未学习电流方向时的红色提示 */
    private TextView learnWarn;
    private CheckBox negBox;

    /** Shizuku 授权结果回调 */
    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                if (requestCode != ShizukuShell.REQ_CODE) return;
                runOnUiThread(() -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        ensureShellSensorsQuietly();
                        Toast.makeText(this,
                                "已授权 Shizuku，去「管理传感器」勾选占用率项",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show();
                    }
                    syncShizuku();
                });
            };
    private TextView batteryBtn;

    private final Map<String, TextView> cardValues = new HashMap<>();
    private final List<TextView> rateChips = new ArrayList<>();
    private final List<TextView> sampleChips = new ArrayList<>();
    /** 是否走三列布局（横屏 / 平板） */
    private boolean wideLayout = false;
    /** 左列"传感器 → 实时值"行 */
    private final Map<String, TextView> rowValues = new HashMap<>();
    private LinearLayout rowsBox;
    /** 待导出的历史文件（用户选完目标位置后才用得上） */
    private java.io.File pendingHistoryExport;
    private TextView historyStatus;
    private TextView pluginStatus;
    private TextView transferStatus;

    /** 固化下来的整张传感器表 */
    private List<SensorSpec> table = new ArrayList<>();
    private List<Sensor> activeSensors = new ArrayList<>();
    private TextView sensorSummary;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] vec = new float[]{0f, 0f};
    private float stepPx = STEP_BASE;
    private float accX;
    private float accY;
    private boolean moving;
    private int lastRenderedRev = -1;

    // ------------------------------------------------------------------ 循环

    private final Runnable moveLoop = new Runnable() {
        @Override
        public void run() {
            if (vec[0] == 0f && vec[1] == 0f) {
                moving = false;
                return;
            }
            accX += vec[0] * stepPx;
            accY += vec[1] * stepPx;
            int ix = (int) accX;
            int iy = (int) accY;
            if (ix != 0 || iy != 0) {
                HudState.x += ix;
                HudState.y += iy;
                accX -= ix;
                accY -= iy;
                clampHud();
                HudState.bumpPos();
            }
            handler.postDelayed(this, 16L);
        }
    };

    private final Runnable dataLoop = new Runnable() {
        @Override
        public void run() {
            if (HudState.revision != lastRenderedRev) {
                lastRenderedRev = HudState.revision;
                applyLayoutToPreview();
            }
            refreshData();
            // 前台预览降到最高 800ms 一次，避免和 Service 的采样叠加成双倍 IO
            handler.postDelayed(this, Math.max(800L, Math.min(HudState.updateMs, 2000L)));
        }
    };

    private void kickMove() {
        if (!moving) {
            moving = true;
            handler.post(moveLoop);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        // 真实屏幕尺寸：分屏/小窗下 getDisplayMetrics() 给的是窗口尺寸
        screenW = Screen.width(this);
        screenH = Screen.height(this);

        Prefs.loadIntoState(this);
        // 旧版本存的是相册 uri（重装后授权失效），这里补一次私有副本
        migrateLegacyBg();
        table = SensorTable.load(this);
        activeSensors = Sensor.resolve(table, HudState.sensorIds);
        // 旧版本存的 id 可能已不在表里，回退到默认组，避免出现空条
        if (activeSensors.isEmpty()) {
            HudState.sensorIds = new ArrayList<>(SensorTable.DEFAULT_IDS);
            Prefs.setSensors(this, HudState.sensorIds);
            activeSensors = Sensor.resolve(table, HudState.sensorIds);
        }

        getWindow().setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_PAGE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(wrap, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // 底部留出悬浮按钮的高度，否则滚到底会被挡住
        col.setPadding(dp(18), dp(10), dp(18), dp(96));
        // 三列并排需要足够横向空间：窄屏（手机竖屏）硬塞三列会把每列压到 120dp 以下
        wideLayout = screenW / density >= 700f;
        int contentW = Math.min(screenW - dp(24), wideLayout ? dp(1320) : dp(760));
        wrap.addView(col, new LinearLayout.LayoutParams(
                contentW, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            statusBarPx = Math.max(bars.top, dp(24));
            scroll.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        buildHeader(col);
        buildPreview(col);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(wideLayout ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        col.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout left = columnBox();
        buildEditColumn(left);
        LinearLayout mid = columnBox();
        buildPlaceColumn(mid);
        LinearLayout right = columnBox();
        buildAdvancedColumn(right);

        if (wideLayout) {
            columns.addView(left, columnLp(1.02f));
            columns.addView(mid, columnLp(1.16f));
            columns.addView(right, columnLp(1f));
        } else {
            columns.addView(left, columnLp(1f));
            columns.addView(vGap());
            columns.addView(mid, columnLp(1f));
            columns.addView(vGap());
            columns.addView(right, columnLp(1f));
        }

        buildBottomBar(root);

        setContentView(root);
        applyRecentsHidden();
        askNotificationPermission();
        ShellAccess.init(getApplicationContext());
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }

        // 默认就开：有权限且没在跑，直接拉起悬浮窗
        if (!HudState.running && Settings.canDrawOverlays(this)) {
            startHud();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(dataLoop);
        refreshSteppers();
        syncToggle();
        syncSystemState();
        syncShizuku();
        // 后台线程连上桥需要一点时间，稍后再刷一次状态文字
        handler.postDelayed(this::syncShizuku, 1500L);
        // 先读一遍蓝牙再算状态，否则文案会停在"连接 0 台"
        ensureBtSensorsQuietly();
        syncBtState();
        applyRecentsHidden();
        ensureShellSensorsQuietly();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(dataLoop);
        handler.removeCallbacks(moveLoop);
        moving = false;
        vec[0] = 0f;
        vec[1] = 0f;
        Prefs.saveFromState(this);
    }

    // ------------------------------------------------------------------ 头部 / 预览

    private void buildHeader(LinearLayout col) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(2));

        TextView title = new TextView(this);
        title.setText("DEVICE HUD");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTextColor(TEXT_MAIN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.32f);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        stateText = new TextView(this);
        stateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        stateText.setTypeface(Typeface.MONOSPACE);
        stateText.setLetterSpacing(0.14f);
        row.addView(stateText);

        col.addView(row);

        TextView sub = new TextView(this);
        sub.setText("sensor mux \u00B7 chart capsule \u00B7 history csv \u00B7 json plugins");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setTextColor(0xFF3E383D);
        sub.setPadding(0, 0, 0, dp(16));
        col.addView(sub);
    }

    private void buildPreview(LinearLayout col) {
        sectionLabel(col, "PREVIEW");

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(roundRect(0xFF0F0E11, 14f, 0x14FFFFFF));
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));

        preview = new HudView(this);
        preview.setTheme(HudTheme.byIndex(HudState.themeIndex));
        preview.setSensors(activeSensors);
        preview.setInsets(HudState.insetLeft, HudState.insetRight);
        preview.setTextSizeSp(HudState.textSizeSp);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(HudState.barHeightDp)));
        // 点预览直接进全屏：设置页那条永远看不出"和状态栏叠在一起"的效果
        frame.setOnClickListener(v -> FullScreenPreview.show(this));

        col.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        posText = new TextView(this);
        posText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        posText.setTextColor(0xFF3E383D);
        posText.setTypeface(Typeface.MONOSPACE);
        posText.setPadding(dp(2), dp(7), 0, dp(16));
        col.addView(posText);

        cardsBox = new LinearLayout(this);
        cardsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(cardsBox);
        rebuildCards();
    }

    /** 卡片随传感器选择重建，最多铺两列 */
    private void rebuildCards() {
        cardsBox.removeAllViews();
        cardValues.clear();

        LinearLayout row = null;
        for (int i = 0; i < activeSensors.size(); i++) {
            if (i % 2 == 0 || row == null) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(8);
                cardsBox.addView(row, rlp);
            }
            Sensor s = activeSensors.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(makeCard(s), lp);
        }
        cardsBox.addView(spacer(6));
    }

    private View makeCard(Sensor s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackground(roundRect(BG_CARD, 12f, STROKE_CARD));

        TextView t = new TextView(this);
        t.setText((s.label() + " " + s.unit()).toUpperCase(Locale.US));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(TEXT_DIM);
        t.setLetterSpacing(0.1f);
        card.addView(t);

        TextView v = new TextView(this);
        v.setText("--");
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextColor(TEXT_MAIN);
        v.setPadding(0, dp(3), 0, 0);
        card.addView(v);

        cardValues.put(s.id(), v);
        return card;
    }

    // ------------------------------------------------------------------ 传感器选择

    private void buildSensors(LinearLayout col) {
        sectionLabel(col, "EDIT COLUMN");

        col.addView(smallButton("管理传感器 / 单位 / 样式\u2026", v -> openSensorManager()),
                matchWrap());

        // 一行一个"名字 + 实时值 + 加曲线"：这是左列的主内容，
        // 改样式仍然走上面的管理对话框，这里只做"看得见 + 一键加曲线"
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(8);
        col.addView(rowsBox, rlp);
        rebuildRows();

        sensorSummary = new TextView(this);
        sensorSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        sensorSummary.setTextColor(0xFF3E383D);
        sensorSummary.setTypeface(Typeface.MONOSPACE);
        sensorSummary.setPadding(dp(2), dp(8), 0, dp(16));
        col.addView(sensorSummary);

        updateSensorSummary();
    }

    private void openSensorManager() {
        SensorManagerDialog.show(this, new SensorManagerDialog.Host() {
            @Override
            public List<SensorSpec> table() {
                return table;
            }

            @Override
            public void onTableChanged(List<SensorSpec> t) {
                table = t;
                SensorTable.save(MainActivity.this, t);
                applySensors();
            }

            @Override
            public List<String> enabled() {
                return HudState.sensorIds;
            }

            @Override
            public void onEnabledChanged(List<String> ids) {
                HudState.sensorIds = ids;
                Prefs.setSensors(MainActivity.this, ids);
                applySensors();
            }

            @Override
            public void onRebuild() {
                table = SensorTable.rebuild(MainActivity.this);
                applySensors();
            }
        });
    }

    /** 表或启用集合变化后统一收口：重算渲染列表、重建卡片、通知服务 */
    private void applySensors() {
        activeSensors = Sensor.resolve(table, HudState.sensorIds);
        if (preview != null) preview.setSensors(activeSensors);
        rebuildCards();
        rebuildRows();
        updateSensorSummary();
        HudState.bump();
    }

    private void updateSensorSummary() {
        if (sensorSummary == null) return;
        StringBuilder sb = new StringBuilder();
        for (Sensor s : activeSensors) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(s.label());
        }
        sensorSummary.setText(String.format(Locale.US, "%d / %d   %s",
                activeSensors.size(), table.size(), sb));
    }

    // ------------------------------------------------------------------ 刷新频率

    private void buildRate(LinearLayout col) {
        sectionLabel(col, "RATES");

        // —— 采样率：读原始数据的节奏
        TextView sampleTitle = new TextView(this);
        sampleTitle.setText("采样率  sample");
        sampleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        sampleTitle.setTextColor(TEXT_DIM);
        sampleTitle.setTypeface(Typeface.MONOSPACE);
        sampleTitle.setPadding(dp(2), 0, 0, dp(5));
        col.addView(sampleTitle);

        LinearLayout sampleRow = new LinearLayout(this);
        sampleRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Prefs.SAMPLE_RATES.length; i++) {
            final int idx = i;
            TextView b = rateChip(Prefs.SAMPLE_LABELS[i]);
            b.setOnClickListener(v -> {
                HudState.sampleMs = Prefs.SAMPLE_RATES[idx];
                Prefs.setSampleMs(this, HudState.sampleMs);
                HudState.bump();
                refreshSampleChips();
            });
            sampleChips.add(b);
            sampleRow.addView(b, chipLp());
        }
        col.addView(sampleRow);

        col.addView(spacer(10));

        // —— 刷新率：上屏的节奏
        TextView rateTitle = new TextView(this);
        rateTitle.setText("刷新率  update");
        rateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        rateTitle.setTextColor(TEXT_DIM);
        rateTitle.setTypeface(Typeface.MONOSPACE);
        rateTitle.setPadding(dp(2), 0, 0, dp(5));
        col.addView(rateTitle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Prefs.RATES.length; i++) {
            final int idx = i;
            TextView b = rateChip(Prefs.RATE_LABELS[i]);
            b.setOnClickListener(v -> {
                HudState.updateMs = Prefs.RATES[idx];
                Prefs.setUpdateMs(this, HudState.updateMs);
                HudState.bump();
                refreshRateChips();
            });
            rateChips.add(b);
            row.addView(b, chipLp());
        }
        col.addView(row);

        TextView note = new TextView(this);
        note.setText("两者互相独立，不做约束：\n"
                + "采样快于刷新 → 上屏取区间内全部采样点的平均值；\n"
                + "采样慢于刷新 → 读数值在下一次采样前保持不变（不会闪 --）。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        note.setTextColor(0xFF3E383D);
        note.setTypeface(Typeface.MONOSPACE);
        note.setPadding(dp(2), dp(7), 0, dp(16));
        col.addView(note);

        refreshRateChips();
        refreshSampleChips();
    }

    private TextView rateChip(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        b.setTypeface(Typeface.MONOSPACE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(10), 0, dp(10));
        return b;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private void refreshSampleChips() {
        int cur = Prefs.sampleIndex(this);
        for (int i = 0; i < sampleChips.size(); i++) {
            TextView b = sampleChips.get(i);
            boolean on = i == cur;
            b.setTextColor(on ? ACCENT : TEXT_DIM);
            b.setBackground(roundRect(on ? BG_CHIP_ON : BG_CARD, 9f,
                    on ? ACCENT : STROKE_CHIP));
        }
    }

    private void refreshRateChips() {
        int cur = Prefs.rateIndex(this);
        for (int i = 0; i < rateChips.size(); i++) {
            TextView b = rateChips.get(i);
            boolean on = i == cur;
            b.setTextColor(on ? ACCENT : TEXT_DIM);
            b.setBackground(roundRect(on ? BG_CHIP_ON : BG_CARD, 9f,
                    on ? ACCENT : STROKE_CHIP));
        }
    }

    // ------------------------------------------------------------------ 布局微调

    private void buildLayoutTuning(LinearLayout col) {
        sectionLabel(col, "LAYOUT");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(stepper("左留白", () -> HudState.insetLeft + "px",
                () -> shiftInset(true, +8), () -> shiftInset(true, -8)), weightLp());
        row.addView(stepper("右留白", () -> HudState.insetRight + "px",
                () -> shiftInset(false, +8), () -> shiftInset(false, -8)), weightLp());
        row.addView(stepper("高度", () -> HudState.barHeightDp + "dp", () -> {
            HudState.barHeightDp = clamp(HudState.barHeightDp + 2, 18, 90);
            Prefs.setBarHeightDp(this, HudState.barHeightDp);
            HudState.bump();
        }, () -> {
            HudState.barHeightDp = clamp(HudState.barHeightDp - 2, 18, 90);
            Prefs.setBarHeightDp(this, HudState.barHeightDp);
            HudState.bump();
        }), weightLp());

        col.addView(row);
        col.addView(spacer(8));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        row2.addView(stepper("字号", () -> HudState.textSizeSp + "sp", () -> {
            HudState.textSizeSp = clamp(HudState.textSizeSp + 1, 7, 22);
            Prefs.setTextSp(this, HudState.textSizeSp);
            HudState.bump();
        }, () -> {
            HudState.textSizeSp = clamp(HudState.textSizeSp - 1, 7, 22);
            Prefs.setTextSp(this, HudState.textSizeSp);
            HudState.bump();
        }), weightLp());

        row2.addView(stepper("间距", () -> HudState.gapDp + "dp", () -> {
            HudState.gapDp = clamp(HudState.gapDp + 2, 0, 60);
            Prefs.setGapDp(this, HudState.gapDp);
            HudState.bump();
        }, () -> {
            HudState.gapDp = clamp(HudState.gapDp - 2, 0, 60);
            Prefs.setGapDp(this, HudState.gapDp);
            HudState.bump();
        }), weightLp());

        row2.addView(stepper("竖屏宽", () -> Prefs.portraitPct(this) + "%", () -> {
            Prefs.setPortraitPct(this, clamp(Prefs.portraitPct(this) + 5, 30, 100));
            HudState.bump();
        }, () -> {
            Prefs.setPortraitPct(this, clamp(Prefs.portraitPct(this) - 5, 30, 100));
            HudState.bump();
        }), weightLp());

        col.addView(row2);
        col.addView(spacer(8));

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        row3.addView(stepper("背景", () -> HudState.showBackground ? "开" : "关", () -> {
            HudState.showBackground = !HudState.showBackground;
            Prefs.setShowBackground(this, HudState.showBackground);
            HudState.bump();
        }, () -> {
            HudState.showBackground = !HudState.showBackground;
            Prefs.setShowBackground(this, HudState.showBackground);
            HudState.bump();
        }), weightLp());

        // 占位，保持两个宽度一致
        View filler = new View(this);
        row3.addView(filler, weightLp());
        col.addView(row3);
        col.addView(spacer(16));
    }

    // ------------------------------------------------------------------ 外观

    private void buildAppearance(LinearLayout col) {
        sectionLabel(col, "BACKGROUND & COLOUR");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(colorButton("背景色", true, () -> HudState.bgColor, c -> {
            HudState.bgColor = c;
            Prefs.setBgColor(this, c);
            HudState.bump();
            applyLayoutToPreview();
        }), weightLp());

        row.addView(colorButton("边框色", false, () -> HudState.borderColor, c -> {
            HudState.borderColor = c;
            Prefs.setBorderColor(this, c);
            HudState.bump();
            applyLayoutToPreview();
        }), weightLp());

        col.addView(row);
        col.addView(spacer(8));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        row2.addView(stepper("边框", () -> HudState.showBorder ? "开" : "关", () -> {
            HudState.showBorder = !HudState.showBorder;
            Prefs.setShowBorder(this, HudState.showBorder);
            HudState.bump();
            applyLayoutToPreview();
        }, () -> {
            HudState.showBorder = !HudState.showBorder;
            Prefs.setShowBorder(this, HudState.showBorder);
            HudState.bump();
            applyLayoutToPreview();
        }), weightLp());

        row2.addView(stepper("圆角", () -> HudState.radiusDp + "dp", () -> {
            HudState.radiusDp = clamp(HudState.radiusDp + 1, 0, 40);
            Prefs.setRadiusDp(this, HudState.radiusDp);
            HudState.bump();
            applyLayoutToPreview();
        }, () -> {
            HudState.radiusDp = clamp(HudState.radiusDp - 1, 0, 40);
            Prefs.setRadiusDp(this, HudState.radiusDp);
            HudState.bump();
            applyLayoutToPreview();
        }), weightLp());

        col.addView(row2);
        col.addView(spacer(8));

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        row3.addView(stepper("最多", () -> HudState.maxItems + "项", () -> {
            HudState.maxItems = clamp(HudState.maxItems + 1, 3, 14);
            Prefs.setMaxItems(this, HudState.maxItems);
            HudState.bump();
            applyLayoutToPreview();
        }, () -> {
            HudState.maxItems = clamp(HudState.maxItems - 1, 3, 14);
            Prefs.setMaxItems(this, HudState.maxItems);
            HudState.bump();
            applyLayoutToPreview();
        }), weightLp());

        row3.addView(stepper("缩放", () -> BgImage.scaleName(HudState.bgScale), () -> {
            HudState.bgScale = (HudState.bgScale + 1) % BgImage.SCALE_NAMES.length;
            Prefs.setBgScale(this, HudState.bgScale);
            HudState.bump();
            applyLayoutToPreview();
            refreshSteppers();
        }, () -> {
            HudState.bgScale = (HudState.bgScale + BgImage.SCALE_NAMES.length - 1)
                    % BgImage.SCALE_NAMES.length;
            Prefs.setBgScale(this, HudState.bgScale);
            HudState.bump();
            applyLayoutToPreview();
            refreshSteppers();
        }), weightLp());

        row3.addView(stepper("对齐", () -> BgImage.anchorName(HudState.bgAnchor), () -> {
            HudState.bgAnchor = (HudState.bgAnchor + 1) % BgImage.ANCHOR_NAMES.length;
            Prefs.setBgAnchor(this, HudState.bgAnchor);
            HudState.bump();
            applyLayoutToPreview();
            refreshSteppers();
        }, () -> {
            HudState.bgAnchor = (HudState.bgAnchor + BgImage.ANCHOR_NAMES.length - 1)
                    % BgImage.ANCHOR_NAMES.length;
            Prefs.setBgAnchor(this, HudState.bgAnchor);
            HudState.bump();
            applyLayoutToPreview();
            refreshSteppers();
        }), weightLp());

        col.addView(row3);
        col.addView(spacer(8));

        // 两个独立的 alpha：窗口级（连文字一起淡）/ 图片级（只淡背景图）
        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);

        rowA.addView(stepper("窗口", () -> HudState.windowAlphaPct + "%", () -> {
            HudState.windowAlphaPct = clamp(HudState.windowAlphaPct + 5, 5, 100);
            Prefs.setWindowAlphaPct(this, HudState.windowAlphaPct);
            HudState.bump();
            applyWindowAlphaToPreview();
        }, () -> {
            HudState.windowAlphaPct = clamp(HudState.windowAlphaPct - 5, 5, 100);
            Prefs.setWindowAlphaPct(this, HudState.windowAlphaPct);
            HudState.bump();
            applyWindowAlphaToPreview();
        }), weightLp());

        rowA.addView(stepper("图片", () -> HudState.imageAlphaPct + "%", () -> {
            HudState.imageAlphaPct = clamp(HudState.imageAlphaPct + 5, 0, 100);
            Prefs.setImageAlphaPct(this, HudState.imageAlphaPct);
            HudState.bump();
            applyLayoutToPreview();
        }, () -> {
            HudState.imageAlphaPct = clamp(HudState.imageAlphaPct - 5, 0, 100);
            Prefs.setImageAlphaPct(this, HudState.imageAlphaPct);
            HudState.bump();
            applyLayoutToPreview();
        }), weightLp());

        col.addView(rowA);
        col.addView(spacer(8));

        // 实际像素尺寸 —— 方便按这个比例准备背景图
        sizeText = new TextView(this);
        sizeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        sizeText.setTextColor(TEXT_DIM);
        sizeText.setTypeface(Typeface.MONOSPACE);
        sizeText.setPadding(dp(2), dp(4), 0, dp(6));
        col.addView(sizeText);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.addView(smallButton("选择背景图\u2026", v -> pickBackgroundImage()), weightLp());
        row4.addView(smallButton("清除背景图", v -> {
            HudState.bgImageUri = "";
            Prefs.setBgImageUri(this, "");
            BgImage.clearPrivate(this);
            HudState.bump();
            applyLayoutToPreview();
            refreshSteppers();
            Toast.makeText(this, "背景图已清除", Toast.LENGTH_SHORT).show();
        }), weightLp());
        col.addView(row4);

        // 尺寸与图片状态随参数变化刷新
        stepperRefreshers.add(() -> {
            int bw = previewWidthPx();
            int bh = dp(HudState.barHeightDp);
            String img;
            if (HudState.bgImageUri.isEmpty()) {
                img = "未设置背景图";
            } else if (!BgImage.isUsable(this, HudState.bgImageUri)) {
                img = "背景图读取失败，请重新选择";
            } else {
                img = "背景图：" + BgImage.scaleName(HudState.bgScale)
                        + " / " + BgImage.anchorName(HudState.bgAnchor);
            }
            sizeText.setText("悬浮条实际 " + bw + " × " + bh + " px\n"
                    + img + "\n" + BgImage.SCALE_DESC[BgImage.clampScale(HudState.bgScale)]);
        });

        col.addView(spacer(16));
    }

    /**
     * 旧配置（content://）迁移成私有副本。
     *
     * <p>解码 + 重新编码是重活，放后台线程做；失败就保持原样，下次启动再试。
     */
    private void migrateLegacyBg() {
        final String cur = HudState.bgImageUri;
        if (cur == null || !cur.startsWith("content:")) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String got = BgImage.migrate(app, cur);
            if (got == null || got.isEmpty() || got.equals(cur)) return;
            runOnUiThread(() -> {
                HudState.bgImageUri = got;
                Prefs.setBgImageUri(this, got);
                HudState.bump();
                refreshSteppers();
            });
        }, "bg-migrate").start();
    }

    /** 预览条用 View 自身的 alpha 模拟窗口 alpha（真窗口 alpha 只作用于悬浮窗） */
    private void applyWindowAlphaToPreview() {
        preview.setAlpha(Math.max(0.05f, HudState.windowAlphaPct / 100f));
    }

    /** 预览条宽度（与 HudService.barWidth 同规则） */
    private int previewWidthPx() {
        boolean portrait = Screen.isPortrait(this);
        int w = Screen.width(this);
        if (!portrait) return w;
        int pct = Prefs.portraitPct(this);
        return Math.max(dp(120), Math.round(w * pct / 100f));
    }

    private static final int REQ_BG_IMAGE = 0x4B1;
    private static final int REQ_EXPORT_SETTINGS = 0x4C1;
    private static final int REQ_IMPORT_SETTINGS = 0x4C2;
    private static final int REQ_EXPORT_HISTORY = 0x4C3;
    private static final int REQ_IMPORT_PLUGIN = 0x4C4;

    /** 源图解码长边上限：再大就只是白占内存，条上根本用不到 */
    private static final int SOURCE_MAX_DIM = 3200;

    /** 走系统文件选择器（SAF），不需要存储/相册权限 */
    private void pickBackgroundImage() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_BG_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 悬浮条实际宽高比（宽 / 高） */
    private float barAspect() {
        return previewWidthPx() / (float) Math.max(1, dp(HudState.barHeightDp));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri picked = data.getData();

        // 导出 / 导入 / 插件都走同一个 SAF 回调，先在这里分流再走背景图那条老路
        if (requestCode == REQ_EXPORT_SETTINGS) {
            if (picked != null) doExportSettings(picked);
            return;
        }
        if (requestCode == REQ_IMPORT_SETTINGS) {
            if (picked != null) doImportSettings(picked);
            return;
        }
        if (requestCode == REQ_IMPORT_PLUGIN) {
            if (picked != null) doImportPlugin(picked);
            return;
        }
        if (requestCode == REQ_EXPORT_HISTORY) {
            if (picked != null && pendingHistoryExport != null) {
                exportHistoryTo(picked, pendingHistoryExport);
            }
            return;
        }

        if (requestCode != REQ_BG_IMAGE) return;
        Uri uri = picked;
        if (uri == null) return;

        // 选完就解码成位图，之后的裁剪与保存都在私有目录里完成，
        // 不再依赖 SAF 的持久化授权（重装 APK 后它一定失效）
        Bitmap src = BgImage.loadSource(this, uri, SOURCE_MAX_DIM);
        if (src == null) {
            Toast.makeText(this, "读不到这张图，换一张试试", Toast.LENGTH_LONG).show();
            return;
        }

        int outW = Math.max(320, previewWidthPx());
        ImageCropDialog.show(this, src, barAspect(), outW,
                previewWidthPx() + " \u00D7 " + dp(HudState.barHeightDp) + " px",
                cropped -> {
                    Bitmap out = (cropped != null) ? cropped : src;
                    String saved = BgImage.savePrivate(this, out);
                    if (cropped != null) cropped.recycle();
                    src.recycle();

                    if (saved.isEmpty()) {
                        Toast.makeText(this, "背景图保存失败", Toast.LENGTH_LONG).show();
                        return;
                    }
                    HudState.bgImageUri = saved;
                    Prefs.setBgImageUri(this, saved);
                    HudState.bump();
                    applyLayoutToPreview();
                    refreshSteppers();
                    Toast.makeText(this, "背景图已保存到应用私有目录",
                            Toast.LENGTH_SHORT).show();
                }, () -> {
                    // 取消：源图没人要了，立刻还回去
                    if (!src.isRecycled()) src.recycle();
                });
    }

    /** 色块按钮：显示当前颜色，点击开取色面板 */
    private View colorButton(String name, boolean isBg,
                             java.util.function.IntSupplier getter,
                             java.util.function.IntConsumer setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(roundRect(BG_CARD, 11f, STROKE_CARD));
        box.setPadding(dp(8), dp(6), dp(8), dp(6));

        View dot = new View(this);
        dot.setBackground(roundRect(colorPreview(getter.getAsInt(), isBg), 6f, 0));
        box.addView(dot, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        label.setTypeface(Typeface.MONOSPACE);
        label.setPadding(dp(8), 0, 0, 0);
        box.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Runnable refresh = () -> {
            int c = getter.getAsInt();
            dot.setBackground(roundRect(colorPreview(c, isBg), 6f, 0));
            label.setText(name + " " + (c == 0 ? "主题" : String.format(Locale.US, "%08X", c)));
            label.setTextColor(c == 0 ? TEXT_DIM : TEXT_MAIN);
        };
        refresh.run();

        box.setOnClickListener(v -> pickColor(name, getter.getAsInt(), isBg, c -> {
            setter.accept(c);
            refresh.run();
        }));

        return box;
    }

    /** 0 表示跟随主题，用主题色画预览 */
    private int colorPreview(int c, boolean isBg) {
        if (c != 0) return c;
        HudTheme th = HudTheme.byIndex(HudState.themeIndex);
        return isBg ? th.bg : th.stroke;
    }

    /** 取色面板：R/G/B/A 四条滑杆 + 实时预览 + 跟随主题 */
    private void pickColor(String title, int initial, boolean isBg,
                           java.util.function.IntConsumer onApply) {
        int start = initial != 0 ? initial
                : (isBg ? HudTheme.byIndex(HudState.themeIndex).bg
                        : HudTheme.byIndex(HudState.themeIndex).stroke);

        final Dialog dlg = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(BG_PANEL, 14f, STROKE_CARD));
        box.setPadding(dp(18), dp(16), dp(18), dp(14));

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        head.setTextColor(ACCENT);
        head.setTypeface(Typeface.MONOSPACE);
        box.addView(head);

        View swatch = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        slp.topMargin = dp(10);
        box.addView(swatch, slp);

        TextView hex = new TextView(this);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        hex.setTextColor(TEXT_SOFT);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setPadding(0, dp(8), 0, dp(6));
        box.addView(hex);

        final int[] ch = {(start >> 16) & 0xFF, (start >> 8) & 0xFF, start & 0xFF, (start >>> 24) & 0xFF};
        final int[] composed = {start};
        final String[] names = {"R", "G", "B", "A"};

        Runnable sync = () -> {
            composed[0] = (ch[3] << 24) | (ch[0] << 16) | (ch[1] << 8) | ch[2];
            swatch.setBackground(roundRect(composed[0], 10f, 0));
            hex.setText(String.format(Locale.US, "#%08X  A%d", composed[0], ch[3]));
        };

        for (int i = 0; i < 4; i++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView nm = new TextView(this);
            nm.setText(names[i]);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            nm.setTextColor(TEXT_DIM);
            nm.setTypeface(Typeface.MONOSPACE);
            nm.setGravity(Gravity.CENTER);
            line.addView(nm, new LinearLayout.LayoutParams(dp(18),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            final int idx = i;
            TextView val = new TextView(this);
            val.setText(String.valueOf(ch[i]));
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            val.setTextColor(TEXT_MAIN);
            val.setTypeface(Typeface.MONOSPACE);
            val.setGravity(Gravity.END);
            val.setWidth(dp(34));

            SeekBar bar = new SeekBar(this);
            bar.setMax(255);
            bar.setProgress(ch[i]);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    ch[idx] = progress;
                    val.setText(String.valueOf(progress));
                    sync.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });

            line.addView(bar, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(val);
            box.addView(line);
        }

        sync.run();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        box.addView(actions, alp);

        TextView theme = smallButton("跟随主题", v -> {
            onApply.accept(0);
            dlg.dismiss();
        });
        actions.addView(theme, weightLp());

        TextView pick = smallButton("确定", v -> {
            onApply.accept(composed[0]);
            dlg.dismiss();
        });
        pick.setTextColor(ACCENT);
        actions.addView(pick, weightLp());

        dlg.setContentView(box);
        android.view.Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            win.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.6f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private void shiftInset(boolean left, int deltaPx) {
        int max = screenW / 2;
        if (left) {
            HudState.insetLeft = clamp(HudState.insetLeft + deltaPx, 0, max);
            Prefs.setInsetLeft(this, HudState.insetLeft);
        } else {
            HudState.insetRight = clamp(HudState.insetRight + deltaPx, 0, max);
            Prefs.setInsetRight(this, HudState.insetRight);
        }
        HudState.bump();
    }

    // ------------------------------------------------------------------ 主题

    private void buildTheme(LinearLayout col) {
        sectionLabel(col, "THEME");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < HudTheme.ALL.length; i++) {
            final int idx = i;
            final HudTheme t = HudTheme.ALL[i];

            TextView btn = new TextView(this);
            btn.setText(t.name);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            btn.setTypeface(Typeface.MONOSPACE);
            btn.setGravity(Gravity.CENTER);
            btn.setTextColor(t.accent);
            btn.setPadding(0, dp(11), 0, dp(11));
            btn.setBackground(roundRect(t.bg, 11f, t.stroke));
            btn.setOnClickListener(v -> {
                HudState.themeIndex = idx;
                Prefs.setThemeIndex(this, idx);
                HudState.bump();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(btn, lp);
        }

        col.addView(row);

        // 主题一键覆盖：清掉所有单项样式，全部回到跟随主题
        TextView unify = smallButton("统一样式（清空所有单项覆盖）", v -> {
            for (SensorSpec sp : table) {
                sp.color = -1;
                sp.textSp = 0;
                sp.bold = false;
                sp.italic = false;
            }
            SensorTable.save(this, table);
            applySensors();
            Toast.makeText(this, "已全部交给主题控制", Toast.LENGTH_SHORT).show();
        });
        col.addView(unify, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(spacer(16));
    }

    // ------------------------------------------------------------------ 悬浮窗控制

    private void buildOverlayControl(LinearLayout col) {
        sectionLabel(col, "PLACE EDIT");

        FrameLayout card = new FrameLayout(this);
        card.setBackground(roundRect(BG_CARD, 16f, STROKE_CARD));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView hint = new TextView(this);
        hint.setText("状态栏会吞掉悬浮窗触摸，位置在此调整（方向键 1px，摇杆 1~6px）");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        hint.setTextColor(TEXT_DIM);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(12));
        body.addView(hint);

        JoystickView stick = new JoystickView(this);
        stick.setOnMoveListener((nx, ny) -> {
            vec[0] = nx;
            vec[1] = ny;
            if (nx != 0f || ny != 0f) {
                float k = Math.max(Math.abs(nx), Math.abs(ny));
                stepPx = STEP_BASE + k * STEP_JOY_EXTRA;
                kickMove();
            }
        });

        // 三栏并排：左＝摇杆，中＝方向键，右＝四个预设竖排（贴顶/居中/隐藏/复位）
        // 这样整块高度只等于一栏，比原来「杆 + 键 + 一排按钮」矮了将近一半
        LinearLayout padRow = new LinearLayout(this);
        padRow.setOrientation(LinearLayout.HORIZONTAL);
        padRow.setGravity(Gravity.CENTER_VERTICAL);

        final int padSize = dp(126);

        LinearLayout.LayoutParams stickLp =
                new LinearLayout.LayoutParams(padSize, padSize);
        stickLp.rightMargin = dp(8);
        padRow.addView(stick, stickLp);

        // 中间栏用 weight 撑开，让方向键落在卡片水平中央
        LinearLayout midCol = new LinearLayout(this);
        midCol.setGravity(Gravity.CENTER);
        midCol.addView(buildDpad());
        padRow.addView(midCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 第三栏：四行等分同一高度，逐个加分隔
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.VERTICAL);

        presets.addView(presetButton("贴顶", v -> {
            HudState.x = 0;
            HudState.y = 0;
            HudState.hidden = false;
            Prefs.setPos(this, 0, 0);
            Prefs.setVisible(this, true);
            HudState.bump();
        }), presetLp(false));

        presets.addView(presetButton("居中", v -> {
            HudState.x = 0;
            HudState.y = (screenH - dp(HudState.barHeightDp)) / 2;
            HudState.hidden = false;
            Prefs.setPos(this, HudState.x, HudState.y);
            Prefs.setVisible(this, true);
            HudState.bump();
        }), presetLp(false));

        presets.addView(presetButton("隐藏", v -> {
            HudState.hidden = !HudState.hidden;
            Prefs.setVisible(this, !HudState.hidden);
            HudState.bump();
        }), presetLp(false));

        presets.addView(presetButton("复位", v -> {
            HudState.x = 0;
            HudState.y = 0;
            HudState.barHeightDp = 32;
            HudState.textSizeSp = 12;
            HudState.sideMarginDp = 6;
            HudState.insetLeft = 0;
            HudState.insetRight = 0;
            HudState.hidden = false;
            HudState.showBackground = true;
            Prefs.saveFromState(this);
            HudState.bump();
            Toast.makeText(this, "已复位", Toast.LENGTH_SHORT).show();
        }), presetLp(true));

        LinearLayout.LayoutParams presetsLp =
                new LinearLayout.LayoutParams(dp(84), padSize);
        presetsLp.leftMargin = dp(8);
        padRow.addView(presets, presetsLp);
        body.addView(padRow);

        card.addView(body);
        col.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(spacer(16));
    }

    /** 第三栏的预设按钮：拉满栏宽、高度由 weight 等分 */
    private TextView presetButton(String text, View.OnClickListener l) {
        TextView tv = smallButton(text, l);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        tv.setPadding(dp(2), 0, dp(2), 0);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private LinearLayout.LayoutParams presetLp(boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (!last) lp.bottomMargin = dp(5);
        return lp;
    }

    private View buildDpad() {
        int cell = dp(36);
        int gap = dp(6);
        int size = cell * 3 + gap * 2;

        FrameLayout pad = new FrameLayout(this);
        pad.setLayoutParams(new LinearLayout.LayoutParams(size, size));

        pad.addView(dirKey("\u25B2", 0f, -1f), atCell(1, 0, cell, gap));
        pad.addView(dirKey("\u25C0", -1f, 0f), atCell(0, 1, cell, gap));
        pad.addView(dirKey("\u25B6", 1f, 0f), atCell(2, 1, cell, gap));
        pad.addView(dirKey("\u25BC", 0f, 1f), atCell(1, 2, cell, gap));
        return pad;
    }

    private FrameLayout.LayoutParams atCell(int col, int row, int cell, int gap) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cell, cell);
        lp.leftMargin = col * (cell + gap);
        lp.topMargin = row * (cell + gap);
        return lp;
    }

    private TextView dirKey(String glyph, final float vx, final float vy) {
        final TextView tv = new TextView(this);
        tv.setText(glyph);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(TEXT_SOFT);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundRect(BG_CHIP, 11f, STROKE_CHIP));
        tv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackground(roundRect(0xFF2A2429, 11f, ACCENT));
                    // 锁定模式：只在按下的上升沿走一步，长按不连发
                    HudState.x += Math.round(vx * STEP_BASE);
                    HudState.y += Math.round(vy * STEP_BASE);
                    clampHud();
                    Prefs.setPos(this, HudState.x, HudState.y);
                    HudState.bumpPos();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackground(roundRect(BG_CHIP, 11f, STROKE_CHIP));
                    return true;
                default:
                    return false;
            }
        });
        return tv;
    }

    // ------------------------------------------------------------------ 系统

    /** 数据着色规则：BAT 颜色 / 慢充告警 / 电流符号 */
    private void buildBatColour(LinearLayout col) {
        sectionLabel(col, "BAT COLOUR");

        CheckBox batColor = batCheck("启用 BAT 颜色变化（忽略主题色）", HudState.batColor, (btn, c) -> {
            HudState.batColor = c;
            Prefs.setBatColor(this, c);
            HudState.bump();
        });
        col.addView(batColor);

        CheckBox slow = batCheck("充电缓慢显示警告（红色）", HudState.batSlowWarn, (btn, c) -> {
            HudState.batSlowWarn = c;
            Prefs.setBatSlowWarn(this, c);
            HudState.bump();
        });
        col.addView(slow);

        CheckBox neg = batCheck("设备放电时电流为负值", HudState.dischargeNegative, (btn, c) -> {
            HudState.dischargeNegative = c;
            // 手动改过就算用户已确认方向，不再提示
            HudState.dischargeSignLearned = true;
            Prefs.setDischargeNegative(this, c);
            Prefs.setNegDetected(this, true);
            HudState.bump();
            refreshSteppers();
        });
        negBox = neg;
        col.addView(neg);

        // 方向未知时的告警：只在"确实在放电且没插电源"的样本上才会学习，
        // 所以这里直接告诉用户该做什么
        learnWarn = new TextView(this);
        learnWarn.setText("\u26A0 充放电方向未学习，请拔下充电器以识别方向");
        learnWarn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        learnWarn.setTextColor(0xFFFF5252);
        learnWarn.setTypeface(Typeface.MONOSPACE);
        learnWarn.setPadding(dp(2), dp(8), 0, dp(2));
        col.addView(learnWarn);

        stepperRefreshers.add(() -> {
            boolean learned = HudState.dischargeSignLearned;
            if (learnWarn != null) {
                learnWarn.setVisibility(learned ? View.GONE : View.VISIBLE);
            }
            if (negBox != null) {
                negBox.setText("设备放电时电流为负值"
                        + (learned ? "" : "（未学习）"));
                negBox.setChecked(HudState.dischargeNegative);
            }
        });

        TextView rule = new TextView(this);
        rule.setText("放电=主题色 · 充电=充电色 · 充电中平均电流<500mA=红色");
        rule.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        rule.setTextColor(0xFF3E383D);
        rule.setTypeface(Typeface.MONOSPACE);
        rule.setPadding(dp(2), dp(6), 0, dp(16));
        col.addView(rule);
    }

    /** 自启与后台活动 */
    private void buildSelfStart(LinearLayout col) {
        sectionLabel(col, "SELF-START & BACKGROUND");

        autoBtn = smallButton("开机自启：--", v -> {
            boolean next = !Prefs.autoStart(this);
            Prefs.setAutoStart(this, next);
            syncSystemState();
        });
        col.addView(autoBtn, matchWrap());
        col.addView(spacer(8));

        batteryBtn = smallButton("电池优化：--", v -> requestIgnoreBattery());
        col.addView(batteryBtn, matchWrap());
        col.addView(spacer(8));

        CheckBox recents = batCheck("在最近任务中隐藏本应用", Prefs.hideRecents(this), (btn, c) -> {
            Prefs.setHideRecents(this, c);
            applyRecentsHidden();
        });
        col.addView(recents);

        TextView note = new TextView(this);
        note.setText("息屏后采样与绘制全部停止，亮屏自动恢复。\n"
                + "电池优化未忽略时，部分 ROM 会在后台清掉悬浮条。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        note.setTextColor(0xFF3E383D);
        note.setTypeface(Typeface.MONOSPACE);
        note.setPadding(dp(2), dp(6), 0, dp(16));
        col.addView(note);
    }

    /** ADB / SHELL 状态：电脑旁路桥与 Shizuku 两条高权限通道 */
    private void buildShellState(LinearLayout col) {
        sectionLabel(col, "ADB / SHELL STATE");

        shizukuBtn = smallButton("Shizuku / 旁路桥：--", v -> onShizukuClick());
        col.addView(shizukuBtn, matchWrap());

        TextView shizukuNote = new TextView(this);
        shizukuNote.setText("高权限 Shell 有两条路径：\n"
                + "电脑旁路桥（电脑上跑 bridge/bridge.sh start）\n"
                + "或 Shizuku 授权。任一就绪即可读到\n"
                + "真实 CPU 占用率（整机 + 每核）");
        shizukuNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        shizukuNote.setTextColor(0xFF3E383D);
        shizukuNote.setTypeface(Typeface.MONOSPACE);
        shizukuNote.setPadding(dp(2), dp(6), 0, dp(10));
        col.addView(shizukuNote);

        btBtn = smallButton("蓝牙电量：--", v -> onBtClick());
        col.addView(btBtn, matchWrap());
        col.addView(spacer(8));

        LinearLayout btRow = new LinearLayout(this);
        btRow.setOrientation(LinearLayout.HORIZONTAL);
        btRow.addView(stepper("轮询", () -> HudState.btPollSec + "s", () -> {
            HudState.btPollSec = clamp(HudState.btPollSec + 15, 15, 600);
            Prefs.setBtPollSec(this, HudState.btPollSec);
            HudState.bump();
        }, () -> {
            HudState.btPollSec = clamp(HudState.btPollSec - 15, 15, 600);
            Prefs.setBtPollSec(this, HudState.btPollSec);
            HudState.bump();
        }), weightLp());
        btRow.addView(new View(this), weightLp());
        col.addView(btRow);

        TextView btNote = new TextView(this);
        btNote.setText("读的是系统缓存的蓝牙电量（0x180F 电量服务）。\n"
                + "有变化时系统会广播，平时按上面的间隔主动轮询一次。");
        btNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        btNote.setTextColor(0xFF3E383D);
        btNote.setTypeface(Typeface.MONOSPACE);
        btNote.setPadding(dp(2), dp(6), 0, dp(16));
        col.addView(btNote);
    }

    /** 通知：接管前台服务那条通知 */
    private void buildNotificationState(LinearLayout col) {
        sectionLabel(col, "NOTIFICATION STATE");

        CheckBox notif = batCheck("接管安卓「悬浮窗已开启」通知", HudState.notifTakeover, (btn, c) -> {
            HudState.notifTakeover = c;
            Prefs.setNotifTakeover(this, c);
            refreshNotification();
        });
        col.addView(notif);

        TextView notifHint = new TextView(this);
        notifHint.setText("第一行 = 前 3 项数据（与悬浮条同色）\n第二行 = 关闭悬浮窗 / 打开应用 / 警报消除");
        notifHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        notifHint.setTextColor(0xFF3E383D);
        notifHint.setTypeface(Typeface.MONOSPACE);
        notifHint.setPadding(dp(2), dp(4), 0, dp(8));
        col.addView(notifHint);

        // 系统那条「X 正在其他应用上层显示」由 WindowManager 在悬浮窗表面可见时投放，
        // 只认"窗口可见"这一个条件，应用无权改写或撤销，这里只把人送到它的通道开关前
        col.addView(smallButton("系统悬浮窗提醒开关（系统发的）\u2026",
                        v -> openSystemOverlayNotifSettings()),
                matchWrap());
        col.addView(spacer(16));
    }

    private CheckBox batCheck(String text, boolean checked,
                              android.widget.CompoundButton.OnCheckedChangeListener l) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        cb.setTextColor(TEXT_SOFT);
        cb.setTypeface(Typeface.MONOSPACE);
        cb.setChecked(checked);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        cb.setPadding(dp(2), dp(4), 0, dp(4));
        cb.setOnCheckedChangeListener(l);
        return cb;
    }

    private void syncSystemState() {
        boolean auto = Prefs.autoStart(this);
        autoBtn.setText("开机自启：" + (auto ? "开" : "关"));
        autoBtn.setTextColor(auto ? ACCENT : TEXT_SOFT);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryBtn.setText("电池优化：" + (ignored ? "已忽略" : "未忽略"));
        batteryBtn.setTextColor(ignored ? ACCENT : TEXT_SOFT);
    }

    // ------------------------------------------------------------------ Shizuku

    private void onShizukuClick() {
        if (ShellAccess.mode() == ShellAccess.Mode.BRIDGE) {
            Toast.makeText(this, "电脑旁路已连接：CPU 占用率可用", Toast.LENGTH_SHORT).show();
            syncShizuku();
            return;
        }
        if (ShizukuShell.ready()) {
            Toast.makeText(this, "已授权：CPU 占用率传感器可用", Toast.LENGTH_SHORT).show();
            syncShizuku();
            return;
        }
        if (!ShizukuShell.canRequest()) {
            // 服务没起来，把人送到 Shizuku 应用里自己启动
            try {
                Intent i = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i != null) {
                    startActivity(i);
                    Toast.makeText(this, "先在 Shizuku 里启动服务", Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {
            }
            Toast.makeText(this, "Shizuku 未运行，且找不到 Shizuku 应用", Toast.LENGTH_LONG).show();
            return;
        }
        ShizukuShell.request();
        Toast.makeText(this, "请在 Shizuku 的弹窗里点「允许」", Toast.LENGTH_SHORT).show();
    }

    private void syncShizuku() {
        if (shizukuBtn == null) return;
        ShellAccess.Mode m = ShellAccess.mode();
        if (m == ShellAccess.Mode.BRIDGE) {
            shizukuBtn.setText("Shell：电脑旁路已连接");
            shizukuBtn.setTextColor(ACCENT);
            return;
        }
        if (m == ShellAccess.Mode.SHIZUKU) {
            shizukuBtn.setText("Shell：Shizuku 已授权");
            shizukuBtn.setTextColor(ACCENT);
            return;
        }
        boolean alive = ShizukuShell.canRequest();
        shizukuBtn.setText("Shell：" + (alive ? "未连接（点击试 Shizuku）" : "未连接"));
        shizukuBtn.setTextColor(TEXT_SOFT);
    }

    // ------------------------------------------------------------------ 蓝牙电量

    private static final int REQ_BT = 91;

    private void onBtClick() {
        if (!BtBattery.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
                Toast.makeText(this, "请允许「附近的设备」权限", Toast.LENGTH_SHORT).show();
            } else {
                BtBattery.poll(this);
                afterBtReady();
            }
            return;
        }
        if (!BtBattery.enabled(this)) {
            Toast.makeText(this, "蓝牙未开启", Toast.LENGTH_SHORT).show();
            return;
        }
        BtBattery.poll(this);
        afterBtReady();
        Toast.makeText(this, BtBattery.status(this), Toast.LENGTH_SHORT).show();
    }

    /** 授权后把蓝牙设备补进表，并自动勾上已连接的那几台 */
    private void afterBtReady() {
        int before = table.size();
        List<SensorSpec> next = SensorTable.ensureBtSensors(this, table);
        if (next != null) table = next;

        boolean enableNew = false;
        for (SensorSpec sp : table) {
            if (sp.kind != SensorSpec.Kind.BT_BATT) continue;
            if (HudState.sensorIds.contains(sp.id)) continue;
            if (!BtBattery.hasLevel(sp.id)) continue;
            HudState.sensorIds.add(sp.id);
            enableNew = true;
        }
        if (enableNew) Prefs.setSensors(this, HudState.sensorIds);

        if (next != null || enableNew || table.size() != before) {
            activeSensors = Sensor.resolve(table, HudState.sensorIds);
            HudState.bump();
            updateSensorSummary();
        }
        syncBtState();
    }

    /** 进前台时补一次蓝牙设备（权限已给的情况下才动） */
    private void ensureBtSensorsQuietly() {
        if (!BtBattery.hasPermission(this) || !BtBattery.enabled(this)) return;
        BtBattery.poll(this);
        int before = table.size();
        List<SensorSpec> next = SensorTable.ensureBtSensors(this, table);
        if (next == null && table.size() == before) return;
        if (next != null) table = next;
        activeSensors = Sensor.resolve(table, HudState.sensorIds);
        HudState.bump();
        updateSensorSummary();
        syncBtState();
    }

    private void syncBtState() {
        if (btBtn == null) return;
        btBtn.setText("蓝牙电量：" + BtBattery.status(this));
        btBtn.setTextColor(BtBattery.hasPermission(this) ? ACCENT : TEXT_SOFT);
    }

    /** 授权到位后把占用率传感器补进表（幂等，没变化就什么都不做） */
    private void ensureShellSensorsQuietly() {
        if (!ShellAccess.hasShell()) return;
        int before = table.size();
        table = SensorTable.ensureShellSensors(this, table);
        if (table.size() == before) return;

        activeSensors = Sensor.resolve(table, HudState.sensorIds);
        HudState.bump();
        updateSensorSummary();
    }

    @SuppressWarnings("BatteryLife")
    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Android 13+ 没有 POST_NOTIFICATIONS 时，前台服务通知不会出现在通知栏
     * （服务照跑，但通知被系统吞掉），所以必须主动要一次。
     */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 90);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] result) {
        super.onRequestPermissionsResult(code, perms, result);
        if (code == 90 && HudState.running) {
            // 授权后把通知重建一次，否则要等服务下一轮刷新才出现
            refreshNotification();
        }
        if (code == REQ_BT) {
            boolean ok = result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                BtBattery.poll(this);
                afterBtReady();
            } else {
                Toast.makeText(this, "没有「附近的设备」权限，读不到蓝牙电量", Toast.LENGTH_LONG).show();
                syncBtState();
            }
        }
    }

    /**
     * 跳到那条系统提醒的通道设置。
     *
     * 通道 id 由 WindowManagerService 拼成：
     *   "com.android.server.wm.AlertWindowNotification - <包名>"，宿主包是 "android"。
     * 不同 ROM 的通道页深浅不一，打不开就退到"Android 系统"的通知总设置。
     */
    private void openSystemOverlayNotifSettings() {
        String channel = "com.android.server.wm.AlertWindowNotification - " + getPackageName();
        try {
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, "android")
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel));
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, "android"));
        } catch (Exception e) {
            Toast.makeText(this, "请到 设置 → 通知 → Android 系统 里关闭", Toast.LENGTH_LONG).show();
        }
    }

    /** 让服务立刻重建通知（接管开关、按钮状态变化后调用） */
    private void refreshNotification() {
        if (!HudState.running) return;
        try {
            startService(new Intent(this, HudService.class)
                    .setAction(HudService.ACTION_REFRESH_NOTIF));
        } catch (Exception ignored) {
        }
    }

    /**
     * 最近任务隐藏。
     *
     * 清单里写死 excludeFromRecents 会让它永远隐藏，所以走运行时：
     * 给本应用当前的 task 打标记，恢复可见时再清掉。
     * 注意新冷启动的 task 会重新出现一次，onCreate/onResume 各打一次标记兜住。
     */
    private void applyRecentsHidden() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            boolean hide = Prefs.hideRecents(this);
            for (ActivityManager.AppTask t : am.getAppTasks()) {
                t.setExcludeFromRecents(hide);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ 逻辑

    private void startHud() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        Prefs.saveFromState(this);
        startForegroundService(new Intent(this, HudService.class));
        handler.postDelayed(this::syncToggle, 250);
    }

    private void syncToggle() {
        boolean on = HudState.running;
        toggleBtn.setText(on ? "关闭悬浮窗" : "开启悬浮窗");
        toggleBtn.setBackground(roundRect(on ? 0xFF2A2429 : ACCENT, 14f,
                on ? 0x33FFFFFF : 0));
        toggleBtn.setTextColor(on ? TEXT_MAIN : 0xFF14100F);
        stateText.setText(on ? "\u25CF RUNNING" : "\u25CB STOPPED");
        stateText.setTextColor(on ? ACCENT : TEXT_DIM);
    }

    private void applyLayoutToPreview() {
        preview.setTheme(HudTheme.byIndex(HudState.themeIndex));
        preview.setTextSizeSp(HudState.textSizeSp);
        preview.setInsets(HudState.insetLeft, HudState.insetRight);
        preview.setShowBackground(HudState.showBackground);
        preview.setGapDp(HudState.gapDp);
        preview.setColors(HudState.bgColor, HudState.borderColor);
        preview.setBorderVisible(HudState.showBorder);
        preview.setRadiusDp(HudState.radiusDp);
        preview.setBackgroundMode(HudState.bgAnchor, HudState.bgScale);
        preview.setImageAlpha(HudState.imageAlphaPct);
        applyWindowAlphaToPreview();
        preview.setBackgroundImage(BgImage.load(this, HudState.bgImageUri,
                preview.getWidth() > 0 ? preview.getWidth() : previewWidthPx(),
                dp(HudState.barHeightDp), HudState.insetLeft, HudState.insetRight,
                HudState.bgAnchor, HudState.bgScale));
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp != null) {
            lp.height = dp(HudState.barHeightDp);
            preview.setLayoutParams(lp);
        }
    }

    private void refreshData() {
        try {
            DataCollector.Snapshot s = DataCollector.capture(this);

            // 悬浮窗没开时也要能学习电流方向，否则用户关掉悬浮条就永远校准不了
            if (!HudState.dischargeSignLearned) {
                boolean before = HudState.dischargeSignLearned;
                DataCollector.maybeLearnDischargeSign(this, s);
                if (!before && HudState.dischargeSignLearned) {
                    refreshSteppers();
                    HudState.bump();
                }
            }

            // readAll 而不是逐个 read：插件指标要等基础通道都算完才能求值
            Map<String, Float> vals = Sensor.readAll(activeSensors, s);
            // 服务没跑时由这里喂图表历史与记录，跑了就交给服务，
            // 两边都喂等于按两倍频率采样，曲线会"跑得快"
            if (!HudState.running) {
                ChartHistory.observe(vals);
                if (HistoryRecorder.isRecording()) HistoryRecorder.offer(vals);
                // shell 插件的调度跟着数据循环走；服务在跑时由服务那边 tick
                ShellPlugins.tick(this);
            }
            float cur = vals.containsKey("batt-cur")
                    ? vals.get("batt-cur") : Math.abs(s.currentMa);
            preview.setValues(vals, s.charging, cur);

            // 左列的实时值：只 setText，不重建 View
            for (Sensor se : activeSensors) {
                if (se.spec.kind == SensorSpec.Kind.CHART) continue;  // 曲线行固定显示"曲线"
                TextView tv = rowValues.get(se.id());
                if (tv == null) continue;
                float v = vals.containsKey(se.id()) ? vals.get(se.id()) : Float.NaN;
                tv.setText(se.format(v) + se.unit());
            }
            if (historyStatus != null && HistoryRecorder.isRecording()) {
                refreshHistoryStatus();
            }

            HudTheme th = HudTheme.byIndex(HudState.themeIndex);
            for (Sensor se : activeSensors) {
                TextView tv = cardValues.get(se.id());
                if (tv == null) continue;
                float v = vals.containsKey(se.id()) ? vals.get(se.id()) : Float.NaN;
                tv.setText(se.format(v) + se.unit());
                tv.setTextColor(colorFor(se, v, s.charging, th));
            }

            posText.setText(String.format(Locale.US,
                    "x %d   y %d   %s   %d\u00B0c\u00B7%d probes",
                    HudState.x, HudState.y,
                    s.charging ? "charging" : "discharging",
                    (int) s.cpuTempC, s.zoneCount));
        } catch (Exception ignored) {
        }
    }

    private int colorFor(Sensor se, float v, boolean charging, HudTheme th) {
        if (Float.isNaN(v)) return TEXT_DIM;
        switch (se.role()) {
            case TEMP:
                return v >= 45f ? th.accent : TEXT_MAIN;
            case MEMORY:
                return v >= 88f ? th.accent : TEXT_MAIN;
            case POWER:
                return charging ? th.charge : TEXT_MAIN;
            default:
                return TEXT_MAIN;
        }
    }

    private void clampHud() {
        int barW = screenW;
        int barH = dp(HudState.barHeightDp);
        HudState.x = clamp(HudState.x, -barW / 2, screenW - barW / 2);
        HudState.y = clamp(HudState.y, -barH, screenH - barH / 2);
    }

    // ------------------------------------------------------------------ 三列布局

    /** 一列的容器：本身不带背景，靠里面各区块自己的卡片撑视觉 */
    private LinearLayout columnBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout.LayoutParams columnLp(float weight) {
        // 关键区别：横向排列时 weight 分的是**宽度**，竖向排列时 weight 分的是高度。
        // 竖向再写 width=0 + weight 会得到零宽的列 —— 整块内容直接消失，
        // 而且不报错，很难查。
        if (!wideLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 0);
            return lp;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        // 列之间留一点空隙，否则三栏的卡片会粘成一整片
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    /** 单列（窄屏）时列与列之间的竖向空隙 */
    private View vGap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 左列：传感器清单 + 采样/刷新节奏 */
    private void buildEditColumn(LinearLayout col) {
        buildSensors(col);
        buildRate(col);
    }

    /** 中列：位置调整 + 布局微调 + 背景与配色 */
    private void buildPlaceColumn(LinearLayout col) {
        buildOverlayControl(col);
        buildLayoutTuning(col);
        buildAppearance(col);
        buildTheme(col);
        buildBatColour(col);
    }

    /** 右列：自启、Shell 状态、通知、导入导出、历史、插件 */
    private void buildAdvancedColumn(LinearLayout col) {
        sectionLabel(col, "ADVANCED SETTINGS");
        buildSelfStart(col);
        buildShellState(col);
        buildNotificationState(col);
        buildTransfer(col);
        buildHistory(col);
        buildPlugins(col);
    }

    /** 底部悬浮条：全屏预览 + 开关悬浮窗，滚动时始终可见 */
    private void buildBottomBar(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackground(roundRect(0xF00D0C0F, 20f, 0x26FFFFFF));

        TextView full = barButton("\u26F6 全屏预览", false,
                v -> FullScreenPreview.show(this));
        bar.addView(full, new LinearLayout.LayoutParams(0, dp(42), 1f));

        toggleBtn = barButton("开启悬浮窗", true, v -> {
            if (HudState.running) {
                stopService(new Intent(this, HudService.class));
                handler.postDelayed(this::syncToggle, 150);
            } else {
                startHud();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(42), 1.25f);
        tlp.leftMargin = dp(8);
        bar.addView(toggleBtn, tlp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp(20), 0, dp(20), dp(18));
        root.addView(bar, lp);
    }

    private TextView barButton(String text, boolean primary, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTypeface(Typeface.MONOSPACE, primary ? Typeface.BOLD : Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER);
        tv.setLetterSpacing(0.06f);
        if (primary) {
            tv.setTextColor(0xFF14100F);
            tv.setBackground(roundRect(ACCENT, 14f, 0));
        } else {
            tv.setTextColor(TEXT_SOFT);
            tv.setBackground(roundRect(BG_CHIP, 14f, STROKE_CHIP));
        }
        tv.setOnClickListener(l);
        return tv;
    }

    // ------------------------------------------------------------------ 左列：传感器行

    /**
     * 重建"名字 + 实时值"行。
     *
     * <p>值本身由 {@link #refreshData()} 每帧塞进去，这里只管结构与交互 ——
     * 每帧重建 View 会让滚动位置和按下的手感全乱。
     */
    private void rebuildRows() {
        if (rowsBox == null) return;
        rowsBox.removeAllViews();
        rowValues.clear();

        if (activeSensors.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有启用的传感器");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            empty.setTextColor(TEXT_DIM);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setPadding(dp(2), dp(6), 0, dp(6));
            rowsBox.addView(empty);
            return;
        }

        for (Sensor se : activeSensors) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(roundRect(BG_CARD, 10f, STROKE_CARD));
            row.setPadding(dp(10), dp(7), dp(6), dp(7));

            TextView name = new TextView(this);
            String nm = se.label() == null || se.label().isEmpty() ? "(空)" : se.label();
            boolean isChart = se.spec.kind == SensorSpec.Kind.CHART;
            // 曲线项的名字和数据源同名，行首加个符号区分开，否则列表里会出现两个 CPU
            name.setText((isChart ? "\u223F " : "") + nm.toUpperCase(Locale.US));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            name.setTextColor(TEXT_DIM);
            name.setTypeface(Typeface.MONOSPACE);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView val = new TextView(this);
            val.setText(isChart ? "曲线" : "--");
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            val.setTextColor(isChart ? TEXT_DIM : TEXT_MAIN);
            val.setTypeface(Typeface.MONOSPACE);
            val.setSingleLine(true);
            row.addView(val);

            // 图表是"按传感器加"的：点一下就为这一项生成一条曲线，
            // 比先去管理列表里翻"曲线图表"再绑定数据源直观得多。
            // 已经是曲线的那行改成齿轮，点开调量程/点数（在管理对话框里）。
            TextView curve = isChart
                    ? smallButton("\u2699", v -> openSensorManager())
                    : smallButton("\u223F", v -> addChartFor(se.id()));
            curve.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            curve.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(8);
            row.addView(curve, clp);

            LinearLayout.LayoutParams rlp = matchWrap();
            rlp.bottomMargin = dp(5);
            rowsBox.addView(row, rlp);
            rowValues.put(se.id(), val);
        }
    }

    /** 给某个传感器加一条胶囊曲线并启用 */
    private void addChartFor(String srcId) {
        SensorSpec sp = SensorTable.newChart(table, srcId);
        if (Sensor.find(table, sp.id) == null) table.add(sp);
        if (!HudState.sensorIds.contains(sp.id)) {
            HudState.sensorIds = new ArrayList<>(HudState.sensorIds);
            HudState.sensorIds.add(sp.id);
        }
        SensorTable.save(this, table);
        Prefs.setSensors(this, HudState.sensorIds);
        applySensors();
        Toast.makeText(this, "已添加曲线：" + sp.label, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 右列：导入导出

    private void buildTransfer(LinearLayout col) {
        sectionLabel(col, "IMPORT & EXPORT");

        transferStatus = new TextView(this);
        transferStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        transferStatus.setTextColor(TEXT_DIM);
        transferStatus.setTypeface(Typeface.MONOSPACE);
        transferStatus.setPadding(dp(2), 0, 0, dp(8));
        col.addView(transferStatus);
        refreshTransferStatus();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallButton("导出设置\u2026", v -> exportSettings()), weightLp());
        row.addView(smallButton("导入设置\u2026", v -> importSettings()), weightLp());
        col.addView(row);

        TextView note = new TextView(this);
        note.setText("含：外观 / 布局 / 传感器表 / 启用项 / 机型信息\n"
                + "不含：背景图（指向本机私有文件）\n"
                + "机型不同会先弹警告，可选择只导外观");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        note.setTextColor(0xFF3E383D);
        note.setTypeface(Typeface.MONOSPACE);
        note.setPadding(dp(2), dp(7), 0, dp(16));
        col.addView(note);
    }

    private void refreshTransferStatus() {
        if (transferStatus == null) return;
        transferStatus.setText("当前 " + Prefs.snapshot(this).size() + " 项偏好 · 传感器表 "
                + table.size() + " 项");
    }

    private void exportSettings() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, SettingsTransfer.suggestName());
            startActivityForResult(i, REQ_EXPORT_SETTINGS);
        } catch (Exception e) {
            Toast.makeText(this, "打不开保存界面：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importSettings() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // 有些文件管理器把 .json 标成 octet-stream，收窄类型会选不到文件
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT_SETTINGS);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void doExportSettings(final Uri uri) {
        new Thread(() -> {
            String err = null;
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("无法写入");
                SettingsTransfer.write(this, os);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> Toast.makeText(this,
                    ferr == null ? "设置已导出" : "导出失败：" + ferr,
                    Toast.LENGTH_LONG).show());
        }, "settings-export").start();
    }

    private void doImportSettings(final Uri uri) {
        new Thread(() -> {
            SettingsTransfer.Preview pv = null;
            String err = null;
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IllegalStateException("读不到文件");
                pv = SettingsTransfer.peek(this, is);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final SettingsTransfer.Preview fpv = pv;
            final String ferr = err;
            runOnUiThread(() -> {
                if (fpv == null) {
                    Toast.makeText(this, "导入失败：" + ferr, Toast.LENGTH_LONG).show();
                    return;
                }
                confirmImport(fpv);
            });
        }, "settings-import").start();
    }

    /**
     * 导入确认。
     *
     * <p>机型不一致时给三个选项：全部导入 / 只导外观 / 取消。
     * 之所以不直接拒绝：换机迁移时用户**就是**想把外观搬过去，
     * 但传感器表一定得留在本机重新探测，否则条上会挂一堆读不到值的项。
     */
    private void confirmImport(final SettingsTransfer.Preview pv) {
        StringBuilder sb = new StringBuilder();
        for (String line : SettingsTransfer.summary(pv)) sb.append(line).append('\n');

        if (!pv.sameDevice) {
            sb.append('\n').append("\u26A0 机型不一致\n");
            sb.append("来源：").append(pv.sourceDevice).append('\n');
            sb.append("本机：").append(pv.localDevice).append('\n');
            sb.append("传感器表是按机型探测的，直接导入可能得到一堆读不到值的项。\n")
                    .append("建议选「仅导入外观」——保留本机传感器表。");
        }

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(pv.sameDevice ? "导入设置" : "导入设置（机型不同）")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null);

        if (pv.sameDevice) {
            b.setPositiveButton("全部导入", (d, w) -> applyImport(pv, true));
        } else {
            b.setPositiveButton("仅导入外观", (d, w) -> applyImport(pv, false));
            b.setNeutralButton("仍然全部导入", (d, w) -> applyImport(pv, true));
        }
        b.show();
    }

    private void applyImport(SettingsTransfer.Preview pv, boolean includeSensors) {
        try {
            int n = SettingsTransfer.apply(this, pv, includeSensors);

            // 表可能被整体替换过，必须重新读盘并重建全部派生对象
            table = SensorTable.load(this);
            HudState.sensorIds = Prefs.sensors(this);
            activeSensors = Sensor.resolve(table, HudState.sensorIds);
            if (activeSensors.isEmpty()) {
                HudState.sensorIds = new ArrayList<>(SensorTable.DEFAULT_IDS);
                activeSensors = Sensor.resolve(table, HudState.sensorIds);
            }
            if (preview != null) preview.setSensors(activeSensors);
            applyLayoutToPreview();
            rebuildCards();
            rebuildRows();
            updateSensorSummary();
            refreshSteppers();
            syncSystemState();
            refreshSampleChips();
            refreshRateChips();
            refreshTransferStatus();
            HudState.bump();

            Toast.makeText(this, "已导入 " + n + " 项" + (includeSensors ? "" : "（保留本机传感器表）"),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 右列：历史记录

    private void buildHistory(LinearLayout col) {
        sectionLabel(col, "RECORD HISTORY");

        historyStatus = new TextView(this);
        historyStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        historyStatus.setTextColor(TEXT_DIM);
        historyStatus.setTypeface(Typeface.MONOSPACE);
        historyStatus.setPadding(dp(2), 0, 0, dp(8));
        col.addView(historyStatus);
        refreshHistoryStatus();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallButton("开始 / 停止", v -> toggleRecording()), weightLp());
        row.addView(stepper("间隔", () -> Prefs.historyIntervalMs(this) + "ms", () -> {
            Prefs.setHistoryIntervalMs(this, clamp(Prefs.historyIntervalMs(this) + 250,
                    250, 60000));
            refreshHistoryStatus();
        }, () -> {
            Prefs.setHistoryIntervalMs(this, clamp(Prefs.historyIntervalMs(this) - 250,
                    250, 60000));
            refreshHistoryStatus();
        }), weightLp());
        col.addView(row);
        col.addView(spacer(6));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(smallButton("导出 CSV\u2026", v -> exportHistory()), weightLp());
        row2.addView(smallButton("清空记录", v -> clearHistory()), weightLp());
        col.addView(row2);

        TextView note = new TextView(this);
        note.setText("记录跟随采样节奏，息屏采样停止时一并暂停。\n"
                + "CSV 以 # 注释开头，写清机型 / SoC / 间隔，\n"
                + "Excel 与 pandas 都能直接读。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        note.setTextColor(0xFF3E383D);
        note.setTypeface(Typeface.MONOSPACE);
        note.setPadding(dp(2), dp(7), 0, dp(16));
        col.addView(note);
    }

    private void refreshHistoryStatus() {
        if (historyStatus == null) return;
        if (HistoryRecorder.isRecording()) {
            java.io.File f = HistoryRecorder.currentFile();
            historyStatus.setText(String.format(Locale.US,
                    "\u25CF 记录中 · %d 行 · %s · %s",
                    HistoryRecorder.rows(), fmtDuration(HistoryRecorder.durationMs()),
                    f == null ? "" : (f.length() / 1024) + " KB"));
            historyStatus.setTextColor(ACCENT);
        } else {
            int n = HistoryRecorder.files(this).size();
            long bytes = HistoryRecorder.totalBytes(this);
            historyStatus.setText("\u25CB 未记录 · 已有 " + n + " 个文件 · "
                    + (bytes / 1024) + " KB · 间隔 " + Prefs.historyIntervalMs(this) + "ms");
            historyStatus.setTextColor(TEXT_DIM);
        }
    }

    private static String fmtDuration(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void toggleRecording() {
        if (HistoryRecorder.isRecording()) {
            long rows = HistoryRecorder.rows();
            java.io.File f = HistoryRecorder.stop();
            Toast.makeText(this, "已停止记录：" + rows + " 行"
                    + (f == null ? "" : " · " + f.getName()), Toast.LENGTH_LONG).show();
        } else {
            List<String> ids = new ArrayList<>();
            for (Sensor se : activeSensors) ids.add(se.id());
            boolean ok = HistoryRecorder.start(this, ids, Prefs.historyIntervalMs(this));
            Toast.makeText(this, ok
                            ? "开始记录 · 间隔 " + Prefs.historyIntervalMs(this) + "ms · "
                            + ids.size() + " 个通道"
                            : "启动失败：没有启用的传感器？",
                    Toast.LENGTH_LONG).show();
        }
        refreshHistoryStatus();
    }

    private void exportHistory() {
        java.io.File f = HistoryRecorder.isRecording()
                ? HistoryRecorder.currentFile() : null;
        if (f == null) {
            List<java.io.File> all = HistoryRecorder.files(this);
            if (!all.isEmpty()) f = all.get(0);
        }
        if (f == null) {
            Toast.makeText(this, "还没有任何记录", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingHistoryExport = f;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_TITLE, f.getName());
            startActivityForResult(i, REQ_EXPORT_HISTORY);
        } catch (Exception e) {
            Toast.makeText(this, "打不开保存界面：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportHistoryTo(final Uri target, final java.io.File src) {
        new Thread(() -> {
            String err = null;
            try {
                HistoryRecorder.export(this, target, src);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> Toast.makeText(this,
                    ferr == null ? "已导出 " + src.getName() : "导出失败：" + ferr,
                    Toast.LENGTH_LONG).show());
        }, "history-export").start();
    }

    private void clearHistory() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空记录")
                .setMessage("会删除应用私有目录下的全部历史 CSV，已导出的文件不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    HistoryRecorder.deleteAll(this);
                    refreshHistoryStatus();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ------------------------------------------------------------------ 右列：插件

    private void buildPlugins(LinearLayout col) {
        sectionLabel(col, "PLUGINS");

        pluginStatus = new TextView(this);
        pluginStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        pluginStatus.setTextColor(TEXT_DIM);
        pluginStatus.setTypeface(Typeface.MONOSPACE);
        pluginStatus.setPadding(dp(2), 0, 0, dp(8));
        col.addView(pluginStatus);
        refreshPluginStatus();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallButton("导入插件\u2026", v -> importPlugin()), weightLp());
        row.addView(smallButton("重新加载", v -> reloadPlugins()), weightLp());
        col.addView(row);

        TextView note = new TextView(this);
        note.setText("两条通道：\n"
                + "\u00B7 表达式 JSON（高频，跟着采样跑）：只能算，不执行代码\n"
                + "   变量用安全名 batt_cur / agg_cpu，支持 if(x>1,a,b)\n"
                + "\u00B7 shell 脚本（低频，默认 5s 批量跑一次）：能读任意节点\n"
                + "   # @metric fan_rpm FAN rpm 0 声明指标，echo name=value 出值\n"
                + "\n\u26A0 脚本以应用身份运行：能读本应用全部私有数据\n"
                + "（含 bridge.token）。装脚本 = 信任它的作者。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        note.setTextColor(0xFF3E383D);
        note.setTypeface(Typeface.MONOSPACE);
        note.setPadding(dp(2), dp(7), 0, dp(4));
        col.addView(note);
    }

    private void refreshPluginStatus() {
        if (pluginStatus == null) return;
        List<PluginHost.Metric> ms = PluginHost.metrics();
        List<String> warns = new ArrayList<>(PluginHost.warnings());
        warns.addAll(ShellPlugins.warnings());
        int shScripts = ShellPlugins.fileNames(this).size();
        int shMetrics = ShellPlugins.metricCount();
        StringBuilder sb = new StringBuilder();
        sb.append("\u25CF ").append(ms.size()).append(" 个表达式指标 · ")
                .append(PluginHost.fileNames(this).size()).append(" 个 json");
        if (shScripts > 0) {
            sb.append("  \u00B7  ").append(shMetrics).append(" 个 shell 指标 · ")
                    .append(shScripts).append(" 个 sh");
        }
        if (!warns.isEmpty()) {
            sb.append(" · ").append(warns.size()).append(" 个问题");
        }
        pluginStatus.setText(sb.toString());
        pluginStatus.setTextColor(warns.isEmpty() ? TEXT_DIM : 0xFFFFB74D);

        if (!warns.isEmpty()) {
            // 只显示前两条，多了会把整列顶开
            StringBuilder d = new StringBuilder("以下插件未能加载：\n");
            for (int i = 0; i < Math.min(2, warns.size()); i++) {
                d.append("· ").append(warns.get(i)).append('\n');
            }
            pluginStatus.setText(sb + "\n" + d.toString().trim());
        }
    }

    private void importPlugin() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT_PLUGIN);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void doImportPlugin(final Uri uri) {
        new Thread(() -> {
            String err = null;
            String name = null;
            try {
                // 两种插件共用一个入口：内容以 { 开头当声明式 JSON，
                // 否则当 shell 脚本。比看扩展名可靠 —— 从聊天软件存下来的文件
                // 经常没有后缀，或者被改成 .txt
                String text = readUriText(uri);
                String trimmed = text == null ? "" : text.trim();
                if (trimmed.startsWith("{")) {
                    name = PluginHost.importText(this, trimmed);
                } else {
                    name = ShellPlugins.importText(this,
                            uri.getLastPathSegment(), trimmed);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            final String fname = name;
            runOnUiThread(() -> {
                if (fname == null) {
                    Toast.makeText(this, "插件导入失败：" + ferr, Toast.LENGTH_LONG).show();
                    return;
                }
                reloadPlugins();
                Toast.makeText(this, "已导入 " + fname, Toast.LENGTH_SHORT).show();
            });
        }, "plugin-import").start();
    }

    /** 把选中的文件整份读成文本（插件导入要判断是 JSON 还是脚本） */
    private String readUriText(Uri uri) throws Exception {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalStateException("读不到文件");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bos.size() + n > 512 * 1024) break;   // 插件不该有半兆
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    private void reloadPlugins() {
        // force：用户点"重新加载"就是要把刚改的文件读进来，不能被节流挡回去
        PluginHost.load(this, true);
        ShellPlugins.load(this, true);
        List<SensorSpec> next = SensorTable.ensurePlugins(this, table);
        if (next != null) table = next;
        List<SensorSpec> next2 = SensorTable.ensureShellMetrics(this, table);
        if (next2 != null) table = next2;
        applySensors();
        refreshPluginStatus();
        refreshTransferStatus();
    }

    // ------------------------------------------------------------------ 控件工厂

    private void sectionLabel(LinearLayout col, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        tv.setTextColor(TEXT_DIM);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLetterSpacing(0.2f);
        tv.setPadding(dp(2), 0, 0, dp(8));
        col.addView(tv);
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private void refreshSteppers() {
        for (Runnable r : stepperRefreshers) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }

    private LinearLayout stepper(String name, java.util.function.Supplier<String> value,
                                 Runnable inc, Runnable dec) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(roundRect(BG_CARD, 11f, STROKE_CARD));
        box.setPadding(dp(3), dp(3), dp(3), dp(3));

        TextView minus = miniKey("\u2212");
        minus.setOnClickListener(v -> {
            dec.run();
            refreshSteppers();
        });
        box.addView(minus, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        label.setTextColor(TEXT_DIM);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.MONOSPACE);
        box.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 名称 + 当前值；值变化后自动刷新
        Runnable refresh = () -> label.setText(name + " " + value.get());
        stepperRefreshers.add(refresh);
        refresh.run();

        TextView plus = miniKey("+");
        plus.setOnClickListener(v -> {
            inc.run();
            refreshSteppers();
        });
        box.addView(plus, new LinearLayout.LayoutParams(dp(28), dp(28)));
        return box;
    }

    private TextView miniKey(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(TEXT_SOFT);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundRect(BG_CHIP, 8f, STROKE_CHIP));
        return tv;
    }

    private TextView smallButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tv.setTextColor(TEXT_SOFT);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(4), dp(11), dp(4), dp(11));
        tv.setBackground(roundRect(BG_CHIP, 11f, STROKE_CHIP));
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView bigButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(0xFF14100F);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setLetterSpacing(0.1f);
        tv.setBackground(roundRect(ACCENT, 14f, 0));
        tv.setOnClickListener(l);
        return tv;
    }

    private GradientDrawable roundRect(int color, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, Math.round(density * 0.75f)), strokeColor);
        }
        return d;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
