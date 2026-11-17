package io.github.zuoyexingchen.devicehud;

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

    /** CUSTOM 在选择器里的下标（不在 ALL 里，由自定义色即时生成） */
    public static final int CUSTOM_INDEX = ALL.length;

    /**
     * 按档位取主题。
     *
     * <p>越界的档位（含 CUSTOM_INDEX）走 floorMod：老版本只认前 5 个档位，
     * 这里不能抛异常，否则换降级包会直接崩在启动路径上。
     */
    public static HudTheme byIndex(int i) {
        if (i == CUSTOM_INDEX) {
            return custom(HudState.customAccent, HudState.customBg);
        }
        return ALL[Math.floorMod(i, ALL.length)];
    }

    /**
     * 用户自定义主题：只让用户选强调色和底色，其余三色按亮度自动派生。
     *
     * <p>全部颜色都开放会变成调色盘，条子上必然出现"浅底浅字"这种不可读的组合。
     * 这里用底色亮度决定文字用深还是浅，保证任何配色下文字都能看清。
     */
    private static HudTheme sCache;
    private static int sCacheAccent;
    private static int sCacheBg;

    public static HudTheme custom(int accent, int bg) {
        // 这个函数在渲染路径上被反复调用（每帧每项都要算颜色），
        // 每次都 new 一个对象纯属给 GC 添活。参数没变就复用
        if (sCache != null && accent == sCacheAccent && bg == sCacheBg) return sCache;

        // 底色先按"叠在白壁纸上"估亮度：半透明底遇到白色壁纸时最坏，
        // 按最坏情况选文字色，两种壁纸下都不会糊成一片
        int a = (bg >>> 24) & 0xFF;
        double alpha = a / 255.0;
        double r = ((bg >> 16) & 0xFF) * alpha + 255 * (1 - alpha);
        double g = ((bg >> 8) & 0xFF) * alpha + 255 * (1 - alpha);
        double b = (bg & 0xFF) * alpha + 255 * (1 - alpha);
        double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;

        boolean darkBg = luma < 0.52;
        int text = darkBg ? 0xFFF4F0F2 : 0xFF14100F;
        int dim = darkBg ? 0xFF8B8189 : 0xFF5C5459;
        int stroke = darkBg ? 0x24FFFFFF : 0x24000000;
        int charge = mixColors(accent, 0xFF7BD89A, 0.62f);
        HudTheme t = new HudTheme("CUSTOM", bg, stroke, text, dim, accent, charge);
        sCache = t;
        sCacheAccent = accent;
        sCacheBg = bg;
        return t;
    }

    /** 当前生效的主题（含自定义） */
    public static HudTheme current() {
        return byIndex(HudState.themeIndex);
    }

    /** 用户是否选中了自定义主题 */
    public static boolean isCustom() {
        return HudState.themeIndex == CUSTOM_INDEX;
    }

    /** 把 a 往 b 混 t（0=全 a，1=全 b），保留 a 的 alpha */
    private static int mixColors(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (aa << 24) | (r << 16) | (g << 8) | bl;
    }
}
