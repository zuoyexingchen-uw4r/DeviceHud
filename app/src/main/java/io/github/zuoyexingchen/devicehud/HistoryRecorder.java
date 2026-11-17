package io.github.zuoyexingchen.devicehud;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 历史数据记录：把采样值按固定间隔落成 CSV，供事后导出分析。
 *
 * <p>为什么直接写 CSV 而不是先攒在内存再落盘：记录动辄跑几小时，
 * 内存里攒几万行会直接吃掉几十 MB；CSV 又刚好是人能直接打开看的格式，
 * 出问题不用写解析器就能排查。
 *
 * <p>写文件用的是 {@link BufferedWriter}，每 {@link #FLUSH_EVERY} 行刷一次 ——
 * 每行都 fsync 会让采样线程卡在 IO 上，几小时下来还伤闪存。
 * 代价是进程被杀时最多丢最后几十行，这个取舍对遥测日志是划算的。
 *
 * <p>记录点只在**服务采样循环**里喂（见 {@code Sampler.offer}），
 * 所以息屏后采样停止，记录也跟着停 —— 这是刻意的，跟着省电路径走。
 */
public final class HistoryRecorder {

    private static final String DIR = "history";
    /** 记录间隔下限：再密就是在写磁盘玩 */
    private static final long MIN_INTERVAL_MS = 200L;
    /** 单个文件上限，超过就自动停（防止忘关把存储写满） */
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    /** 保留最近几个文件 */
    private static final int MAX_FILES = 12;
    /** 每多少行 flush 一次 */
    private static final int FLUSH_EVERY = 32;

    private static final Object LOCK = new Object();

    private static BufferedWriter writer;
    private static File file;
    private static long rows;
    private static long startedAt;
    private static long lastWriteAt;
    private static long intervalMs = 1000L;
    private static long bytes;
    private static int sinceFlush;
    private static final List<String> ids = new ArrayList<>();

    private HistoryRecorder() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static boolean isRecording() {
        synchronized (LOCK) {
            return writer != null;
        }
    }

    public static File currentFile() {
        synchronized (LOCK) {
            return file;
        }
    }

    public static long rows() {
        synchronized (LOCK) {
            return rows;
        }
    }

    public static long durationMs() {
        synchronized (LOCK) {
            return writer == null ? 0L : System.currentTimeMillis() - startedAt;
        }
    }

    public static List<String> columns() {
        synchronized (LOCK) {
            return new ArrayList<>(ids);
        }
    }

    /**
     * 开始记录。
     *
     * @param intervalMs 落盘间隔，太小会被夹到 {@link #MIN_INTERVAL_MS}
     * @return 成功返回 true；已在记录中直接返回 false
     */
    public static boolean start(Context c, List<String> sensorIds, long intervalMs) {
        synchronized (LOCK) {
            if (writer != null) return false;
            if (sensorIds == null || sensorIds.isEmpty()) return false;

            pruneOld(c);

            ids.clear();
            ids.addAll(sensorIds);
            intervalMs = Math.max(MIN_INTERVAL_MS, Math.max(intervalMs, Prefs.historyIntervalMs(c)));

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            file = new File(dir(c), "hud-" + stamp + ".csv");

            try {
                OutputStream os = new FileOutputStream(file);
                writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                // # 开头的注释行：Excel / pandas 都能跳过，人也能直接看懂这是哪台机器的
                writer.write("# DeviceHud history");
                writer.write("\n# device=" + DeviceInfo.model());
                writer.write("\n# soc=" + DeviceInfo.soc());
                writer.write("\n# sdk=" + Build.VERSION.SDK_INT);
                writer.write("\n# app=" + DeviceInfo.appVersion(c));
                writer.write("\n# interval_ms=" + intervalMs);
                writer.write("\nt_ms,iso,");
                StringBuilder sb = new StringBuilder();
                for (String id : ids) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(csv(id));
                }
                writer.write(sb.toString());
                writer.write('\n');
                writer.flush();
            } catch (Exception e) {
                closeQuietly();
                file = null;
                return false;
            }

            rows = 0;
            bytes = 0;
            sinceFlush = 0;
            startedAt = System.currentTimeMillis();
            lastWriteAt = 0L;
            return true;
        }
    }

    /**
     * 喂一行。调用方可以按采样率喂，这里自己按 interval 节流。
     *
     * @return true 表示这次真的写了一行
     */
    public static boolean offer(Map<String, Float> values) {
        synchronized (LOCK) {
            if (writer == null || values == null) return false;
            long now = System.currentTimeMillis();
            if (lastWriteAt != 0 && now - lastWriteAt < intervalMs) return false;
            lastWriteAt = now;

            try {
                String iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date(now));
                StringBuilder sb = new StringBuilder(96);
                sb.append(now - startedAt).append(',')
                        .append(iso);
                for (String id : ids) {
                    Float v = values.get(id);
                    sb.append(',');
                    if (v == null || v.isNaN() || v.isInfinite()) {
                        // 空字段而不是 "NaN"：pandas 默认会把空当缺失值处理，
                        // 写 NaN 反而会变成字符串列
                        sb.append("");
                    } else {
                        sb.append(String.format(Locale.US, "%.3f", v));
                    }
                }
                sb.append('\n');
                String line = sb.toString();
                writer.write(line);
                bytes += line.length();
                rows++;

                if (++sinceFlush >= FLUSH_EVERY) {
                    writer.flush();
                    sinceFlush = 0;
                }

                if (bytes >= MAX_BYTES) {
                    // 到达上限就自己收尾，别等用户发现存储满了
                    closeQuietly();
                }
                return true;
            } catch (Exception e) {
                closeQuietly();
                return false;
            }
        }
    }

    /** 停止记录并返回落盘的文件（没有在记录时返回 null） */
    public static File stop() {
        synchronized (LOCK) {
            File f = file;
            closeQuietly();
            return f;
        }
    }

    private static void closeQuietly() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (Exception ignored) {
            }
        }
        writer = null;
        lastWriteAt = 0L;
    }

    // ------------------------------------------------------------------ 文件管理

    /** 现有记录文件，按时间从新到旧 */
    public static List<File> files(Context c) {
        File[] arr = dir(c).listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".csv"));
        List<File> out = new ArrayList<>();
        if (arr != null) out.addAll(Arrays.asList(arr));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** 只保留最近 MAX_FILES 个，其余删掉 */
    private static void pruneOld(Context c) {
        List<File> all = files(c);
        for (int i = MAX_FILES - 1; i < all.size(); i++) {
            try {
                all.get(i).delete();
            } catch (Exception ignored) {
            }
        }
    }

    public static void deleteAll(Context c) {
        synchronized (LOCK) {
            closeQuietly();
            file = null;
        }
        for (File f : files(c)) {
            try {
                f.delete();
            } catch (Exception ignored) {
            }
        }
    }

    public static long totalBytes(Context c) {
        long sum = 0;
        for (File f : files(c)) sum += f.length();
        return sum;
    }

    /** 导出：把某个记录文件整份复制到用户选的位置 */
    public static void export(Context c, Uri target, File src) throws Exception {
        if (src == null || !src.isFile()) throw new IllegalStateException("没有可导出的记录");
        try (OutputStream os = c.getContentResolver().openOutputStream(target)) {
            if (os == null) throw new IllegalStateException("无法写入目标位置");
            // 按字节流式复制：记录文件可能到 24MB，整份读进 String 会翻倍占内存
            try (java.io.InputStream is = new java.io.FileInputStream(src)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            os.flush();
        }
    }

    /**
     * 读回行数（用于界面展示已有文件的规模）。
     * 文件可能很大，只数换行，不解析内容。
     */
    public static long countRows(File f) {
        if (f == null || !f.isFile()) return 0;
        long n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) {
                    first = false;
                    // 跳注释与非表头
                }
                if (line.startsWith("#")) continue;
                n++;
            }
        } catch (Exception e) {
            return 0;
        }
        return Math.max(0, n - 1);
    }

    private static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
