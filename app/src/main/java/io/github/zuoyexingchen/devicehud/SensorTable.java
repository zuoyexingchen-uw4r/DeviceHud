package io.github.zuoyexingchen.devicehud;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 传感器表的探测与固化。
 *
 *   - load() 首次调用探测整机能力并写盘；
 *   - 之后直接读盘，不再扫描系统；
 *   - rebuild() 供用户手动触发。
 *
 * 内置项带中文说明（desc），热区这类系统原始名不做翻译，原样保留。
 */
public final class SensorTable {

    private static final int MAX_ZONE = 64;

    /** 默认勾选的一组，兼顾电源、温度、内存、峰值频率 */
    public static final List<String> DEFAULT_IDS = List.of(
            "batt-volt", "batt-cur", "agg-cpu", "agg-gpu", "batt-temp", "mem", "clk-max");

    /** 打孔屏适配用的占位项 id */
    public static final String PLACEHOLDER_ID = "placeholder";

    private SensorTable() {
    }

    // ------------------------------------------------------------------ 探测

    public static List<SensorSpec> probe() {
        List<SensorSpec> out = new ArrayList<>();

        // —— 电池（走 BatteryManager，不依赖 sysfs 权限）
        out.add(mk("batt-volt", "PWR", "v", SensorSpec.Kind.BATT_VOLT, 0, 2,
                SensorSpec.Role.POWER, "电压"));
        out.add(mk("batt-cur", "CUR", "mA", SensorSpec.Kind.BATT_CURR, 0, 0,
                SensorSpec.Role.POWER, "电流"));
        out.add(mk("batt-watt", "W", "W", SensorSpec.Kind.BATT_WATT, 0, 2,
                SensorSpec.Role.POWER, "功率"));
        out.add(mk("batt-level", "BAT", "%", SensorSpec.Kind.BATT_LEVEL, 0, 0,
                SensorSpec.Role.POWER, "电量"));
        out.add(mk("batt-temp", "BATT", "\u00B0c", SensorSpec.Kind.BATT_TEMP, 0, 1,
                SensorSpec.Role.TEMP, "电池温度"));

        // —— 聚合温度
        out.add(mk("agg-cpu", "CPU", "\u00B0c", SensorSpec.Kind.ZONE_AGG, 0, 1,
                SensorSpec.Role.TEMP, "CPU 温度"));
        out.add(mk("agg-gpu", "GPU", "\u00B0c", SensorSpec.Kind.ZONE_AGG, 1, 1,
                SensorSpec.Role.TEMP, "GPU 温度"));
        out.add(mk("agg-chg", "CHG", "\u00B0c", SensorSpec.Kind.ZONE_AGG, 2, 1,
                SensorSpec.Role.TEMP, "充电温度"));
        out.add(mk("agg-max", "MAX", "\u00B0c", SensorSpec.Kind.ZONE_MAX, 0, 1,
                SensorSpec.Role.TEMP, "最高温度"));

        // —— 每一个热区独立成项，名字直接用系统里的原始 type，不做翻译
        for (int i = 0; i < MAX_ZONE; i++) {
            String type = readLine("/sys/class/thermal/thermal_zone" + i + "/type");
            if (type == null || type.isEmpty()) continue;

            String low = type.toLowerCase(Locale.US);
            // 过温保护阈值是常量（常见 95°C），不是实测值，不入表
            if (low.contains("trip") || low.contains("limit")
                    || low.contains("crit") || low.contains("threshold")) {
                continue;
            }

            out.add(new SensorSpec("tz" + i, type, "\u00B0c",
                    SensorSpec.Kind.ZONE, i, 1, SensorSpec.Role.TEMP));
        }

        // —— 每颗核心的频率
        for (int c = 0; c < 8; c++) {
            if (readInt("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq", 0) <= 0
                    && !new File("/sys/devices/system/cpu/cpu" + c).exists()) {
                continue;
            }
            out.add(mk("clk-c" + c, "cpu" + c, "G", SensorSpec.Kind.CLK, c, 2,
                    SensorSpec.Role.PLAIN, "核心 " + c + " 频率"));
        }
        out.add(mk("clk-max", "PEAK", "G", SensorSpec.Kind.CLK_MAX, 0, 2,
                SensorSpec.Role.PLAIN, "峰值频率"));
        out.add(mk("clk-avg", "CLK", "G", SensorSpec.Kind.CLK_AVG, 0, 2,
                SensorSpec.Role.PLAIN, "平均频率"));

        // —— CPU 占用率（需要 Shizuku 的 shell 才读得到 /proc/stat）
        if (ShellAccess.hasShell()) {
            out.add(mk("cpu-usage", "CPU%", "%", SensorSpec.Kind.CPU_USAGE, 0, 0,
                    SensorSpec.Role.USAGE, "CPU 占用率"));
            for (int c = 0; c < 8; c++) {
                if (!new File("/sys/devices/system/cpu/cpu" + c).exists()) continue;
                out.add(mk("core" + c + "-usage", "c" + c + "%", "%",
                        SensorSpec.Kind.CORE_USAGE, c, 0, SensorSpec.Role.USAGE,
                        "核心 " + c + " 占用"));
            }
        }

        // —— 内存与显示
        out.add(mk("mem", "MEM", "%", SensorSpec.Kind.MEM_PCT, 0, 0,
                SensorSpec.Role.MEMORY, "内存占用"));
        out.add(mk("mem-free", "FREE", "G", SensorSpec.Kind.MEM_FREE, 0, 2,
                SensorSpec.Role.PLAIN, "可用内存"));
        out.add(mk("hz", "HZ", "Hz", SensorSpec.Kind.HZ, 0, 0,
                SensorSpec.Role.PLAIN, "刷新率"));

        // —— 占位方块：无名称/无数据/无单位，靠空格撑长度，用来避开打孔区域。
        //    默认不启用，由打孔屏适配模块按需 enable。
        out.add(mk(PLACEHOLDER_ID, "", "", SensorSpec.Kind.PLACEHOLDER, 8, 0,
                SensorSpec.Role.PLAIN, "占位方块"));

        return out;
    }

    private static SensorSpec mk(String id, String label, String unit,
                                 SensorSpec.Kind kind, int arg, int decimals,
                                 SensorSpec.Role role, String desc) {
        SensorSpec s = new SensorSpec(id, label, unit, kind, arg, decimals, role);
        s.desc = desc;
        return s;
    }

    // ------------------------------------------------------------------ 存取

    /** 表结构版本：新增字段或内置项时 +1，升级后自动重探测一次 */
    private static final int TABLE_VERSION = 2;

    /** 读表：首次或版本过期则探测并固化 */
    public static List<SensorSpec> load(Context c) {
        if (Prefs.tableVersion(c) != TABLE_VERSION) {
            List<SensorSpec> t = probe();
            save(c, t);
            Prefs.setTableVersion(c, TABLE_VERSION);
            PluginHost.load(c);
            ShellPlugins.load(c);
            return withPlugins(c, t);
        }
        List<SensorSpec> out = decodeAll(Prefs.sensorTable(c));
        PluginHost.load(c);
        ShellPlugins.load(c);
        if (!out.isEmpty()) return withPlugins(c, out);

        out = probe();
        save(c, out);
        Prefs.setTableVersion(c, TABLE_VERSION);
        return withPlugins(c, out);
    }

    /**
     * ensurePlugins 在没有变化时返回 null（这是给它自己的调用方省事的约定），
     * 但 load() 的契约是"一定给一张可用的表"，所以这里必须兜一层。
     */
    private static List<SensorSpec> withPlugins(Context c, List<SensorSpec> table) {
        List<SensorSpec> a = ensurePlugins(c, table);
        List<SensorSpec> b = ensureShellMetrics(c, a != null ? a : table);
        if (b != null) return b;
        return a != null ? a : table;
    }

    /**
     * 把 shell 插件声明的指标同步进表。
     *
     * <p>和表达式插件同样的双向同步：脚本删了指标也要消失。
     * 两者的前缀不同（{@code plug-} / {@code sh-}），各管各的，互不干扰。
     */
    public static List<SensorSpec> ensureShellMetrics(Context c, List<SensorSpec> table) {
        if (table == null) return null;

        java.util.Map<String, SensorSpec> old = new java.util.HashMap<>();
        List<SensorSpec> base = new ArrayList<>();
        for (SensorSpec s : table) {
            if (s.id.startsWith(ShellPlugins.ID_PREFIX)) old.put(s.id, s);
            else base.add(s);
        }

        List<SensorSpec> fresh = new ArrayList<>();
        for (ShellPlugins.Script sc : ShellPlugins.scripts()) {
            for (ShellPlugins.MetricDecl m : sc.metrics) {
                fresh.add(ShellPlugins.toSpec(m, sc.file));
            }
        }

        boolean same = fresh.size() == old.size();
        if (same) {
            for (SensorSpec s : fresh) {
                SensorSpec prev = old.get(s.id);
                if (prev == null || !prev.label.equals(s.label) || !prev.unit.equals(s.unit)
                        || prev.decimals != s.decimals || prev.role != s.role
                        || !prev.desc.equals(s.desc)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return null;

        int pos = base.size();
        for (int i = 0; i < base.size(); i++) {
            if (base.get(i).kind == SensorSpec.Kind.PLACEHOLDER) {
                pos = i;
                break;
            }
        }
        base.addAll(pos, fresh);
        save(c, base);
        return base;
    }

    /**
     * 把插件指标同步进表：新增的补上、表达式变了的更新、文件删掉的移除。
     *
     * <p>和蓝牙不同，插件是"配置文件驱动"的，所以这里必须支持双向同步 ——
     * 用户删掉 json 之后那些指标要真的消失，否则表里会留一堆永远算不出来的孤儿项。
     *
     * @return 有变化返回新表，没变化返回 null（调用方可以省掉一次重排与落盘）
     */
    public static List<SensorSpec> ensurePlugins(Context c, List<SensorSpec> table) {
        if (table == null) return null;

        java.util.Map<String, SensorSpec> old = new java.util.HashMap<>();
        List<SensorSpec> base = new ArrayList<>();
        for (SensorSpec s : table) {
            if (s.id.startsWith(PluginHost.ID_PREFIX)) old.put(s.id, s);
            else base.add(s);
        }

        List<SensorSpec> fresh = new ArrayList<>();
        for (PluginHost.Metric m : PluginHost.metrics()) fresh.add(PluginHost.toSpec(m));

        boolean same = fresh.size() == old.size();
        if (same) {
            for (SensorSpec s : fresh) {
                SensorSpec prev = old.get(s.id);
                if (prev == null || !prev.expr.equals(s.expr) || !prev.label.equals(s.label)
                        || !prev.desc.equals(s.desc) || prev.decimals != s.decimals
                        || prev.role != s.role) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return null;

        // 插在占位方块之前：插件属于"普通指标"，占位块是打孔适配，应该留在末尾附近
        int pos = base.size();
        for (int i = 0; i < base.size(); i++) {
            if (base.get(i).kind == SensorSpec.Kind.PLACEHOLDER) {
                pos = i;
                break;
            }
        }
        base.addAll(pos, fresh);
        save(c, base);
        return base;
    }

    /** 新建一个绑定到 srcId 的曲线图表项；同源已存在时直接返回已有的 */
    public static SensorSpec newChart(List<SensorSpec> table, String srcId) {
        String id = "chart-" + srcId;
        for (SensorSpec s : table) {
            if (s.id.equals(id)) return s;
        }
        SensorSpec src = findById(table, srcId);
        String label = (src == null || src.label == null || src.label.isEmpty())
                ? "CHART" : src.label;
        SensorSpec sp = new SensorSpec(id, label, "", SensorSpec.Kind.CHART, 0, 0,
                src != null ? src.role : SensorSpec.Role.PLAIN);
        sp.src = srcId;
        sp.chartPoints = 96;
        sp.chartMode = SensorSpec.CHART_AUTO;
        sp.desc = "曲线：" + (src == null ? srcId : src.displayTitle());
        return sp;
    }

    /** 删掉某个图表项（图表是用户加的，需要能清掉） */
    public static boolean remove(List<SensorSpec> table, String id) {
        if (table == null) return false;
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).id.equals(id)) {
                table.remove(i);
                return true;
            }
        }
        return false;
    }

    public static void save(Context c, List<SensorSpec> list) {
        StringBuilder sb = new StringBuilder();
        for (SensorSpec s : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.encode());
        }
        Prefs.setSensorTable(c, sb.toString());
    }

    /** 用户手动重建：重新扫描整机并覆盖旧表 */
    public static List<SensorSpec> rebuild(Context c) {
        List<SensorSpec> t = probe();
        save(c, t);
        Prefs.setTableVersion(c, TABLE_VERSION);
        return t;
    }

    /**
     * 授权 Shizuku 之后把占用率传感器补进已固化的表。
     *
     * 表是首启固化的，用户可能在探测之后才装上/授权 Shizuku，
     * 所以这里做一次幂等补齐：已经有就原样返回。
     */
    public static List<SensorSpec> ensureShellSensors(Context c, List<SensorSpec> table) {
        if (table == null || table.isEmpty()) return table;
        for (SensorSpec s : table) {
            if (s.kind == SensorSpec.Kind.CPU_USAGE) return table;
        }
        if (!ShellAccess.hasShell()) return table;

        List<SensorSpec> out = new ArrayList<>(table);
        int pos = out.size();
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).kind == SensorSpec.Kind.PLACEHOLDER) {
                pos = i;
                break;
            }
        }
        out.add(pos++, mk("cpu-usage", "CPU%", "%", SensorSpec.Kind.CPU_USAGE, 0, 0,
                SensorSpec.Role.USAGE, "CPU 占用率"));
        for (int core = 0; core < 8; core++) {
            if (!new File("/sys/devices/system/cpu/cpu" + core).exists()) continue;
            out.add(pos++, mk("core" + core + "-usage", "c" + core + "%", "%",
                    SensorSpec.Kind.CORE_USAGE, core, 0, SensorSpec.Role.USAGE,
                    "核心 " + core + " 占用"));
        }
        save(c, out);
        return out;
    }

    /**
     * 把"已连接（或已知电量）"的蓝牙设备补进表里，一个设备一项。
     *
     * <p>表是首启固化的，而蓝牙设备随时会配对/连接，所以这里做幂等补齐：
     * 已经有该项就跳过；返回 null 表示没有变化（调用方可以省掉一次重排）。
     */
    public static List<SensorSpec> ensureBtSensors(Context c, List<SensorSpec> table) {
        if (table == null || table.isEmpty()) return null;
        if (!BtBattery.hasPermission(c)) return null;

        List<BtBattery.Dev> devs = BtBattery.refresh(c);
        if (devs.isEmpty()) return null;

        List<SensorSpec> out = new ArrayList<>(table);
        boolean changed = false;
        for (BtBattery.Dev d : devs) {
            String id = BtBattery.idOf(d.address);
            String full = d.name == null ? "" : d.name.trim();
            String shortName = BtBattery.shortName(full);

            SensorSpec old = findById(out, id);
            if (old != null) {
                // 已经建过：设备名可能变了（或早期截断规则更短），只在"名字仍是旧名前缀"
                // 时才跟着更新，用户自己改过的名字不动
                if (!shortName.equals(old.label) && old.label != null
                        && full.startsWith(old.label)) {
                    old.label = shortName;
                    changed = true;
                }
                if ((old.desc == null || old.desc.isEmpty()) && !full.isEmpty()) {
                    old.desc = full;
                    changed = true;
                }
                continue;
            }

            SensorSpec sp = mk(id, shortName, "%",
                    SensorSpec.Kind.BT_BATT, 0, 0, SensorSpec.Role.PLAIN,
                    full.isEmpty() ? "蓝牙设备电量" : full);
            out.add(indexOfPlaceholder(out), sp);
            changed = true;
        }
        if (!changed) return null;
        save(c, out);
        return out;
    }

    private static SensorSpec findById(List<SensorSpec> list, String id) {
        for (SensorSpec s : list) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    private static int indexOfPlaceholder(List<SensorSpec> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).kind == SensorSpec.Kind.PLACEHOLDER) return i;
        }
        return list.size();
    }

    private static List<SensorSpec> decodeAll(String raw) {
        List<SensorSpec> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            SensorSpec s = SensorSpec.decode(line.trim());
            if (s == null) continue;
            // 旧版把占位块的空名字回填成了 id，这里顺手修掉：
            // 占位块的 label 一律清空（它只负责撑长度，不显示文字）
            if (s.kind == SensorSpec.Kind.PLACEHOLDER && s.label != null
                    && (s.label.equals(s.id) || s.label.equals(PLACEHOLDER_ID))) {
                s.label = "";
            }
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ IO

    private static String readLine(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static int readInt(String path, int def) {
        String v = readLine(path);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
