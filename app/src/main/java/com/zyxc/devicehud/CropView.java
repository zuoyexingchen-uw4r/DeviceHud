package com.zyxc.devicehud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 条带专用裁剪器。
 *
 * <h3>为什么自己写</h3>
 * 悬浮条是 3392×84 这种 40:1 的极端比例，系统裁剪器要么直接拒绝这个比例、
 * 要么按"自由裁剪"给你一个随手拉歪的框。这里把比例固定成条的宽高比，
 * 用户只做两件事：拖、缩放，选中的那一条带就是最终上屏的样子。
 *
 * <h3>坐标系</h3>
 * 位图 → 视图用一个 {@link Matrix}；裁剪窗口（{@link #band}）固定在视图中间，
 * 不随手指移动。这样裁剪结果永远是"窗口内的那块源区域"，
 * 换算只需把 band 反向映射回源坐标。
 *
 * <h3>为什么窗口可能很薄</h3>
 * 40:1 的窗口在手机屏上只有几十像素高，这是**真实比例**，不做拉伸——
 * 窗口外的画面用暗色压住，能看到上下文，便于判断选哪一条。
 */
public class CropView extends View {

    private static final float MAX_ZOOM = 12f;
    /** 允许缩小到"盖满窗口"尺度的多少倍 —— 小于 1 就意味着图片比窗口窄，两侧留透明 */
    private static final float MIN_ZOOM_OUT = 0.10f;

    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix matrix = new Matrix();
    private final Matrix inv = new Matrix();
    private final RectF band = new RectF();
    private final RectF srcRect = new RectF();
    private final RectF imgRect = new RectF();
    private final Path dimPath = new Path();
    private final Path edgePath = new Path();

    private Bitmap source;
    /** 宽高比 = 宽 / 高（始终 > 0） */
    private float aspect = 1f;

    /** 盖满裁剪窗口所需的尺度（"1x"的那种铺满），重置时回到这里 */
    private float coverScale = 1f;
    /** 手势允许的最小尺度 = coverScale * MIN_ZOOM_OUT */
    private float minScale = 1f;
    private float curScale = 1f;

    private final float density;

    // —— 手势
    private float lastX;
    private float lastY;
    private float pinchDist;
    private float pinchX;
    private float pinchY;
    private boolean pinching;

    public CropView(Context c) {
        this(c, null);
    }

    public CropView(Context c, AttributeSet attrs) {
        super(c, attrs);
        density = getResources().getDisplayMetrics().density;
        dimPaint.setColor(0xCC000000);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Math.max(1f, density * 1.2f));
        edgePaint.setColor(0xFFE58BA6);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, density * 0.5f));
        gridPaint.setColor(0x33FFFFFF);
        // 40:1 的窗口只有几十像素高，只画一条线太容易看丢，
        // 里面再垫一层很淡的强调色，既看得见又不影响判断画面
        bandFill.setColor(0x2EE58BA6);
    }

    /**
     * 设置源图与目标宽高比。
     *
     * @param bmp    源图（不持有所有权，调用方负责回收）
     * @param aspect 宽 / 高，必须 > 0
     */
    public void setSource(Bitmap bmp, float aspect) {
        this.source = bmp;
        this.aspect = (aspect > 0.01f) ? aspect : 0.01f;
        layoutBand(getWidth(), getHeight());
        resetTransform();
        invalidate();
    }

    public boolean hasSource() {
        return source != null && !source.isRecycled();
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutBand(w, h);
        resetTransform();
    }

    /** 裁剪窗口：水平撑满（留一点边距），高度按比例推；太高就反过来由高度推宽度 */
    private void layoutBand(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float pad = 6f * density;
        float maxW = Math.max(1f, w - pad * 2f);
        float maxH = Math.max(1f, h - pad * 2f);

        float bw = maxW;
        float bh = bw / aspect;
        if (bh > maxH) {
            bh = maxH;
            bw = bh * aspect;
        }
        // 极窄的条（40:1）窗口只有几十像素高，给一个下限纯粹是为了能看清边线，
        // 但**不改变比例**：高度不为 0 就按比例算宽度，宽度才是决定性的那一维。
        float cx = w / 2f;
        float cy = h / 2f;
        band.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
    }

    /** 复位：图片刚好盖住窗口，居中 */
    public void resetTransform() {
        if (!hasSource() || band.width() <= 1f) return;
        matrix.reset();
        float sx = band.width() / source.getWidth();
        float sy = band.height() / source.getHeight();
        coverScale = Math.max(sx, sy);
        // 缩放下限放开到 1/10：图片可以比裁剪窗口小，
        // 空出来的部分在输出里是纯透明的，不像以前那样被强制拉回铺满
        minScale = coverScale * MIN_ZOOM_OUT;
        curScale = coverScale;
        matrix.postScale(coverScale, coverScale);

        srcRect.set(0, 0, source.getWidth(), source.getHeight());
        imgRect.set(srcRect);
        matrix.mapRect(imgRect);

        // 让窗口中心对准图片中心
        float dx = band.centerX() - imgRect.centerX();
        float dy = band.centerY() - imgRect.centerY();
        matrix.postTranslate(dx, dy);
        clamp();
        invalidate();
    }

    // ------------------------------------------------------------------ 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!hasSource()) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                pinching = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinchDist = dist(e);
                    pinchX = (e.getX(0) + e.getX(1)) / 2f;
                    pinchY = (e.getY(0) + e.getY(1)) / 2f;
                    pinching = true;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    if (pinchDist > 1f && d > 1f) {
                        float k = d / pinchDist;
                        pinchDist = d;
                        zoomBy(k, pinchX, pinchY);
                    }
                    return true;
                }
                float x = e.getX();
                float y = e.getY();
                matrix.postTranslate(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                clamp();
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                // 抬起一根手指后，用剩下那根重新起算，避免跳一下
                if (e.getPointerCount() >= 2) {
                    int keep = (e.getActionIndex() == 0) ? 1 : 0;
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pinching = false;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    private float dist(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0);
        float dy = e.getY(1) - e.getY(0);
        return (float) Math.hypot(dx, dy);
    }

    /** 以 (px,py) 为锚点缩放，结果夹在 [minScale, minScale*MAX_ZOOM] */
    private void zoomBy(float k, float px, float py) {
        float target = curScale * k;
        float lo = minScale;
        float hi = coverScale * MAX_ZOOM;
        if (target < lo) target = lo;
        if (target > hi) target = hi;
        float real = target / curScale;
        if (Math.abs(real - 1f) < 0.0005f) return;

        matrix.postScale(real, real, px, py);
        curScale = target;
        clamp();
        invalidate();
    }

    /**
     * 位置约束。
     *
     * <p>图片比窗口大时按"必须盖满"约束（贴边）；
     * 比窗口小时允许自由摆放，只保证它别整块滑出窗口 —— 这时窗口里
     * 没被图片盖住的部分就是透明，正是"缩到小于 1x，两侧留空"要的效果。
     */
    private void clamp() {
        if (!hasSource()) return;
        imgRect.set(srcRect);
        matrix.mapRect(imgRect);

        float dx = 0f;
        float dy = 0f;

        if (imgRect.width() >= band.width()) {
            if (imgRect.left > band.left) dx = band.left - imgRect.left;
            else if (imgRect.right < band.right) dx = band.right - imgRect.right;
        } else {
            if (imgRect.right < band.left + 1f) dx = band.left + 1f - imgRect.right;
            else if (imgRect.left > band.right - 1f) dx = band.right - 1f - imgRect.left;
        }

        if (imgRect.height() >= band.height()) {
            if (imgRect.top > band.top) dy = band.top - imgRect.top;
            else if (imgRect.bottom < band.bottom) dy = band.bottom - imgRect.bottom;
        } else {
            if (imgRect.bottom < band.top + 1f) dy = band.top + 1f - imgRect.bottom;
            else if (imgRect.top > band.bottom - 1f) dy = band.bottom - 1f - imgRect.top;
        }

        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy);
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasSource()) {
            canvas.drawColor(0xFF0A090C);
            return;
        }
        canvas.drawColor(0xFF0A090C);

        // 图片没盖满窗口时，窗口内空出来的地方会被导出成透明。
        // 画一层棋盘格当"透明"的视觉提示，否则用户只看到一片黑，分不清是图还是空。
        if (!coversBand()) {
            drawChecker(canvas);
        }

        canvas.drawBitmap(source, matrix, bmpPaint);

        // 窗口外压暗：奇偶填充的"整块 - 窗口"
        dimPath.reset();
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        dimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        dimPath.addRect(band, Path.Direction.CW);
        canvas.drawPath(dimPath, dimPaint);

        // 窗口边线 + 三等分线（只画竖线，横线在极窄的条上没有意义）
        canvas.drawRect(band, bandFill);
        edgePath.reset();
        edgePath.addRect(band, Path.Direction.CW);
        canvas.drawPath(edgePath, edgePaint);
        float tx = band.width() / 3f;
        if (band.width() > 60f * density) {
            for (int i = 1; i <= 2; i++) {
                float x = band.left + tx * i;
                canvas.drawLine(x, band.top, x, band.bottom, gridPaint);
            }
        }
    }

    /** 当前图片是否完全盖住裁剪窗口 */
    private boolean coversBand() {
        if (!hasSource()) return true;
        imgRect.set(srcRect);
        matrix.mapRect(imgRect);
        return imgRect.left <= band.left + 0.5f && imgRect.right >= band.right - 0.5f
                && imgRect.top <= band.top + 0.5f && imgRect.bottom >= band.bottom - 0.5f;
    }

    /** 棋盘格（仅用于提示透明区域，不参与导出） */
    private void drawChecker(Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(band);
        float cell = 9f * density;
        int cols = (int) Math.ceil(band.width() / cell);
        int rows = (int) Math.ceil(band.height() / cell);
        for (int r = 0; r < rows; r++) {
            float t = band.top + r * cell;
            float b = Math.min(t + cell, band.bottom);
            for (int c = 0; c < cols; c++) {
                float l = band.left + c * cell;
                float rr = Math.min(l + cell, band.right);
                checkPaint.setColor((((r + c) & 1) == 0) ? 0xFF3A3540 : 0xFF211E26);
                canvas.drawRect(l, t, rr, b, checkPaint);
            }
        }
        canvas.restoreToCount(save);
    }

    // ------------------------------------------------------------------ 输出

    /**
     * 取出裁剪结果。
     *
     * @param outW 期望输出宽度；<= 0 时用源区域宽度。结果按 {@link #aspect} 定高
     */
    public Bitmap crop(int outW) {
        if (!hasSource() || band.width() < 1f) return null;

        matrix.invert(inv);
        RectF r = new RectF(band);
        inv.mapRect(r);
        if (r.width() < 0.5f || r.height() < 0.5f) return null;

        // 目标尺寸：由裁剪窗口（而不是源区域）决定 —— 图片可能只占窗口一小块，
        // 其余部分是透明的，那部分也要如实导出
        int tw = (outW > 0) ? Math.round(outW) : Math.round(band.width());
        tw = Math.max(16, Math.min(4096, tw));
        int th = Math.max(1, Math.round(tw / aspect));

        Bitmap out;
        try {
            out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        // 源坐标 -> 输出像素：把 r（窗口在源图上的位置）铺到 (0,0,tw,th)
        float kx = tw / r.width();
        float ky = th / r.height();
        Matrix m = new Matrix();
        m.postScale(kx, ky);
        m.postTranslate(-r.left * kx, -r.top * ky);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, m, p);
        return out;
    }
}
