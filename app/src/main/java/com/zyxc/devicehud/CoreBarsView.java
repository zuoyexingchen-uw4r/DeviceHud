package com.zyxc.devicehud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** 8 颗核心的实时频率竖条。 */
public class CoreBarsView extends View {

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int[] khz = new int[8];
    private int[] maxKhz = new int[8];

    private final float density;

    public CoreBarsView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        track.setColor(0xFF1A181B);
        bar.setColor(0xFFE58BA6);

        label.setTypeface(Typeface.MONOSPACE);
        label.setTextSize(8f * getResources().getDisplayMetrics().scaledDensity);
        label.setColor(0xFF6B6168);
        label.setTextAlign(Paint.Align.CENTER);

        value.setTypeface(Typeface.MONOSPACE);
        value.setTextSize(8f * getResources().getDisplayMetrics().scaledDensity);
        value.setColor(0xFFB9AEB4);
        value.setTextAlign(Paint.Align.CENTER);
    }

    public void update(int[] cur, int[] max) {
        this.khz = cur;
        this.maxKhz = max;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) (132 * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int n = 8;
        float gap = 6f * density;
        float labelH = 12f * density;
        float valueH = 12f * density;
        float top = valueH;
        float bottom = getHeight() - labelH;
        float barH = bottom - top;

        float slot = (getWidth() - gap * (n - 1)) / n;
        // 横屏下 slot 会被撑得很宽，柱宽必须封顶，否则圆角半径跟着变大，柱子会变成圆块
        float barW = Math.min(slot * 0.62f, 16f * density);
        if (barW < 2f) barW = 2f;
        float radius = Math.min(barW / 2f, 5f * density);

        for (int i = 0; i < n; i++) {
            float left = i * (slot + gap) + (slot - barW) / 2f;
            float cx = left + barW / 2f;

            canvas.drawRoundRect(left, top, left + barW, bottom, radius, radius, track);

            float ratio = 0f;
            if (maxKhz != null && i < maxKhz.length && maxKhz[i] > 0 && khz != null && i < khz.length) {
                ratio = khz[i] / (float) maxKhz[i];
            }
            if (ratio < 0f) ratio = 0f;
            if (ratio > 1f) ratio = 1f;

            float filled = Math.max(barW, barH * ratio);
            if (filled > barH) filled = barH;
            canvas.drawRoundRect(left, bottom - filled, left + barW, bottom, radius, radius, bar);

            String v = (khz != null && i < khz.length && khz[i] > 0)
                    ? String.format(Locale.US, "%.1f", khz[i] / 1_000_000f)
                    : "--";
            canvas.drawText(v, cx, top - 3f * density, value);
            canvas.drawText("c" + i, cx, bottom + labelH - 2f * density, label);
        }
    }
}
