package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.observe.metrics.IdentityMetrics.IdentityEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份埋点（{@link IdentityMetrics}）的语义实测 —— 放行 / 拒绝两路**都**要记、两条通道是不是都发。
 *
 * <p>同一套路：不起 Spring、不起 MySQL，SimpleMeterRegistry 每个测试方法一份。
 * ⚠️ 本类写的是**出口**的语义；它今天**没有生产写入点**（写入点 = 下一笔的攻击引擎；
 * 块 1 / 块 2 的鉴权与封禁链不在本块的改动范围内），见 {@link IdentityMetrics} 类注释「写入点」。
 */
class IdentityMetricsTest {

    private static final String SUBJECT = "U0001";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IdentityMetrics metrics = new IdentityMetrics(registry);

    @Test
    @DisplayName("passed / rejected **两路都记**（要有分母才看得出拒绝率），rejected 带原因")
    void bothOutcomesAreRecorded() {
        metrics.passed(SUBJECT, "/goods/goodsUp");
        metrics.rejected(SUBJECT, "封禁用户", "/goods/goodsUp");

        assertThat(count("passed")).isEqualTo(1.0);
        assertThat(count("rejected")).isEqualTo(1.0);

        List<IdentityEvent> events = metrics.recentEvents();
        assertThat(events).extracting(IdentityEvent::result).containsExactly("passed", "rejected");
        assertThat(events).extracting(IdentityEvent::subject).containsExactly(SUBJECT, SUBJECT);
        assertThat(events.get(0).reason()).as("放行没有原因").isNull();
        assertThat(events.get(1).reason()).isEqualTo("封禁用户");
        // 块 7 · commit 8 新带的第三列：被访问的请求路径（身份环的「对象字段补全」第一层）
        assertThat(events).extracting(IdentityEvent::path)
                .containsExactly("/goods/goodsUp", "/goods/goodsUp");
        assertThat(events.get(0).traceId()).as("不在请求线程上 ⇒ traceId 为 null").isNull();
    }

    @Test
    @DisplayName("不埋「拒绝率」：它是 rejected / (passed + rejected) 的派生量（规范第 6 条）")
    void rejectionRateIsNotItsOwnCounter() {
        metrics.passed(SUBJECT, "/order/orderReceive");
        metrics.rejected(SUBJECT, "令牌无效", "/order/orderReceive");

        assertThat(registry.find(IdentityMetrics.CHECKS).counters())
                .as("本指标下只有两路计数，没有第三个「率」").hasSize(2);
        assertThat(registry.find("mall.identity.rejection.rate").counters()).isEmpty();
    }

    @Test
    @DisplayName("基数铁律：业务号与原因文本都不进 tag —— 两个不同主体共用一个时间序列")
    void subjectAndReasonNeverBecomeTags() {
        metrics.rejected("U0001", "封禁用户", "/goods/goodsUp");
        metrics.rejected("U0002", "令牌无效", "/order/orderReceive");

        assertThat(count("rejected")).as("两个不同的主体 + 两条不同的原因落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(IdentityMetrics.CHECKS).counters())
                .as("该指标下的时间序列数 = 1（原因词表由各守卫自己拼，本环不假定它封闭）").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(IdentityEvent::reason)
                .containsExactly("封禁用户", "令牌无效");
    }

    @Test
    @DisplayName("事件环：写满即覆盖最旧、seq 单调；容量有界（2048，块 7 · commit 10 裁决 2）")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        int extra = 8;
        for (int i = 0; i < capacity + extra; i++) {
            metrics.passed(SUBJECT, "/goods/goodsUp");
        }

        List<IdentityEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(capacity);
        assertThat(events.get(0).seq()).isEqualTo(extra);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(count("passed")).isEqualTo(capacity + extra);
    }

    /** 计数器的当前值；没注册过该 (name, result) 组合时按 0。 */
    private double count(String result) {
        var counter = registry.find(IdentityMetrics.CHECKS).tags("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
