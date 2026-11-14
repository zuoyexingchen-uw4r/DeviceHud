package io.github.zuoyexingchen.devicehud;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * AIDA64 OSD 式的传感器管理面板。
 *
 * 普通模式每行：启用开关 / 名称 / 单位 / 样式按钮 / 上移 / 下移。
 * 排序模式每行：拖拽手柄 / 名称 / 单位 —— 按住手柄上下拖即可像歌单那样移动。
 *
 * 表顺序即渲染顺序；启用集合只决定显示与否，两者互不干扰。
 */
public final class SensorManagerDialog {

    public interface Host {
        List<SensorSpec> table();

        void onTableChanged(List<SensorSpec> table);

        List<String> enabled();

        void onEnabledChanged(List<String> ids);

        void onRebuild();
    }

    private static final int BG = 0xFF0E0D10;
    private static final int BG_ROW = 0xFF161419;
    private static final int BG_ROW_TOP = 0xFF211E24;
    private static final int TEXT = 0xFFF2E6EA;
    private static final int DIM = 0xFF6B6168;
    private static final int ACCENT = 0xFFE58BA6;

    private SensorManagerDialog() {
    }

    public static void show(Activity act, Host host) {
        Dialog d = new Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        float dp = act.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(round(BG, 16 * dp, 0x22FFFFFF, dp));
        root.setPadding(i(dp, 14), i(dp, 12), i(dp, 14), i(dp, 12));

        // —— 标题
        TextView title = new TextView(act);
        title.setText("SENSOR TABLE");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextColor(TEXT);
        title.setLetterSpacing(0.18f);
        title.setPadding(0, 0, 0, i(dp, 10));
        root.addView(title);

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable[] refresh = new Runnable[1];
        boolean[] sorting = {false};

        // —— 操作行
        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);

        tools.addView(toolButton(act, "全选", v -> {
            List<String> all = new ArrayList<>();
            for (SensorSpec s : host.table()) all.add(s.id);
            host.onEnabledChanged(all);
            refresh[0].run();
        }), weight(dp));

        tools.addView(toolButton(act, "清空", v -> {
            if (host.table().isEmpty()) return;
            List<String> one = new ArrayList<>();
            one.add(host.table().get(0).id);
            host.onEnabledChanged(one);
            refresh[0].run();
        }), weight(dp));

        TextView sortBtn = toolButton(act, "排序", v -> {
            sorting[0] = !sorting[0];
            ((TextView) v).setText(sorting[0] ? "完成排序" : "排序");
            ((TextView) v).setTextColor(sorting[0] ? ACCENT : TEXT);
            ((TextView) v).setBackground(round(BG_ROW, 8 * dp,
                    sorting[0] ? ACCENT : 0x1FFFFFFF, dp));
            refresh[0].run();
        });
        tools.addView(sortBtn, weight(dp));

        tools.addView(toolButton(act, "重建表", v -> {
            host.onRebuild();
            refresh[0].run();
            Toast.makeText(act, "已重新扫描本机传感器", Toast.LENGTH_SHORT).show();
        }), weight(dp));

        tools.addView(toolButton(act, "完成", v -> d.dismiss()), weight(dp));

        root.addView(tools);
        root.addView(spacer(act, i(dp, 8)));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refresh[0] = () -> {
            list.removeAllViews();
            List<SensorSpec> table = host.table();
            List<String> on = host.enabled();

            for (int k = 0; k < table.size(); k++) {
                list.addView(sorting[0]
                        ? buildSortRow(act, host, table, k, on, refresh[0], list, scroll)
                        : buildRow(act, host, table, k, on, refresh[0]));
            }
        };
        refresh[0].run();

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setLayout(
                    (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88f),
                    (int) (act.getResources().getDisplayMetrics().heightPixels * 0.84f));
        }
        d.show();
    }

    // ------------------------------------------------------------------ 普通行

    private static View buildRow(Activity act, Host host, List<SensorSpec> table,
                                 int index, List<String> enabled, Runnable refresh) {
        float dp = act.getResources().getDisplayMetrics().density;
        SensorSpec sp = table.get(index);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(i(dp, 58));
        row.setPadding(i(dp, 6), i(dp, 4), i(dp, 6), i(dp, 4));

        CheckBox cb = new CheckBox(act);
        cb.setChecked(enabled.contains(sp.id));
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        cb.setOnCheckedChangeListener((b, checked) -> {
            List<String> ids = new ArrayList<>(host.enabled());
            if (checked) {
                if (!ids.contains(sp.id)) ids.add(sp.id);
            } else {
                if (ids.size() <= 1) {
                    b.setChecked(true);
                    Toast.makeText(act, "至少保留一项", Toast.LENGTH_SHORT).show();
                    return;
                }
                ids.remove(sp.id);
            }
            host.onEnabledChanged(ids);
        });
        row.addView(cb, new LinearLayout.LayoutParams(i(dp, 40), i(dp, 40)));

        // 名称：翻译名 + 系统原始名，一眼能看出是什么
        TextView name = new TextView(act);
        name.setText(sp.displayTitle());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(i(dp, 2));
        row.addView(name, nameLp);

        TextView unit = new TextView(act);
        unit.setText(sp.unit);
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        unit.setTypeface(Typeface.MONOSPACE);
        unit.setTextColor(DIM);
        unit.setPadding(i(dp, 8), i(dp, 8), i(dp, 8), i(dp, 8));
        unit.setOnClickListener(v -> editStyle(act, host, sp, true, refresh));
        row.addView(unit);

        TextView style = new TextView(act);
        style.setText("Aa");
        style.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        style.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        style.setTextColor(sp.color == -1 ? DIM : sp.color);
        style.setGravity(Gravity.CENTER);
        style.setBackground(round(BG_ROW, 9 * dp, 0x1FFFFFFF, dp));
        style.setOnClickListener(v -> editStyle(act, host, sp, false, refresh));
        LinearLayout.LayoutParams styleLp = new LinearLayout.LayoutParams(
                i(dp, 46), i(dp, 38));
        styleLp.setMarginEnd(i(dp, 4));
        row.addView(style, styleLp);

        row.addView(moveKey(act, "\u25B2", v -> {
            if (index == 0) return;
            table.add(index - 1, table.remove(index));
            host.onTableChanged(table);
            refresh.run();
        }));

        row.addView(moveKey(act, "\u25BC", v -> {
            if (index >= table.size() - 1) return;
            table.add(index + 1, table.remove(index));
            host.onTableChanged(table);
            refresh.run();
        }));

        return row;
    }

    private static TextView moveKey(Activity act, String glyph, View.OnClickListener l) {
        float dp = act.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(act);
        tv.setText(glyph);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(DIM);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(i(dp, 40), i(dp, 40)));
        tv.setOnClickListener(l);
        return tv;
    }

    // ------------------------------------------------------------------ 排序行（拖拽）

    /** 排序模式行高。等高是拖拽换算的前提，别改成一行的自适应高度 */
    private static int sortRowH(float dp) {
        return i(dp, 64);
    }

    private static View buildSortRow(Activity act, Host host, List<SensorSpec> table,
                                     int index, List<String> enabled,
                                     Runnable refresh, LinearLayout list, ScrollView scroll) {
        float dp = act.getResources().getDisplayMetrics().density;
        SensorSpec sp = table.get(index);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sortRowH(dp)));
        row.setPadding(i(dp, 4), 0, i(dp, 10), 0);

        TextView handle = new TextView(act);
        handle.setText("\u2261");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        handle.setTextColor(ACCENT);
        handle.setGravity(Gravity.CENTER);
        handle.setBackground(round(BG_ROW, 9 * dp, 0x1FFFFFFF, dp));
        row.addView(handle, new LinearLayout.LayoutParams(i(dp, 52),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView dot = new TextView(act);
        dot.setText("\u25CF");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        dot.setTextColor(enabled.contains(sp.id) ? ACCENT : 0xFF3A343A);
        dot.setPadding(i(dp, 8), 0, i(dp, 8), 0);
        row.addView(dot);

        TextView name = new TextView(act);
        name.setText(sp.displayTitle());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView unit = new TextView(act);
        unit.setText(sp.unit);
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        unit.setTypeface(Typeface.MONOSPACE);
        unit.setTextColor(DIM);
        row.addView(unit);

        handle.setOnTouchListener(new DragTouch(act, host, table, row, list, scroll, refresh));
        return row;
    }

    /**
     * 手柄拖拽。
     *
     * 关键点：所有行等高且无 margin，所以第 i 行的顶部恒等于 i×H。
     * 于是"手指走到第几格"完全由位移算出来，不依赖 layout 是否已经跑完 ——
     * 否则 addView/removeView 之后 getTop() 还是旧值，会抖一帧。
     */
    private static final class DragTouch implements View.OnTouchListener {

        private final Activity act;
        private final Host host;
        private final List<SensorSpec> table;
        private final View row;
        private final LinearLayout list;
        private final ScrollView scroll;
        private final Runnable refresh;
        private final float dp;
        private final int h;

        private float downRawY;
        private float containerTop0;
        private int startIndex;
        private boolean dragging;

        DragTouch(Activity act, Host host, List<SensorSpec> table, View row,
                  LinearLayout list, ScrollView scroll, Runnable refresh) {
            this.act = act;
            this.host = host;
            this.table = table;
            this.row = row;
            this.list = list;
            this.scroll = scroll;
            this.refresh = refresh;
            this.dp = act.getResources().getDisplayMetrics().density;
            this.h = sortRowH(dp);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    downRawY = e.getRawY();
                    startIndex = list.indexOfChild(row);
                    containerTop0 = screenTop(list);
                    scroll.requestDisallowInterceptTouchEvent(true);
                    row.setAlpha(0.94f);
                    row.setBackground(round(BG_ROW_TOP, 10 * dp, ACCENT, dp));
                    row.setElevation(dp * 6);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return true;
                    autoScroll(e.getRawY());

                    // 内容滚动了多少：滚动会让行整体上移，拖拽位置要补回来
                    float scrollShift = containerTop0 - screenTop(list);
                    float delta = e.getRawY() - downRawY + scrollShift;

                    int cur = list.indexOfChild(row);
                    int target = Math.max(0, Math.min(list.getChildCount() - 1,
                            startIndex + Math.round(delta / h)));

                    while (cur < target) {
                        swap(cur, cur + 1);
                        cur = list.indexOfChild(row);
                    }
                    while (cur > target) {
                        swap(cur, cur - 1);
                        cur = list.indexOfChild(row);
                    }

                    row.setTranslationY(delta - (cur - startIndex) * h);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) return true;
                    dragging = false;
                    int cur = list.indexOfChild(row);
                    row.setTranslationY(0f);
                    row.setAlpha(1f);
                    row.setBackground(null);
                    row.setElevation(0f);
                    scroll.requestDisallowInterceptTouchEvent(false);

                    if (cur != startIndex && startIndex >= 0
                            && startIndex < table.size() && cur < table.size()) {
                        table.add(cur, table.remove(startIndex));
                        host.onTableChanged(table);
                    }
                    refresh.run();
                    return true;
                }

                default:
                    return false;
            }
        }

        /** 把被拖行旁边的行挤到它原来的位置（只动别人，不动被拖的那一行） */
        private void swap(int dragged, int other) {
            View v = list.getChildAt(other);
            if (v == null) return;
            list.removeView(v);
            list.addView(v, dragged);
            // 先让它停在原视觉位置，再滑过去，观感接近歌单拖动
            v.setTranslationY(other > dragged ? h : -h);
            v.animate().translationY(0f).setDuration(120).start();
        }

        /** 拖到列表上下边缘时自动滚动 */
        private void autoScroll(float rawY) {
            float top = screenTop(scroll);
            float bottom = top + scroll.getHeight();
            if (rawY > bottom - h) {
                scroll.scrollBy(0, Math.max(1, h / 3));
            } else if (rawY < top + h) {
                scroll.scrollBy(0, -Math.max(1, h / 3));
            }
        }

        private float screenTop(View v) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return loc[1];
        }
    }

    // ------------------------------------------------------------------ 单项样式

    /** 参考 AIDA64 的配置窗口：名称、单位、颜色、字号、粗体、斜体 */
    private static void editStyle(Activity act, Host host, SensorSpec sp,
                                  boolean focusUnit, Runnable refresh) {
        float dp = act.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(i(dp, 18), i(dp, 8), i(dp, 18), 0);

        boolean ph = sp.kind == SensorSpec.Kind.PLACEHOLDER;

        EditText nameIn = input(act, "名称", sp.label);
        EditText unitIn = input(act, "单位", sp.unit);
        EditText sizeIn = input(act, ph ? "占位宽度（空格数）" : "字号 sp（0 = 跟随全局）",
                String.valueOf(ph ? sp.arg : sp.textSp));
        sizeIn.setInputType(InputType.TYPE_CLASS_NUMBER);

        box.addView(label(act, "名称"));
        box.addView(nameIn);
        box.addView(label(act, "单位"));
        box.addView(unitIn);
        box.addView(label(act, ph ? "宽度" : "字号"));
        box.addView(sizeIn);

        // 颜色
        box.addView(label(act, "颜色"));
        LinearLayout colors = new LinearLayout(act);
        colors.setOrientation(LinearLayout.HORIZONTAL);

        int[] palette = {0xFFF2E6EA, 0xFFE58BA6, 0xFF8FD8A8, 0xFFFFD479,
                0xFF4FC3F7, 0xFFFF9F43, 0xFFB8FFC9};
        final int[] picked = {sp.color};

        List<View> swatches = new ArrayList<>();
        for (int c : palette) {
            View dot = new View(act);
            dot.setBackground(round(c, 11 * dp, 0, dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(i(dp, 38), i(dp, 38));
            lp.setMargins(i(dp, 6), i(dp, 6), i(dp, 6), i(dp, 6));
            dot.setOnClickListener(v -> {
                picked[0] = c;
                for (int k = 0; k < swatches.size(); k++) {
                    View s = swatches.get(k);
                    s.setAlpha(palette[k] == c ? 1f : 0.35f);
                }
            });
            dot.setAlpha(c == sp.color ? 1f : 0.35f);
            swatches.add(dot);
            colors.addView(dot, lp);
        }

        TextView reset = new TextView(act);
        reset.setText("跟随主题");
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        reset.setTextColor(DIM);
        reset.setPadding(i(dp, 12), i(dp, 16), 0, 0);
        reset.setOnClickListener(v -> {
            picked[0] = -1;
            for (View s : swatches) s.setAlpha(0.35f);
        });
        colors.addView(reset);

        box.addView(colors);

        CheckBox bold = new CheckBox(act);
        bold.setText("粗体");
        bold.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        bold.setTextColor(TEXT);
        bold.setChecked(sp.bold);
        box.addView(bold);

        CheckBox italic = new CheckBox(act);
        italic.setText("斜体");
        italic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        italic.setTextColor(TEXT);
        italic.setChecked(sp.italic);
        box.addView(italic);

        new AlertDialog.Builder(act)
                .setTitle(sp.displayTitle())
                .setView(box)
                .setPositiveButton("确定", (dd, w) -> {
                    String raw = nameIn.getText().toString();
                    String nm = raw.trim();
                    String un = unitIn.getText().toString();
                    // 占位块允许留空、也允许真的填一个空格：原样保存，不回填 id。
                    // 普通项留空时仍然回填 id，避免条上出现无名数据。
                    sp.label = ph ? raw : (nm.isEmpty() ? sp.id : nm);
                    sp.unit = un;
                    try {
                        int n = Integer.parseInt(sizeIn.getText().toString().trim());
                        if (ph) {
                            sp.arg = Math.max(1, n);
                        } else {
                            sp.textSp = Math.max(0, n);
                        }
                    } catch (Exception e) {
                        if (!ph) sp.textSp = 0;
                    }
                    sp.color = picked[0];
                    sp.bold = bold.isChecked();
                    sp.italic = italic.isChecked();

                    host.onTableChanged(host.table());
                    refresh.run();
                })
                .setNegativeButton("取消", null)
                .show();

        if (focusUnit) unitIn.requestFocus();
    }

    // ------------------------------------------------------------------ 小工具

    private static EditText input(Activity act, String hint, String value) {
        EditText e = new EditText(act);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setPadding(0, i(act.getResources().getDisplayMetrics().density, 8), 0,
                i(act.getResources().getDisplayMetrics().density, 8));
        return e;
    }

    private static TextView label(Activity act, String t) {
        float dp = act.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(act);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(DIM);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(0, i(dp, 10), 0, 0);
        return tv;
    }

    private static TextView toolButton(Activity act, String text, View.OnClickListener l) {
        float dp = act.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, i(dp, 12), 0, i(dp, 12));
        tv.setBackground(round(BG_ROW, 9 * dp, 0x1FFFFFFF, dp));
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

    private static View spacer(android.content.Context ctx, int px) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        return v;
    }

    private static GradientDrawable round(int color, float radiusPx, int stroke, float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        if (stroke != 0) d.setStroke(Math.max(1, Math.round(dp * 0.75f)), stroke);
        return d;
    }

    private static int i(float dp, int v) {
        return Math.round(dp * v);
    }
}
