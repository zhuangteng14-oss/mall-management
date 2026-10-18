package com.minke.mallmanagement.observe.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.observe.dao.ObservedEventDao;
import com.minke.mallmanagement.observe.dao.ObservedEventDlqDao;
import com.minke.mallmanagement.observe.service.EventBroadcaster;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费者的**死信副本**分支（D3-2 新增）。
 *
 * <h2>【本文件钉的三件事】</h2>
 * <ol>
 *   <li>解析失败的消息：NACK 照旧，**同时**把原始字节写进副本表；</li>
 *   <li>副本落库失败：**绝不影响 NACK**（broker 的 DLQ 仍是最终兜底），也不把异常抛给容器；</li>
 *   <li>台账重试耗尽：三次都失败后逐条 NACK，副本写的是**解析成功的整批事件**，
 *       而不是原始字节（那一条只在「连 JSON 都解不开」时才有）。</li>
 * </ol>
 *
 * <p>⚠️ 本文件不验真实 broker / MySQL —— 那是 dev 环境手验的范围；这里只钉
 * 「消费者在失败路径上到底调了谁、顺序对不对」。
 */
class ObservedEventConsumerDlqTest {

    private final ObservedEventDao eventDao = mock(ObservedEventDao.class);
    private final ObservedEventDlqDao dlqDao = mock(ObservedEventDlqDao.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final EventBroadcaster broadcaster = mock(EventBroadcaster.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObservedEventConsumer consumer = new ObservedEventConsumer(
            eventDao, dlqDao, redis, mapper, broadcaster, registry);

    private final Channel channel = mock(Channel.class);

    @Test
    @DisplayName("非法 JSON ⇒ NACK + 原始字节进副本；stage/异常类都写进去")
    void invalidJsonIsNackedAndPersistedAsRawBody() throws Exception {
        Message message = message("not-json", 11L);

        consumer.onBatch(List.of(message), channel);

        verify(channel).basicNack(11L, false, false);
        verify(dlqDao).insertRaw(eq("not-json"), eq("消息不是合法的事件 JSON"),
                anyString(), anyString());
        verify(dlqDao, never()).insertBatch(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("⚠️ 副本写失败只 WARN：NACK 仍要执行，异常不得漏给容器")
    void aFailedCopyDoesNotStopTheNack() throws Exception {
        Message message = message("not-json", 12L);
        when(dlqDao.insertRaw(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> consumer.onBatch(List.of(message), channel)).doesNotThrowAnyException();

        verify(channel).basicNack(12L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("台账重试 3 次耗尽 ⇒ 逐条 NACK + 整批事件（不是原始字节）进副本")
    void exhaustedRetriesNackEachMessageAndPersistTheBatch() throws Exception {
        ObservedEvent event = new ObservedEvent(
                1L, 1_700_000_000_000L, "stock", "info", "库存扣减", "detail", "SKU-1",
                "sku", "SKU-1", "trace-1", "batch-1",
                "C-42", 2, 171L, 98, null);
        String json = mapper.writeValueAsString(event);
        Message message = message(json, 21L);
        when(eventDao.insertBatch(any())).thenThrow(new IllegalStateException("db down"));

        consumer.onBatch(List.of(message), channel);

        verify(eventDao, times(ObservedEventConsumer.MAX_ATTEMPTS)).insertBatch(any());
        verify(channel).basicNack(21L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(dlqDao).insertBatch(any(), eq("落库连续 " + ObservedEventConsumer.MAX_ATTEMPTS + " 次失败"),
                anyString(), anyString());
        verify(dlqDao, never()).insertRaw(any(), anyString(), anyString(), anyString());
    }

    private static Message message(String body, long deliveryTag) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(deliveryTag);
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(message.getMessageProperties()).thenReturn(props);
        return message;
    }
}
