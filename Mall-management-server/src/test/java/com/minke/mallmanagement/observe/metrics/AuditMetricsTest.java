package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.observe.metrics.AuditMetrics.AuditEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计埋点（{@link AuditMetrics}）的语义实测 —— 成 / 败两路分不分得清、两条通道是不是都发。
 *
 * <p>同一套路：不起 Spring、不起 MySQL，SimpleMeterRegistry 每个测试方法一份。
 * ⚠️ 本类写的是**出口**的语义；它今天**没有生产写入点**（写入点 = 下一笔的攻击引擎），
 * 见 {@link AuditMetrics} 类注释「写入点」—— 那条边界不在本文件的可验范围内。
 */
class AuditMetricsTest {

    private static final String ACTOR = "U0001";
    private static final String ACTION = "admin.order.cancel";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditMetrics metrics = new AuditMetrics(registry);

    @Test
    @DisplayName("written / failed 两路各自计数；failed 带原因、written 不带")
    void bothOutcomesAreRecorded() {
        metrics.written(ACTOR, ACTION, "goods", "M0001:P0001");
        metrics.failed(ACTOR, ACTION, "connection reset", "goods", "M0001:P0001");

        assertThat(count("written")).isEqualTo(1.0);
        assertThat(count("failed")).isEqualTo(1.0);

        List<AuditEvent> events = metrics.recentEvents();
        assertThat(events).extracting(AuditEvent::result).containsExactly("written", "failed");
        assertThat(events).extracting(AuditEvent::actor).containsExactly(ACTOR, ACTOR);
        assertThat(events).extracting(AuditEvent::action).containsExactly(ACTION, ACTION);
        assertThat(events.get(0).reason()).as("成功的那条没有原因").isNull();
        assertThat(events.get(1).reason()).isEqualTo("connection reset");
        // 块 7 · commit 8 新带的两列：被改对象（审计环的「对象字段补全」第一层）
        assertThat(events).extracting(AuditEvent::targetType).containsExactly("goods", "goods");
        assertThat(events).extracting(AuditEvent::targetId).containsExactly("M0001:P0001", "M0001:P0001");
        assertThat(events.get(0).traceId()).as("不在请求线程上 ⇒ traceId 为 null").isNull();
    }

    @Test
    @DisplayName("基数铁律：actor / action 都不进 tag —— 两个不同的动作共用一个时间序列")
    void actorAndActionNeverBecomeTags() {
        metrics.written("U0001", "admin.order.cancel", "order", "O0001");
        metrics.written("U0002", "admin.goods.audit", "goods", "M0002:P0002");

        assertThat(count("written")).as("两条不同的 (actor, action) 落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(AuditMetrics.WRITES).counters())
                .as("该指标下的时间序列数 = 1；若 action 进了 tag，序列数会随 AuditActions 的常量数增长")
                .hasSize(1);
        assertThat(metrics.recentEvents()).extracting(AuditEvent::action)
                .containsExactly("admin.order.cancel", "admin.goods.audit");
    }

    @Test
    @DisplayName("事件环：写满即覆盖最旧、seq 单调；容量有界（2048，块 7 · commit 10 裁决 2）")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        int extra = 40;
        for (int i = 0; i < capacity + extra; i++) {
            metrics.written(ACTOR, ACTION, "goods", "M0001:P0001");
        }

        List<AuditEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(capacity);
        assertThat(events.get(0).seq()).isEqualTo(extra);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(count("written")).isEqualTo(capacity + extra);
    }

    /** 计数器的当前值；没注册过该 (name, result) 组合时按 0。 */
    private double count(String result) {
        var counter = registry.find(AuditMetrics.WRITES).tags("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
