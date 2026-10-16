package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.observe.metrics.NotifyMetrics.NotifyEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知埋点（{@link NotifyMetrics}）的语义实测 —— 发没发出去分不分得清、两条通道是不是都发。
 *
 * <p>同一套路：不起 Spring、不起 MySQL，SimpleMeterRegistry 每个测试方法一份。
 * ⚠️ 本类写的是**出口**的语义；它今天**没有生产写入点**（写入点 = 下一笔的攻击引擎），
 * 见 {@link NotifyMetrics} 类注释「写入点」。
 */
class NotifyMetricsTest {

    private static final String TO_TYPE = "1";
    private static final String EVENT_TYPE = "order.shipped";
    private static final String KEY = "U0001";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotifyMetrics metrics = new NotifyMetrics(registry);

    @Test
    @DisplayName("sent / failed 两路各自计数；failed 带原因、sent 不带")
    void bothOutcomesAreRecorded() {
        metrics.sent(TO_TYPE, EVENT_TYPE, KEY, 1_500L);
        metrics.failed(TO_TYPE, EVENT_TYPE, KEY, "收件人不存在", 2_000_000L);

        assertThat(count("sent")).isEqualTo(1.0);
        assertThat(count("failed")).isEqualTo(1.0);

        List<NotifyEvent> events = metrics.recentEvents();
        assertThat(events).extracting(NotifyEvent::result).containsExactly("sent", "failed");
        assertThat(events).extracting(NotifyEvent::toType).containsExactly(TO_TYPE, TO_TYPE);
        assertThat(events).extracting(NotifyEvent::eventType).containsExactly(EVENT_TYPE, EVENT_TYPE);
        assertThat(events).extracting(NotifyEvent::key).containsExactly(KEY, KEY);
        assertThat(events.get(0).reason()).isNull();
        assertThat(events.get(1).reason()).isEqualTo("收件人不存在");
        // 块 7 · 阶段 3a.3 新增：发送耗时（纳秒入参 → 微秒落进事件）与发起者身份
        assertThat(events).extracting(NotifyEvent::durationUs)
                .as("纳秒→微秒：1500ns→1µs、2000000ns→2000µs").containsExactly(1L, 2_000L);
        assertThat(events.get(0).actorId()).as("无 MDC ⇒ actorId 为 null").isNull();
        assertThat(events.get(0).actorRole()).as("无 MDC ⇒ actorRole 为 null").isNull();
    }

    @Test
    @DisplayName("基数铁律：收件人业务号不进 tag —— 两个不同收件人共用一个时间序列")
    void recipientKeyNeverBecomesATag() {
        metrics.sent(TO_TYPE, EVENT_TYPE, "U0001", 1_500L);
        metrics.sent(TO_TYPE, EVENT_TYPE, "U0002", 1_500L);

        assertThat(count("sent")).as("两个不同的收件人落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(NotifyMetrics.SENDS).counters())
                .as("该指标下的时间序列数 = 1（若 key 进了 tag 就会随用户数无界增长）").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(NotifyEvent::key)
                .as("但事件流里必须能看出发给了谁").containsExactly("U0001", "U0002");
    }

    @Test
    @DisplayName("事件环：写满即覆盖最旧、seq 单调；容量有界（2048，块 7 · commit 10 裁决 2）")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        int extra = 24;
        for (int i = 0; i < capacity + extra; i++) {
            metrics.sent(TO_TYPE, EVENT_TYPE, KEY, 1_500L);
        }

        List<NotifyEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(capacity);
        assertThat(events.get(0).seq()).isEqualTo(extra);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(count("sent")).isEqualTo(capacity + extra);
    }

    /** 计数器的当前值；没注册过该 (name, result) 组合时按 0。 */
    private double count(String result) {
        var counter = registry.find(NotifyMetrics.SENDS).tags("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
