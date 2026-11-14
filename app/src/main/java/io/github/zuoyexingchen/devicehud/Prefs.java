package io.github.zuoyexingchen.devicehud;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 设置持久化。键值都集中在这里，避免散落在各个类里。 */
public final class Prefs {

    private static final String FILE = "hud_prefs";

    private static final String K_SENSORS = "sensors";
    private static final String K_UPDATE_MS = "update_ms";
    private static final String K_SAMPLE_MS = "sample_ms";
    private static final String K_INSET_LEFT = "inset_left";
    private static final String K_INSET_RIGHT = "inset_right";
    private static final String K_THEME = "theme";
    private static final String K_BAR_H = "bar_h";
    private static final String K_TEXT_SP = "text_sp";
    private static final String K_MARGIN = "margin";
    private static final String K_X = "x";
    private static final String K_Y = "y";
    private static final String K_AUTOSTART = "autostart";
    private static final String K_VISIBLE = "visible";
    private static final String K_BG = "bg";
    private static final String K_GAP = "bar_gap_dp";
    private static final String K_BG_COLOR = "bar_bg_color";
    private static final String K_BORDER_COLOR = "bar_border_color";
    private static final String K_BORDER_ON = "bar_border_on";
    private static final String K_RADIUS = "bar_radius_dp";
    private static final String K_MAX_ITEMS = "bar_max_items";
    private static final String K_BG_URI = "bar_bg_uri";
    private static final String K_BG_ANCHOR = "bar_bg_anchor";
    private static final String K_BG_SCALE = "bar_bg_scale";
    private static final String K_BT_POLL = "bt_poll_sec";
    private static final String K_WIN_ALPHA = "bar_window_alpha";
    private static final String K_IMG_ALPHA = "bar_image_alpha";
    private static final String K_TABLE = "sensor_table";
    private static final String K_BAT_COLOR = "bat_color";
    private static final String K_BAT_SLOW = "bat_slow_warn";
    private static final String K_BAT_NEG = "bat_discharge_neg";
    private static final String K_BAT_NEG_DETECTED = "bat_discharge_neg_detected";
    private static final String K_PORTRAIT_PCT = "portrait_pct";
    private static final String K_TABLE_VER = "sensor_table_ver";
    private static final String K_NOTIF = "notif_takeover";
    private static final String K_HIDE_RECENTS = "hide_recents";

    /** 上屏档位（刷新率）。与采样率互相独立，不做任何约束 */
    public static final long[] RATES = {200, 500, 1000, 2000, 5000, 10000, 30000, 60000};
    public static final String[] RATE_LABELS = {"0.2s", "0.5s", "1s", "2s", "5s", "10s", "30s", "60s"};

    /**
     * 采样档位（采样率）。读出原始数据并送进 {@link Sampler} 的节奏。
     *
     * <p>和刷新率完全独立：采样快、刷新慢 = 上屏值是区间均值；
     * 采样慢、刷新快 = 上屏值在下次采样前保持不变。
     */
    public static final long[] SAMPLE_RATES = {100, 250, 500, 1000, 2000, 5000};
    public static final String[] SAMPLE_LABELS = {"0.1s", "0.25s", "0.5s", "1s", "2s", "5s"};

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 传感器

    public static List<String> sensors(Context c) {
        String raw = sp(c).getString(K_SENSORS, null);
        if (raw == null) return new ArrayList<>(SensorTable.DEFAULT_IDS);
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        // 全部被取消勾选时回退到默认，避免出现空条
        return out.isEmpty() ? new ArrayList<>(SensorTable.DEFAULT_IDS) : out;
    }

    public static void setSensors(Context c, Collection<String> ids) {
        sp(c).edit().putString(K_SENSORS, String.join(",", ids)).apply();
    }

    // ------------------------------------------------------------------ 传感器表（固化）

    /** 整张传感器表的序列化文本；为空表示尚未探测过 */
    public static String sensorTable(Context c) {
        return sp(c).getString(K_TABLE, "");
    }

    public static void setSensorTable(Context c, String raw) {
        sp(c).edit().putString(K_TABLE, raw == null ? "" : raw).apply();
    }

    /** 传感器表结构版本，用于升级后自动重探测 */
    public static int tableVersion(Context c) {
        return sp(c).getInt(K_TABLE_VER, 0);
    }

    public static void setTableVersion(Context c, int v) {
        sp(c).edit().putInt(K_TABLE_VER, v).apply();
    }

    // ------------------------------------------------------------------ 刷新节奏

    public static long updateMs(Context c) {
        return sp(c).getLong(K_UPDATE_MS, 500L);
    }

    public static void setUpdateMs(Context c, long ms) {
        sp(c).edit().putLong(K_UPDATE_MS, ms).apply();
    }

    public static long sampleMs(Context c) {
        return sp(c).getLong(K_SAMPLE_MS, 500L);
    }

    public static void setSampleMs(Context c, long ms) {
        sp(c).edit().putLong(K_SAMPLE_MS, ms).apply();
    }

    /** 当前采样档位在 SAMPLE_RATES 里的下标，找不到就回退到 500ms */
    public static int sampleIndex(Context c) {
        long v = sampleMs(c);
        for (int i = 0; i < SAMPLE_RATES.length; i++) {
            if (SAMPLE_RATES[i] == v) return i;
        }
        return 2;
    }

    /** 当前档位在 RATES 里的下标，找不到就回退到最快档 */
    public static int rateIndex(Context c) {
        long v = updateMs(c);
        for (int i = 0; i < RATES.length; i++) {
            if (RATES[i] == v) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------------ 布局

    /** 左侧留白（px）。这段区域不显示文字，但背景仍然铺满 */
    public static int insetLeft(Context c) {
        return sp(c).getInt(K_INSET_LEFT, 0);
    }

    public static void setInsetLeft(Context c, int px) {
        sp(c).edit().putInt(K_INSET_LEFT, px).apply();
    }

    public static int insetRight(Context c) {
        return sp(c).getInt(K_INSET_RIGHT, 0);
    }

    public static void setInsetRight(Context c, int px) {
        sp(c).edit().putInt(K_INSET_RIGHT, px).apply();
    }

    public static int barHeightDp(Context c) {
        return sp(c).getInt(K_BAR_H, 32);
    }

    public static void setBarHeightDp(Context c, int v) {
        sp(c).edit().putInt(K_BAR_H, v).apply();
    }

    public static int textSp(Context c) {
        return sp(c).getInt(K_TEXT_SP, 12);
    }

    public static void setTextSp(Context c, int v) {
        sp(c).edit().putInt(K_TEXT_SP, v).apply();
    }

    public static int sideMarginDp(Context c) {
        return sp(c).getInt(K_MARGIN, 6);
    }

    public static void setSideMarginDp(Context c, int v) {
        sp(c).edit().putInt(K_MARGIN, v).apply();
    }

    /** 竖屏时信息条占屏宽的百分比（横屏恒为 100） */
    public static int portraitPct(Context c) {
        return sp(c).getInt(K_PORTRAIT_PCT, 72);
    }

    public static void setPortraitPct(Context c, int v) {
        sp(c).edit().putInt(K_PORTRAIT_PCT, v).apply();
    }

    public static int posX(Context c) {
        return sp(c).getInt(K_X, 0);
    }

    public static int posY(Context c) {
        return sp(c).getInt(K_Y, 0);
    }

    public static void setPos(Context c, int x, int y) {
        sp(c).edit().putInt(K_X, x).putInt(K_Y, y).apply();
    }

    // ------------------------------------------------------------------ 其他

    public static int themeIndex(Context c) {
        return sp(c).getInt(K_THEME, 0);
    }

    public static void setThemeIndex(Context c, int v) {
        sp(c).edit().putInt(K_THEME, v).apply();
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean(K_AUTOSTART, true);
    }

    public static void setAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean(K_AUTOSTART, v).apply();
    }

    public static boolean visible(Context c) {
        return sp(c).getBoolean(K_VISIBLE, true);
    }

    public static void setVisible(Context c, boolean v) {
        sp(c).edit().putBoolean(K_VISIBLE, v).apply();
    }

    public static boolean showBackground(Context c) {
        return sp(c).getBoolean(K_BG, true);
    }

    public static int gapDp(Context c) {
        return sp(c).getInt(K_GAP, 10);
    }

    public static void setGapDp(Context c, int v) {
        sp(c).edit().putInt(K_GAP, v).apply();
    }

    public static int bgColor(Context c) {
        return sp(c).getInt(K_BG_COLOR, 0);
    }

    public static void setBgColor(Context c, int v) {
        sp(c).edit().putInt(K_BG_COLOR, v).apply();
    }

    public static int borderColor(Context c) {
        return sp(c).getInt(K_BORDER_COLOR, 0);
    }

    public static void setBorderColor(Context c, int v) {
        sp(c).edit().putInt(K_BORDER_COLOR, v).apply();
    }

    public static boolean showBorder(Context c) {
        return sp(c).getBoolean(K_BORDER_ON, true);
    }

    public static void setShowBorder(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BORDER_ON, v).apply();
    }

    public static int maxItems(Context c) {
        return sp(c).getInt(K_MAX_ITEMS, 9);
    }

    public static void setMaxItems(Context c, int v) {
        sp(c).edit().putInt(K_MAX_ITEMS, v).apply();
    }

    public static String bgImageUri(Context c) {
        String v = sp(c).getString(K_BG_URI, "");
        return v == null ? "" : v;
    }

    public static void setBgImageUri(Context c, String v) {
        sp(c).edit().putString(K_BG_URI, v == null ? "" : v).apply();
    }

    public static int bgAnchor(Context c) {
        return sp(c).getInt(K_BG_ANCHOR, BgImage.ANCHOR_CENTER);
    }

    public static void setBgAnchor(Context c, int v) {
        sp(c).edit().putInt(K_BG_ANCHOR, v).apply();
    }

    public static int windowAlphaPct(Context c) {
        return sp(c).getInt(K_WIN_ALPHA, 80);
    }

    public static void setWindowAlphaPct(Context c, int v) {
        sp(c).edit().putInt(K_WIN_ALPHA, v).apply();
    }

    public static int imageAlphaPct(Context c) {
        return sp(c).getInt(K_IMG_ALPHA, 100);
    }

    public static void setImageAlphaPct(Context c, int v) {
        sp(c).edit().putInt(K_IMG_ALPHA, v).apply();
    }

    public static int btPollSec(Context c) {
        return sp(c).getInt(K_BT_POLL, 60);
    }

    public static void setBtPollSec(Context c, int v) {
        sp(c).edit().putInt(K_BT_POLL, v).apply();
    }

    public static int bgScale(Context c) {
        return sp(c).getInt(K_BG_SCALE, BgImage.SCALE_FIT);
    }

    public static void setBgScale(Context c, int v) {
        sp(c).edit().putInt(K_BG_SCALE, v).apply();
    }

    public static int radiusDp(Context c) {
        return sp(c).getInt(K_RADIUS, 12);
    }

    public static void setRadiusDp(Context c, int v) {
        sp(c).edit().putInt(K_RADIUS, v).apply();
    }

    public static void setShowBackground(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BG, v).apply();
    }

    public static boolean batColor(Context c) {
        return sp(c).getBoolean(K_BAT_COLOR, true);
    }

    public static void setBatColor(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BAT_COLOR, v).apply();
    }

    public static boolean batSlowWarn(Context c) {
        return sp(c).getBoolean(K_BAT_SLOW, true);
    }

    public static void setBatSlowWarn(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BAT_SLOW, v).apply();
    }

    public static boolean dischargeNegative(Context c) {
        return sp(c).getBoolean(K_BAT_NEG, true);
    }

    public static void setDischargeNegative(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BAT_NEG, v).apply();
    }

    /** 是否已经根据实测电流自动推断过符号约定 */
    public static boolean negDetected(Context c) {
        return sp(c).getBoolean(K_BAT_NEG_DETECTED, false);
    }

    public static void setNegDetected(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BAT_NEG_DETECTED, v).apply();
    }

    /** 是否接管前台服务通知（自定义两行布局） */
    public static boolean notifTakeover(Context c) {
        return sp(c).getBoolean(K_NOTIF, true);
    }

    public static void setNotifTakeover(Context c, boolean v) {
        sp(c).edit().putBoolean(K_NOTIF, v).apply();
    }

    /** 在最近任务（多任务卡片）里隐藏本应用 */
    public static boolean hideRecents(Context c) {
        return sp(c).getBoolean(K_HIDE_RECENTS, false);
    }

    public static void setHideRecents(Context c, boolean v) {
        sp(c).edit().putBoolean(K_HIDE_RECENTS, v).apply();
    }

    // ------------------------------------------------------------------ 与运行时状态互转

    /** 把持久化设置灌进 HudState 静态字段 */
    public static void loadIntoState(Context c) {
        HudState.sensorIds = sensors(c);
        HudState.updateMs = updateMs(c);
        HudState.sampleMs = sampleMs(c);
        HudState.insetLeft = insetLeft(c);
        HudState.insetRight = insetRight(c);
        HudState.barHeightDp = barHeightDp(c);
        HudState.textSizeSp = textSp(c);
        HudState.sideMarginDp = sideMarginDp(c);
        HudState.themeIndex = themeIndex(c);
        HudState.hidden = !visible(c);
        HudState.showBackground = showBackground(c);
        HudState.gapDp = gapDp(c);
        HudState.bgColor = bgColor(c);
        HudState.borderColor = borderColor(c);
        HudState.showBorder = showBorder(c);
        HudState.radiusDp = radiusDp(c);
        HudState.maxItems = maxItems(c);
        HudState.bgImageUri = bgImageUri(c);
        HudState.bgAnchor = BgImage.clampAnchor(bgAnchor(c));
        HudState.bgScale = BgImage.clampScale(bgScale(c));
        HudState.btPollSec = btPollSec(c);
        HudState.windowAlphaPct = windowAlphaPct(c);
        HudState.imageAlphaPct = imageAlphaPct(c);
        HudState.batColor = batColor(c);
        HudState.batSlowWarn = batSlowWarn(c);
        HudState.dischargeNegative = dischargeNegative(c);
        HudState.dischargeSignLearned = negDetected(c);
        HudState.notifTakeover = notifTakeover(c);
        HudState.x = posX(c);
        HudState.y = posY(c);
    }

    /** 把当前运行时状态写回持久化 */
    public static void saveFromState(Context c) {
        setSensors(c, HudState.sensorIds == null ? SensorTable.DEFAULT_IDS : HudState.sensorIds);
        setUpdateMs(c, HudState.updateMs);
        setSampleMs(c, HudState.sampleMs);
        setInsetLeft(c, HudState.insetLeft);
        setInsetRight(c, HudState.insetRight);
        setBarHeightDp(c, HudState.barHeightDp);
        setTextSp(c, HudState.textSizeSp);
        setSideMarginDp(c, HudState.sideMarginDp);
        setThemeIndex(c, HudState.themeIndex);
        setVisible(c, !HudState.hidden);
        setShowBackground(c, HudState.showBackground);
        setGapDp(c, HudState.gapDp);
        setBgColor(c, HudState.bgColor);
        setBorderColor(c, HudState.borderColor);
        setShowBorder(c, HudState.showBorder);
        setRadiusDp(c, HudState.radiusDp);
        setMaxItems(c, HudState.maxItems);
        setBgImageUri(c, HudState.bgImageUri);
        setBgAnchor(c, HudState.bgAnchor);
        setBgScale(c, HudState.bgScale);
        setBtPollSec(c, HudState.btPollSec);
        setWindowAlphaPct(c, HudState.windowAlphaPct);
        setImageAlphaPct(c, HudState.imageAlphaPct);
        setBatColor(c, HudState.batColor);
        setBatSlowWarn(c, HudState.batSlowWarn);
        setDischargeNegative(c, HudState.dischargeNegative);
        setNegDetected(c, HudState.dischargeSignLearned);
        setNotifTakeover(c, HudState.notifTakeover);
        setPos(c, HudState.x, HudState.y);
    }

    /** 供设置界面切换传感器时保持顺序稳定 */
    public static Set<String> orderedSet(Collection<String> ids) {
        return new LinkedHashSet<>(ids);
    }
}
