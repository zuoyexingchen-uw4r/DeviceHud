package com.zyxc.devicehud;

/**
 * 悬浮条配色主题。
 *
 * 一套主题描述几个颜色契约：底色、描边、主文字、次文字、强调色、充电色。
 * 排版结构由 HudView 统一负责，主题不参与布局。
 */
public final class HudTheme {

    public final String name;
    public final int bg;
    public final int stroke;
    public final int text;
    public final int dim;
    /** 强调色：温度/内存超阈值、放电状态使用 */
    public final int accent;
    /** 充电色：与 accent 同族但可区分，用来标记充电中 */
    public final int charge;

    public HudTheme(String name, int bg, int stroke, int text, int dim, int accent, int charge) {
        this.name = name;
        this.bg = bg;
        this.stroke = stroke;
        this.text = text;
        this.dim = dim;
        this.accent = accent;
        this.charge = charge;
    }

    /** 粉白 + 淡粉，最初那版"黑线" */
    public static final HudTheme INK = new HudTheme(
            "INK", 0xF20A0A0C, 0x24FFFFFF, 0xFFF2E6EA, 0xFF847880,
            0xFFE58BA6, 0xFF8FD8A8);

    /** 琥珀仪表盘 */
    public static final HudTheme AMBER = new HudTheme(
            "AMBER", 0xF2120D06, 0x30FFB300, 0xFFFFD9A8, 0xFFB08A55,
            0xFFFF9F43, 0xFFCFE06B);

    /** 冷蓝夜间 */
    public static final HudTheme ICE = new HudTheme(
            "ICE", 0xF2060A10, 0x332E9BFF, 0xFFE6F2FF, 0xFF7C93A8,
            0xFF4FC3F7, 0xFF7BE0C0);

    /** 纯灰阶 */
    public static final HudTheme MONO = new HudTheme(
            "MONO", 0xF20C0C0C, 0x2EFFFFFF, 0xFFEDEDED, 0xFF7A7A7A,
            0xFFEDEDED, 0xFF9ED9A8);

    /** 终端绿 */
    public static final HudTheme PHOSPHOR = new HudTheme(
            "PHOSPHOR", 0xF2060A06, 0x3333FF66, 0xFFB8FFC9, 0xFF5E9E6E,
            0xFF33FF88, 0xFFD8FF6B);

    public static final HudTheme[] ALL = {INK, AMBER, ICE, MONO, PHOSPHOR};

    public static HudTheme byIndex(int i) {
        return ALL[Math.floorMod(i, ALL.length)];
    }
}
