package io.github.zuoyexingchen.devicehud;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 背景图裁剪对话框。
 *
 * <p>比例锁定成悬浮条的宽高比（横向撑满、纵向按比例），用户拖动 / 双指缩放选一段。
 * 底栏四个动作：
 * <ul>
 *   <li>整张 —— 不裁，整张图存进私有目录（传统做法，配合"等比/1:1/拉伸"使用）</li>
 *   <li>重置 —— 图片回到刚好盖住窗口</li>
 *   <li>取消</li>
 *   <li>确定 —— 把窗口内的那块按条的实际像素导出</li>
 * </ul>
 */
public final class ImageCropDialog {

    /** 裁剪完成回调；{@code cropped == null} 表示"整张图不裁剪" */
    public interface Done {
        void onResult(Bitmap cropped);
    }

    private static final int BG_PANEL = 0xFF101013;
    private static final int BG_CARD = 0xFF161419;
    private static final int TEXT = 0xFFF2E6EA;
    private static final int DIM = 0xFF6B6168;
    private static final int ACCENT = 0xFFE58BA6;

    private ImageCropDialog() {
    }

    /**
     * @param src    源图（所有权仍归调用方）
     * @param aspect 目标宽高比 = 宽 / 高
     * @param outW   输出宽度（px），一般给悬浮条的实际宽度
     * @param info   标题下的一行说明，例如 "3392 × 84 px"
     */
    public static void show(Activity act, Bitmap src, float aspect,
                            int outW, String info, Done done) {
        show(act, src, aspect, outW, info, done, null);
    }

    /**
     * @param onCancel 取消/返回键关闭时的回调，可空（用来释放源图）
     */
    public static void show(Activity act, final Bitmap src, final float aspect,
                            final int outW, String info, final Done done,
                            final Runnable onCancel) {
        float dp = act.getResources().getDisplayMetrics().density;

        Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(BG_PANEL, 16 * dp, 0x22FFFFFF, dp));
        box.setPadding(i(dp, 14), i(dp, 12), i(dp, 14), i(dp, 12));

        TextView title = new TextView(act);
        title.setText("CROP");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextColor(TEXT);
        title.setLetterSpacing(0.18f);
        box.addView(title);

        TextView hint = new TextView(act);
        hint.setText("锁定比例 " + trim(aspect) + " : 1   " + (info == null ? "" : info)
                + "   输出 PNG（保留透明）");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setTextColor(DIM);
        hint.setPadding(0, i(dp, 4), 0, i(dp, 10));
        box.addView(hint);

        CropView crop = new CropView(act);
        crop.setSource(src, aspect);
        // 给固定高度而不是 weight：对话框内容是 WRAP_CONTENT，
        // weight 在 AT_MOST 测量下会被算成 0，裁剪区直接消失
        int cropH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.52f);
        box.addView(crop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cropH));

        TextView tip = new TextView(act);
        tip.setText("拖动移动 · 双指缩放（可缩到比窗口小，空出来的地方导出为透明）");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        tip.setTypeface(Typeface.MONOSPACE);
        tip.setTextColor(DIM);
        tip.setPadding(0, i(dp, 8), 0, i(dp, 6));
        box.addView(tip);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final boolean[] handled = {false};

        actions.addView(button(act, "整张", TEXT, v -> {
            handled[0] = true;
            done.onResult(null);
            dlg.dismiss();
        }), weight(dp));

        actions.addView(button(act, "重置", TEXT, v -> crop.resetTransform()), weight(dp));

        actions.addView(button(act, "取消", DIM, v -> dlg.dismiss()), weight(dp));

        actions.addView(button(act, "确定", ACCENT, v -> {
            Bitmap out = crop.crop(outW);
            if (out == null) {
                dlg.dismiss();
                return;
            }
            handled[0] = true;
            done.onResult(out);
            dlg.dismiss();
        }), weight(dp));

        box.addView(actions);

        // 取消、返回键、点空白关掉：都算未处理，交给调用方释放源图
        dlg.setOnDismissListener(dd -> {
            if (!handled[0] && onCancel != null) onCancel.run();
        });

        dlg.setContentView(box);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            dlg.getWindow().setLayout(
                    (int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private static String trim(float v) {
        if (Math.abs(v - Math.round(v)) < 0.02f) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static TextView button(Activity act, String text, int color,
                                   View.OnClickListener l) {
        float dp = act.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, i(dp, 11), 0, i(dp, 11));
        tv.setBackground(round(BG_CARD, 9 * dp, 0x1FFFFFFF, dp));
        tv.setOnClickListener(l);
        return tv;
    }

    private static LinearLayout.LayoutParams weight(float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = i(dp, 3);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private static GradientDrawable round(int color, float radiusPx, int stroke, float dp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (stroke != 0) g.setStroke(Math.max(1, Math.round(dp * 0.75f)), stroke);
        return g;
    }

    private static int i(float dp, int v) {
        return Math.round(dp * v);
    }
}
