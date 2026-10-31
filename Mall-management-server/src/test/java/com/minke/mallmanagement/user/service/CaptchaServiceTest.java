package com.minke.mallmanagement.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CaptchaService} 的四条语义（2026-10-31「上线前扫雷」）。
 *
 * <p>为什么这里**不**起 Spring：本类要证明的正是「Redis 有没有都成立」，
 * 而起一个真 {@code StringRedisTemplate} 只会把「本地没有 Redis」这件事
 * 变成一条环境依赖。下面用一张 {@code ConcurrentHashMap} 冒充 Redis，
 * 顺带让「一次性」这条能被真实观察到 —— 若照常 {@code when(...).thenReturn(code)}，
 * 第二次校验照样返回 {@code true}，那「错一次即失效」就**测不出来**（测出来也会是假绿）。
 */
class CaptchaServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    /** 冒充 Redis 的键值表（值 = 验证码明文）。 */
    private Map<String, String> fakeRedis;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        fakeRedis = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(ops);

        // ⚠️ `ValueOperations.set` 是 **void** ⇒ 只能用 `doAnswer(..).when(ops).set(..)`，
        //    写成 `when(ops.set(..))` 连编译都过不了（"'void' type not allowed here"）。
        Answer<Void> store = inv -> {
            fakeRedis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        };
        doAnswer(store).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> fakeRedis.get(inv.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> fakeRedis.remove(inv.getArgument(0)) != null);
    }

    /** 发一张 → 填对 → 通过；同一张再填一次 → 不通过（先删后比 ⇒ 一次性）。 */
    @Test
    void correctCode_passesOnce_thenTheChallengeIsGone() {
        CaptchaService service = new CaptchaService(redis);
        CaptchaService.Challenge challenge = service.issue();

        assertThat(challenge.id()).isNotBlank();
        assertThat(challenge.image())
                .as("前端直接塞 <img src> ⇒ 必须是 data URL")
                .startsWith("data:image/png;base64,");

        String code = fakeRedis.values().iterator().next();
        assertThat(code).hasSize(4);

        assertThat(service.verifyAndConsume(challenge.id(), code)).isTrue();
        assertThat(service.verifyAndConsume(challenge.id(), code))
                .as("一次性：第二发必须失败（Redis 侧 GET+DEL）")
                .isFalse();
    }

    /** 大小写不敏感（图上是大写，用户不会按 Shift）。 */
    @Test
    void lowercaseInput_isAccepted() {
        CaptchaService service = new CaptchaService(redis);
        CaptchaService.Challenge challenge = service.issue();
        String code = fakeRedis.values().iterator().next();

        assertThat(service.verifyAndConsume(challenge.id(), code.toLowerCase())).isTrue();
    }

    /** 填错 → 不通过，且**这张码当场作废**（用户要求的「错了一次就失效」）。 */
    @Test
    void wrongCode_fails_andInvalidatesTheChallenge() {
        CaptchaService service = new CaptchaService(redis);
        CaptchaService.Challenge challenge = service.issue();
        String code = fakeRedis.values().iterator().next();

        assertThat(service.verifyAndConsume(challenge.id(), "0000")).isFalse();
        assertThat(service.verifyAndConsume(challenge.id(), code))
                .as("错一次之后，正确答案也必须失败 —— 否则「一次作废」是空话")
                .isFalse();
    }

    /** 空参数 / 未知 id：不触碰存储，直接 false。 */
    @Test
    void blankInput_orUnknownId_isRejected() {
        CaptchaService service = new CaptchaService(redis);
        assertThat(service.verifyAndConsume(null, "ABCD")).isFalse();
        assertThat(service.verifyAndConsume("  ", "ABCD")).isFalse();
        assertThat(service.verifyAndConsume("no-such-id", "ABCD")).isFalse();
        assertThat(service.verifyAndConsume("no-such-id", null)).isFalse();
    }

    /**
     * Redis 不可用 ⇒ 退回进程内表，语义不变（本地演示环境没有 Redis，这条是它的前提）。
     *
     * <p>⚠️ 两个方向都要挂：{@code set} 挂 ⇒ 写入退内存；{@code get} 挂 ⇒ 读取也走内存。
     * 只挂一个的话，会出现「存在 Redis 里却从内存读」的错配 —— 那正是本用例要防的形态。
     */
    @Test
    void redisDown_fallsBackToInProcessStore() {
        AtomicReference<String> storedCode = new AtomicReference<>();
        doAnswer(inv -> {
            storedCode.set(inv.getArgument(1));
            throw new RedisConnectionFailureException("down");
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        CaptchaService service = new CaptchaService(redis);
        CaptchaService.Challenge challenge = service.issue();

        assertThat(storedCode.get()).hasSize(4);
        assertThat(service.verifyAndConsume(challenge.id(), storedCode.get())).isTrue();
        assertThat(service.verifyAndConsume(challenge.id(), storedCode.get()))
                .as("内存表同样是一次性")
                .isFalse();
        assertThat(service.verifyAndConsume("unknown", "ABCD")).isFalse();
    }
}
