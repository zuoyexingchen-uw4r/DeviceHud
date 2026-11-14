package com.zyxc.devicehud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Map;

/**
 * 悬浮信息条服务。
 *
 * 省电设计（重点）：
 *   - 采样与上屏彻底解耦：采样率、刷新率各自独立可调（互不约束），
 *     上屏时取区间内所有采样点的平均值；刷新比采样快时沿用上次的值，不会闪 --。
 *   - 息屏立即停掉采样线程、上屏循环、布局轮询，零后台动作；
 *     亮屏再恢复。这是"不偷电"的关键。
 *   - 触摸全部穿透（FLAG_NOT_TOUCHABLE），不干扰状态栏下拉。
 */
public class HudService extends Service {

    private static final String CHANNEL_ID = "hud_overlay_v2";
    private static final int NOTIF_ID = 1001;

    /** 通知按钮动作：都走服务，不额外开 Activity */
    public static final String ACTION_TOGGLE_HUD = "com.zyxc.devicehud.action.TOGGLE_HUD";
    public static final String ACTION_MUTE_CHARGE = "com.zyxc.devicehud.action.MUTE_CHARGE";
    public static final String ACTION_REFRESH_NOTIF = "com.zyxc.devicehud.action.REFRESH_NOTIF";

    /** 通知第一行显示几项数据 */
    private static final int NOTIF_SENSORS = 3;
    /** 通知刷新上限：1s 一次，再快系统也会节流，纯属耗电 */
    private static final long NOTIF_MS = 1000L;

    /** 采样节奏与上屏节奏彻底解耦，各自读 HudState 里的档位 */
    private static long sampleMs() {
        return Math.max(50L, HudState.sampleMs);
    }

    private WindowManager wm;
    private HudView view;
    private WindowManager.LayoutParams lp;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;

    private final Sampler sampler = new Sampler();

    private volatile List<Sensor> sensors;
    private List<SensorSpec> table;
    private volatile boolean chargingNow;

    private boolean added = false;
    private final Map<String, Float> lastValues = new java.util.HashMap<>();
    private boolean lastCharging = false;
    /** 息屏或用户暂停时置 true，所有循环停摆 */
    private volatile boolean suspended = false;

    private BroadcastReceiver screenRx;

    /** 上次刷新通知的时间，用于节流 */
    private long lastNotifAt = 0L;
    /** 上一次生效的采样间隔；档位一变就重启采样循环，免得等旧间隔跑完 */
    private long lastSampleMs = 0L;

    // ------------------------------------------------------------------ 循环

    private final Runnable sampleLoop = new Runnable() {
        @Override
        public void run() {
            if (suspended) return;
            try {
                DataCollector.Snapshot s = DataCollector.capture(HudService.this);
                chargingNow = s.charging;

                // "消除充电警报"只对本次充电有效，一拔电就复位，
                // 免得下次插枪时警报被上一次的静音给吞掉
                if (!s.charging) HudState.muteChargeAlert = false;

                // 只有在"确实放电 + 没插任何电源"时才学习电流符号约定，
                // 避免旁路供电 / 极慢充电把方向学反
                DataCollector.maybeLearnDischargeSign(HudService.this, s);

                List<Sensor> list = sensors;
                if (list != null) sampler.offer(s, list);
            } catch (Exception ignored) {
            }
            bg.postDelayed(this, sampleMs());
        }
    };

    /** 蓝牙电量主动轮询：广播只在变化时来，这里按用户设的间隔兜底刷一次 */
    private final Runnable btPollLoop = new Runnable() {
        @Override
        public void run() {
            if (suspended) return;
            if (bg != null) {
                bg.post(() -> {
                    if (suspended) return;
                    BtBattery.poll(HudService.this);
                });
            }
            int sec = Math.max(15, HudState.btPollSec);
            if (bg != null) bg.postDelayed(this, sec * 1000L);
        }
    };

    /** 蓝牙侧有变化（新设备、电量更新、断开）时调用，可能与主线程不同 */
    private void onBtChanged() {
        if (bg == null) return;
        bg.post(() -> {
            List<SensorSpec> next = SensorTable.ensureBtSensors(HudService.this, table);
            boolean enableNew = false;
            if (next != null) {
                table = next;
                for (SensorSpec sp : table) {
                    if (sp.kind != SensorSpec.Kind.BT_BATT) continue;
                    if (HudState.sensorIds.contains(sp.id)) continue;
                    if (!BtBattery.hasLevel(sp.id)) continue;
                    HudState.sensorIds.add(sp.id);
                    enableNew = true;
                }
            }
            if (next != null || enableNew) {
                if (enableNew) Prefs.setSensors(HudService.this, HudState.sensorIds);
                main.post(() -> {
                    if (view == null) return;
                    sensors = Sensor.resolve(table, HudState.sensorIds);
                    view.setSensors(sensors);
                    sampler.clear();
                });
            }
        });
    }

    private final Runnable renderLoop = new Runnable() {
        @Override
        public void run() {
            if (suspended || !added) return;
            List<Sensor> list = sensors;
            if (list != null) {
                Map<String, Float> avg = sampler.take(list);
                // 采样率与刷新率独立后，刷新可能比采样更密：
                // 这一区间内没采到新值的通道，沿用上次上屏的值 ——
                // 否则整个 map 会缺失这些通道，界面上瞬间全变 --（以前正是这个现象）。
                for (Map.Entry<String, Float> e : lastValues.entrySet()) {
                    if (!avg.containsKey(e.getKey())) avg.put(e.getKey(), e.getValue());
                }
                float cur = avg.containsKey("batt-cur") ? avg.get("batt-cur") : Float.NaN;
                // 值没变就不重绘，减少无效 invalidate
                if (!avg.equals(lastValues) || chargingNow != lastCharging) {
                    lastValues.clear();
                    lastValues.putAll(avg);
                    lastCharging = chargingNow;
                    view.setValues(avg, chargingNow, cur);
                    maybeUpdateNotification();
                }
            }
            long interval = Math.max(200L, HudState.updateMs);
            main.postDelayed(this, interval);
        }
    };

    // ------------------------------------------------------------------ 生命周期
    @Override
    public void onCreate() {
        super.onCreate();

        if (!Settings.canDrawOverlays(this)) {
            // 没有悬浮窗权限也要先把前台服务立起来，否则系统会判超时
            startAsForeground();
            stopSelf();
            return;
        }

        // 先恢复设置：通知该长什么样取决于"接管通知"开关
        Prefs.loadIntoState(this);
        // 高权限 Shell（电脑旁路桥 / Shizuku）——占用率传感器的数据来源
        ShellAccess.init(getApplicationContext());
        startAsForeground();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            stopSelf();
            return;
        }

        // 传感器表：首次启动探测并固化，之后直接读盘
        table = SensorTable.load(this);
        sensors = Sensor.resolve(table, HudState.sensorIds);
        if (sensors.isEmpty()) {
            HudState.sensorIds = new java.util.ArrayList<>(SensorTable.DEFAULT_IDS);
            Prefs.setSensors(this, HudState.sensorIds);
            sensors = Sensor.resolve(table, HudState.sensorIds);
        }

        view = new HudView(this);
        view.setSensors(sensors);
        view.setTheme(HudTheme.byIndex(HudState.themeIndex));
        view.setTextSizeSp(HudState.textSizeSp);
        view.setShowBackground(HudState.showBackground);
        view.setInsets(HudState.insetLeft, HudState.insetRight);

        lp = buildParams();
        try {
            wm.addView(view, lp);
            added = true;
        } catch (Exception e) {
            stopSelf();
            return;
        }

        bgThread = new HandlerThread("hud-sampler");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        // 蓝牙电量：先注册监听（广播可能随时来），再补一遍表
        BtBattery.init(this, this::onBtChanged);
        onBtChanged();

        screenRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    suspend();
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    resume();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f);

        HudState.running = true;

        // 参数变化由调用方直接触发，不再靠定时轮询 —— 轮询会让 CPU 无法休眠
        HudState.onBump = () -> main.post(this::applySettings);
        HudState.onPos = () -> main.post(this::applyPosition);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isInteractive()) {
            resume();
        } else {
            suspend();
        }
    }

    // ------------------------------------------------------------------ 暂停 / 恢复

    private void suspend() {
        suspended = true;
        ShellAccess.setPaused(true);
        main.removeCallbacks(renderLoop);
        if (bg != null) {
            bg.removeCallbacks(sampleLoop);
            bg.removeCallbacks(btPollLoop);
        }
        sampler.clear();
    }

    private void resume() {
        ShellAccess.setPaused(false);
        // 启动/亮屏时把全部外观参数应用一遍。
        // 以前只有参数变化才走 applySettings，导致新建的 View 一直用构造默认值。
        applySettings();
        if (suspended) {
            sampler.clear();
        }
        suspended = false;

        if (bg != null) {
            lastSampleMs = sampleMs();
            bg.removeCallbacks(sampleLoop);
            bg.post(sampleLoop);
            bg.removeCallbacks(btPollLoop);
            bg.post(btPollLoop);
        }
        main.removeCallbacks(renderLoop);
        main.post(renderLoop);
    }

    // ------------------------------------------------------------------ 窗口

    /** 窗口级不透明度；太低会看不见，留一点下限 */
    private static float windowAlpha() {
        int pct = Math.max(5, Math.min(100, HudState.windowAlphaPct));
        return pct / 100f;
    }

    private WindowManager.LayoutParams buildParams() {
        int width = barWidth();

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.max(1, width),
                dp(HudState.barHeightDp),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        p.gravity = Gravity.TOP | Gravity.START;
        p.x = HudState.x;
        p.y = HudState.y;
        p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        p.alpha = windowAlpha();
        p.setTitle("DeviceHud");
        return p;
    }

    /** 只挪坐标：不重建传感器列表，拖动时每帧走这条路 */
    private void applyPosition() {
        if (!added || view == null || lp == null) return;
        lp.x = HudState.x;
        lp.y = HudState.y;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }

    /**
     * 横屏撑满整屏；竖屏按比例缩短（默认 72%），
     * 留白仍是绝对像素值，不随方向缩放。
     */
    private int barWidth() {
        // 必须用真实屏幕宽度：getResources().getDisplayMetrics() 在分屏/小窗下
        // 给的是窗口尺寸，拿它当条宽会让条被裁短
        int screenW = Screen.width(this);
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        if (!portrait) return screenW;
        int pct = Prefs.portraitPct(this);
        return Math.max(dp(120), Math.round(screenW * pct / 100f));
    }

    private void applySettings() {
        if (!added || view == null) return;

        if (HudState.hidden) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int margin = dp(HudState.sideMarginDp);

        lp.alpha = windowAlpha();
        lp.width = Math.max(1, barWidth());
        lp.height = dp(HudState.barHeightDp);
        lp.x = HudState.x;
        lp.y = HudState.y;

        table = SensorTable.load(this);
        List<Sensor> list = Sensor.resolve(table, HudState.sensorIds);
        sensors = list;
        view.setSensors(list);
        view.setTextSizeSp(HudState.textSizeSp);
        view.setShowBackground(HudState.showBackground);
        view.setInsets(HudState.insetLeft, HudState.insetRight);
        view.setTheme(HudTheme.byIndex(HudState.themeIndex));
        view.setGapDp(HudState.gapDp);
        view.setColors(HudState.bgColor, HudState.borderColor);
        view.setBorderVisible(HudState.showBorder);
        view.setRadiusDp(HudState.radiusDp);
        view.setBackgroundMode(HudState.bgAnchor, HudState.bgScale);
        view.setImageAlpha(HudState.imageAlphaPct);
        view.setBackgroundImage(BgImage.load(this, HudState.bgImageUri,
                lp.width, lp.height, HudState.insetLeft, HudState.insetRight,
                HudState.bgAnchor, HudState.bgScale));

        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }

        // 采样档位变了就重启采样循环，否则要等上一次 postDelayed 到点才生效
        if (bg != null && !suspended && lastSampleMs != sampleMs()) {
            lastSampleMs = sampleMs();
            bg.removeCallbacks(sampleLoop);
            bg.post(sampleLoop);
        }

        // 主题 / 传感器 / 显示开关都可能变，通知第一行得跟着走
        updateNotification();
    }

    // ------------------------------------------------------------------ 前台通知

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            // IMPORTANCE_MIN 的通知在通知栏里是折叠成一条的，自定义内容根本看不见；
            // 接管模式要显示两行，所以用 LOW（无声、不弹横幅，但内容可见）
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "悬浮信息条", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            // 清掉旧通道，避免设置里出现两条同名通知类别
            try {
                nm.deleteNotificationChannel("hud_overlay");
            } catch (Exception ignored) {
            }
        }

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /**
     * 通知内容。
     *
     * 接管模式：第一行是前 3 个传感器的实时数据（与悬浮条同一套着色规则，
     * 只是把间距从 5dp 压成一个空格），第二行是三个按钮。
     * 按钮不用系统 action 而画在 RemoteViews 里，是因为折叠态下系统 action 不可见。
     */
    private Notification buildNotification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (HudState.notifTakeover) {
            RemoteViews rv = buildContent();
            b.setStyle(new Notification.DecoratedCustomViewStyle())
                    .setCustomContentView(rv)
                    .setCustomBigContentView(rv)
                    .setContentTitle("DeviceHud");
        } else {
            b.setContentTitle("DeviceHud 运行中")
                    .setContentText("悬浮信息条已显示");
        }
        return b.build();
    }

    private RemoteViews buildContent() {
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_hud);
        rv.setTextViewText(R.id.notif_line, buildLine());
        rv.setTextViewText(R.id.notif_btn_toggle,
                HudState.hidden ? "开启悬浮窗" : "关闭悬浮窗");
        rv.setTextViewText(R.id.notif_btn_mute,
                HudState.muteChargeAlert ? "警报已消除" : "警报消除");

        rv.setOnClickPendingIntent(R.id.notif_btn_toggle,
                serviceAction(ACTION_TOGGLE_HUD, 11));
        rv.setOnClickPendingIntent(R.id.notif_btn_open, openAppIntent());
        rv.setOnClickPendingIntent(R.id.notif_btn_mute,
                serviceAction(ACTION_MUTE_CHARGE, 12));
        return rv;
    }

    /** 第一行：前 3 项数据，标签用次文字色，数值按与悬浮条相同的规则着色 */
    private CharSequence buildLine() {
        HudTheme th = HudTheme.byIndex(HudState.themeIndex);
        Float cur = lastValues.get("batt-cur");
        float avgCur = cur == null ? Float.NaN : cur;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<Sensor> list = sensors;
        if (list == null || list.isEmpty()) {
            sb.append("无启用传感器");
            sb.setSpan(new ForegroundColorSpan(th.dim), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return new SpannableString(sb);
        }

        int shown = 0;
        for (Sensor s : list) {
            if (shown >= NOTIF_SENSORS) break;
            if (shown > 0) sb.append("  ");

            SensorSpec sp = s.spec;
            Float boxed = lastValues.get(s.id());
            float v = (boxed == null) ? Float.NaN : boxed;
            String label = s.label();
            String val = s.format(v) + s.unit();

            if (!label.isEmpty()) {
                int st = sb.length();
                sb.append(label).append(' ');
                sb.setSpan(new ForegroundColorSpan(HudView.resolveLabelColor(sp, th)),
                        st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int vs = sb.length();
            sb.append(val);
            sb.setSpan(new ForegroundColorSpan(
                            HudView.resolveValueColor(sp, s, v, th, chargingNow, avgCur)),
                    vs, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown++;
        }
        // SpannableString 才带 Parcelable span，跨进程到 SystemUI 才保得住颜色
        return new SpannableString(sb);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Prefs.hideRecents(this)) {
            i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }
        return PendingIntent.getActivity(this, 10, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent serviceAction(String action, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, HudService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 立即重建通知（设置变化、按钮点击后调用） */
    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        lastNotifAt = SystemClock.elapsedRealtime();
        try {
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    /** 数据变化时的节流刷新：通知栏没必要跟着 500ms 抖 */
    private void maybeUpdateNotification() {
        if (!HudState.notifTakeover) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifAt < NOTIF_MS) return;
        updateNotification();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 横竖屏切换由系统事件驱动，不轮询 */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (added) main.post(this::applySettings);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = (intent == null) ? null : intent.getAction();
        if (a != null) {
            switch (a) {
                case ACTION_TOGGLE_HUD:
                    // 只隐藏悬浮条，服务继续跑 —— 否则通知跟着没了，
                    // "开启悬浮窗"这个按钮就永远点不到
                    HudState.hidden = !HudState.hidden;
                    Prefs.setVisible(this, !HudState.hidden);
                    Prefs.saveFromState(this);
                    HudState.bump();
                    break;
                case ACTION_MUTE_CHARGE:
                    HudState.muteChargeAlert = !HudState.muteChargeAlert;
                    HudState.bump();
                    break;
                default:
                    break;
            }
            main.post(this::updateNotification);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        suspended = true;
        main.removeCallbacksAndMessages(null);
        if (bg != null) {
            bg.removeCallbacksAndMessages(null);
            bgThread.quitSafely();
        }
        if (screenRx != null) {
            try {
                unregisterReceiver(screenRx);
            } catch (Exception ignored) {
            }
        }
        BtBattery.release();
        HudState.running = false;
        HudState.onBump = null;
        HudState.onPos = null;

        if (added && wm != null && view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        added = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
