package com.zyxc.devicehud;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * 真实屏幕尺寸。
 *
 * <p>{@code getResources().getDisplayMetrics()} 在 Activity 里给的是**窗口**尺寸：
 * 分屏、小窗、悬浮窗场景都会比真实屏幕小，拿它当悬浮条宽度会让条被裁。
 * 这里统一走 {@link WindowManager#getMaximumWindowMetrics()}（API 30+），
 * 老系统退回 {@code Display.getRealMetrics()}。
 */
public final class Screen {

    private Screen() {
    }

    public static int width(Context c) {
        return bounds(c).width();
    }

    public static int height(Context c) {
        return bounds(c).height();
    }

    public static Rect bounds(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return new Rect(wm.getMaximumWindowMetrics().getBounds());
            } catch (Exception ignored) {
            }
        }
        DisplayMetrics dm = new DisplayMetrics();
        try {
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            dm = c.getResources().getDisplayMetrics();
            return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
    }

    public static boolean isPortrait(Context c) {
        Rect b = bounds(c);
        return b.height() > b.width();
    }
}
