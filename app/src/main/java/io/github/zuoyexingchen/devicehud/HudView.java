package io.github.zuoyexingchen.devicehud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 信息条渲染层。
 *
 * 布局规则：
 *   1. 背景永远铺满整个 View 宽度；
 *   2. 文字只排在 [insetLeft, width-insetRight] 区间内；
 *   3. 区间按传感器数量等分槽位，每个传感器在自己的槽位里居中 ——
 *      这样增删传感器时整体自动重新均分，不需要手调间距。
 *
 * 触摸：窗口设了 FLAG_NOT_TOUCHABLE，这里不处理任何手势。
 */
public class HudView extends View {

    private static final float HOT_C = 45f;
    private static final int MEM_HIGH = 88;
    private static final float USAGE_HIGH = 85f;
    /** 充电缓慢告警色 */
    private static final int WARN_RED = 0xFFFF5252;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** 背景图（已按缩放/对齐裁好；等比与拉伸模式下正好等于可见尺寸） */
    private android.graphics.Bitmap bgImage;
    /** 背景图对齐与缩放模式，见 {@link BgImage} */
    private int bgAnchor = BgImage.ANCHOR_CENTER;
    private int bgScale = BgImage.SCALE_FIT;
    private final android.graphics.Path imgClip = new android.graphics.Path();
    private final RectF imgDst = new RectF();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // —— 胶囊图表用的绘制资源（字段持有，避免每帧分配）
    private final Paint chartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capsulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chartDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path chartPath = new android.graphics.Path();
    private final RectF chartBox = new RectF();
    private final float[] chartBuf = new float[ChartHistory.CAPACITY];

    private final List<Sensor> sensors = new ArrayList<>();
    private final Map<String, Float> values = new HashMap<>();
    private final Map<String, Typeface> tfCache = new HashMap<>();
    private boolean charging;
    /** 本区间平均电流（mA），用于判断是否"充电缓慢" */
    private float avgCurrentMa = Float.NaN;

    private final float density;
    private float radius;
    private final float innerGap;
    /** 用户设定的理想间距 */
    private float gapPref;
    private int radiusDp = 12;
    private final float strokeW;
    private final float fontScale;
    private float baseSp = 12f;
    private float padV;

    private int insetLeft;
    private int insetRight;

    private boolean showBackground = true;
    private boolean showBorder = true;
    /** 自定义色（0 = 跟随主题） */
    private int customBg = 0;
    private int customStroke = 0;
    private HudTheme theme = HudTheme.INK;

    public HudView(Context context) {
        this(context, null);
    }

    public HudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        fontScale = getResources().getDisplayMetrics().scaledDensity;

        radius = 12f * density;
        innerGap = 5f * density;
        gapPref = 10f * density;
        padV = 8f * density;
        strokeW = Math.max(1f, density * 0.75f);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(theme.bg);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeW);
        strokePaint.setColor(theme.stroke);

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(baseSp * fontScale);
        textPaint.setSubpixelText(true);
    }

    // ------------------------------------------------------------------ 配置

    public void setTheme(HudTheme t) {
        this.theme = (t == null) ? HudTheme.INK : t;
        applyColors();
        invalidate();
    }

    /** 背景色 / 边框色（0 = 跟随主题） */
    public void setColors(int bg, int stroke) {
        this.customBg = bg;
        this.customStroke = stroke;
        applyColors();
        invalidate();
    }

    private void applyColors() {
        bgPaint.setColor(customBg != 0 ? customBg : theme.bg);
        strokePaint.setColor(customStroke != 0 ? customStroke : theme.stroke);
    }

    /** 背景图；传 null 取消 */
    public void setBackgroundImage(android.graphics.Bitmap b) {
        if (this.bgImage == b) return;
        this.bgImage = b;
        invalidate();
    }

    /**
     * 背景图自身的不透明度（0~100%）。
     *
     * <p>只作用在图片这一层，文字、边框、底色都不受影响 ——
     * 想压暗图片但保持读数清楚时用这个。
     */
    public void setImageAlpha(int pct) {
        int a = Math.round(Math.max(0, Math.min(100, pct)) * 255f / 100f);
        if (imgPaint.getAlpha() == a) return;
        imgPaint.setAlpha(a);
        invalidate();
    }

    /** 背景图的对齐方式与缩放模式（{@link BgImage} 里的常量） */
    public void setBackgroundMode(int anchor, int scale) {
        int a = BgImage.clampAnchor(anchor);
        int sc = BgImage.clampScale(scale);
        if (a == bgAnchor && sc == bgScale) return;
        bgAnchor = a;
        bgScale = sc;
        invalidate();
    }

    /** 无边框模式 */
    public void setBorderVisible(boolean on) {
        this.showBorder = on;
        invalidate();
    }

    /** 圆角弧半径（dp），0 = 直角 */
    public void setRadiusDp(int dp) {
        this.radiusDp = Math.max(0, dp);
        this.radius = radiusDp * density;
        invalidate();
    }

    /** 传感器间距（dp） */
    public void setGapDp(int dp) {
        this.gapPref = Math.max(0, dp) * density;
        invalidate();
    }

    public void setShowBackground(boolean show) {
        this.showBackground = show;
        invalidate();
    }

    public void setTextSizeSp(float sp) {
        baseSp = sp;
        textPaint.setTextSize(sp * fontScale);
        requestLayout();
        invalidate();
    }

    public void setCompact(boolean compact) {
        padV = (compact ? 4f : 8f) * density;
        requestLayout();
        invalidate();
    }

    /** 设置要显示的传感器（顺序即渲染顺序） */
    public void setSensors(List<Sensor> list) {
        sensors.clear();
        if (list != null) sensors.addAll(list);
        requestLayout();
        invalidate();
    }

    /** 左右留白：该区域不排文字，背景仍然覆盖 */
    public void setInsets(int leftPx, int rightPx) {
        this.insetLeft = Math.max(0, leftPx);
        this.insetRight = Math.max(0, rightPx);
        invalidate();
    }

    public void setValues(Map<String, Float> v, boolean charging) {
        setValues(v, charging, Float.NaN);
    }

    public void setValues(Map<String, Float> v, boolean charging, float avgCurrentMa) {
        values.clear();
        if (v != null) values.putAll(v);
        this.charging = charging;
        this.avgCurrentMa = avgCurrentMa;
        invalidate();
    }

    // ------------------------------------------------------------------ 测量

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int wMode = MeasureSpec.getMode(widthMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);

        float base = baseSp * fontScale;
        float maxTextH = 0f;

        if (wMode != MeasureSpec.EXACTLY) {
            float total = insetLeft + insetRight;
            for (Sensor s : sensors) {
                textPaint.setTextSize(sizeFor(s));
                textPaint.setTypeface(typefaceFor(s.spec));
                total += textPaint.measureText(s.label()) + innerGap
                        + textPaint.measureText(sampleValue(s));
                float th = textPaint.descent() - textPaint.ascent();
                if (th > maxTextH) maxTextH = th;
            }
            w = (int) Math.ceil(total + 24f * density);
        } else {
            textPaint.setTextSize(base);
            maxTextH = textPaint.descent() - textPaint.ascent();
        }

        if (hMode != MeasureSpec.EXACTLY) {
            h = (int) Math.ceil(maxTextH + padV * 2f);
        }

        textPaint.setTextSize(base);
        textPaint.setTypeface(Typeface.MONOSPACE);
        setMeasuredDimension(w, h);
    }

    private float sizeFor(Sensor s) {
        int sp = s.spec.textSp;
        return (sp > 0 ? sp : baseSp) * fontScale;
    }

    /**
     * 基础字体：有自定义字体就用它，否则系统等宽。
     *
     * <p>等宽是刻意的默认值 —— 数值每秒都在变，非等宽字体会让整条在跳动，
     * 想换观感就自己带一个等宽的 ttf。
     */
    private Typeface baseTypeface() {
        Typeface custom = HudFont.typeface(getContext());
        return custom != null ? custom : Typeface.MONOSPACE;
    }

    private Typeface typefaceFor(SensorSpec spec) {
        Typeface base = baseTypeface();
        if (!spec.bold && !spec.italic) return base;

        // key 里带上字体身份：换字体之后 bold/italic 的派生字体也必须重新建，
        // 否则换了字体只有正体变了、粗体还是老字体
        String key = spec.id + (spec.bold ? "b" : "") + (spec.italic ? "i" : "")
                + "@" + System.identityHashCode(base);
        Typeface tf = tfCache.get(key);
        if (tf == null) {
            int style = (spec.bold ? Typeface.BOLD : 0) | (spec.italic ? Typeface.ITALIC : 0);
            tf = Typeface.create(base, style);
            tfCache.put(key, tf);
        }
        return tf;
    }

    /**
     * 估宽用的样本串。
     *
     * <p>普通项用 "000.00+单位" 这种定长串估算，数值怎么变都不会引起排布抖动；
     * 占位方块要按它真实的空格数算 —— 以前这里也用了 "000.00"，
     * 于是改宽度参数完全没反应。
     */
    private String sampleValue(Sensor s) {
        if (s.spec.kind == SensorSpec.Kind.PLACEHOLDER) {
            return " ".repeat(Math.max(1, s.spec.arg));
        }
        // 图表没有数值文本，宽度由 chartWidth 单独给
        if (s.spec.kind == SensorSpec.Kind.CHART) return "";
        return "000.00" + s.unit();
    }

    /** 胶囊图表在条上占的宽度（dp）：太窄画不出趋势，太宽又挤掉别的项 */
    private float chartWidth() {
        return 64f * density;
    }

    /** 单个传感器在条上大约占的宽度（用固定占位串估算，避免数值变化导致排布抖动） */
    private float unitWidth(Sensor s) {
        textPaint.setTextSize(sizeFor(s));
        textPaint.setTypeface(typefaceFor(s.spec));
        float lw = textPaint.measureText(s.label());
        if (s.spec.kind == SensorSpec.Kind.CHART) {
            return Math.max(lw + (lw > 0 ? innerGap : 0f) + chartWidth(), 6f * density);
        }
        float vw = textPaint.measureText(sampleValue(s));
        float w = lw + (lw > 0 ? innerGap : 0f) + vw;
        return Math.max(w, 6f * density);
    }

    /**
     * 在给定内容宽度内能放几个传感器。
     *
     * <p>间距就是用户设定的那个值——调小就能塞下更多列，调大就更松散。
     * 之前这里在溢出时固定按 0 间距贪心，导致把间距从 10 调到 0 完全没有效果，
     * 现在改成严格用 gapPref 排布，间距参数才真正起作用。
     */
    private int fitCount(float contentW) {
        int n = sensors.size();
        if (n == 0) return 0;

        float used = 0f;
        int count = 0;
        for (int i = 0; i < n; i++) {
            float uw = unitWidth(sensors.get(i));
            float sep = count > 0 ? gapPref : 0f;
            if (count > 0 && used + uw + sep > contentW) break;
            used += uw + sep;
            count++;
        }
        restorePaint();
        return count;
    }

    private void restorePaint() {
        textPaint.setTextSize(baseSp * fontScale);
        textPaint.setTypeface(Typeface.MONOSPACE);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1) 背景始终铺满整条，包括左右留白区
        if (showBackground) {
            float inset = strokeW / 2f;
            rect.set(inset, inset, w - inset, h - inset);
            canvas.drawRoundRect(rect, radius, radius, bgPaint);

            // 背景图：按对齐方式摆到条上，再按圆角裁掉溢出部分。
            // 等比/拉伸解码出来就是可见尺寸，所以这里直接按原图大小画；
            // 1:1 模式下原图就是像素尺寸，正好一个像素对一个像素。
            if (bgImage != null && !bgImage.isRecycled()) {
                float bw = bgImage.getWidth();
                float bh = bgImage.getHeight();

                float[] box = new float[2];
                BgImage.box(bgAnchor, (int) w, insetLeft, insetRight, box);

                float left;
                switch (bgAnchor) {
                    case BgImage.ANCHOR_RIGHT_SCREEN:
                    case BgImage.ANCHOR_RIGHT_INSET:
                        left = box[1] - bw;
                        break;
                    case BgImage.ANCHOR_CENTER:
                        left = box[0] + (box[1] - box[0] - bw) / 2f;
                        break;
                    default:
                        left = box[0];
                        break;
                }
                float top = (h - bh) / 2f;
                imgDst.set(left, top, left + bw, top + bh);

                boolean clipped = radius > 0.5f;
                if (clipped) {
                    imgClip.reset();
                    imgClip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(imgClip);
                }
                canvas.drawBitmap(bgImage, null, imgDst, imgPaint);
                if (clipped) canvas.restore();
            }

            if (showBorder) {
                canvas.drawRoundRect(rect, radius, radius, strokePaint);
            }
        }

        int n = sensors.size();
        if (n == 0) return;

        // 2) 文字只排在留白之间
        float contentL = insetLeft;
        float contentR = w - insetRight;
        if (contentR - contentL < 1f) {
            contentL = 0f;
            contentR = w;
        }
        float contentW = contentR - contentL;

        // 3) 先按宽度算能放几个，再与用户设定的上限取小：
        //    超过上限的项**直接丢掉**，不再压缩间距（挤在一起的数字谁也看不清）
        int show = fitCount(contentW);
        int cap = HudState.maxItems > 0 ? HudState.maxItems : 9;
        if (show > cap) show = cap;
        if (show < 1) show = 1;

        float slot = contentW / show;
        float baseSize = baseSp * fontScale;

        for (int i = 0; i < show; i++) {
            Sensor s = sensors.get(i);
            SensorSpec sp = s.spec;

            // 单项样式覆盖：字号 / 粗斜体
            textPaint.setTextSize(sizeFor(s));
            textPaint.setTypeface(typefaceFor(sp));

            // 图表项自己占满整个槽位（标签 + 胶囊 sparkline），不走文字排版
            if (sp.kind == SensorSpec.Kind.CHART) {
                drawChart(canvas, s, contentL + slot * i, slot, h);
                continue;
            }

            Float boxed = values.get(s.id());
            float v = (boxed == null) ? Float.NaN : boxed;

            String label = s.label();
            String val = s.format(v) + s.unit();

            float lw = textPaint.measureText(label);
            float vw = textPaint.measureText(val);
            float unitW = lw + innerGap + vw;
            float cx = contentL + slot * (i + 0.5f);
            float sx = cx - unitW / 2f;
            // 字号不同则基线不同，逐项垂直居中
            float bl = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;

            textPaint.setColor(sp.color != -1 ? sp.color : theme.dim);
            canvas.drawText(label, sx, bl, textPaint);

            textPaint.setColor(valueColor(sp, s, v));
            canvas.drawText(val, sx + lw + innerGap, bl, textPaint);
        }

        textPaint.setTextSize(baseSize);
        textPaint.setTypeface(Typeface.MONOSPACE);
    }

    /**
     * 画一个胶囊图表。
     *
     * <p>布局：左边是标签（跟着字号走），右边一条圆角胶囊，里面是最近
     * {@code chartPoints} 个采样点的折线。圆角半径取高度的一半，看起来就是"胶囊"。
     *
     * <p>量程有两种：
     * <ul>
     *   <li>自动：取窗口内 min/max，上下各留 8% 余量。数据几乎不变时
     *       （比如温度稳定在 38.0）会退化成一条贴中线的直线，所以设了一个
     *       最小跨度 —— 否则 0.1 度的噪声会被放大成满屏锯齿，看着像在剧烈波动。</li>
     *   <li>手动：用用户填的 min/max，超出范围的采样点裁到边界，不会画出胶囊。</li>
     * </ul>
     */
    private void drawChart(Canvas canvas, Sensor s, float slotLeft, float slotW, float h) {
        SensorSpec sp = s.spec;

        float padX = 2f * density;
        float labelW = 0f;
        String label = s.label();
        float baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
        if (!label.isEmpty()) {
            labelW = textPaint.measureText(label) + innerGap;
            textPaint.setColor(resolveLabelColor(sp, theme));
            canvas.drawText(label, slotLeft + padX, baseline, textPaint);
        }

        float boxL = slotLeft + padX + labelW;
        float boxR = slotLeft + slotW - padX;
        if (boxR - boxL < 14f * density) return;    // 槽位太窄，画了也是一团糊

        float boxH = Math.min(h * 0.56f, 22f * density);
        float cy = h / 2f;
        chartBox.set(boxL, cy - boxH / 2f, boxR, cy + boxH / 2f);

        int lineColor = sp.color != -1 ? sp.color : theme.accent;
        float radius = boxH / 2f;

        capsulePaint.setStyle(Paint.Style.FILL);
        capsulePaint.setColor(withAlpha(lineColor, 26));
        canvas.drawRoundRect(chartBox, radius, radius, capsulePaint);

        int total = ChartHistory.copy(sp.src, chartBuf);
        int want = Math.max(8, Math.min(sp.chartPoints, ChartHistory.CAPACITY));
        if (total < 2) return;
        int start = Math.max(0, total - want);
        int n = total - start;

        float lo;
        float hi;
        if (sp.chartMode == SensorSpec.CHART_MANUAL) {
            lo = Math.min(sp.chartMin, sp.chartMax);
            hi = Math.max(sp.chartMin, sp.chartMax);
            if (hi - lo < 1e-6f) hi = lo + 1f;
        } else {
            lo = Float.MAX_VALUE;
            hi = -Float.MAX_VALUE;
            for (int i = start; i < total; i++) {
                float v = chartBuf[i];
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            float span = hi - lo;
            float minSpan = Math.max(1e-3f, Math.abs(hi) * 0.02f);
            if (span < minSpan) {
                float mid = (lo + hi) / 2f;
                lo = mid - minSpan / 2f;
                hi = mid + minSpan / 2f;
            } else {
                float padY = span * 0.08f;
                lo -= padY;
                hi += padY;
            }
        }

        float inset = 1.5f * density;
        float x0 = chartBox.left + inset;
        float x1 = chartBox.right - inset;
        float y0 = chartBox.top + inset;
        float y1 = chartBox.bottom - inset;

        chartPath.reset();
        float lastY = (y0 + y1) / 2f;
        for (int i = 0; i < n; i++) {
            float v = chartBuf[start + i];
            float px = x0 + (x1 - x0) * (i / (float) (n - 1));
            float t = (v - lo) / (hi - lo);
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            float py = y1 - (y1 - y0) * t;
            if (i == 0) chartPath.moveTo(px, py);
            else chartPath.lineTo(px, py);
            lastY = py;
        }

        chartPaint.setStyle(Paint.Style.STROKE);
        // 线宽跟着单项设置走（默认 1.4dp）。太细在户外看不清，
        // 太粗会把整个胶囊糊成一块，所以这里只做下限保护
        chartPaint.setStrokeWidth(Math.max(0.8f, sp.chartStroke * density));
        chartPaint.setColor(lineColor);
        chartPaint.setStrokeCap(Paint.Cap.ROUND);
        chartPaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(chartPath, chartPaint);

        // 末端亮点：一眼看出最新值落在哪
        chartDotPaint.setStyle(Paint.Style.FILL);
        chartDotPaint.setColor(lineColor);
        canvas.drawCircle(x1, lastY, Math.max(1.2f, sp.chartStroke * 1.15f) * density,
                chartDotPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /**
     * 值着色优先级：
     *   1. BAT 充放电着色开关（开启时强制接管电源项，忽略主题与单项色）
     *   2. 单项自定义色
     *   3. 主题规则（温度/内存越界）
     *
     * 做成静态是为了让通知里那一行文字复用完全相同的规则，
     * 否则条上和通知里出现两种红，很容易看岔。
     */
    private int valueColor(SensorSpec sp, Sensor s, float v) {
        return resolveValueColor(sp, s, v, theme, charging, avgCurrentMa);
    }

    public static int resolveValueColor(SensorSpec sp, Sensor s, float v,
                                        HudTheme th, boolean charging, float avgCur) {
        if (HudState.batColor && s.role() == SensorSpec.Role.POWER) {
            return batteryColor(th, charging, avgCur);
        }
        if (sp.color != -1) return sp.color;
        return colorFor(s, v, th);
    }

    /** 标签色：单项自定义色优先，否则用主题次文字色 */
    public static int resolveLabelColor(SensorSpec sp, HudTheme th) {
        return sp.color != -1 ? sp.color : th.dim;
    }

    private static int batteryColor(HudTheme th, boolean charging, float avgCurrentMa) {
        if (!charging) return th.text;                  // 放电：跟随主题主色
        // 用户点了"消除充电警报"：本次充电内不再标红
        if (HudState.batSlowWarn && !HudState.muteChargeAlert
                && !Float.isNaN(avgCurrentMa)
                && avgCurrentMa < HudState.slowChargeMa) {
            return WARN_RED;                            // 插着电但没真正充进去
        }
        return th.charge;                               // 正常充电：主题充电色
    }

    /** 着色策略：温度/内存越界转强调色，电源项按充放电区分 */
    private static int colorFor(Sensor s, float v, HudTheme th) {
        if (Float.isNaN(v)) return th.dim;

        switch (s.role()) {
            case TEMP:
                return v >= HOT_C ? th.accent : th.text;
            case MEMORY:
                return v >= MEM_HIGH ? th.accent : th.text;
            case USAGE:
                return v >= USAGE_HIGH ? th.accent : th.text;
            case POWER:
                return th.text;
            default:
                return th.text;
        }
    }
}
