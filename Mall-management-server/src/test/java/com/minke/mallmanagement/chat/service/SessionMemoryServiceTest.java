package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionMemoryService} 的写入形状与读取判据（增量：Redis 短期记忆）。
 *
 * <p>不连真 Redis：{@code StringRedisTemplate} 与 {@code ListOperations} 都用替身，
 * 验的是「写两条 / 裁到 10 条 / TTL 30 分钟 / 只取业务意图」这几条可观察契约。
 */
class SessionMemoryServiceTest {

    private static final String KEY = "chat:session:c1";

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ListOperations<String, String> ops;
    private SessionMemoryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(ops);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        service = new SessionMemoryService(provider, mapper);
    }

    private String json(SessionMemoryService.Entry entry) throws Exception {
        return mapper.writeValueAsString(entry);
    }

    @Test
    @DisplayName("remember：写 user + assistant 两条、裁到最近 10 条、TTL 30 分钟")
    void rememberWritesPairAndTtl() throws Exception {
        service.remember("c1", "查我的订单", ChatIntent.ORDER_QUERY, "r2vfwK6z3E", "你有 3 笔订单");

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(ops, times(2)).rightPush(eq(KEY), values.capture());
        assertThat(values.getAllValues().get(0)).contains("\"role\":\"user\"");
        assertThat(values.getAllValues().get(1))
                .contains("\"role\":\"assistant\"", "\"intent\":\"ORDER_QUERY\"", "r2vfwK6z3E");
        verify(ops).trim(KEY, -10, -1);
        verify(redis).expire(KEY, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("lastContext：从新到旧取最近一条业务意图")
    void lastContextTakesLatestBusinessIntent() throws Exception {
        when(ops.range(KEY, -10, -1)).thenReturn(List.of(
                json(new SessionMemoryService.Entry("user", "查订单", null, null, 1)),
                json(new SessionMemoryService.Entry("assistant", "订单…", "ORDER_QUERY", "r2vfwK6z3E", 2)),
                json(new SessionMemoryService.Entry("user", "那退款呢", null, null, 3)),
                json(new SessionMemoryService.Entry("assistant", "退款…", "REFUND_QUERY", null, 4))));

        SessionMemoryService.Context context = service.lastContext("c1");

        assertThat(context).isNotNull();
        assertThat(context.intent()).isEqualTo(ChatIntent.REFUND_QUERY);
        assertThat(context.orderId()).isNull();
    }

    @Test
    @DisplayName("lastContext：跳过 FAQ / 问候 / 兜底，继续往旧找业务意图")
    void lastContextSkipsSmallTalk() throws Exception {
        when(ops.range(KEY, -10, -1)).thenReturn(List.of(
                json(new SessionMemoryService.Entry("assistant", "订单…", "ORDER_QUERY", "r2vfwK6z3E", 1)),
                json(new SessionMemoryService.Entry("assistant", "运费…", "FAQ", null, 2)),
                json(new SessionMemoryService.Entry("assistant", "你好…", "GREETING", null, 3)),
                json(new SessionMemoryService.Entry("assistant", "听不懂…", "UNKNOWN", null, 4))));

        SessionMemoryService.Context context = service.lastContext("c1");

        assertThat(context).isNotNull();
        assertThat(context.intent()).isEqualTo(ChatIntent.ORDER_QUERY);
        assertThat(context.orderId()).isEqualTo("r2vfwK6z3E");
    }

    @Test
    @DisplayName("未登录：一个 Redis 命令都不发")
    void anonymousDoesNotWrite() {
        service.remember(null, "查我的订单", ChatIntent.ORDER_QUERY, null, "…");

        verify(ops, never()).rightPush(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 故障：读写都 fail-soft，不把异常抛给客服主链路")
    void redisFailureIsSwallowed() {
        when(ops.rightPush(anyString(), anyString())).thenThrow(new RuntimeException("down"));
        when(ops.range(KEY, -10, -1)).thenThrow(new RuntimeException("down"));

        assertThatCode(() -> service.remember("c1", "查订单", ChatIntent.ORDER_QUERY, null, "…"))
                .doesNotThrowAnyException();
        assertThat(service.lastContext("c1")).isNull();
    }
}
