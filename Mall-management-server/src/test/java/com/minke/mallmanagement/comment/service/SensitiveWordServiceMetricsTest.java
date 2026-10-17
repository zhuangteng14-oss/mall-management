package com.minke.mallmanagement.comment.service;

import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.comment.dao.SensitiveWordDao;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 块 7 · commit 2b —— 敏感词环的**真观测点**（{@code SensitiveWordService.filter}）。
 *
 * <p>本环的口径是「只记**真被处置的**」⇒ 三个早退分支（空白 / 词表空 / 零命中）
 * 是**最该被钉死**的地方：它们不记账，才能让「拦住了多少」不被「扫了多少」稀释。
 *
 * <p>三件事：① 低于阈值 ⇒ {@code masked} +1，事件 {@code hit/total} 就是拿去比阈值的命中率；
 * ② 达到阈值 ⇒ {@code rejected} +1（**且 {@code masked} 不动** —— 两路互斥）；
 * ③ 干净文本 ⇒ 两条计数都 0、环里一条都没有。
 */
class SensitiveWordServiceMetricsTest {

    /** ⚠️ 与 {@code SensitiveMetrics.CHECKS} 同一个字面量（那边是包私有常量）。 */
    private static final String CHECKS = "mall.sensitive.checks";

    private SimpleMeterRegistry registry;
    private SensitiveMetrics metrics;
    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SensitiveMetrics(registry);
        SensitiveWordDao wordDao = mock(SensitiveWordDao.class);
        when(wordDao.selectAllWords()).thenReturn(List.of("广告"));
        service = new SensitiveWordService(wordDao, mock(CommentDao.class), metrics);
    }

    @Test
    @DisplayName("命中率低于阈值 ⇒ masked +1（事件里 hit/total = 被比的那个命中率）")
    void maskedWhenBelowThreshold() {
        // 10 字正文、命中 2 字 ⇒ 0.2 < 0.40 ⇒ 打码放行
        Map<String, Object> result = service.filter("广告一二三四五六七八");

        assertThat(result.get("rejected")).isEqualTo(false);
        assertThat(result.get("masked")).isEqualTo("***一二三四五六七八");
        assertThat(registry.counter(CHECKS, "result", "masked").count()).isEqualTo(1.0d);
        assertThat(registry.counter(CHECKS, "result", "rejected").count()).isZero();
        assertThat(metrics.recentEvents()).singleElement().satisfies(e -> {
            assertThat(e.result()).isEqualTo("masked");
            assertThat(e.scene()).isEqualTo("评论");
            assertThat(e.hit()).isEqualTo(2);
            assertThat(e.total()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("命中率达到阈值 ⇒ rejected +1，两路互斥（masked 不动）")
    void rejectedAtOrAboveThreshold() {
        // 10 字正文、命中 10 字 ⇒ 1.0 >= 0.40 ⇒ 拒收
        Map<String, Object> result = service.filter("广告广告广告广告广告");

        assertThat(result.get("rejected")).isEqualTo(true);
        assertThat(registry.counter(CHECKS, "result", "rejected").count()).isEqualTo(1.0d);
        assertThat(registry.counter(CHECKS, "result", "masked").count()).isZero();
        assertThat(metrics.recentEvents()).singleElement()
                .satisfies(e -> assertThat(e.hit()).isEqualTo(e.total()));
    }

    @Test
    @DisplayName("干净文本 ⇒ 一条不记（三处早退都要走这条口径）")
    void cleanTextRecordsNothing() {
        assertThat(service.filter("这是一条正常评论").get("rejected")).isEqualTo(false);
        service.filter("");
        service.filter(null);

        assertThat(metrics.recentEvents()).isEmpty();
        assertThat(registry.find(CHECKS).counters()).isEmpty();
    }
}
