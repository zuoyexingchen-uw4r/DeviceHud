package io.github.zuoyexingchen.devicehud;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 插件宿主：把 {@code files/plugins/*.json} 里声明的派生指标接进传感器表。
 *
 * <p><b>为什么插件是"声明式 JSON"而不是可执行代码。</b>
 * 本项目的硬约束是零第三方依赖、不碰 AndroidX，所以没有一个现成的
 * 脚本引擎可用；而为了跑插件去加载外部 dex/jar 意味着要在应用里
 * 动态加载未知代码，安全模型直接崩掉，也不符合"和用户同一个 uid"的定位。
 * 折中方案是：插件只能**声明公式**，由 {@link Expr} 求值 —— 表达力足够
 * 覆盖"组合已有指标"这个真实需求（例如 电压×电流/1000 得到功率），
 * 但拿不到任何系统能力，最坏情况只是显示 --。
 *
 * <p>插件文件格式：
 * <pre>
 * {
 *   "id": "demo",
 *   "name": "示例插件",
 *   "metrics": [
 *     {"id":"est-watt","label":"EST","unit":"W","decimals":2,"role":"POWER",
 *      "expr":"batt_volt * abs(batt_cur) / 1000",
 *      "desc":"按电压电流估算的功率"}
 *   ]
 * }
 * </pre>
 *
 * <p>表达式里引用传感器要用"安全名"：把 id 里的 {@code -} 换成 {@code _}，
 * 例如 {@code batt-cur} → {@code batt_cur}。
 *
 * <p>支持 {@code + - * / %}、括号、比较运算（{@code < > <= >= == !=}，返回 1/0）
 * 以及函数 {@code abs min max clamp sqrt pow round floor ceil avg if}。
 * 例：{@code if(agg_cpu > 45, 1, 0)} 可以当"过热"标记用。
 */
public final class PluginHost {

    /** 插件目录（应用私有，卸载即清） */
    public static final String DIR = "plugins";
    /** 注入到传感器表时统一加的前缀，避免和内置 id 撞车 */
    public static final String ID_PREFIX = "plug-";

    public static final class Metric {
        public final String pluginId;
        public final String pluginName;
        public final String id;
        public final String label;
        public final String unit;
        public final String expr;
        public final String desc;
        public final int decimals;
        public final SensorSpec.Role role;

        Metric(String pluginId, String pluginName, String id, String label, String unit,
               String expr, String desc, int decimals, SensorSpec.Role role) {
            this.pluginId = pluginId;
            this.pluginName = pluginName;
            this.id = id;
            this.label = label;
            this.unit = unit;
            this.expr = expr;
            this.desc = desc;
            this.decimals = decimals;
            this.role = role;
        }

        /** 进传感器表用的 id */
        public String fullId() {
            return ID_PREFIX + id;
        }
    }

    private static final Map<String, Metric> METRICS = new LinkedHashMap<>();
    private static final Map<String, Expr.Node> COMPILED = new HashMap<>();
    private static final List<String> WARNINGS = new ArrayList<>();
    private static boolean loaded = false;
    private static long loadedAt = 0L;

    private PluginHost() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory() && !d.mkdirs()) {
            // 建不出来也应该让上层拿到一个路径，写文件时自然会报错
        }
        return d;
    }

    public static synchronized List<Metric> metrics() {
        return new ArrayList<>(METRICS.values());
    }

    public static synchronized List<String> warnings() {
        return new ArrayList<>(WARNINGS);
    }

    public static synchronized boolean loaded() {
        return loaded;
    }

    public static synchronized long loadedAt() {
        return loadedAt;
    }

    /**
     * 扫描插件目录并编译全部表达式。
     *
     * <p>每次重新加载都清空重来，保证"删掉文件后指标真的消失"。
     * 语法错误的单条指标只丢自己，不影响同一个文件里的其它指标。
     */
    public static synchronized void load(Context c) {
        load(c, false);
    }

    /**
     * @param force true 表示无论如何都重扫。导入插件、点「重新加载」必须用 true ——
     *              否则会撞上节流窗口，用户刚导入的文件"没反应"。
     */
    public static synchronized void load(Context c, boolean force) {
        // 这张表在"设置变化 → applySettings"里会被反复读，
        // 每次都重新扫目录 + 解析 JSON 太浪费，5 秒内直接复用上次结果
        if (!force && loaded && System.currentTimeMillis() - loadedAt < 5000L) return;

        METRICS.clear();
        COMPILED.clear();
        WARNINGS.clear();

        File[] files = dir(c).listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".json"));
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                try {
                    parseFile(f);
                } catch (Exception e) {
                    WARNINGS.add(f.getName() + "：" + e.getMessage());
                }
            }
        }
        ensureExample(c);
        loaded = true;
        loadedAt = System.currentTimeMillis();
    }

    private static void parseFile(File f) throws Exception {
        String text = readText(f);
        JSONObject root = new JSONObject(text);
        String pluginId = root.optString("id", f.getName().replace(".json", ""));
        String pluginName = root.optString("name", pluginId);

        JSONArray arr = root.optJSONArray("metrics");
        if (arr == null) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;

            String id = m.optString("id", "").trim();
            String expr = m.optString("expr", "").trim();
            if (id.isEmpty() || expr.isEmpty()) {
                WARNINGS.add(pluginName + "：第 " + (i + 1) + " 项缺少 id 或 expr");
                continue;
            }

            String label = m.optString("label", id);
            String unit = m.optString("unit", "");
            String desc = m.optString("desc", pluginName);
            int decimals = clamp(m.optInt("decimals", 1), 0, 4);
            SensorSpec.Role role = roleOf(m.optString("role", "PLAIN"));

            Metric metric = new Metric(pluginId, pluginName, id, label, unit,
                    expr, desc, decimals, role);

            String key = metric.fullId();
            if (METRICS.containsKey(key)) {
                // 同名指标以先加载的文件为准，后一个给出警告而不是静默覆盖
                WARNINGS.add(pluginName + "：" + key + " 与前面的插件重名，已忽略");
                continue;
            }

            METRICS.put(key, metric);
            try {
                COMPILED.put(key, Expr.parse(expr));
            } catch (Exception e) {
                WARNINGS.add(pluginName + " / " + id + "：" + e.getMessage());
            }
        }
    }

    private static SensorSpec.Role roleOf(String s) {
        try {
            return SensorSpec.Role.valueOf(s.trim().toUpperCase(Locale.US));
        } catch (Exception e) {
            return SensorSpec.Role.PLAIN;
        }
    }

    /**
     * 求值。ctx 里同时有原始 id 和安全名两种键，插件写哪种都能命中。
     *
     * @return 求不出来（没编译成功 / 变量缺失 / 除零）一律 NaN
     */
    public static float eval(String fullId, Map<String, Float> ctx) {
        Expr.Node node;
        synchronized (PluginHost.class) {
            node = COMPILED.get(fullId);
        }
        if (node == null) return Float.NaN;
        return Expr.eval(node, name -> {
            Float v = ctx.get(name);
            if (v == null) {
                // 表达式作者用的可能是安全名，ctx 里两种键都放了；
                // 兜底再试一次反变换，免得漏掉大小写/分隔符写法上的差异
                v = ctx.get(name.replace('_', '-'));
            }
            return v == null ? Float.NaN : v;
        });
    }

    /** 把某个指标描述成传感器表里的一项 */
    public static SensorSpec toSpec(Metric m) {
        SensorSpec sp = new SensorSpec(m.fullId(), m.label, m.unit,
                SensorSpec.Kind.PLUGIN, 0, m.decimals, m.role);
        sp.desc = m.desc.isEmpty() ? m.pluginName : m.desc;
        sp.expr = m.expr;
        return sp;
    }

    // ------------------------------------------------------------------ 文件

    private static String readText(File f) throws Exception {
        try (InputStream is = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * 首次运行时写一个示例插件，让"插件长什么样"不用翻文档。
     *
     * <p>只在目录里一个 json 都没有时才写 —— 用户删掉它之后不会又被塞回来。
     */
    private static void ensureExample(Context c) {
        File d = dir(c);
        File[] files = d.listFiles((x, n) -> n.toLowerCase(Locale.US).endsWith(".json"));
        if (files != null && files.length > 0) return;

        String example = "{\n"
                + "  \"id\": \"example\",\n"
                + "  \"name\": \"示例插件\",\n"
                + "  \"metrics\": [\n"
                + "    {\n"
                + "      \"id\": \"est-watt\",\n"
                + "      \"label\": \"EST\",\n"
                + "      \"unit\": \"W\",\n"
                + "      \"decimals\": 2,\n"
                + "      \"role\": \"POWER\",\n"
                + "      \"expr\": \"batt_volt * abs(batt_cur) / 1000\",\n"
                + "      \"desc\": \"按电压×电流估算的功率\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
        try (OutputStream os = new FileOutputStream(new File(d, "example.json"))) {
            os.write(example.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        // 刚写出来的这份还没进表，就地补一次（不递归调用 load）
        try {
            Metric m = new Metric("example", "示例插件", "est-watt", "EST", "W",
                    "batt_volt * abs(batt_cur) / 1000", "按电压×电流估算的功率", 2,
                    SensorSpec.Role.POWER);
            if (!METRICS.containsKey(m.fullId())) {
                METRICS.put(m.fullId(), m);
                COMPILED.put(m.fullId(), Expr.parse(m.expr));
            }
        } catch (Exception ignored) {
        }
    }

    /** 从外部 uri 导入一个插件文件，返回落盘后的文件名；失败抛异常 */
    public static synchronized String importFrom(Context c, Uri uri) throws Exception {
        String text;
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalStateException("读不到文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            text = bos.toString(StandardCharsets.UTF_8.name());
        }
        return importText(c, text);
    }

    /** 已经拿到文本内容的导入路径（供调用方先判断是 JSON 还是 shell） */
    public static synchronized String importText(Context c, String text) throws Exception {
        // 先按 JSON 解析一遍：语法错的文件不该落地，否则每次启动都会报同一堆错
        JSONObject root = new JSONObject(text);
        String id = root.optString("id", "").trim();
        if (id.isEmpty()) throw new IllegalArgumentException("缺少 id 字段");

        String name = safeFileName(id) + ".json";
        File out = new File(dir(c), name);
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return name;
    }

    private static String safeFileName(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            sb.append(ok ? ch : '_');
        }
        return sb.length() == 0 ? "plugin" : sb.toString();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 供设置界面列出现有文件 */
    public static synchronized List<String> fileNames(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".json"));
        List<String> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) out.add(f.getName());
            Collections.sort(out);
        }
        return out;
    }
}
