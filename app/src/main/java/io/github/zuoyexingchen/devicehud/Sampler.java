package io.github.zuoyexingchen.devicehud;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采样累加器。
 *
 * 采样永远按固定节奏跑（500ms），上屏节奏由用户设定。
 * 上屏时取"上一次上屏到现在"这个区间内所有采样点的算术平均 ——
 * 不是只比较两帧的瞬时值，这样低频档位下读数才稳定、不跳。
 *
 * take() 取完即清空，天然形成区间。
 */
public final class Sampler {

    private static final class Acc {
        float sum;
        int count;

        void add(float v) {
            sum += v;
            count++;
        }

        float avg() {
            return count > 0 ? sum / count : Float.NaN;
        }

        void reset() {
            sum = 0f;
            count = 0;
        }
    }

    private final Map<String, Acc> accs = new HashMap<>();

    /**
     * 喂入一次采样，值为 NaN 的通道直接丢弃，不污染均值。
     *
     * @return 本次采样算出的原始值（未经区间平均），给历史记录用
     */
    public Map<String, Float> offer(DataCollector.Snapshot s, List<Sensor> sensors) {
        if (s == null || sensors == null) return java.util.Collections.emptyMap();
        // 走 readAll：插件指标依赖别的传感器，必须两趟算；
        // 顺便把整组值喂给曲线图的历史缓冲 —— 采样点就是图表的原始数据
        Map<String, Float> vals = Sensor.readAll(sensors, s);
        ChartHistory.observe(vals);
        for (Map.Entry<String, Float> e : vals.entrySet()) {
            float v = e.getValue();
            if (Float.isNaN(v) || Float.isInfinite(v)) continue;
            Acc a = accs.get(e.getKey());
            if (a == null) {
                a = new Acc();
                accs.put(e.getKey(), a);
            }
            a.add(v);
        }
        return vals;
    }

    /** 取出区间均值并清空。返回 id -> 平均值 */
    public Map<String, Float> take(List<Sensor> sensors) {
        Map<String, Float> out = new HashMap<>();
        if (sensors == null) return out;
        for (Sensor se : sensors) {
            Acc a = accs.get(se.id());
            if (a == null || a.count == 0) continue;
            out.put(se.id(), a.avg());
            a.reset();
        }
        return out;
    }

    public void clear() {
        accs.clear();
    }
}
