package com.zyxc.devicehud;

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
                default:
                    return Float.NaN;
            }
        } catch (Exception e) {
            return Float.NaN;
        }
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
