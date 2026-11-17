package io.github.zuoyexingchen.devicehud;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SensorSpec} 的运行时视图：负责真正从快照里取值。
 *
 * 加新数据源时：SensorSpec.Kind 加枚举 + 这里加一个 case + SensorTable.probe 里生成。
 * 三处改动即可，渲染层与设置界面无需触碰。
 */
public final class Sensor {

    public final SensorSpec spec;

    public Sensor(SensorSpec spec) {
        this.spec = spec;
    }

    public String id() {
        return spec.id;
    }

    public String label() {
        return spec.label;
    }

    public String unit() {
        return spec.unit;
    }

    public SensorSpec.Role role() {
        return spec.role;
    }

    public String format(float v) {
        // 占位方块不输出数值，只输出 arg 个空格撑出长度
        if (spec.kind == SensorSpec.Kind.PLACEHOLDER) {
            return " ".repeat(Math.max(1, spec.arg));
        }
        // 图表自己画曲线，不占用文字宽度
        if (spec.kind == SensorSpec.Kind.CHART) return "";
        return spec.format(v);
    }

    public float read(DataCollector.Snapshot s) {
        if (s == null) return Float.NaN;
        try {
            switch (spec.kind) {
                case BATT_VOLT:
                    return s.voltV;
                case BATT_CURR: {
                    // 统一口径：充电恒为正；放电按用户/自动推断的约定决定符号
                    float ma = s.currentMa;
                    if (s.charging) return Math.abs(ma);
                    // 还没学到本机约定时，一律按"放电 = 负值"显示
                    if (!HudState.dischargeSignLearned) return -Math.abs(ma);
                    return HudState.dischargeNegative ? -Math.abs(ma) : Math.abs(ma);
                }
                case BATT_WATT:
                    return s.powerW;
                case BATT_LEVEL:
                    return s.capacityPct;
                case BATT_TEMP:
                    return s.batteryTempC;
                case ZONE:
                    return s.zoneTemp(spec.arg);
                case ZONE_AGG:
                    return s.aggTemp(spec.arg);
                case ZONE_MAX:
                    return s.maxTempC;
                case CLK:
                    if (spec.arg < 0 || spec.arg >= s.cpuKhz.length) return Float.NaN;
                    return s.cpuKhz[spec.arg] / 1_000_000f;
                case CLK_MAX:
                    return s.cpuPeakKhz / 1_000_000f;
                case CLK_AVG:
                    return s.cpuAvgKhz / 1_000_000f;
                case MEM_PCT:
                    return s.memPct;
                case MEM_FREE:
                    return (s.memTotalMb - s.memUsedMb) / 1024f;
                case HZ:
                    return s.refreshHz;
                case CPU_USAGE:
                    return s.cpuUsagePct;
                case CORE_USAGE:
                    if (spec.arg < 0 || spec.arg >= s.coreUsagePct.length) return Float.NaN;
                    return s.coreUsagePct[spec.arg];
                case BT_BATT: {
                    Float v = s.btLevels.get(spec.id);
                    return v == null ? Float.NaN : v;
                }
                case PLACEHOLDER:
                    return Math.max(1, spec.arg);
                case SHELL:
                    // 值由后台批处理脚本写好，这里只读缓存；
                    // 超过 3 个周期没更新会自己变 NaN（界面显示 --）
                    return ShellPlugins.value(spec.src);
                // 这两个不直接读硬件：CHART 的值来自 ChartHistory（画图用），
                // PLUGIN 需要其它传感器的值，必须走 readAll 的两趟计算
                case CHART:
                case PLUGIN:
                default:
                    return Float.NaN;
            }
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    /**
     * 一次算出整组传感器的值。
     *
     * <p>插件指标依赖别的传感器的读数，所以必须分两趟：先算所有能直接读的，
     * 再把结果喂给表达式求值。单趟循环里算插件会拿到 NaN —— 顺序靠后的
     * 插件依赖靠前的插件更是必然失败。
     *
     * @return id -> 值（顺序与传入顺序一致，NaN 也会保留，让界面上显示 --）
     */
    public static java.util.Map<String, Float> readAll(List<Sensor> list, DataCollector.Snapshot s) {
        java.util.Map<String, Float> out = new java.util.LinkedHashMap<>();
        if (list == null || list.isEmpty()) return out;

        java.util.Map<String, Float> ctx = new java.util.HashMap<>();
        for (Sensor se : list) {
            if (se.spec.kind == SensorSpec.Kind.PLUGIN) continue;
            float v = se.read(s);
            out.put(se.id(), v);
            if (!Float.isNaN(v)) {
                ctx.put(se.id(), v);
                ctx.put(safeName(se.id()), v);
            }
        }
        for (Sensor se : list) {
            if (se.spec.kind != SensorSpec.Kind.PLUGIN) continue;
            float v = PluginHost.eval(se.id(), ctx);
            out.put(se.id(), v);
            if (!Float.isNaN(v)) {
                ctx.put(se.id(), v);
                ctx.put(safeName(se.id()), v);
            }
        }
        return out;
    }

    /**
     * 传感器 id → 表达式里的合法标识符。
     *
     * <p>id 里有连字符（{@code batt-cur}），而连字符在表达式里是减号，
     * 直接塞进去会被解析成 {@code batt 减 cur}。统一换成下划线，
     * 插件作者写 {@code batt_cur} 就行。
     */
    public static String safeName(String id) {
        if (id == null) return "";
        return id.replace('-', '_').replace('.', '_');
    }

    // ------------------------------------------------------------------ 表操作

    public static List<Sensor> wrap(List<SensorSpec> specs) {
        List<Sensor> out = new ArrayList<>();
        if (specs != null) {
            for (SensorSpec sp : specs) out.add(new Sensor(sp));
        }
        return out;
    }

    /**
     * 按"表顺序"取出已启用的传感器。
     *
     * 渲染顺序由表决定（用户在管理界面里上移/下移改的就是表顺序），
     * 启用集合只决定显示与否，这样调顺序和点启用互不干扰。
     */
    public static List<Sensor> resolve(List<SensorSpec> table, List<String> enabledIds) {
        List<Sensor> out = new ArrayList<>();
        if (table == null || enabledIds == null) return out;
        for (SensorSpec sp : table) {
            if (enabledIds.contains(sp.id)) out.add(new Sensor(sp));
        }
        return out;
    }

    public static SensorSpec find(List<SensorSpec> table, String id) {
        if (table == null || id == null) return null;
        for (SensorSpec sp : table) {
            if (sp.id.equals(id)) return sp;
        }
        return null;
    }
}
