package com.minke.mallmanagement.observe.mq;

import com.minke.mallmanagement.observe.service.AttackSwitchConfig;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code mqDown} 注入的**发布侧**语义（D3-1）—— 只钉两条判据：
 * <ol>
 *   <li>注入开 ⇒ {@link RabbitEventPublisher#publish} 一次 broker 都不碰（不是「发了但失败」）；</li>
 *   <li>该路径**不涉及消费者**：本类根本没有消费者的引用，ACK/NACK 由监听容器负责。</li>
 * </ol>
 * ⚠️ 本类不测「broker 真能不能发」：那是 A3 的并联门禁与现场手验的事；这里只验证拦截点。
 */
class RabbitEventPublisherInjectionTest {

    private static final ObservedEvent EVENT = new ObservedEvent(
            0L, 0L, "cache", "info", "标题", "明细", null, null, null,
            null, null, null, null, null, null, null);

    private static AttackSwitchConfig config(List<String> injections) {
        AttackSwitchConfig config = mock(AttackSwitchConfig.class);
        when(config.injections()).thenReturn(injections);
        return config;
    }

    /**
     * 构造一个「探测线程已经报过 broker 可用」的发布器。
     * ⚠️ {@code connected} 初值 false（真实的探测线程在 {@code @PostConstruct} 里才起）——
     * 不把它置 true，gauge 恒 0，就分不清「注入关着」与「还没探测过」。
     */
    private static RabbitEventPublisher publisher(RabbitTemplate rabbit, List<String> injections,
                                                  SimpleMeterRegistry registry) {
        RabbitEventPublisher publisher = new RabbitEventPublisher(
                rabbit, config(injections), registry, 300_000L);
        ReflectionTestUtils.setField(publisher, "connected", new AtomicBoolean(true));
        return publisher;
    }

    @Test
    @DisplayName("mqDown 开：publish 直接丢弃，RabbitTemplate 一次都没被调用；mq.up gauge = 0")
    void mqDownDropsAtPublishSideWithoutTouchingBroker() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RabbitEventPublisher publisher = publisher(rabbit, List.of("mqDown"), registry);

        publisher.publish(EVENT);
        publisher.publish(EVENT);

        verifyNoInteractions(rabbit);
        Gauge gauge = registry.find("mall.observe.mq.up").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).as("注入中 ⇒ 发布侧不可用").isZero();
        assertThat(publisher.connected()).as("connected() 仍如实回答 broker 通不通（未被注入篡改）")
                .isTrue();
        assertThat(publisher.usable()).as("日志帧读的页面口径：注入中 ⇒ 不可用").isFalse();
    }

    @Test
    @DisplayName("mqDown 关：gauge = 1（broker 初始视为连着），且仍走正常入队路径")
    void mqUpIsOneWhenNotInjected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RabbitEventPublisher publisher = publisher(mock(RabbitTemplate.class), List.of(), registry);

        Gauge gauge = registry.find("mall.observe.mq.up").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
        assertThat(publisher.usable()).as("注入关着且 broker 连着 ⇒ 页面口径可用").isTrue();
    }
}
