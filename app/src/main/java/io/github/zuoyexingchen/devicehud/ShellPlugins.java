package io.github.zuoyexingchen.devicehud;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell 插件：允许用 {@code *.sh} 扩展数据源。
 *
 * <p><b>和表达式插件的分工</b>：表达式（{@link PluginHost}）只能组合**已有**指标，
 * 但它能跟着采样节奏跑（0.1s）；shell 能读任意节点、跑任意命令，
 * 但每次都要 fork 一个进程，代价高一个数量级。
 *
 * <p>所以这里刻意做成**低频 + 批量**：
 * <ul>
 *   <li>每个脚本有独立的最小间隔（{@code @interval}，下限 {@link #MIN_INTERVAL_MS}）；</li>
 *   <li>所有到期的脚本**拼成一个临时 sh 跑一次**，N 个脚本只 fork 一次；</li>
 *   <li>执行放独立线程，绝不阻塞采样线程 —— 采样路径上多等 10ms，
 *       就会让 0.1s 档位实际变成 0.15s。</li>
 * </ul>
 *
 * <p><b>安全边界（必须让用户知道）</b>：脚本是**以应用自己的 uid** 执行的，
 * 所以它能做的一切都等于应用能做的一切 —— 包括读 {@code files/bridge.token}
 * （拿到即等于电脑侧 shell 通道）、读全部偏好与历史文件、联网。
 * 这不是"插件沙箱"，装 shell 插件等于信任它的作者。界面上对此有明确提示。
 *
 * <h3>脚本格式</h3>
 * <pre>
 * #!/system/bin/sh
 * # @interval 5000        ← 多久跑一次（ms，最小 1000）
 * # @timeout 2000         ← 单次执行上限（ms）
 * # @metric fan_rpm  FAN  rpm  0     ← name label unit decimals [role]
 *
 * v=$(cat /sys/class/fan/fan_speed 2>/dev/null)
 * echo "fan_rpm=${v:-0}"
 * </pre>
 * stdout 里 {@code NAME=VALUE} 的行会被解析成值；其它行原样忽略。
 */
public final class ShellPlugins {

    /** 插件目录：与表达式插件分开，免得扫目录时混在一起 */
    public static final String DIR = "plugins/shell";
    /** 注入传感器表的前缀 */
    public static final String ID_PREFIX = "sh-";

    /** 间隔下限：再密就不如用表达式插件了，而且会明显吃电 */
    public static final long MIN_INTERVAL_MS = 1000L;
    private static final long DEFAULT_INTERVAL = 5000L;
    private static final long DEFAULT_TIMEOUT = 2000L;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    /** 值超过"间隔 × 该倍数"未更新就认为失效（脚本卡死/被删） */
    private static final int STALE_FACTOR = 3;

    /** 一个脚本声明的指标 */
    public static final class MetricDecl {
        public final String name;
        public final String label;
        public final String unit;
        public final int decimals;
        public final SensorSpec.Role role;

        MetricDecl(String name, String label, String unit, int decimals, SensorSpec.Role role) {
            this.name = name;
            this.label = label;
            this.unit = unit;
            this.decimals = decimals;
            this.role = role;
        }
    }

    /** 一个脚本 */
    public static final class Script {
        public final String file;
        public final long intervalMs;
        public final long timeoutMs;
        public final List<MetricDecl> metrics = new ArrayList<>();
        long lastRunAt;

        Script(String file, long intervalMs, long timeoutMs) {
            this.file = file;
            this.intervalMs = intervalMs;
            this.timeoutMs = timeoutMs;
        }
    }

    private static final class Val {
        final float v;
        final long at;

        Val(float v, long at) {
            this.v = v;
            this.at = at;
        }
    }

    private static final List<Script> SCRIPTS = new ArrayList<>();
    private static final Map<String, Val> VALUES = new ConcurrentHashMap<>();
    private static final List<String> WARNINGS = new ArrayList<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sh-plugins");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean running = false;
    /**
     * 最近一帧的读数，会以 {@code HUD_<NAME>} 的形式注入脚本环境。
     *
     * <p>没有这个，脚本只能读文件，写不了"温度超过 45 就输出 1"这类逻辑 ——
     * 而这些值在应用内存里，文件系统里根本没有。
     */
    private static volatile Map<String, Float> latest = java.util.Collections.emptyMap();
    private static volatile long lastRunAt = 0L;
    private static volatile long lastCostMs = 0L;
    private static boolean loaded = false;
    private static long loadedAt = 0L;

    private ShellPlugins() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static synchronized List<Script> scripts() {
        return new ArrayList<>(SCRIPTS);
    }

    public static synchronized List<String> warnings() {
        return new ArrayList<>(WARNINGS);
    }

    public static int metricCount() {
        int n = 0;
        for (Script s : SCRIPTS) n += s.metrics.size();
        return n;
    }

    /** 上次执行耗时（ms），给界面显示"shell 插件花了多少" */
    public static long lastCostMs() {
        return lastCostMs;
    }

    // ------------------------------------------------------------------ 扫描

    public static synchronized void load(Context c) {
        load(c, false);
    }

    public static synchronized void load(Context c, boolean force) {
        if (!force && loaded && System.currentTimeMillis() - loadedAt < 5000L) return;

        SCRIPTS.clear();
        WARNINGS.clear();

        File[] files = dir(c).listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".sh"));
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                try {
                    SCRIPTS.add(parse(f));
                } catch (Exception e) {
                    WARNINGS.add(f.getName() + "：" + e.getMessage());
                }
            }
        }
        ensureExample(c);

        loaded = true;
        loadedAt = System.currentTimeMillis();
    }

    /**
     * 首次运行时写一个示例脚本，省得用户去翻文档猜格式。
     *
     * <p>示例只读环境变量（{@code HUD_AGG_CPU}），不依赖任何厂商私有节点 ——
     * 拿它当模板是"一定能跑起来"的，而不是一上手就一片 --。
     * 而且它的指标默认不启用，所以不会产生任何 fork。
     */
    private static void ensureExample(Context c) {
        File d = dir(c);
        File[] files = d.listFiles((x, n) -> n.toLowerCase(Locale.US).endsWith(".sh"));
        if (files != null && files.length > 0) return;

        String example = "#!/system/bin/sh\n"
                + "# 示例 shell 插件：用当前读数做阈值判断\n"
                + "# @interval 5000\n"
                + "# @timeout 1500\n"
                + "# @metric cpu_hot  HOT\n"
                + "#\n"
                + "# 应用会把当前读数注入成环境变量：HUD_AGG_CPU / HUD_BATT_CUR ...\n"
                + "# 变量名 = HUD_ + 传感器 id 转大写、连字符换下划线\n"
                + "t=\"${HUD_AGG_CPU:-0}\"\n"
                + "t=\"${t%%.*}\"\n"
                + "if [ \"$t\" -ge 45 ]; then\n"
                + "  echo \"cpu_hot=1\"\n"
                + "else\n"
                + "  echo \"cpu_hot=0\"\n"
                + "fi\n";
        try (OutputStream os = new FileOutputStream(new File(d, "example.sh"))) {
            os.write(example.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        try {
            SCRIPTS.add(parse(new File(d, "example.sh")));
        } catch (Exception ignored) {
        }
    }

    /** 匹配"去掉 # 之后"的声明体：@metric name [label] [unit] [decimals] [role] */
    private static final Pattern METRIC = Pattern.compile(
            "^@metric\\s+(\\S+)(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?");

    private static Script parse(File f) throws Exception {
        long interval = DEFAULT_INTERVAL;
        long timeout = DEFAULT_TIMEOUT;
        List<MetricDecl> metrics = new ArrayList<>();

        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }

        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("#")) continue;

            // 去掉 # 再判断，这样 "#@interval" / "#  @interval" 都能认；
            // 只认严格一种写法会让脚本作者踩没必要的坑
            String body = line.substring(1).trim();
            String low = body.toLowerCase(Locale.US);

            if (low.startsWith("@interval")) {
                interval = clamp(parseLong(body.substring(9).trim(), DEFAULT_INTERVAL),
                        MIN_INTERVAL_MS, 600000L);
            } else if (low.startsWith("@timeout")) {
                timeout = clamp(parseLong(body.substring(8).trim(), DEFAULT_TIMEOUT),
                        MIN_INTERVAL_MS, 10000L);
            } else if (low.startsWith("@metric")) {
                Matcher m = METRIC.matcher(body);
                if (!m.find()) continue;
                String name = m.group(1);
                String label = m.group(2) == null ? name : m.group(2);
                String unit = m.group(3) == null ? "" : m.group(3);
                int dec = m.group(4) == null ? 0 : (int) parseLong(m.group(4), 0);
                SensorSpec.Role role = roleOf(m.group(5));
                if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    metrics.add(new MetricDecl(name, label, unit,
                            Math.max(0, Math.min(4, dec)), role));
                } else {
                    WARNINGS.add(f.getName() + "：指标名 " + name
                            + " 只能用字母数字下划线");
                }
            }
        }

        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("没有声明任何 @metric");
        }
        Script out = new Script(f.getName(), interval, timeout);
        out.metrics.addAll(metrics);
        return out;
    }

    private static SensorSpec.Role roleOf(String s) {
        if (s == null) return SensorSpec.Role.PLAIN;
        try {
            return SensorSpec.Role.valueOf(s.toUpperCase(Locale.US));
        } catch (Exception e) {
            return SensorSpec.Role.PLAIN;
        }
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 采样循环每 tick 调一次。内部判断有没有到期的脚本，没有就立刻返回。
     *
     * <p>执行是异步的：即使某个脚本卡住 2 秒，采样线程也不会被拖慢。
     * 用 {@code running} 标志保证同一时刻只有一个批次在跑，不会堆积。
     */
    public static void tick(Context c) {
        tick(c, null);
    }

    /**
     * @param values 当前读数，会作为 {@code HUD_xxx} 环境变量传进脚本；可空
     */
    public static void tick(Context c, Map<String, Float> values) {
        // 复制一份再存：调用方的 map 生命周期不受我们控制，
        // 直接持有引用等于拿别人的锁去读别人的可变对象
        if (values != null && !values.isEmpty()) latest = new java.util.HashMap<>(values);

        // 上一批还没跑完就跳过这一轮：宁可晚一个周期，也不让批次排队堆起来
        if (running) return;

        List<Script> due = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (ShellPlugins.class) {
            if (SCRIPTS.isEmpty()) return;
            for (Script s : SCRIPTS) {
                // 指标没启用的脚本一律不跑：每个脚本都是一次 fork，
                // 挂在那儿空转和"不偷电"是直接冲突的
                if (!enabled(s)) continue;
                if (s.lastRunAt == 0L || now - s.lastRunAt >= s.intervalMs) {
                    due.add(s);
                    s.lastRunAt = now;      // 先占位，避免这次没跑成又立刻重试
                }
            }
        }
        if (due.isEmpty()) return;

        final Context app = c.getApplicationContext();
        running = true;
        POOL.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                runBatch(app, due);
            } catch (Exception ignored) {
            } finally {
                lastCostMs = System.currentTimeMillis() - t0;
                lastRunAt = System.currentTimeMillis();
                running = false;
            }
        });
    }

    /** 这个脚本声明的指标里，有没有至少一个上了条 */
    private static boolean enabled(Script s) {
        // HudState.sensorIds 是 UI 线程随时会换掉的 ArrayList，
        // 这边在采样线程上 contains 有可能撞上并发修改 —— 宁可当成"没启用"
        try {
            java.util.List<String> ids = HudState.sensorIds;
            if (ids == null || ids.isEmpty()) return false;
            for (MetricDecl d : s.metrics) {
                if (ids.contains(ID_PREFIX + d.name)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 把一批脚本拼成一个临时 sh 跑一次。
     *
     * <p>用 {@code ( ... )} 包住每个脚本：里面的 cd/变量/exit 都只影响子 shell，
     * 前一个脚本写错不会毁掉后面所有脚本。
     */
    private static void runBatch(Context c, List<Script> due) {
        File tmp = new File(c.getCacheDir(), "sh-plugins-batch.sh");
        Map<String, String> sectionOf = new LinkedHashMap<>();
        long timeout = DEFAULT_TIMEOUT;

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("cd '").append(dir(c).getAbsolutePath().replace("'", "'\\''")).append("' 2>/dev/null\n");

        for (int i = 0; i < due.size(); i++) {
            Script sc = due.get(i);
            String key = "s" + i;
            sectionOf.put(key, sc.file);
            timeout = Math.max(timeout, sc.timeoutMs);

            sb.append("echo '@@B ").append(key).append("'\n");
            sb.append("(\n");
            for (String line : readLines(new File(dir(c), sc.file))) {
                sb.append(line).append('\n');
            }
            sb.append(") 2>&1\n");
            sb.append("echo '@@E ").append(key).append("'\n");
        }

        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }

        Process p = null;
        long stamp = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", tmp.getAbsolutePath());
            pb.directory(dir(c));
            pb.redirectErrorStream(true);

            // 清空继承的环境：不该把应用进程的环境变量（可能有调试用的东西）
            // 原样漏给一堆来路不明的脚本
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("PATH", "/system/bin:/system/xbin:/vendor/bin");
            env.put("HOME", dir(c).getAbsolutePath());
            env.put("TMPDIR", c.getCacheDir().getAbsolutePath());

            // 当前读数：HUD_AGG_CPU=45.000 这种。变量名统一大写，
            // 脚本里用 ${HUD_AGG_CPU:-0} 取，读不到时不会炸
            for (Map.Entry<String, Float> e : latest.entrySet()) {
                Float v = e.getValue();
                if (v == null || v.isNaN() || v.isInfinite()) continue;
                env.put("HUD_" + Sensor.safeName(e.getKey()).toUpperCase(Locale.US),
                        String.format(Locale.US, "%.3f", v));
            }

            p = pb.start();
            final Process proc = p;

            StringBuilder out = new StringBuilder();
            final int[] budget = {MAX_OUTPUT_BYTES};
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        budget[0] -= line.length() + 1;
                        if (budget[0] <= 0) break;   // 脚本疯狂 echo 时及时刹车
                        out.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }, "sh-plugins-reader");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                p.destroy();
                try {
                    p.waitFor(200, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                }
            }
            reader.join(300);

            parseOutput(out.toString(), sectionOf, due, stamp);
        } catch (Exception ignored) {
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Exception ignored) {
            }
            try {
                tmp.delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 解析 @@B/@@E 之间那些 NAME=VALUE */
    private static void parseOutput(String text, Map<String, String> sectionOf,
                                    List<Script> due, long stamp) {
        String cur = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("@@B ")) {
                cur = line.substring(4).trim();
                continue;
            }
            if (line.startsWith("@@E ")) {
                cur = null;
                continue;
            }
            if (cur == null || line.isEmpty()) continue;

            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;

            float v = firstFloat(value);
            if (Float.isNaN(v)) continue;
            VALUES.put(name, new Val(v, stamp));
        }
    }

    /**
     * 从字符串里抠出第一个数字。
     *
     * <p>脚本输出经常带单位（{@code 4.03V}、{@code 3200 rpm}），
     * 严格 {@code parseFloat} 会全丢；这里只要求"开头附近有个数"。
     */
    private static float firstFloat(String s) {
        Matcher m = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(s);
        if (!m.find()) return Float.NaN;
        try {
            return Float.parseFloat(m.group());
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    // ------------------------------------------------------------------ 取值

    /**
     * 取某个指标的当前值。
     *
     * @return 超过 3 个周期没更新、或从未跑过，一律 NaN（上屏显示 --）
     */
    public static float value(String name) {
        Val v = VALUES.get(name);
        if (v == null) return Float.NaN;
        long interval = intervalOf(name);
        if (System.currentTimeMillis() - v.at > Math.max(10000L, interval * STALE_FACTOR)) {
            return Float.NaN;
        }
        return v.v;
    }

    private static long intervalOf(String name) {
        synchronized (ShellPlugins.class) {
            for (Script s : SCRIPTS) {
                for (MetricDecl d : s.metrics) {
                    if (d.name.equals(name)) return s.intervalMs;
                }
            }
        }
        return DEFAULT_INTERVAL;
    }

    /** 把声明转成传感器表项 */
    public static SensorSpec toSpec(MetricDecl m, String scriptFile) {
        SensorSpec sp = new SensorSpec(ID_PREFIX + m.name, m.label, m.unit,
                SensorSpec.Kind.SHELL, 0, m.decimals, m.role);
        sp.src = m.name;
        sp.desc = "shell：" + scriptFile;
        return sp;
    }

    public static synchronized void clearRuntime() {
        VALUES.clear();
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 导入一段脚本源码。
     *
     * <p>不做语法校验（shell 没有便宜的静态检查），但要求至少有一条
     * {@code @metric} 声明 —— 没有声明的脚本跑起来也只是空转。
     *
     * @return 落盘后的文件名
     */
    public static synchronized String importText(Context c, String suggestedName,
                                                 String text) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("文件是空的");
        if (!text.contains("@metric")) {
            throw new IllegalArgumentException("脚本里没有 @metric 声明，不会有任何指标");
        }
        String name = safeFileName(suggestedName);
        File out = new File(dir(c), name);
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return name;
    }

    private static String safeFileName(String s) {
        String base = s == null ? "" : s.trim();
        if (base.isEmpty()) base = "script.sh";
        if (!base.toLowerCase(Locale.US).endsWith(".sh")) base = base + ".sh";
        StringBuilder sb = new StringBuilder();
        for (char ch : base.toCharArray()) {
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.';
            sb.append(ok ? ch : '_');
        }
        return sb.toString();
    }

    /** 供界面列出文件 */
    public static List<String> fileNames(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".sh"));
        List<String> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) out.add(f.getName());
            Collections.sort(out);
        }
        return out;
    }
}
