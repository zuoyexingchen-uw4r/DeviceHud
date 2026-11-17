package io.github.zuoyexingchen.devicehud;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 机型信息：用于设置导入导出时的"同机型"判断，也写进历史 CSV 的表头。
 *
 * <p>为什么需要它：传感器表是**按机型探测出来的**（热区名、核心数、可读节点
 * 各家都不一样）。把 A 机的设置导进 B 机，条上会出现一堆永远读不到值的项。
 * 所以导出时带上机型，导入前拿它比对并给出警告 —— 而不是假装无事发生。
 */
public final class DeviceInfo {

    private DeviceInfo() {
    }

    /** "OPPO PKU110" 这种人类可读的机型名 */
    public static String model() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String d = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (m.isEmpty()) return d;
        // 有些 ROM 的 MODEL 里已经带了厂商名，避免出现 "OPPO OPPO xxx"
        if (d.toLowerCase(Locale.US).startsWith(m.toLowerCase(Locale.US))) return d;
        return m + " " + d;
    }

    /** SoC 标识：API 31+ 有 SOC_MODEL，更老的只能用 HARDWARE */
    public static String soc() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                String s = Build.SOC_MODEL;
                if (s != null && !s.isEmpty() && !"unknown".equals(s)) return s;
            }
        } catch (Throwable ignored) {
        }
        return Build.HARDWARE == null ? "" : Build.HARDWARE;
    }

    public static String appVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    public static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("model", model());
            o.put("soc", soc());
            o.put("device", safe(Build.DEVICE));
            o.put("board", safe(Build.BOARD));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * 判断导入的配置是不是同一台机器。
     *
     * <p>判定标准故意放宽到 model + soc：同一台机器刷了不同 ROM（甚至换了
     * Android 版本）也不该被当成"换了机型"反复警告；但换一台手机一定会命中。
     */
    public static boolean sameDevice(JSONObject dev) {
        if (dev == null) return false;
        String m = dev.optString("model", "");
        String s = dev.optString("soc", "");
        return model().equalsIgnoreCase(m) && soc().equalsIgnoreCase(s);
    }

    /** 给对话框用的一行描述 */
    public static String describe(JSONObject dev) {
        if (dev == null) return "未知机型";
        String m = dev.optString("model", "?");
        String s = dev.optString("soc", "?");
        int sdk = dev.optInt("sdk", 0);
        return m + "  ·  " + s + (sdk > 0 ? "  ·  API " + sdk : "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
