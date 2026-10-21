package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.web.AuthTokenStore;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import com.minke.mallmanagement.user.dao.UserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TD-18 凭据驱动铸票（{@link AdminLoginService}）的**纯单测** —— 四条安全硬约束里
 * 「防爆破」「禁弱口令」「可撤销」的判定面都在服务层，能在这里钉死，不必起 Spring。
 *
 * <h2>【为什么 Redis 用 mock，而不是内嵌 Redis】</h2>
 * 本类验的是**判定与调用顺序**（第几次失败该锁、锁定时是否还查库、失败时是否铸票），
 * 不是 Redis 本身的行为。用 mock 可以把「第 5 次失败」构造成一次确定性的
 * {@code increment → 5}，不必真的打五遍、也不用等 15 分钟过期。
 * ⚠️ 真 Redis 后端（{@code RedisAuthTokenBackend}）的集成验证由手工验收覆盖
 * （默认 profile 起服务 + 真实请求），单测不替它背书。
 *
 * <h2>【判据里为什么不只看异常类型】</h2>
 * 每个拒绝用例都同时断言 {@link AuthTokenStore#liveCount()} 为 0 —— 「抛了异常」与
 * 「没铸票」是两件事；只在铸票**之后**抛异常的实现同样会通过前一种断言。
 */
@ExtendWith(MockitoExtension.class)
class AdminLoginServiceTest {

    private static final String USERNAME = "admin";
    private static final String KEY = "auth:admin:fail:admin";
    private static final String STRONG = "Str0ng-Passw0rd-2026";

    @Mock private UserDao userDao;
    @Mock private UserService userService;
    @Mock private UserStateLookup userStateLookup;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;

    private AdminLoginService service;

    @BeforeEach
    void setUp() {
        AuthTokenStore.clearForTest();
        when(redis.opsForValue()).thenReturn(ops);
        service = new AdminLoginService(userDao, userService, userStateLookup, redis, 5, 15);
    }

    @AfterEach
    void clearTokens() {
        AuthTokenStore.clearForTest();
    }

    // ==================== 1. 防爆破 ====================

    @Test
    @DisplayName("连续 5 次密码错：前 4 次 BAD_CREDENTIALS，第 5 次 LOCKED，且一张票都没铸")
    void fiveWrongPasswords_lockOnTheFifth() {
        when(userDao.selectRoleByCredentials(USERNAME, "wrong"))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 0));
        when(ops.increment(KEY)).thenReturn(1L, 2L, 3L, 4L, 5L);

        for (int attempt = 1; attempt <= 4; attempt++) {
            AdminLoginService.RejectedException rejected = catchThrowableOfType(
                    () -> service.login(USERNAME, "wrong"), AdminLoginService.RejectedException.class);
            assertThat(rejected.reason())
                    .as("第 %d 次失败应只报凭据错，不是锁定", attempt)
                    .isEqualTo(AdminLoginService.Reason.BAD_CREDENTIALS);
        }

        AdminLoginService.RejectedException fifth = catchThrowableOfType(
                () -> service.login(USERNAME, "wrong"), AdminLoginService.RejectedException.class);
        assertThat(fifth.reason()).isEqualTo(AdminLoginService.Reason.LOCKED);
        assertThat(fifth.retryAfterSeconds()).as("15 分钟 = 900 秒").isEqualTo(900L);
        assertThat(AuthTokenStore.liveCount()).as("失败路径绝不能留下任何票").isZero();

        // 第 1 次起计时、第 5 次把锁定期重新钉满 —— 两处，不多不少。
        verify(redis, times(2)).expire(eq(KEY), eq(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("锁定期间直接拒绝，不再查库（避免把锁定当作密码校验侧信道）")
    void alreadyLocked_rejectsWithoutTouchingTheDatabase() {
        when(ops.get(KEY)).thenReturn("5");
        when(redis.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(600L);

        AdminLoginService.RejectedException rejected = catchThrowableOfType(
                () -> service.login(USERNAME, STRONG), AdminLoginService.RejectedException.class);

        assertThat(rejected.reason()).isEqualTo(AdminLoginService.Reason.LOCKED);
        assertThat(rejected.retryAfterSeconds()).isEqualTo(600L);
        verifyNoInteractions(userDao);
        assertThat(AuthTokenStore.liveCount()).isZero();
    }

    @Test
    @DisplayName("Redis 不可用 ⇒ UNAVAILABLE（fail-closed），不熔穿成「密码错」")
    void redisDown_answersUnavailable() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        AdminLoginService.RejectedException rejected = catchThrowableOfType(
                () -> service.login(USERNAME, STRONG), AdminLoginService.RejectedException.class);

        assertThat(rejected.reason()).isEqualTo(AdminLoginService.Reason.UNAVAILABLE);
        verifyNoInteractions(userDao);
        assertThat(AuthTokenStore.liveCount()).isZero();
    }

    // ==================== 2. 禁弱口令 ====================

    @Test
    @DisplayName("凭据正确但口令命中弱口令黑名单 ⇒ WEAK_PASSWORD，铸票之前就拒绝")
    void weakPassword_isRejectedBeforeMinting() {
        String weak = "password";   // 通用弱口令夹具（详见 AdminPasswordPolicyTest 的说明）
        when(userDao.selectRoleByCredentials(USERNAME, weak)).thenReturn(3);
        when(userStateLookup.isBannedByUsername(USERNAME)).thenReturn(false);

        AdminLoginService.RejectedException rejected = catchThrowableOfType(
                () -> service.login(USERNAME, weak), AdminLoginService.RejectedException.class);

        assertThat(rejected.reason()).isEqualTo(AdminLoginService.Reason.WEAK_PASSWORD);
        verify(userService, never()).businessId(USERNAME, 3);
        assertThat(AuthTokenStore.liveCount()).isZero();
    }

    // ==================== 3. 铸票与清计数 ====================

    @Test
    @DisplayName("强口令 + 管理员 ⇒ 铸出可解析的 role=3 票，并清掉失败计数")
    void strongCredentials_mintAnAdminTokenAndClearFailures() {
        when(userDao.selectRoleByCredentials(USERNAME, STRONG)).thenReturn(3);
        when(userStateLookup.isBannedByUsername(USERNAME)).thenReturn(false);
        when(userService.businessId(USERNAME, 3)).thenReturn("ADMIN-1");

        String token = service.login(USERNAME, STRONG);

        assertThat(token).isNotBlank();
        AuthTokenStore.Grant grant = AuthTokenStore.resolve(token);
        assertThat(grant).as("铸出的票必须能被票存储解析").isNotNull();
        assertThat(grant.userId()).isEqualTo("ADMIN-1");
        assertThat(grant.role()).isEqualTo(3);
        verify(redis).delete(KEY);
    }

    @Test
    @DisplayName("凭据正确但不是管理员 ⇒ NOT_ADMIN，不计数、不铸票")
    void nonAdmin_isRejectedWithoutCountingOrMinting() {
        when(userDao.selectRoleByCredentials(USERNAME, STRONG)).thenReturn(1);

        AdminLoginService.RejectedException rejected = catchThrowableOfType(
                () -> service.login(USERNAME, STRONG), AdminLoginService.RejectedException.class);

        assertThat(rejected.reason()).isEqualTo(AdminLoginService.Reason.NOT_ADMIN);
        verify(ops, never()).increment(anyString());
        assertThat(AuthTokenStore.liveCount()).isZero();
    }
}
