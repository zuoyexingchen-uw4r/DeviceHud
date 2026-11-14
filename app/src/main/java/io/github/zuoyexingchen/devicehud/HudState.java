package io.github.zuoyexingchen.devicehud;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面与悬浮窗之间的共享状态（同进程内静态传递）。
 *
 * 位置类字段用 revision 做变更信号：摇杆每动一次只写一次 int，
 * Service 侧比较 revision 决定是否重设窗口布局，比走广播轻得多。
 */
public final class HudState {

    public static volatile boolean running = false;

    /** 悬浮条左上角坐标（px，允许负值） */
    public static volatile int x = 0;
    public static volatile int y = 0;

    public static volatile int barHeightDp = 32;
    public static volatile int textSizeSp = 12;
    public static volatile int sideMarginDp = 6;

    public static volatile boolean hidden = false;
    public static volatile boolean showBackground = true;
    public static volatile int themeIndex = 0;

    /** BAT 充放电着色：开启后电源项忽略主题/单项色，按充电状态着色 */
    public static volatile boolean batColor = true;
    /** 充电缓慢告警：充电中但平均电流小于阈值时标红 */
    public static volatile boolean batSlowWarn = true;
    /** 慢充判定阈值（mA） */
    public static volatile int slowChargeMa = 500;
    /** 放电时电流显示为负值（部分机型电流计符号相反，这里统一口径） */
    public static volatile boolean dischargeNegative = true;
    /**
     * 是否已经学到本机的电流符号约定。
     *
     * <p>没学到之前一律按"放电 = 负值"显示，并在界面挂红色提示 ——
     * 与其显示一个可能是反的符号，不如明确告诉用户还没校准。
     */
    public static volatile boolean dischargeSignLearned = false;

    /** 接管前台服务那条"悬浮窗已开启"通知，换成自定义布局 */
    public static volatile boolean notifTakeover = true;
    /**
     * 本次充电内静音充电告警。
     * 只在充电期间有效，拔电后由服务自动清零 —— 下次插电仍会正常告警。
     */
    public static volatile boolean muteChargeAlert = false;

    /** 启用的传感器 id，顺序即渲染顺序 */
    public static volatile List<String> sensorIds = new ArrayList<>(SensorTable.DEFAULT_IDS);

    /**
     * 上屏间隔（ms）—— 刷新率。
     *
     * <p>和 {@link #sampleMs} 完全独立，两者不互相约束：
     * 采样快于刷新时，上屏值是区间均值；采样慢于刷新时，读数值保持不变。
     */
    public static volatile long updateMs = 500L;

    /** 采样间隔（ms）—— 采样率。决定多久读一次原始数据 */
    public static volatile long sampleMs = 500L;

    /** 传感器之间的间距（dp）。放不下时会自动继续收窄到 0，而不是直接丢项 */
    public static volatile int gapDp = 10;

    /**
     * 条上最多显示几项。
     *
     * <p>超过这个数就**直接丢掉后面的**，不再压缩间距 ——
     * 更密的字挤在一起谁也看不清，不如明确截断。
     */
    public static volatile int maxItems = 9;

    /** 蓝牙电量主动轮询间隔（秒）。广播只在变化时来，靠它兜底 */
    public static volatile int btPollSec = 60;

    /** 整个悬浮窗的不透明度（%）：窗口级 alpha，文字背景一起变 */
    public static volatile int windowAlphaPct = 80;
    /** 背景图自身的不透明度（%）：只影响图片对象，不动文字 */
    public static volatile int imageAlphaPct = 100;

    /** 背景图 uri（正常情况下指向应用私有副本 files/bar_bg.img；空 = 不用图） */
    public static volatile String bgImageUri = "";
    /** 背景图横向对齐方式，见 {@link BgImage#ANCHOR_NAMES} */
    public static volatile int bgAnchor = BgImage.ANCHOR_CENTER;
    /** 背景图缩放模式，见 {@link BgImage#SCALE_NAMES}；默认等比缩放 */
    public static volatile int bgScale = BgImage.SCALE_FIT;

    /** 自定义背景色（ARGB，0 = 跟随主题） */
    public static volatile int bgColor = 0;
    /** 自定义边框色（ARGB，0 = 跟随主题） */
    public static volatile int borderColor = 0;
    /** 是否绘制边框；关掉即无边框模式 */
    public static volatile boolean showBorder = true;
    /** 圆角弧半径（dp），0 即直角 */
    public static volatile int radiusDp = 12;

    /** 左右两侧留白（px）：该区域不排布文字，但背景仍然覆盖 */
    public static volatile int insetLeft = 0;
    public static volatile int insetRight = 0;

    /** 任何影响窗口外观的参数变化时自增 */
    public static volatile int revision = 1;

    /** 服务注册的即时回调：参数一变立刻重设布局，省掉定时轮询 */
    public static volatile Runnable onBump;
    /** 仅位置变化的轻量回调，拖动时每帧调用 */
    public static volatile Runnable onPos;

    public static void bump() {
        revision++;
        fire(onBump);
    }

    /** 只动坐标时用它，避免每帧重建传感器列表 */
    public static void bumpPos() {
        fire(onPos);
    }

    private static void fire(Runnable r) {
        if (r == null) return;
        try {
            r.run();
        } catch (Exception ignored) {
        }
    }

    /** 快速判断某传感器是否启用 */
    public static boolean enabled(String id) {
        return sensorIds != null && sensorIds.contains(id);
    }

    private HudState() {
    }
}
