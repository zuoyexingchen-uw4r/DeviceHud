package com.zyxc.devicehud;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 采集本机可用的一切遥测数据。
 *
 * 可用数据源（已在本机 OPPO / Android 16 上实测）：
 *   - /sys/class/thermal/thermal_zone<N>/{type,temp}   40+ 个探针，含 CPU / GPU / 充电 IC
 *   - /sys/devices/system/cpu/cpu<N>/cpufreq/scaling_cur_freq   8 核实时频率
 *   - BatteryManager + 粘性 ACTION_BATTERY_CHANGED 广播
 *   - ActivityManager.MemoryInfo
 *
 * 不可用（权限拒绝）：/sys/class/power_supply/battery/*、/sys/class/kgsl/kgsl-3d0/gpuclk
 */
public final class DataCollector {

    private static final int MAX_CPU = 8;
    private static final int MAX_ZONE = 64;

    private static int[] sCpuMaxKhz = null;
    private static final List<Zone> sZonesScratch = new ArrayList<>();

    /**
     * 热区探针的类型是固定的，只在首帧扫描一次并缓存索引。
     * 之后每次采样只打开 temp 文件，把 IO 次数砍掉一半。
     */
    private static int[] sZoneIndex = null;
    private static String[] sZoneType = null;

    /** /proc/stat 上一次的累计 jiffies，用来算区间差值（含每核） */
    private static final Object CPU_LOCK = new Object();
    private static long[] sCpuPrevTotal;
    private static long[] sCpuPrevIdle;

    /**
     * 上一次算出来的占用率。
     *
     * <p>两帧读到同一份 /proc/stat（刷新线程 400ms、采样 500ms，天然会撞）时
     * 差值为 0，旧实现直接留 NaN —— 整个上屏区间没有样本，界面就闪成 --。
     * 这里保留上次的值顶几秒，等下一份新快照到了自然更新。
     */
    /** 上一帧快照：慢变量（电池、温度、内存）按时间节流时用它补齐 */
    private static Snapshot sPrev;

    // 慢变量的最小读取间隔。按**时间**而不是帧数分频：
    // 采样率是可调的，按帧数分频会让"采样调到 0.1s"变成"温度也每 0.2s 读一次"。
    private static final long BATT_EVERY_MS = 2000L;
    private static final long THERMAL_EVERY_MS = 1000L;
    private static final long MEM_EVERY_MS = 2000L;
    private static long sLastBattAt;
    private static long sLastThermalAt;
    private static long sLastMemAt;

    private static volatile float sLastCpuPct = Float.NaN;
    private static final float[] sLastCorePct = new float[MAX_CPU];
    private static volatile long sLastCpuAt;
    private static final long USAGE_HOLD_MS = 5000L;

    static {
        java.util.Arrays.fill(sLastCorePct, Float.NaN);
    }

    private DataCollector() {
    }

    // ------------------------------------------------------------------ 数据快照

    public static final class Zone {
        public int index;
        public String type = "";
        public float tempC;
    }

    public static final class Snapshot {
        public float voltV;
        public int currentMa;
        public float powerW;
        public int capacityPct;
        public int statusCode;
        /** EXTRA_PLUGGED：0 = 未接任何电源 */
        public int plugged;
        public boolean charging;
        /** 未充电时电流是否为负值 —— 用于自动推断本机的电流符号约定 */
        public boolean dischargeIsNegative;
        public float batteryTempC;

        public float cpuTempC;
        public float gpuTempC;
        public float chargerTempC;
        public float maxTempC;

        public final int[] cpuKhz = new int[MAX_CPU];
        public final int[] cpuMaxKhz = new int[MAX_CPU];
        public int cpuAvgKhz;
        public int cpuPeakKhz;

        public long memUsedMb;
        public long memTotalMb;
        public int memPct;

        /** 按 thermal_zone 索引存放的实测温度，未读到为 NaN */
        public final float[] zoneTemp = new float[MAX_ZONE];

        public float refreshHz;
        public long uptimeSec;

        public String soc = "";
        public String device = "";

        public int zoneCount;

        /** 真实 CPU 占用率（需 Shizuku）；不可用时为 NaN */
        public float cpuUsagePct = Float.NaN;
        /** 每核占用率，不可用为 NaN */
        public final float[] coreUsagePct = new float[MAX_CPU];

        /** 蓝牙设备电量：传感器 id -> 百分比。每帧从 BtBattery 缓存拷一份 */
        public final java.util.Map<String, Float> btLevels = new java.util.HashMap<>();

        public float zoneTemp(int idx) {
            if (idx < 0 || idx >= zoneTemp.length) return Float.NaN;
            float v = zoneTemp[idx];
            return v <= 0f ? Float.NaN : v;
        }

        /** which: 0=CPU 1=GPU 2=充电 */
        public float aggTemp(int which) {
            switch (which) {
                case 0:
                    return cpuTempC;
                case 1:
                    return gpuTempC;
                case 2:
                    return chargerTempC;
                default:
                    return Float.NaN;
            }
        }
    }

    // ------------------------------------------------------------------ 主入口

    /**
     * 采一帧。
     *
     * <p>不是所有数据都值得每帧读一次：电池广播是一次 binder 往返，
     * 热区要开 40+ 个 sysfs 文件，内存也要 binder。这里按**时间**分频 ——
     * 频率、占用率这类每帧都读，温度最多 1s 一次、电池/内存最多 2s 一次，
     * 跳过的帧沿用上一帧的值。这样把采样率调到 0.1s 也不会把 IO 翻五倍。
     */
    public static Snapshot capture(Context ctx) {
        Snapshot s = new Snapshot();
        java.util.Arrays.fill(s.zoneTemp, Float.NaN);

        boolean first = (sPrev == null);
        long now = SystemClock.elapsedRealtime();
        if (sPrev != null) carrySlow(sPrev, s);

        if (first || now - sLastBattAt >= BATT_EVERY_MS) {
            readBattery(ctx, s);
            sLastBattAt = now;
        }
        if (first || now - sLastThermalAt >= THERMAL_EVERY_MS) {
            readThermal(s);
            sLastThermalAt = now;
        }
        readCpuFreq(s);
        if (first || now - sLastMemAt >= MEM_EVERY_MS) {
            readMemory(ctx, s);
            sLastMemAt = now;
        }
        readDisplay(ctx, s);
        readCpuUsage(s);

        s.btLevels.putAll(BtBattery.levels());

        sPrev = s;
        s.uptimeSec = SystemClock.elapsedRealtime() / 1000L;
        s.soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? trim(Build.SOC_MODEL, Build.HARDWARE)
                : trim(Build.HARDWARE, "");
        s.device = trim(Build.MANUFACTURER, "") + " " + trim(Build.MODEL, "");
        return s;
    }

    /** 把上一帧的慢变量拷过来，节流的那些帧不至于显示成 0 */
    private static void carrySlow(Snapshot from, Snapshot to) {
        to.voltV = from.voltV;
        to.currentMa = from.currentMa;
        to.powerW = from.powerW;
        to.capacityPct = from.capacityPct;
        to.statusCode = from.statusCode;
        to.plugged = from.plugged;
        to.charging = from.charging;
        to.batteryTempC = from.batteryTempC;

        to.cpuTempC = from.cpuTempC;
        to.gpuTempC = from.gpuTempC;
        to.chargerTempC = from.chargerTempC;
        to.maxTempC = from.maxTempC;
        to.zoneCount = from.zoneCount;
        System.arraycopy(from.zoneTemp, 0, to.zoneTemp, 0, MAX_ZONE);

        to.memUsedMb = from.memUsedMb;
        to.memTotalMb = from.memTotalMb;
        to.memPct = from.memPct;
    }

    // ------------------------------------------------------------------ 电池

    private static void readBattery(Context ctx, Snapshot s) {
        Intent it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (it != null) {
            int mv = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) s.voltV = mv / 1000f;

            int tenths = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths != Integer.MIN_VALUE) s.batteryTempC = tenths / 10f;

            s.statusCode = it.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);

            s.charging = s.statusCode == BatteryManager.BATTERY_STATUS_CHARGING
                    || s.statusCode == BatteryManager.BATTERY_STATUS_FULL;
            s.plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        }

        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            s.capacityPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

            int ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (ua != Integer.MIN_VALUE && ua != 0) {
                // 官方语义为微安；个别机型直接返回毫安，做一次量级纠正
                s.currentMa = Math.abs(ua) > 30_000 ? ua / 1000 : ua;
            }
        }

        // 功率只表示"这一刻的吞吐量"，取绝对值 ——
        // 各机型电流计符号不一致，跟着符号走会出现"充电时显示 -6.9W"这种怪事
        s.powerW = s.voltV * Math.abs(s.currentMa) / 1000f;

        // 符号约定不在这里推断：只有在"确实在放电且没插任何电源"的样本上才可信，
        // 见 maybeLearnDischargeSign()
        s.dischargeIsNegative = s.currentMa < 0;
    }

    /**
     * 学习本机电流符号约定（一次性）。
     *
     * <p>只在**真正放电**的样本上采信：状态必须是 DISCHARGING 且 EXTRA_PLUGGED 为 0。
     * 光看"没在充电"不够 —— 旁路供电（插着电但电池在放电）、
     * 极慢速充电都会落在 NOT_CHARGING 上，那时电流符号不可信。
     *
     * <p>学到即固化到 Prefs，之后不再重复检测，用户仍可手动改。
     */
    public static void maybeLearnDischargeSign(Context c, Snapshot s) {
        if (s == null) return;
        if (HudState.dischargeSignLearned) return;
        if (s.statusCode != BatteryManager.BATTERY_STATUS_DISCHARGING) return;
        if (s.plugged != 0) return;
        if (s.currentMa == 0) return;

        boolean neg = s.currentMa < 0;
        HudState.dischargeNegative = neg;
        HudState.dischargeSignLearned = true;
        Prefs.setDischargeNegative(c, neg);
        Prefs.setNegDetected(c, true);
    }

    // ------------------------------------------------------------------ 温度

    private static void readThermal(Snapshot s) {
        ensureZoneIndex();

        List<Zone> zones = new ArrayList<>();
        float cpu = -1f, gpu = -1f, chg = -1f, max = -1f;

        int n = (sZoneIndex == null) ? 0 : sZoneIndex.length;
        for (int k = 0; k < n; k++) {
            String raw = readLine("/sys/class/thermal/thermal_zone" + sZoneIndex[k] + "/temp");
            if (raw == null) continue;

            float v;
            try {
                v = Integer.parseInt(raw) / 1000f;
            } catch (NumberFormatException e) {
                continue;
            }
            if (v <= 0f || v > 200f) continue;

            String type = sZoneType[k];
            Zone z = new Zone();
            z.index = sZoneIndex[k];
            z.type = type;
            z.tempC = v;
            zones.add(z);
            s.zoneTemp[sZoneIndex[k]] = v;

            String ty = type.toLowerCase(Locale.US);

            // trip / limit / crit 是过温保护阈值（常见 95°C），不是实测温度，不能参与聚合
            boolean isThreshold = ty.contains("trip") || ty.contains("limit")
                    || ty.contains("crit") || ty.contains("threshold") || ty.contains("warm");

            if (!isThreshold) {
                if (ty.startsWith("cpu") || ty.contains("cpullc") || ty.contains("soc")
                        || ty.contains("apc")) {
                    if (v > cpu) cpu = v;
                } else if (ty.contains("gpu")) {
                    if (v > gpu) gpu = v;
                } else if (ty.contains("chg") || ty.contains("charger") || ty.contains("usb")) {
                    if (v > chg) chg = v;
                }
                if (v > max) max = v;
            }
        }

        s.cpuTempC = cpu;
        s.gpuTempC = gpu;
        s.chargerTempC = chg;
        s.maxTempC = max;
        s.zoneCount = zones.size();

        synchronized (sZonesScratch) {
            sZonesScratch.clear();
            sZonesScratch.addAll(zones);
        }
    }

    /** 首帧扫描一次热区索引，之后复用 */
    private static synchronized void ensureZoneIndex() {
        if (sZoneIndex != null) return;

        List<Integer> idx = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < MAX_ZONE; i++) {
            String t = readLine("/sys/class/thermal/thermal_zone" + i + "/type");
            if (t == null || t.isEmpty()) continue;
            idx.add(i);
            types.add(t);
        }

        int n = idx.size();
        int[] index = new int[n];
        String[] type = new String[n];
        for (int k = 0; k < n; k++) {
            index[k] = idx.get(k);
            type[k] = types.get(k);
        }
        // 先写数组再置标志，避免其他线程看到半成品
        sZoneType = type;
        sZoneIndex = index;
    }

    /** 返回上一帧采集到的全部温度探针（供主界面列表使用）。 */
    public static List<Zone> lastZones() {
        synchronized (sZonesScratch) {
            return new ArrayList<>(sZonesScratch);
        }
    }

    // ------------------------------------------------------------------ CPU 频率

    private static void readCpuFreq(Snapshot s) {
        int sum = 0, count = 0, peak = 0;
        for (int c = 0; c < MAX_CPU; c++) {
            int khz = readInt("/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq", 0);
            if (khz <= 0) {
                khz = readInt("/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_cur_freq", 0);
            }
            s.cpuKhz[c] = khz;
            if (khz > 0) {
                sum += khz;
                count++;
                if (khz > peak) peak = khz;
            }

            if (sCpuMaxKhz == null) sCpuMaxKhz = new int[MAX_CPU];
            if (sCpuMaxKhz[c] <= 0) {
                sCpuMaxKhz[c] = readInt(
                        "/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_max_freq", 0);
            }
            s.cpuMaxKhz[c] = sCpuMaxKhz[c];
        }
        s.cpuAvgKhz = count > 0 ? sum / count : 0;
        s.cpuPeakKhz = peak;
    }

    // ------------------------------------------------------------------ 内存 / 显示

    private static void readMemory(Context ctx, Snapshot s) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.memTotalMb = mi.totalMem / (1024L * 1024L);
        s.memUsedMb = (mi.totalMem - mi.availMem) / (1024L * 1024L);
        s.memPct = mi.totalMem > 0 ? (int) (100L * s.memUsedMb / s.memTotalMb) : 0;
    }

    @SuppressWarnings("deprecation")
    private static void readDisplay(Context ctx, Snapshot s) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        Display d = wm.getDefaultDisplay();
        if (d == null) return;
        Display.Mode m = d.getMode();
        if (m != null) s.refreshHz = m.getRefreshRate();
    }

    // ------------------------------------------------------------------ IO 工具

    /**
     * 读 sysfs 单行值。
     *
     * 刻意不用 File.exists()/canRead() + BufferedReader：
     * 前者多一次 stat，后者每次采样都要新建缓冲对象，
     * 50 多个探针 × 2 次/秒 累积起来是实打实的开销。
     */
    private static String readLine(String path) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            byte[] buf = new byte[96];
            int n = in.read(buf);
            if (n <= 0) return null;
            int end = 0;
            while (end < n && buf[end] != '\n' && buf[end] != '\r') end++;
            return new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 真实 CPU 占用（Shizuku）

    /**
     * 用 /proc/stat 的累计 jiffies 差值算占用率。
     *
     * 普通应用读不到 /proc/stat（SELinux 拒绝），所以这一步依赖 Shizuku 的 shell。
     * 两帧之间差值太小（<10 jiffies）时直接沿用上一帧结果，避免抖动成噪声。
     */
    private static void readCpuUsage(Snapshot s) {
        java.util.Arrays.fill(s.coreUsagePct, Float.NaN);

        String raw = ShellAccess.readProcStat();
        if (raw == null || raw.isEmpty()) {
            applyLastUsage(s);
            return;
        }

        int cap = 1 + MAX_CPU;
        long[] total = new long[cap];
        long[] idle = new long[cap];
        int maxIdx = 0;

        for (String line : raw.split("\n")) {
            if (!line.startsWith("cpu")) continue;
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) continue;

            int idx;
            if ("cpu".equals(p[0])) {
                idx = 0;
            } else {
                try {
                    idx = Integer.parseInt(p[0].substring(3)) + 1;
                } catch (Exception e) {
                    continue;
                }
                if (idx < 1 || idx >= cap) continue;
            }

            long sum = 0;
            for (int k = 1; k < p.length && k <= 8; k++) {
                try {
                    sum += Long.parseLong(p[k]);
                } catch (NumberFormatException ignored) {
                }
            }
            long id = 0;
            try {
                id = Long.parseLong(p[4]);                       // idle
                if (p.length > 5) id += Long.parseLong(p[5]);     // iowait
            } catch (NumberFormatException ignored) {
            }

            total[idx] = sum;
            idle[idx] = id;
            if (idx > maxIdx) maxIdx = idx;
        }

        synchronized (CPU_LOCK) {
            if (sCpuPrevTotal != null && sCpuPrevTotal.length == cap) {
                for (int i = 0; i <= maxIdx; i++) {
                    long dt = total[i] - sCpuPrevTotal[i];
                    if (dt < 10) continue;
                    long di = idle[i] - sCpuPrevIdle[i];
                    float pct = 100f * (1f - (float) di / dt);
                    if (pct < 0f) pct = 0f;
                    if (pct > 100f) pct = 100f;
                    if (i == 0) {
                        s.cpuUsagePct = pct;
                    } else {
                        s.coreUsagePct[i - 1] = pct;
                    }
                }
            }
            sCpuPrevTotal = total;
            sCpuPrevIdle = idle;
        }

        // 这一帧有没有算出新值？没有就用上次的顶着
        if (!Float.isNaN(s.cpuUsagePct)) {
            sLastCpuPct = s.cpuUsagePct;
            sLastCpuAt = SystemClock.elapsedRealtime();
            System.arraycopy(s.coreUsagePct, 0, sLastCorePct, 0, MAX_CPU);
        } else {
            applyLastUsage(s);
        }
    }

    /** 把最近几秒内的占用率补回快照，避免出现整段 -- */
    private static void applyLastUsage(Snapshot s) {
        if (SystemClock.elapsedRealtime() - sLastCpuAt > USAGE_HOLD_MS) return;
        s.cpuUsagePct = sLastCpuPct;
        for (int i = 0; i < MAX_CPU; i++) {
            if (Float.isNaN(s.coreUsagePct[i])) s.coreUsagePct[i] = sLastCorePct[i];
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

    private static String trim(String a, String b) {
        String s = (a == null ? "" : a).trim();
        if (s.isEmpty() || "unknown".equalsIgnoreCase(s)) s = (b == null ? "" : b).trim();
        return s;
    }
}
