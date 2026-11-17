package io.github.zuoyexingchen.devicehud;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个传感器最近若干个采样点的环形缓冲，供条上的胶囊图表画 sparkline。
 *
 * <p>为什么单独放一份而不是复用 {@link Sampler}：Sampler 是**区间累加器**，
 * take() 取完就清零，拿不到历史；而图表需要的是"过去 N 个点"。
 * 两者的生命周期和语义都不一样，混在一起会互相踩。
 *
 * <p>写入方是服务后台线程（采样循环），读取方是绘制线程，
 * 所以每个 Ring 自带锁、容器用 ConcurrentHashMap。
 * 容量刻意不做成可配：300 个点已经够画满最宽的槽位，再大只是白占内存。
 */
public final class ChartHistory {

    /** 每通道保留的采样点数。按 500ms 采样算约 150 秒；按 100ms 算 30 秒 */
    public static final int CAPACITY = 300;

    private static final Map<String, Ring> RINGS = new ConcurrentHashMap<>();

    private ChartHistory() {
    }

    private static final class Ring {
        final float[] data = new float[CAPACITY];
        int count;
        /** 下一个写入位置 */
        int head;

        synchronized void push(float v) {
            data[head] = v;
            head = (head + 1) % CAPACITY;
            if (count < CAPACITY) count++;
        }

        /** 按时间顺序拷出，返回实际点数（可能小于 out.length） */
        synchronized int copyTo(float[] out) {
            int n = Math.min(count, out.length);
            int start = (head - n + CAPACITY) % CAPACITY;
            for (int i = 0; i < n; i++) {
                out[i] = data[(start + i) % CAPACITY];
            }
            return n;
        }

        synchronized void clear() {
            count = 0;
            head = 0;
        }
    }

    private static Ring ring(String id) {
        Ring r = RINGS.get(id);
        if (r == null) {
            r = new Ring();
            Ring old = RINGS.putIfAbsent(id, r);
            if (old != null) r = old;
        }
        return r;
    }

    /**
     * 喂入一次采样。
     *
     * <p>NaN 直接丢弃（不写进环），否则图表会被"读不到"的时段打成一条断线；
     * 丢掉之后曲线是连续的，代价是时间轴略微不均 —— 对趋势判断没有影响。
     */
    public static void observe(Map<String, Float> values) {
        if (values == null || values.isEmpty()) return;
        for (Map.Entry<String, Float> e : values.entrySet()) {
            Float v = e.getValue();
            if (v == null || v.isNaN() || v.isInfinite()) continue;
            ring(e.getKey()).push(v);
        }
    }

    /** 某通道的历史点数 */
    public static int size(String id) {
        Ring r = RINGS.get(id);
        return r == null ? 0 : r.count;
    }

    /**
     * 取历史。返回写入 out 的点数；out 不够大时只取最近的那些。
     *
     * @param out 复用的缓冲区，避免每次 onDraw 都分配数组
     */
    public static int copy(String id, float[] out) {
        Ring r = RINGS.get(id);
        if (r == null) return 0;
        return r.copyTo(out);
    }

    public static void clear(String id) {
        Ring r = RINGS.get(id);
        if (r != null) r.clear();
    }

    public static void clearAll() {
        for (Ring r : RINGS.values()) r.clear();
    }

    /** 某通道的当前最小/最大值；无数据时返回 null */
    public static float[] range(String id) {
        Ring r = RINGS.get(id);
        if (r == null || r.count == 0) return null;
        float[] buf = new float[r.count];
        int n = r.copyTo(buf);
        if (n == 0) return null;
        float lo = buf[0];
        float hi = buf[0];
        for (int i = 1; i < n; i++) {
            if (buf[i] < lo) lo = buf[i];
            if (buf[i] > hi) hi = buf[i];
        }
        return new float[]{lo, hi};
    }
}
