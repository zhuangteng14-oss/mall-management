package com.minke.mallmanagement.observe.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「一拍只算一次」的小闸门 —— 块 7 三个观测流的产帧服务共用（{@link MetricsSnapshotService} /
 * {@link OrderStreamService} / {@link EventStreamService}）。
 *
 * <h2>【它挡的是什么】</h2>
 * 三条流各自的服务是**单例**，而「算一拍」这件事**有副作用**：
 * <ul>
 *   <li>指标流：差分量会把计数器的读数「读走」（{@code lastCounts} 被覆盖）⇒ 第二条流看到全 0；</li>
 *   <li>订单流 / 日志流：事件游标同理（{@code lastEventSeq} / 合并环游标被推着走）。</li>
 * </ul>
 * 于是「页面开两次」「刷新时旧连接还没断」都会让**后到的那条流**显示成「一切都没发生」——
 * 一个看起来像面板坏了的假故障。本闸门把产帧压成**每半拍至多一次真算**：
 * 半拍内的第二次调用直接复用上一份结果（只换 {@code seq} / {@code ts}）。
 *
 * <h2>【代价（如实说）】</h2>
 * 相位差小于半拍的两条流，会各拿到一帧**内容相同**的帧（{@code seq} 不同）。页面按 {@code seq}
 * 幂等处理即可（重复的一拍画点会重一个点，不是错数据）。若哪天要「每个客户端一份完整增量」，
 * 正确的做法是把差分量改成**按连接记录游标**，而不是加大这个窗口。
 *
 * <h2>【测试怎么会想让它失效】</h2>
 * 窗口 = {@code mall.observe.sse.interval-ms} × 一半。节拍配 0（单测与「抓一帧」用）
 * ⇒ 窗口 0 ⇒ **每次调用都真算**（不会出现「第二次调用拿到上一拍」的意外）。
 */
final class FramePacer {

    private final long minGapNanos;
    private Map<String, Object> last;
    private long lastNanos;

    FramePacer(long intervalMs) {
        this.minGapNanos = Math.max(0L, intervalMs) * 500_000L;
    }

    /** 距上次真算不足半拍 ⇒ 可以复用。 */
    boolean reusable(long nowNanos) {
        return last != null && minGapNanos > 0 && nowNanos - lastNanos < minGapNanos;
    }

    /** 复用上一份结果：浅拷贝 + 换 {@code seq} / {@code ts}（内部结构只读，页面不会改它）。 */
    Map<String, Object> reuse(long tick) {
        Map<String, Object> frame = new LinkedHashMap<>(last);
        frame.put("seq", tick);
        frame.put("ts", System.currentTimeMillis());
        return frame;
    }

    /** 记住本拍真算出来的结果。 */
    void remember(Map<String, Object> frame, long nowNanos) {
        this.last = frame;
        this.lastNanos = nowNanos;
    }
}
