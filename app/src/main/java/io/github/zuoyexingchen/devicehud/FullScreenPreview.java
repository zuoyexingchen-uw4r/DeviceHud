package io.github.zuoyexingchen.devicehud;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全屏状态栏预览。
 *
 * <p>设置页里那条预览只能看到"条子自己长什么样"，看不出**和状态栏叠在一起**
 * 之后的效果：黑色背景下用 INK 主题会不会糊、白色背景下 MONO 主题是不是刺眼、
 * 花色壁纸上半透明到底还剩多少对比度 —— 这些只有放到真实层级里才看得出来。
 *
 * <p>所以这里做的是一个层级复刻，而不是放大版预览：
 * <pre>
 *   背景（黑 / 白 / 花）
 *     └─ 悬浮条（和桌面上那条用同一个 HudView、同一套参数）
 *          └─ 系统状态栏（时钟、信号、电池图标，叠在最上层）
 * </pre>
 * 画在上层的状态栏图标会**真的压住**条子的字，这正是用户在真机上看到的样子。
 */
public final class FullScreenPreview {

    /** 三种背景：越靠后越"花" */
    private static final int BG_BLACK = 0;
    private static final int BG_WHITE = 1;
    private static final int BG_BUSY = 2;
    private static final String[] BG_NAMES = {"黑背景", "白背景", "花色壁纸"};

    private FullScreenPreview() {
    }

    public static void show(Activity act) {
        if (act == null || act.isFinishing()) return;

        final Dialog dlg = new Dialog(act, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(0xFF000000);

        final BackdropView backdrop = new BackdropView(act);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 悬浮条：位置与参数完全照抄 HudState，尺寸也按真实屏幕宽度算
        final HudView bar = new HudView(act);
        final List<Sensor> sensors = styleBar(act, bar);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, HudState.barHeightDp));
        barLp.gravity = Gravity.TOP;
        barLp.topMargin = Math.max(0, HudState.y);
        root.addView(bar, barLp);

        // 模拟状态栏：画在条子之上，用来暴露"被状态栏图标压住"的问题
        final MockStatusBar status = new MockStatusBar(act);
        FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 30));
        stLp.gravity = Gravity.TOP;
        root.addView(status, stLp);

        // —— 底部控件
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 14));
        panel.setBackgroundColor(0x66000000);

        TextView hint = new TextView(act);
        hint.setText("全屏预览 · 三种背景逐一看对比度。返回键退出。");
        hint.setTextSize(11f);
        hint.setTextColor(0xFFD9CFD4);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setPadding(0, 0, 0, dp(act, 10));
        panel.addView(hint);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final TextView[] chips = new TextView[BG_NAMES.length];
        for (int i = 0; i < BG_NAMES.length; i++) {
            final int idx = i;
            TextView chip = new TextView(act);
            chip.setText(BG_NAMES[i]);
            chip.setTextSize(11f);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, dp(act, 10), 0, dp(act, 10));
            chip.setOnClickListener(v -> {
                backdrop.setMode(idx);
                status.setLight(idx == BG_WHITE);
                for (int k = 0; k < chips.length; k++) {
                    boolean on = k == idx;
                    chips[k].setTextColor(on ? 0xFFE58BA6 : 0x99FFFFFF);
                    chips[k].setBackground(round(act, on ? 0x33E58BA6 : 0x22FFFFFF, 10,
                            on ? 0xFFE58BA6 : 0x33FFFFFF));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(act, 3), 0, dp(act, 3), 0);
            row.addView(chip, lp);
            chips[i] = chip;
        }
        panel.addView(row);

        TextView close = new TextView(act);
        close.setText("退出预览");
        close.setTextSize(12f);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(0xFF14100F);
        close.setBackground(round(act, 0xFFE58BA6, 12, 0));
        close.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 44));
        clp.topMargin = dp(act, 10);
        panel.addView(close, clp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM;
        root.addView(panel, plp);

        dlg.setContentView(root);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // 盖住系统状态栏和导航栏：不然"条子压在状态栏上"这件事根本看不到
            w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        // 默认从"黑背景"开始，和大多数人桌面一致
        chips[BG_BLACK].performClick();

        // —— 数据循环：预览里也要看到真实读数，否则判断不了字号够不够
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable loop = new Runnable() {
            @Override
            public void run() {
                try {
                    DataCollector.Snapshot s = DataCollector.capture(act);
                    Map<String, Float> vals = Sensor.readAll(sensors, s);
                    // 不在这里喂 ChartHistory：预览是 Dialog，下面的 Activity 并没有
                    // 进 onPause，它的 dataLoop 还在跑并已经在喂了。两边都喂等于
                    // 采样频率翻倍，曲线会"跑得比真机快"。
                    float cur = vals.containsKey("batt-cur")
                            ? vals.get("batt-cur") : Math.abs(s.currentMa);
                    bar.setValues(vals, s.charging, cur);
                    status.setBattery(s.capacityPct);
                } catch (Exception ignored) {
                }
                h.postDelayed(this, Math.max(200L, HudState.updateMs));
            }
        };

        dlg.setOnDismissListener(d -> h.removeCallbacksAndMessages(null));
        dlg.show();
        // 只在 show 之后 post 一次：setOnShowListener 里再 post 会开两条循环，
        // 两条循环各自 postDelayed，读数是重复采样，白白翻倍耗电
        h.post(loop);
    }

    /** 把当前 HudState 的外观参数套到任意一个 HudView 上（预览与真窗共用同一套） */
    private static List<Sensor> styleBar(Activity act, HudView bar) {
        List<Sensor> sensors = Sensor.resolve(SensorTable.load(act), HudState.sensorIds);
        bar.setSensors(sensors);
        bar.setTheme(HudTheme.byIndex(HudState.themeIndex));
        bar.setTextSizeSp(HudState.textSizeSp);
        bar.setShowBackground(HudState.showBackground);
        bar.setInsets(HudState.insetLeft, HudState.insetRight);
        bar.setGapDp(HudState.gapDp);
        bar.setColors(HudState.bgColor, HudState.borderColor);
        bar.setBorderVisible(HudState.showBorder);
        bar.setRadiusDp(HudState.radiusDp);
        bar.setBackgroundMode(HudState.bgAnchor, HudState.bgScale);
        bar.setImageAlpha(HudState.imageAlphaPct);
        // 全屏预览里窗口 alpha 没法模拟（它不是窗口），改用 View 自身 alpha 近似
        bar.setAlpha(HudState.windowAlphaPct / 100f);

        int w = Screen.width(act);
        bar.setBackgroundImage(BgImage.load(act, HudState.bgImageUri, w,
                dp(act, HudState.barHeightDp), HudState.insetLeft, HudState.insetRight,
                HudState.bgAnchor, HudState.bgScale));
        return sensors;
    }

    // ------------------------------------------------------------------ 背景

    /** 三种背景：纯黑、纯白、彩色"壁纸" */
    private static final class BackdropView extends View {
        private int mode = BG_BLACK;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader gradient;

        BackdropView(Activity a) {
            super(a);
            text.setTypeface(Typeface.MONOSPACE);
            text.setColor(0x40FFFFFF);
            text.setTextSize(26f * a.getResources().getDisplayMetrics().density);
        }

        void setMode(int m) {
            mode = m;
            gradient = null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();

            if (mode == BG_BLACK) {
                canvas.drawColor(0xFF000000);
                return;
            }
            if (mode == BG_WHITE) {
                canvas.drawColor(0xFFFFFFFF);
                // 白色背景加一点点灰色文字，模拟"浅色壁纸/文档"，更接近真实观感
                canvas.drawText("00:00", w * 0.08f, h * 0.5f, dimText(0x22000000));
                return;
            }

            // 花色：竖向渐变 + 几个大色块，故意做高对比杂色
            if (gradient == null) {
                gradient = new LinearGradient(0, 0, w * 0.4f, h,
                        new int[]{0xFF2B5876, 0xFF7B4397, 0xFFDC2430, 0xFF1E9600},
                        new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(gradient);
            }
            canvas.drawRect(0, 0, w, h, paint);

            blob.setColor(0x55FFD166);
            canvas.drawCircle(w * 0.78f, h * 0.18f, w * 0.22f, blob);
            blob.setColor(0x4406D6A0);
            canvas.drawCircle(w * 0.18f, h * 0.72f, w * 0.3f, blob);
            blob.setColor(0x33FFFFFF);
            canvas.drawCircle(w * 0.55f, h * 0.55f, w * 0.16f, blob);
        }

        private Paint dimText(int color) {
            text.setColor(color);
            return text;
        }
    }

    // ------------------------------------------------------------------ 模拟状态栏

    /**
     * 极简的假状态栏：左边时钟、右边电池/信号/ wifi。
     *
     * <p>目的不是"像真的"，而是**在条子上盖一层同样的白字**，
     * 让用户看清楚自己的条子在状态栏下面还剩多少可读性。
     */
    private static final class MockStatusBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean light;
        private float battery = Float.NaN;
        private final float d;

        MockStatusBar(Activity a) {
            super(a);
            d = a.getResources().getDisplayMetrics().density;
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(12f * a.getResources().getDisplayMetrics().scaledDensity);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(1.4f * d);
            glyph.setStrokeCap(Paint.Cap.ROUND);
        }

        void setLight(boolean on) {
            light = on;
            invalidate();
        }

        void setBattery(float pct) {
            battery = pct;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0) return;

            int fg = light ? 0xFF000000 : 0xFFFFFFFF;
            p.setColor(fg);
            glyph.setColor(fg);

            float cy = h / 2f;
            // 左：时钟（ColorOS 把时间放左边，和条子第一项正面撞车）
            String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            float baseline = cy - (p.ascent() + p.descent()) / 2f;
            canvas.drawText(time, 12f * d, baseline, p);

            // 右：电池 + 信号 + wifi，从右往左排
            float x = w - 12f * d;
            RectF bat = new RectF(x - 20f * d, cy - 6f * d, x, cy + 6f * d);
            glyph.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(bat, 2f * d, 2f * d, glyph);
            canvas.drawLine(x, cy - 2f * d, x + 2f * d, cy - 2f * d, glyph);
            canvas.drawLine(x, cy + 2f * d, x + 2f * d, cy + 2f * d, glyph);

            float fill = Float.isNaN(battery) ? 0.7f : Math.max(0, Math.min(1, battery / 100f));
            glyph.setStyle(Paint.Style.FILL);
            canvas.drawRect(bat.left + 1.5f * d, bat.top + 1.5f * d,
                    bat.left + 1.5f * d + (bat.width() - 3f * d) * fill, bat.bottom - 1.5f * d,
                    glyph);
            glyph.setStyle(Paint.Style.STROKE);

            // 信号：四根小竖条
            float sx = bat.left - 16f * d;
            for (int i = 0; i < 4; i++) {
                float bh = (3f + i * 2f) * d;
                canvas.drawLine(sx + i * 3.4f * d, cy + 3f * d,
                        sx + i * 3.4f * d, cy + 3f * d - bh, glyph);
            }

            // wifi：两段弧
            float wx = sx - 16f * d;
            RectF arc = new RectF(wx - 6f * d, cy - 4f * d, wx + 6f * d, cy + 8f * d);
            canvas.drawArc(arc, 215f, 110f, false, glyph);
            canvas.drawCircle(wx, cy + 3.5f * d, 1.2f * d, glyph);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static android.graphics.drawable.GradientDrawable round(Activity a, int color,
                                                                    float radiusDp, int stroke) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusDp * a.getResources().getDisplayMetrics().density);
        if (stroke != 0) {
            g.setStroke(Math.max(1, Math.round(a.getResources().getDisplayMetrics().density
                    * 0.75f)), stroke);
        }
        return g;
    }
}
