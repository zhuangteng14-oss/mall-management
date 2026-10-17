package com.minke.mallmanagement.observe.metrics;

import com.minke.mallmanagement.observe.metrics.SensitiveMetrics.Result;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics.SensitiveEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感词埋点（{@link SensitiveMetrics}）的语义实测 —— 两档处置分不分得清、两条通道是不是都发。
 *
 * <p>同一套路：不起 Spring、不起 MySQL，SimpleMeterRegistry 每个测试方法一份。
 * ⚠️ 本类写的是**出口**的语义；它今天**没有生产写入点**（写入点 = 下一笔的攻击引擎，
 * 块 6 的敏感词服务不在本块的改动范围内），见 {@link SensitiveMetrics} 类注释「写入点」。
 */
class SensitiveMetricsTest {

    private static final String SCENE = "comment";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SensitiveMetrics metrics = new SensitiveMetrics(registry);

    @Test
    @DisplayName("masked / rejected 两档各自计数，命中数与被判词数一并进事件（命中率是它们的派生量）")
    void bothOutcomesAreRecorded() {
        metrics.masked(SCENE, 1, 20, "傻逼", 1_500L);
        metrics.rejected(SCENE, 9, 20, "傻逼,滚", 2_000_000L,
                "敏感词拒收：命中比例 45% ≥ 40%（命中 9/20）");

        assertThat(count(Result.MASKED.tag)).isEqualTo(1.0);
        assertThat(count(Result.REJECTED.tag)).isEqualTo(1.0);

        List<SensitiveEvent> events = metrics.recentEvents();
        assertThat(events).extracting(SensitiveEvent::result).containsExactly("masked", "rejected");
        assertThat(events).extracting(SensitiveEvent::scene).containsExactly(SCENE, SCENE);
        assertThat(events).extracting(SensitiveEvent::hit).containsExactly(1, 9);
        assertThat(events).extracting(SensitiveEvent::total).containsExactly(20, 20);
        // 块 7 · commit 8 新带的一列：**命中的词**（敏感性环的「对象字段补全」第一层）
        assertThat(events).extracting(SensitiveEvent::words).containsExactly("傻逼", "傻逼,滚");
        assertThat(events.get(0).traceId()).as("不在请求线程上 ⇒ traceId 为 null").isNull();
        // 块 7 · 阶段 3a.3 新增：判定耗时（纳秒入参 → 微秒落进事件）与发起者身份
        assertThat(events).extracting(SensitiveEvent::durationUs)
                .as("纳秒→微秒：1500ns→1µs、2000000ns→2000µs").containsExactly(1L, 2_000L);
        assertThat(events.get(0).actorId()).as("无 MDC ⇒ actorId 为 null").isNull();
        assertThat(events.get(0).actorRole()).as("无 MDC ⇒ actorRole 为 null").isNull();
        // 块 7 · 阶段 3a.3 后续新增：拒收档带人话原因（不写命中的词），打码档无原因
        assertThat(events).extracting(SensitiveEvent::reason)
                .containsExactly(null, "敏感词拒收：命中比例 45% ≥ 40%（命中 9/20）");
    }

    @Test
    @DisplayName("⚠️ 干净文本不记进本环 —— 本环回答「拦住了多少」，不是「扫了多少」")
    void cleanTextIsNotRecorded() {
        metrics.masked(SCENE, 1, 20, "傻逼", 1_500L);

        assertThat(registry.find(SensitiveMetrics.CHECKS).counters())
                .as("只有被处置（打码 / 拒收）的才该在这里出现；多出来的那条就是把分母混进了分子")
                .hasSize(1);
        assertThat(count("rejected")).isZero();
    }

    @Test
    @DisplayName("基数铁律：场景不进 tag —— 两个不同场景共用一个时间序列")
    void sceneNeverBecomesATag() {
        metrics.masked("comment", 1, 20, "傻逼", 1_500L);
        metrics.masked("nickname", 1, 20, "滚", 1_500L);

        assertThat(count("masked")).as("两个不同的场景落在同一条计数上").isEqualTo(2.0);
        assertThat(registry.find(SensitiveMetrics.CHECKS).counters())
                .as("该指标下的时间序列数 = 1；scene 的取值表不属本环（块 6 手里），更不该进 tag").hasSize(1);
        assertThat(metrics.recentEvents()).extracting(SensitiveEvent::scene)
                .containsExactly("comment", "nickname");
    }

    @Test
    @DisplayName("事件环：写满即覆盖最旧、seq 单调；容量有界（2048，块 7 · commit 10 裁决 2）")
    void eventRingOverwritesOldestAndKeepsSequenceMonotonic() {
        int capacity = 2048;
        int extra = 16;
        for (int i = 0; i < capacity + extra; i++) {
            metrics.rejected(SCENE, 9, 20, "傻逼", 1_500L, "敏感词拒收：命中比例 45% ≥ 40%（命中 9/20）");
        }

        List<SensitiveEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(capacity);
        assertThat(events.get(0).seq()).isEqualTo(extra);
        assertThat(events.get(events.size() - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(count("rejected")).isEqualTo(capacity + extra);
    }

    /** 计数器的当前值；没注册过该 (name, result) 组合时按 0。 */
    private double count(String result) {
        var counter = registry.find(SensitiveMetrics.CHECKS).tags("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
