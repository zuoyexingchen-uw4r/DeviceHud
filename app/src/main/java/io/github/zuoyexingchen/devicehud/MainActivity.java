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
        col.setPadding(dp(18), dp(10), dp(18), dp(30));
        int contentW = Math.min(screenW - dp(24), dp(760));
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
        buildSensors(col);
        buildRate(col);
        buildLayoutTuning(col);
        buildAppearance(col);
        buildTheme(col);
        buildOverlayControl(col);
        buildSystem(col);

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
        sub.setText("sensor mux \u00B7 independent sample / update \u00B7 interval averaging");
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
        sectionLabel(col, "SENSORS");

        col.addView(smallButton("管理传感器 / 单位 / 样式\u2026", v -> openSensorManager()),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

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
        sectionLabel(col, "APPEARANCE");

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
        if (requestCode != REQ_BG_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
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
        sectionLabel(col, "OVERLAY CONTROL");

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

        col.addView(spacer(12));
        toggleBtn = bigButton("开启悬浮窗", v -> {
            if (HudState.running) {
                stopService(new Intent(this, HudService.class));
                handler.postDelayed(this::syncToggle, 150);
            } else {
                startHud();
            }
        });
        col.addView(toggleBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
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

    private void buildSystem(LinearLayout col) {
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
        rule.setPadding(dp(2), dp(6), 0, dp(18));
        col.addView(rule);

        sectionLabel(col, "NOTIFICATION");

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
        notifHint.setPadding(dp(2), dp(4), 0, dp(6));
        col.addView(notifHint);

        // 系统那条「X 正在其他应用上层显示」由 WindowManager 在悬浮窗表面可见时投放，
        // 只认"窗口可见"这一个条件，应用无权改写或撤销，这里只把人送到它的通道开关前
        col.addView(smallButton("系统悬浮窗提醒开关（系统发的，非本应用）\u2026",
                        v -> openSystemOverlayNotifSettings()),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(spacer(18));

        sectionLabel(col, "SYSTEM");

        autoBtn = smallButton("开机自启：--", v -> {
            boolean next = !Prefs.autoStart(this);
            Prefs.setAutoStart(this, next);
            syncSystemState();
        });
        col.addView(autoBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(spacer(8));

        batteryBtn = smallButton("电池优化：--", v -> requestIgnoreBattery());
        col.addView(batteryBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(spacer(8));

        CheckBox recents = batCheck("在最近任务中隐藏本应用", Prefs.hideRecents(this), (btn, c) -> {
            Prefs.setHideRecents(this, c);
            applyRecentsHidden();
        });
        col.addView(recents);

        col.addView(spacer(8));

        shizukuBtn = smallButton("Shizuku：--", v -> onShizukuClick());
        col.addView(shizukuBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView shizukuNote = new TextView(this);
        shizukuNote.setText("高权限 Shell 有两条路径：\n"
                + "电脑旁路桥（电脑上跑 bridge/bridge.sh start）\n"
                + "或 Shizuku 授权。任一就绪即可读到\n"
                + "真实 CPU 占用率（整机 + 每核）");
        shizukuNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        shizukuNote.setTextColor(0xFF3E383D);
        shizukuNote.setTypeface(Typeface.MONOSPACE);
        shizukuNote.setPadding(dp(2), dp(6), 0, 0);
        col.addView(shizukuNote);

        btBtn = smallButton("蓝牙电量：--", v -> onBtClick());
        col.addView(btBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
                + "有电量变化时系统会广播，平时按上面的间隔主动轮询一次。\n"
                + "权限「附近的设备」只用来读已配对设备的名称与电量。");
        btNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        btNote.setTextColor(0xFF3E383D);
        btNote.setTypeface(Typeface.MONOSPACE);
        btNote.setPadding(dp(2), dp(6), 0, 0);
        col.addView(btNote);

        TextView note = new TextView(this);
        note.setText("息屏后采样与绘制全部停止，亮屏自动恢复");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        note.setTextColor(0xFF3E383D);
        note.setTypeface(Typeface.MONOSPACE);
        note.setPadding(dp(2), dp(10), 0, 0);
        col.addView(note);
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

            Map<String, Float> vals = new HashMap<>();
            for (Sensor se : activeSensors) {
                vals.put(se.id(), se.read(s));
            }
            float cur = vals.containsKey("batt-cur")
                    ? vals.get("batt-cur") : Math.abs(s.currentMa);
            preview.setValues(vals, s.charging, cur);

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
