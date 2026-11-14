package com.zyxc.devicehud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/**
 * 虚拟摇杆。输出归一化位移，由 MainActivity 换算成悬浮窗的像素位移。
 *
 * 关键处理：DOWN 时 requestDisallowInterceptTouchEvent(true)，
 * 否则外层 ScrollView 会把竖直方向的手势抢走，摇杆只能水平动。
 */
public class JoystickView extends View {

    public interface OnMoveListener {
        /**
         * @param nx -1..1，右为正
         * @param ny -1..1，下为正
         */
        void onMove(float nx, float ny);
    }

    private static final float DEAD_ZONE = 0.10f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float radius;
    private float knobR;
    private float kx;
    private float ky;
    private boolean active;
    private OnMoveListener listener;

    public JoystickView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.2f * d);
        ringPaint.setColor(0x33FFFFFF);

        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(1f * d);
        crossPaint.setColor(0x14FFFFFF);

        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(0xFFE58BA6);

        knobEdge.setStyle(Paint.Style.STROKE);
        knobEdge.setStrokeWidth(1f * d);
        knobEdge.setColor(0x66FFFFFF);

        hintPaint.setStyle(Paint.Style.FILL);
        hintPaint.setColor(0xFF4A4449);
        hintPaint.setTextSize(9f * getResources().getDisplayMetrics().scaledDensity);
        hintPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        setClickable(true);
    }

    public void setOnMoveListener(OnMoveListener l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(w, h);
        if (size == 0) size = (int) (160 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        radius = Math.min(w, h) / 2f - 2f * getResources().getDisplayMetrics().density;
        knobR = radius * 0.34f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                active = true;
                update(e.getX() - cx, e.getY() - cy);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!active) return false;
                update(e.getX() - cx, e.getY() - cy);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                active = false;
                kx = 0f;
                ky = 0f;
                invalidate();
                if (listener != null) listener.onMove(0f, 0f);
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(false);
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void update(float dx, float dy) {
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > radius) {
            dx = dx / len * radius;
            dy = dy / len * radius;
            len = radius;
        }
        kx = dx;
        ky = dy;
        invalidate();

        if (listener != null) {
            float nx = radius > 0 ? dx / radius : 0f;
            float ny = radius > 0 ? dy / radius : 0f;
            if (Math.abs(nx) < DEAD_ZONE) nx = 0f;
            if (Math.abs(ny) < DEAD_ZONE) ny = 0f;
            listener.onMove(nx, ny);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        canvas.drawCircle(cx, cy, radius, ringPaint);
        canvas.drawCircle(cx, cy, radius * 0.66f, crossPaint);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint);

        if (!active) {
            canvas.drawText("DRAG", cx, cy - radius * 0.62f, hintPaint);
        }

        float px = cx + kx;
        float py = cy + ky;

        knobPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        canvas.drawCircle(px, py, knobR, knobPaint);
        canvas.drawCircle(px, py, knobR, knobEdge);

        RectF r = new RectF(px - knobR * 0.4f, py - 1f, px + knobR * 0.4f, py + 1f);
        canvas.drawRoundRect(r, 1f, 1f, knobEdge);
    }
}
