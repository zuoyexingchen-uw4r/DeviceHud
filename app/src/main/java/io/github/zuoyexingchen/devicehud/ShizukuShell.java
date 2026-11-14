package io.github.zuoyexingchen.devicehud;

import android.content.pm.PackageManager;
import android.os.SystemClock;

import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 借 Shizuku（shell 身份）读应用层拿不到的数据。
 *
 * 为什么需要它：Android 10+ 上 /proc/stat 对普通应用是 Permission denied
 * （本机 UID 实测 head /proc/stat 直接拒），所以真实 CPU 占用率只能从 shell 侧读。
 *
 * 实现上不开一堆短命进程：起一个常驻 sh，用 echo 哨兵串行化命令，
 * 每次采样只往管道写一行，开销比 fork 一个 shell 小一个数量级。
 */
public final class ShizukuShell {

    private static final String TAG = "DeviceHudShizuku";
    /** 最近一次失败原因，供界面提示用 */
    public static volatile String lastError = "";

    /** 权限请求码，Activity 侧回调要一致 */
    public static final int REQ_CODE = 4210;

    private static final long CMD_TIMEOUT_MS = 900L;

    private static IRemoteProcess proc;
    private static BufferedWriter stdin;
    private static BufferedReader stdout;
    private static int seq = 0;

    private ShizukuShell() {
    }

    // ------------------------------------------------------------------ 状态

    /** Shizuku 服务活着且已授权 */
    public static boolean ready() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务在跑但还没授权 */
    public static boolean canRequest() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String sLastLogged = "";

    private static void logOnce(String msg) {
        if (msg.equals(sLastLogged)) return;
        sLastLogged = msg;
        android.util.Log.i(TAG, msg);
    }

    private static int permCode() {
        try {
            return Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            return -1000;
        }
    }

    public static void request() {
        try {
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 命令执行

    /** 读 /proc/stat；不可用时返回 null */
    public static String readProcStat() {
        if (!ready()) {
            boolean ping = canRequest();
            lastError = "not ready: ping=" + ping + " perm=" + (ping ? permCode() : -1);
            logOnce(lastError);
            return null;
        }
        return exec("cat /proc/stat");
    }

    /**
     * 在常驻 sh 里跑一条命令并取回 stdout。
     * 失败返回 null —— 调用方一律按"这一帧没有数据"处理，不要重试轰炸。
     */
    public static synchronized String exec(String cmd) {
        if (!ensureShell()) return null;
        try {
            String mark = "__HUD_" + (++seq) + "__";
            stdin.write(cmd);
            stdin.write('\n');
            stdin.write("echo " + mark + '\n');
            stdin.flush();

            StringBuilder sb = new StringBuilder();
            long deadline = SystemClock.elapsedRealtime() + CMD_TIMEOUT_MS;

            while (true) {
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line == null) {
                        close();
                        return null;
                    }
                    if (mark.equals(line.trim())) return sb.toString();
                    sb.append(line).append('\n');
                } else {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        // 卡住就废掉这条管道，下次重开
                        close();
                        return sb.length() > 0 ? sb.toString() : null;
                    }
                    Thread.sleep(2L);
                }
            }
        } catch (Exception e) {
            lastError = "exec: " + e;
            android.util.Log.w(TAG, "exec failed", e);
            close();
            return null;
        }
    }

    private static boolean ensureShell() {
        try {
            if (proc != null && proc.alive()) return true;
        } catch (Throwable ignored) {
        }
        close();
        try {
            // Shizuku.newProcess 在 13.x 是私有的，直接走 IShizukuService 的 AIDL
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) return false;
            proc = svc.newProcess(new String[]{"/system/bin/sh"}, null, null);
            if (proc == null) return false;

            stdin = new BufferedWriter(new OutputStreamWriter(
                    new ParcelFileDescriptor.AutoCloseOutputStream(proc.getOutputStream())));
            stdout = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(proc.getInputStream())));
            drain(new ParcelFileDescriptor.AutoCloseInputStream(proc.getErrorStream()));
            return true;
        } catch (Throwable t) {
            proc = null;
            lastError = "newProcess: " + t;
            android.util.Log.w(TAG, "ensureShell failed", t);
            return false;
        }
    }

    /** stderr 必须读掉，否则管道满了 sh 会卡死 */
    private static void drain(final java.io.InputStream err) {
        Thread t = new Thread(() -> {
            try (java.io.InputStream in = err) {
                byte[] buf = new byte[512];
                while (in.read(buf) >= 0) {
                    // 丢弃
                }
            } catch (Exception ignored) {
            }
        }, "shizuku-stderr");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void close() {
        try {
            if (stdin != null) stdin.close();
        } catch (Exception ignored) {
        }
        try {
            if (stdout != null) stdout.close();
        } catch (Exception ignored) {
        }
        try {
            if (proc != null) proc.destroy();
        } catch (Throwable ignored) {
        }
        stdin = null;
        stdout = null;
        proc = null;
    }
}
