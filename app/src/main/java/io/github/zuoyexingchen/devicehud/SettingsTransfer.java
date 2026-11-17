package io.github.zuoyexingchen.devicehud;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设置的导入导出。
 *
 * <p>导出内容包含：全部偏好项、传感器表（含用户改过的名字/单位/颜色/顺序）、
 * 启用集合、机型信息。**背景图不导出** —— 它指向本机私有目录里的文件，
 * 换台机器导进去只会看到"读取失败"。
 *
 * <p>为什么每个偏好项要包一层 {@code {"t":..,"v":..}} 而不是直接写裸值：
 * SharedPreferences 的 {@code getLong} 遇到存成 Integer 的值会直接抛
 * ClassCastException，而 JSON 数字解析出来统一是 Integer/Long/Double。
 * 把原始类型一起写进去，导回时才能还原成一模一样的 Java 类型。
 */
public final class SettingsTransfer {

    /** 格式版本：加字段时 +1，导入端据此判断能不能读 */
    public static final int SCHEMA = 2;

    private SettingsTransfer() {
    }

    /** 解析结果，供界面弹确认框 */
    public static final class Preview {
        public JSONObject root;
        public boolean sameDevice;
        public String sourceDevice = "未知机型";
        public String localDevice = DeviceInfo.model();
        public String appVersion = "";
        public String exportedAt = "";
        public int prefCount;
        public int sensorCount;
        public int tableVersion;
    }

    // ------------------------------------------------------------------ 导出

    public static JSONObject build(Context c) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "DeviceHud");
        root.put("schema", SCHEMA);
        root.put("app_version", DeviceInfo.appVersion(c));
        root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        root.put("device", DeviceInfo.toJson());

        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, Object> e : Prefs.snapshot(c).entrySet()) {
            JSONObject box = new JSONObject();
            Object v = e.getValue();
            if (v instanceof Boolean) {
                box.put("t", "b").put("v", (Boolean) v);
            } else if (v instanceof Integer) {
                box.put("t", "i").put("v", ((Integer) v).intValue());
            } else if (v instanceof Long) {
                box.put("t", "l").put("v", ((Long) v).longValue());
            } else if (v instanceof Float) {
                box.put("t", "f").put("v", ((Float) v).doubleValue());
            } else if (v instanceof String) {
                box.put("t", "s").put("v", (String) v);
            } else {
                continue;
            }
            prefs.put(e.getKey(), box);
        }
        root.put("prefs", prefs);

        // 传感器表单独存原文：它就是一堆 | 分隔的行，没必要拆成 JSON 树，
        // 拆了反而会在新老版本字段差异上出问题
        root.put("sensor_table", Prefs.sensorTable(c));
        root.put("sensor_table_ver", Prefs.tableVersion(c));

        List<SensorSpec> table = SensorTable.load(c);
        JSONArray arr = new JSONArray();
        for (SensorSpec s : table) arr.put(s.id);
        root.put("sensor_table_ids", arr);

        return root;
    }

    public static void write(Context c, OutputStream os) throws Exception {
        JSONObject root = build(c);
        os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    // ------------------------------------------------------------------ 导入

    /** 只解析，不落盘；没有副作用，可以放心在确认框之前调 */
    public static Preview peek(Context c, InputStream is) throws Exception {
        String text = readAll(is);
        JSONObject root = new JSONObject(text);
        if (!root.has("prefs") && !root.has("sensor_table")) {
            throw new IllegalArgumentException("这不是 DeviceHud 的设置文件");
        }
        int schema = root.optInt("schema", 0);
        if (schema > SCHEMA) {
            throw new IllegalArgumentException("设置文件来自更新的版本（schema " + schema
                    + "），请先升级本应用");
        }

        Preview p = new Preview();
        p.root = root;
        p.appVersion = root.optString("app_version", "");
        p.exportedAt = root.optString("exported_at", "");
        JSONObject dev = root.optJSONObject("device");
        p.sameDevice = DeviceInfo.sameDevice(dev);
        p.sourceDevice = DeviceInfo.describe(dev);
        p.prefCount = root.optJSONObject("prefs") == null
                ? 0 : root.optJSONObject("prefs").length();
        p.sensorCount = root.optJSONArray("sensor_table_ids") == null ? 0
                : root.optJSONArray("sensor_table_ids").length();
        p.tableVersion = root.optInt("sensor_table_ver", 0);
        return p;
    }

    /**
     * 应用导入的设置。
     *
     * @param includeSensors 是否连同传感器表一起导入。
     *                       机型不一致时建议传 false —— 保留本机探测出来的表，
     *                       只把外观/偏好搬过来，避免条上出现一堆永远读不到值的项。
     * @return 实际写入的偏好项数量
     */
    public static int apply(Context c, Preview pv, boolean includeSensors) throws Exception {
        if (pv == null || pv.root == null) return 0;

        JSONObject prefs = pv.root.optJSONObject("prefs");
        Map<String, Object> flat = new LinkedHashMap<>();
        if (prefs != null) {
            java.util.Iterator<String> it = prefs.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject box = prefs.optJSONObject(k);
                if (box == null) continue;
                String t = box.optString("t", "s");
                Object v;
                switch (t) {
                    case "b":
                        v = box.optBoolean("v", false);
                        break;
                    case "i":
                        v = box.optInt("v", 0);
                        break;
                    case "l":
                        v = box.optLong("v", 0L);
                        break;
                    case "f":
                        v = (float) box.optDouble("v", 0d);
                        break;
                    default:
                        v = box.optString("v", "");
                        break;
                }
                flat.put(k, v);
            }
        }
        int n = Prefs.applySnapshot(c, flat);

        if (includeSensors) {
            String table = pv.root.optString("sensor_table", "");
            if (!table.isEmpty()) {
                Prefs.setSensorTable(c, table);
                Prefs.setTableVersion(c, pv.root.optInt("sensor_table_ver", 0));
            }
        }

        // 不管导不导传感器表，位置/可见性这类"当前运行态"都要重新灌一遍，
        // 否则界面上还是导入前的旧值
        Prefs.loadIntoState(c);
        return n;
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    /** 导出文件名建议 */
    public static String suggestName() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        String model = DeviceInfo.model().replaceAll("[^A-Za-z0-9]+", "-");
        if (model.isEmpty()) model = "device";
        return "devicehud-" + model + "-" + stamp + ".json";
    }

    /** 给界面显示的一行摘要 */
    public static List<String> summary(Preview pv) {
        List<String> out = new ArrayList<>();
        if (pv == null) return out;
        out.add("来源机型：" + pv.sourceDevice);
        out.add("导出时间：" + (pv.exportedAt.isEmpty() ? "?" : pv.exportedAt)
                + "  版本 " + (pv.appVersion.isEmpty() ? "?" : pv.appVersion));
        out.add("偏好项 " + pv.prefCount + " 条 · 传感器表 " + pv.sensorCount + " 项");
        return out;
    }
}
