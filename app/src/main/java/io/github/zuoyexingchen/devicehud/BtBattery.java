package io.github.zuoyexingchen.devicehud;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已连接蓝牙设备的电量。
 *
 * <p>两条来源并用，谁先给出结果就先用谁：
 * <ol>
 *   <li><b>系统广播</b> {@code android.bluetooth.device.action.BATTERY_LEVEL_CHANGED}
 *       —— 蓝牙栈收到 HFP/LE 电量上报时发出，附带设备与百分比</li>
 *   <li><b>主动轮询</b>：遍历已配对设备，反射 {@code BluetoothDevice.getBatteryLevel()}
 *       取系统缓存值（间隔由用户在界面上调，默认 60s）</li>
 * </ol>
 *
 * <p>为什么要轮询：广播只在电量**发生变化**时发，而系统可能在我们启动前就已经
 * 收到过电量；只看广播的话要等下一次变化才有数。轮询读的是系统缓存，
 * 不会真的去连设备，所以很便宜。
 *
 * <p>权限：Android 12+ 需要 {@code BLUETOOTH_CONNECT}（用户看到的名字是"附近的设备"）。
 * 没有权限时一切静默返回，不影响其它传感器。
 */
public final class BtBattery {

    /** 标准电量广播（@SystemApi 常量，这里直接用字符串，公开 API 里没有） */
    private static final String ACTION_LEVEL =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL";

    private static final String TAG = "BtBattery";

    public static final class Dev {
        public final String address;

        public Dev(String address) {
            this.address = address;
        }

        public String name = "";
        /** -1 = 未知 */
        public int level = -1;
        public boolean connected;
        public long updatedAt;
    }

    private static final Map<String, Dev> sDevices = new LinkedHashMap<>();
    private static BroadcastReceiver sRx;
    private static java.lang.reflect.Method sGetBattery;
    private static java.lang.reflect.Method sIsConnected;
    private static boolean sMethodProbed;
    private static Runnable sOnChange;
    private static Context sApp;

    private BtBattery() {
    }

    // ------------------------------------------------------------------ 生命周期

    /** 注册监听。重复调用无副作用。{@code onChange} 在设备集合变化时回调（可空）。 */
    public static synchronized void init(Context c, Runnable onChange) {
        sOnChange = onChange;
        sApp = c.getApplicationContext();
        if (sRx != null) return;

        sRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                String action = i.getAction();
                if (action == null) return;

                if (ACTION_LEVEL.equals(action)) {
                    BluetoothDevice d = extraDevice(i);
                    int lv = i.getIntExtra(EXTRA_LEVEL, -1);
                    if (d != null && lv >= 0) {
                        setLevel(d, lv);
                        notifyChange();
                    }
                    return;
                }
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (st == BluetoothAdapter.STATE_OFF) {
                        synchronized (BtBattery.class) {
                            for (Dev dv : sDevices.values()) {
                                dv.connected = false;
                                dv.level = -1;
                            }
                        }
                        notifyChange();
                    } else if (st == BluetoothAdapter.STATE_ON) {
                        poll(ctx);
                    }
                    return;
                }
                // ACL 连上/断开：连接状态变了，顺便把电量也刷一遍
                poll(ctx);
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_LEVEL);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            c.getApplicationContext().registerReceiver(sRx, f);
        } catch (Exception e) {
            sRx = null;
        }
    }

    public static synchronized void release() {
        if (sRx != null) {
            try {
                if (sApp != null) sApp.unregisterReceiver(sRx);
            } catch (Exception ignored) {
            }
            sRx = null;
        }
        sOnChange = null;
    }

    private static void notifyChange() {
        Runnable r = sOnChange;
        if (r != null) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 权限 / 蓝牙状态

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;   // 老系统只要声明过
        try {
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean enabled(Context c) {
        BluetoothAdapter a = adapter(c);
        try {
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static BluetoothAdapter adapter(Context c) {
        try {
            BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            return m == null ? null : m.getAdapter();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 轮询

    /** 读一遍已配对设备的电量（需要权限）。可在任意线程调用。 */
    public static void poll(Context c) {
        if (!hasPermission(c) || !enabled(c)) return;
        BluetoothAdapter a = adapter(c);
        if (a == null) return;

        List<BluetoothDevice> bonded;
        try {
            bonded = new ArrayList<>(a.getBondedDevices());
        } catch (Exception e) {
            return;
        }

        boolean changed = false;
        for (BluetoothDevice d : bonded) {
            String addr;
            try {
                addr = d.getAddress();
            } catch (Exception e) {
                continue;
            }
            Dev dv;
            synchronized (BtBattery.class) {
                dv = sDevices.get(addr);
                if (dv == null) {
                    dv = new Dev(addr);
                    sDevices.put(addr, dv);
                    changed = true;
                }
            }
            try {
                String nm = d.getName();
                if (nm != null && !nm.equals(dv.name)) {
                    dv.name = nm;
                    changed = true;
                }
            } catch (Exception ignored) {
            }

            Boolean conn = reflectBool(d);
            if (conn != null && conn != dv.connected) {
                dv.connected = conn;
                changed = true;
            }

            int lv = reflectInt(d);
            if (lv >= 0 && lv != dv.level) {
                dv.level = lv;
                dv.updatedAt = System.currentTimeMillis();
                changed = true;
            }
        }
        if (changed) notifyChange();
    }

    private static BluetoothDevice extraDevice(Intent i) {
        try {
            return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void setLevel(BluetoothDevice d, int lv) {
        String addr;
        String nm = "";
        try {
            addr = d.getAddress();
            nm = d.getName();
        } catch (Exception e) {
            return;
        }
        Dev dv = sDevices.get(addr);
        if (dv == null) {
            dv = new Dev(addr);
            sDevices.put(addr, dv);
        }
        if (nm != null && !nm.isEmpty()) dv.name = nm;
        dv.level = lv;
        dv.updatedAt = System.currentTimeMillis();
        dv.connected = true;
    }

    // ------------------------------------------------------------------ 反射取系统缓存

    private static void probeMethods() {
        if (sMethodProbed) return;
        sMethodProbed = true;
        try {
            sGetBattery = BluetoothDevice.class.getMethod("getBatteryLevel");
        } catch (Throwable t) {
            sGetBattery = null;
        }
        try {
            sIsConnected = BluetoothDevice.class.getMethod("isConnected");
        } catch (Throwable t) {
            sIsConnected = null;
        }
        android.util.Log.i(TAG, "getBatteryLevel=" + (sGetBattery != null)
                + " isConnected=" + (sIsConnected != null));
    }

    private static int reflectInt(BluetoothDevice d) {
        probeMethods();
        if (sGetBattery == null) return -1;
        try {
            Object v = sGetBattery.invoke(d);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Boolean reflectBool(BluetoothDevice d) {
        probeMethods();
        if (sIsConnected == null) return null;
        try {
            Object v = sIsConnected.invoke(d);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 对外数据

    /** 传感器 id：一个设备一个 id，用地址去掉冒号，稳、可序列化 */
    public static String idOf(String address) {
        return "bt-" + (address == null ? "" : address.replace(":", ""));
    }

    public static String addressOf(String id) {
        if (id == null || !id.startsWith("bt-")) return "";
        String raw = id.substring(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 2) {
            if (sb.length() > 0) sb.append(':');
            sb.append(raw, i, Math.min(i + 2, raw.length()));
        }
        return sb.toString();
    }

    /** 传感器 id -> 电量百分比（只给已知的） */
    public static synchronized Map<String, Float> levels() {
        Map<String, Float> out = new LinkedHashMap<>();
        for (Dev d : sDevices.values()) {
            if (d.level >= 0) out.put(idOf(d.address), (float) d.level);
        }
        return out;
    }

    /** 这个设备有没有读到过电量（没读到就别自动勾选，否则条上永远是 --） */
    public static synchronized boolean hasLevel(String sensorId) {
        Dev d = sDevices.get(addressOf(sensorId));
        return d != null && d.level >= 0;
    }

    public static synchronized boolean isConnected(String sensorId) {
        Dev d = sDevices.get(addressOf(sensorId));
        return d != null && d.connected;
    }

    public static synchronized String nameOf(String sensorId) {
        Dev d = sDevices.get(addressOf(sensorId));
        return d == null ? "" : d.name;
    }

    /** 已连接的设备列表（用于界面提示与建表） */
    public static synchronized List<Dev> devices() {
        return new ArrayList<>(sDevices.values());
    }

    /** 建表用：当前已配对且能报电量的设备（读一遍再返回） */
    public static List<Dev> refresh(Context c) {
        poll(c);
        List<Dev> out = new ArrayList<>();
        for (Dev d : devices()) {
            if (d.level >= 0 || d.connected) out.add(d);
        }
        return out;
    }

    /** 条上的标签：设备名去掉 "Bluetooth " 前缀，按整个单词截到 10 字符内 */
    public static String shortName(String raw) {
        if (raw == null) return "BT";
        String n = raw.trim().replaceFirst("(?i)^bluetooth\\s+", "");
        if (n.isEmpty()) return "BT";
        final int MAX = 12;
        if (n.length() <= MAX) return n;
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("\\s+")) {
            if (sb.length() > 0 && sb.length() + 1 + w.length() > MAX) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.length() == 0 ? n.substring(0, MAX) : sb.toString();
    }

    /** 状态文案，给设置页用 */
    public static String status(Context c) {
        if (!hasPermission(c)) return "未授权（点击申请）";
        if (!enabled(c)) return "蓝牙未开启";
        int n = 0;
        int known = 0;
        for (Dev d : devices()) {
            if (d.connected) n++;
            if (d.level >= 0) known++;
        }
        return "已授权 · 连接 " + n + " 台 · 有电量 " + known + " 台";
    }
}
