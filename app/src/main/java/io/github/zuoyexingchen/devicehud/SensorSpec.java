package io.github.zuoyexingchen.devicehud;

import java.util.Locale;

/**
 * 传感器的可序列化定义。
 *
 * 为什么不用硬编码常量表：不同机型的热区名字、核心数、可读节点完全不同，
 * 甚至同一个系统里还能通过 shizuku 之类注入外部数据源。
 * 所以这里把"怎么取值"编码成 Kind + arg，把"叫什么"交给探测时读到的原始名，
 * 整张表可以序列化进 SharedPreferences，需要时再生效。
 *
 * 文本格式（一行一个，字段用 | 分隔，热区名不会包含 |）：
 *   id|label|unit|kind|arg|decimals|role
 */
public final class SensorSpec {

    /** 取值方式。新增数据来源时在这里加一个枚举值 */
    public enum Kind {
        BATT_VOLT, BATT_CURR, BATT_WATT, BATT_LEVEL, BATT_TEMP,
        /** arg = thermal_zone 索引，直接读该探针 */
        ZONE,
        /** arg: 0=CPU 聚合 1=GPU 聚合 2=充电聚合，取同类探针最大值 */
        ZONE_AGG,
        /** 全部探针最大值 */
        ZONE_MAX,
        /** arg = 核心序号 */
        CLK,
        CLK_MAX,
        CLK_AVG,
        MEM_PCT,
        MEM_FREE,
        HZ,
        /** 整机 CPU 占用率（%），需要 Shizuku 读 /proc/stat */
        CPU_USAGE,
        /** arg = 核心号，单核占用率（%） */
        CORE_USAGE,
        /**
         * 蓝牙设备电量（%）。id 形如 {@code bt-C02FCD3FB316}，
         * 数值从 {@link BtBattery} 的缓存里取，抢不到就显示 --
         */
        BT_BATT,
        /** 占位方块：不显示任何文字，只按 arg 个空格占住长度，用于避开打孔区域 */
        PLACEHOLDER,
        /**
         * 胶囊图表：把 {@link #src} 指向的传感器最近若干个采样点画成 sparkline。
         *
         * <p>本身不读硬件，数据来自 {@link ChartHistory}；量程由
         * {@link #chartMode} / {@link #chartMin} / {@link #chartMax} 决定。
         */
        CHART,
        /**
         * 插件指标：值由 {@link #expr} 在 {@link PluginHost} 里求值得到。
         *
         * <p>表达式里只能用"安全名"（把 id 里的 - 换成 _），例如 {@code batt_cur}。
         */
        PLUGIN
    }

    public enum Role {
        PLAIN, TEMP, MEMORY, POWER, USAGE
    }

    public final String id;
    public String label;
    public String unit;
    /** 人类可读的说明，仅用在管理列表里，不参与渲染 */
    public String desc = "";
    public final Kind kind;
    /** ZONE=热区索引 / CLK=核心号 / PLACEHOLDER=空格数，占位方块的长度靠它调 */
    public int arg;
    public int decimals;
    public final Role role;

    // —— 单项样式覆盖（参考 AIDA64 OSD 的做法）
    /** -1 表示跟随主题 */
    public int color = -1;
    /** 0 表示跟随全局字号 */
    public int textSp = 0;
    public boolean bold = false;
    public boolean italic = false;

    // —— 图表 / 插件扩展（老配置里这些字段不存在，解码时按默认值补齐）
    /** CHART：数据源传感器 id；PLUGIN：保留为空 */
    public String src = "";
    /** CHART：保留多少个采样点（1~300） */
    public int chartPoints = 96;
    /** CHART：0 = 自动量程，1 = 手动量程 */
    public int chartMode = CHART_AUTO;
    /** CHART：手动量程下限 */
    public float chartMin = 0f;
    /** CHART：手动量程上限 */
    public float chartMax = 100f;
    /** PLUGIN：表达式源码 */
    public String expr = "";

    public static final int CHART_AUTO = 0;
    public static final int CHART_MANUAL = 1;
    public static final String[] CHART_MODE_NAMES = {"自动量程", "手动量程"};

    public SensorSpec(String id, String label, String unit, Kind kind, int arg,
                      int decimals, Role role) {
        this.id = id;
        this.label = label;
        this.unit = unit;
        this.kind = kind;
        this.arg = arg;
        this.decimals = decimals;
        this.role = role;
    }

    public SensorSpec copy() {
        SensorSpec s = new SensorSpec(id, label, unit, kind, arg, decimals, role);
        s.color = color;
        s.textSp = textSp;
        s.bold = bold;
        s.italic = italic;
        s.desc = desc;
        s.src = src;
        s.chartPoints = chartPoints;
        s.chartMode = chartMode;
        s.chartMin = chartMin;
        s.chartMax = chartMax;
        s.expr = expr;
        return s;
    }

    public String encode() {
        return id + "|" + safe(label) + "|" + safe(unit) + "|" + kind.name()
                + "|" + arg + "|" + decimals + "|" + role.name()
                + "|" + color + "|" + textSp + "|" + (bold ? 1 : 0) + "|" + (italic ? 1 : 0)
                + "|" + safe(desc)
                // 扩展字段一律追加在末尾：老版本读到第 12 个字段就停，
                // 新版本按长度判断有没有这些字段，双向兼容
                + "|" + safe(src) + "|" + chartPoints + "|" + chartMode
                + "|" + chartMin + "|" + chartMax + "|" + safe(expr);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("|", "/");
    }

    public static SensorSpec decode(String line) {
        if (line == null) return null;
        String[] p = line.split("\\|", -1);
        if (p.length < 7) return null;
        try {
            Kind kind = Kind.valueOf(p[3]);
            Role role = Role.valueOf(p[6]);
            SensorSpec s = new SensorSpec(p[0], p[1], p[2], kind,
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), role);
            // 兼容早期只存 7 / 11 个字段的表
            if (p.length >= 11) {
                s.color = Integer.parseInt(p[7]);
                s.textSp = Integer.parseInt(p[8]);
                s.bold = "1".equals(p[9]);
                s.italic = "1".equals(p[10]);
            }
            if (p.length >= 12) s.desc = p[11];
            // 第 12 项起是图表/插件扩展字段，老配置没有，保持默认值即可
            if (p.length >= 17) {
                s.src = p[12];
                s.chartPoints = clampInt(parseInt(p[13], 96), 8, ChartHistory.CAPACITY);
                s.chartMode = parseInt(p[14], CHART_AUTO) == CHART_MANUAL
                        ? CHART_MANUAL : CHART_AUTO;
                s.chartMin = parseFloat(p[15], 0f);
                s.chartMax = parseFloat(p[16], 100f);
            }
            if (p.length >= 18) s.expr = p[17];
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /** 管理列表里显示的标题：翻译名 + 原始标识 */
    public String displayTitle() {
        // 占位方块不参与命名：它的"名字"没有意义，长度才是参数。
        // 早期版本会把空名字回填成 id，列表里就出现了 "placeholder" 这种鬼东西。
        if (kind == Kind.PLACEHOLDER) {
            return "占位方块  \u00B7  " + Math.max(1, arg) + " 空格";
        }
        if (kind == Kind.CHART) {
            return "曲线图表  \u00B7  " + (src == null || src.isEmpty() ? "未绑定数据源" : src)
                    + "  \u00B7  " + chartPoints + " 点  \u00B7  "
                    + CHART_MODE_NAMES[chartMode == CHART_MANUAL ? 1 : 0];
        }
        if (kind == Kind.PLUGIN) {
            return "插件指标  \u00B7  " + (desc == null || desc.isEmpty() ? id : desc);
        }
        if (desc == null || desc.isEmpty()) return label == null || label.isEmpty() ? id : label;
        if (label == null || label.isEmpty()) return desc;
        return desc + "  \u00B7  " + label;
    }

    public String format(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return "--";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    // ------------------------------------------------------------------ 解析工具

    /**
     * 扩展字段的容错解析。
     *
     * <p>传感器表是用户长期持有的文本，不允许因为某个字段写坏了就整行解码失败 ——
     * 那样用户会莫名其妙丢掉整张表。所以这里一律退到默认值。
     */
    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
