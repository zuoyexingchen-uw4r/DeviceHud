package com.zyxc.devicehud;

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
        PLACEHOLDER
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
        return s;
    }

    public String encode() {
        return id + "|" + safe(label) + "|" + safe(unit) + "|" + kind.name()
                + "|" + arg + "|" + decimals + "|" + role.name()
                + "|" + color + "|" + textSp + "|" + (bold ? 1 : 0) + "|" + (italic ? 1 : 0)
                + "|" + safe(desc);
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
        if (desc == null || desc.isEmpty()) return label == null || label.isEmpty() ? id : label;
        if (label == null || label.isEmpty()) return desc;
        return desc + "  \u00B7  " + label;
    }

    public String format(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return "--";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }
}
