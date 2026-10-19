package com.minke.mallmanagement.notification.service;

import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.dao.NotificationDao;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批四第二批（2026-10-19）：低库存告警 {@code NotificationService.alertLowStock}。
 *
 * <h2>【判据为什么是「发没发」而不是返回值】</h2>
 * {@code alertLowStock} 是**旁路**：返回 {@code false} 既可能是「不该发」（≥10、去重命中、
 * 没身份），也可能是「想发但发失败了」—— 光看布尔值分不开。故每个用例都钉
 * {@code notificationDao.insertOne(...)} **有没有被调**，以及落库那一行的
 * {@code EventType / TargetType / TargetId} 三件（去重的键就藏在这三件里）。
 *
 * <p>⚠️ 本类**不**测「真库去重」—— 那需要 {@code notification} 表与 UTC 日期边界，
 * 属于 A3 档；本类只钉住「去重键怎么拼」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockAlertTest {

    @Mock private NotificationDao notificationDao;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationDao, new NotifyMetrics(new SimpleMeterRegistry()));
    }

    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    @Test
    @DisplayName("库存 9：按 (merchant, goods.stock.low, goods_sku, SKU:yyyyMMdd) 发一条")
    void belowThresholdSendsOne() {
        when(notificationDao.existsDelivery(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        boolean sent = service.alertLowStock("M1", "SKU-1", "深空灰 / M", "声谷耳机", 9);

        assertThat(sent).isTrue();
        ArgumentCaptor<String> toId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> targetType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> targetId = ArgumentCaptor.forClass(String.class);
        verify(notificationDao).insertOne(anyString(), toId.capture(), toType.capture(), event.capture(),
                anyString(), anyString(), targetType.capture(), targetId.capture());

        assertThat(toId.getValue()).isEqualTo("M1");
        assertThat(toType.getValue()).isEqualTo("merchant");
        assertThat(event.getValue()).isEqualTo(NotificationEvents.GOODS_STOCK_LOW);
        assertThat(targetType.getValue()).isEqualTo("goods_sku");
        // 去重键：SKU + 当天 —— 同一天再触发会命中 existsDelivery，不再发
        assertThat(targetId.getValue()).isEqualTo("SKU-1:" + today());
    }

    @Test
    @DisplayName("库存 10：阈值边界不告警")
    void atThresholdDoesNotSend() {
        boolean sent = service.alertLowStock("M1", "SKU-1", null, "声谷耳机", 10);

        assertThat(sent).isFalse();
        verify(notificationDao, never()).insertOne(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("库存为 null / 没商家号：不发（读不到剩余 ≠ 0 件）")
    void missingInputsDoNotSend() {
        assertThat(service.alertLowStock("M1", "SKU-1", null, "g", null)).isFalse();
        assertThat(service.alertLowStock("  ", "SKU-1", null, "g", 3)).isFalse();
        verify(notificationDao, never()).insertOne(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("当日已发过：去重命中，不再发")
    void alreadySentTodayDoesNotSendAgain() {
        when(notificationDao.existsDelivery(eq("M1"), eq(NotificationEvents.GOODS_STOCK_LOW),
                eq("goods_sku"), eq("SKU-1:" + today()))).thenReturn(true);

        boolean sent = service.alertLowStock("M1", "SKU-1", null, "声谷耳机", 2);

        assertThat(sent).isFalse();
        verify(notificationDao, never()).insertOne(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("去重查询自己炸了：照发（宁可重复一条，不漏一条）")
    void dedupeFailureStillSends() {
        when(notificationDao.existsDelivery(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        when(notificationDao.insertOne(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        boolean sent = service.alertLowStock("M1", "SKU-1", null, "声谷耳机", 4);

        assertThat(sent).isTrue();
    }

    @Test
    @DisplayName("落库失败：返回 false 且不抛（不能把下单 / 改库存翻成失败）")
    void insertFailureIsSwallowed() {
        when(notificationDao.existsDelivery(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        when(notificationDao.insertOne(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        boolean sent = service.alertLowStock("M1", "SKU-1", null, "声谷耳机", 1);

        assertThat(sent).isFalse();
        verify(notificationDao).insertOne(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
