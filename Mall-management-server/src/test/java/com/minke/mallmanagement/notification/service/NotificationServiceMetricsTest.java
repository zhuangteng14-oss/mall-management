package com.minke.mallmanagement.notification.service;

import com.minke.mallmanagement.notification.dao.NotificationDao;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 块 7 · commit 2b —— 通知环的**真观测点**（{@code NotificationService.send}）。
 *
 * <p>{@code send} 是 **fail-soft 原语**（落库失败只记日志、回 {@code false}，不抛）
 * ⇒ 「通知失败率」这个数**只有**本环读得到 —— 这正是它必须被埋点的理由。
 *
 * <p>三件事：① 落库成功 ⇒ {@code sent} +1，{@code key} 是**收件人业务号**（{@code toId}）；
 * ② 落库抛 ⇒ {@code failed} +1 且**返回值仍是 false、不外抛**；③ 两条事件都带
 * {@code toType} / {@code eventType}（收件人类型与通知类型，均为有界取值）。
 */
class NotificationServiceMetricsTest {

    /** ⚠️ 与 {@code NotifyMetrics.SENDS} 同一个字面量（那边是包私有常量）。 */
    private static final String SENDS = "mall.notify.sends";

    private SimpleMeterRegistry registry;
    private NotifyMetrics metrics;
    private NotificationDao dao;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new NotifyMetrics(registry);
        dao = mock(NotificationDao.class);
        service = new NotificationService(dao, metrics);
    }

    @Test
    @DisplayName("投递成功 ⇒ sent +1，key 记收件人业务号")
    void sentOnSuccess() {
        boolean ok = service.send("B-1", "c-9", "客户", "ORDER_PAID", "标题", "正文", "order", "O-1");

        assertThat(ok).isTrue();
        assertThat(registry.counter(SENDS, "result", "sent").count()).isEqualTo(1.0d);
        assertThat(metrics.recentEvents()).singleElement().satisfies(e -> {
            assertThat(e.result()).isEqualTo("sent");
            assertThat(e.toType()).isEqualTo("客户");
            assertThat(e.eventType()).isEqualTo("ORDER_PAID");
            assertThat(e.key()).isEqualTo("c-9");
            assertThat(e.reason()).isNull();
        });
    }

    @Test
    @DisplayName("投递失败 ⇒ failed +1、仍回 false 且不外抛（fail-soft 原样保留）")
    void failedOnExceptionAndReturnsFalse() {
        doThrow(new IllegalStateException("db down"))
                .when(dao).insertOne(any(), any(), any(), any(), any(), any(), any(), any());

        assertThatCode(() -> service.send("B-1", "c-9", "客户", "ORDER_PAID", "标题", "正文", "order", "O-1"))
                .as("调用方的业务动作已经成功了，通知不能把它翻成失败")
                .doesNotThrowAnyException();

        assertThat(service.send("B-1", "c-9", "客户", "ORDER_PAID", "标题", "正文", "order", "O-1")).isFalse();
        assertThat(registry.counter(SENDS, "result", "failed").count()).isEqualTo(2.0d);
        assertThat(registry.counter(SENDS, "result", "sent").count()).isZero();
    }
}
