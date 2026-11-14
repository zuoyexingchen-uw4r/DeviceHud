package com.zyxc.devicehud;

import android.content.Context;
import android.os.SystemClock;

/**
 * 统一的高权限 Shell 访问层。
 *
 * <p>两个后端，按优先级自动选择：
 * <ol>
 *   <li>{@link ShellBridgeClient} —— 电脑旁路桥（adb 起的 shell 进程，最稳）</li>
 *   <li>{@link ShizukuShell} —— Shizuku，拿不到桥时的备选</li>
 * </ol>
 *
 * <p>为了让 UI 线程和采样线程都不用等网络，这里起一个后台刷新线程，
 * 每 {@value #REFRESH_MS}ms 抓一次 /proc/stat 放进缓存，读者只取最近的快照。
 * 息屏时 {@link #setPaused(boolean)} 让线程彻底停手，不偷电。
 */
public final class ShellAccess {

    /** 后端类型 */
    public enum Mode {
        NONE, BRIDGE, SHIZUKU
    }

    /** 后台抓取间隔；比采样间隔略小，保证每次采样都能拿到新数据 */
    private static final long REFRESH_MS = 400L;
    /** 缓存超过这个时间就认为失效 */
    private static final long STALE_MS = 2000L;

    /** 后端状态由后台线程维护，界面只读缓存，绝不在 UI 线程做网络探测 */
    private static volatile Mode sMode = Mode.NONE;

    private static volatile String sStat;
    private static volatile long sStatAt;
    private static volatile boolean sPaused = true;
    private static volatile boolean sStarted;

    private ShellAccess() {
    }

    public static void init(Context c) {
        ShellBridgeClient.init(c);
        startWorker();
    }

    /** 当前可用后端（读缓存，无 I/O） */
    public static Mode mode() {
        return sMode;
    }

    public static boolean hasShell() {
        return mode() != Mode.NONE;
    }

    /** 界面用的简短状态描述 */
    public static String describe() {
        switch (mode()) {
            case BRIDGE:
                return "电脑旁路已连接";
            case SHIZUKU:
                return "Shizuku 已授权";
            default:
                String err = ShellBridgeClient.lastError();
                return err.isEmpty() ? "无高权限 Shell" : "无（" + err + "）";
        }
    }

    /** 息屏/恢复时调用，停掉后台抓取 */
    public static void setPaused(boolean paused) {
        sPaused = paused;
        if (paused) {
            sStat = null;
            sStatAt = 0L;
            sMode = Mode.NONE;
        }
    }

    /**
     * 取最近的 /proc/stat 快照；太旧或还没抓到则返回 null。
     * 不阻塞调用方，UI 线程可安全调用。
     */
    public static String readProcStat() {
        String s = sStat;
        if (s == null) return null;
        if (SystemClock.elapsedRealtime() - sStatAt > STALE_MS) return null;
        return s;
    }

    private static void startWorker() {
        if (sStarted) return;
        sStarted = true;

        Thread t = new Thread(() -> {
            while (true) {
                long sleep = REFRESH_MS;
                try {
                    if (!sPaused) {
                        String raw = null;
                        if (ShellBridgeClient.ready()) {
                            sMode = Mode.BRIDGE;
                            raw = ShellBridgeClient.readProcStat();
                        } else if (ShizukuShell.ready()) {
                            sMode = Mode.SHIZUKU;
                            raw = ShizukuShell.readProcStat();
                        } else {
                            sMode = Mode.NONE;
                        }
                        if (raw != null && !raw.isEmpty()) {
                            sStat = raw;
                            sStatAt = SystemClock.elapsedRealtime();
                        }
                    } else {
                        sleep = 1000L;
                    }
                } catch (Throwable ignored) {
                    sleep = 1000L;
                }
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "shell-access");

        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
