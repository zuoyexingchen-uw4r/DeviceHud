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
        /** 最后一次采到的值：离散量（比如蓝牙电量）要用它，不能用均值 */
        float last;
        /** 本区间内读到无效值（NaN）的采样点数 */
        int nanCount;

        void add(float v) {
            sum += v;
            count++;
            last = v;
        }

        float avg() {
            return count > 0 ? sum / count : Float.NaN;
        }

        void reset() {
            sum = 0f;
            count = 0;
            nanCount = 0;
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
            Acc a = accs.get(e.getKey());
            if (a == null) {
                a = new Acc();
                accs.put(e.getKey(), a);
            }
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                // 记一笔"这一帧这个通道没有效值"。光跳过不记的话，take() 里
                // 这一项就会缺席，上层会拿上次上屏的值来补 —— 于是蓝牙设备
                // 已经关机、读数是未知，条上还永远挂着最后一次的百分比。
                a.nanCount++;
                continue;
            }
            a.add(v);
        }
        return vals;
    }

    /** 取出区间值并清空。连续量取均值，离散量取最后一次 */
    public Map<String, Float> take(List<Sensor> sensors) {
        Map<String, Float> out = new HashMap<>();
        if (sensors == null) return out;
        for (Sensor se : sensors) {
            Acc a = accs.get(se.id());
            if (a == null) continue;
            if (a.count == 0) {
                // 整个区间一次有效值都没有：显式给出 NaN，
                // 让上层显示 "--" 而不是沿用上一次的旧值
                if (a.nanCount > 0) {
                    out.put(se.id(), Float.NaN);
                    a.reset();
                }
                continue;
            }
            out.put(se.id(), se.isDiscrete() ? a.last : a.avg());
            a.reset();
        }
        return out;
    }

    public void clear() {
        accs.clear();
    }
}
