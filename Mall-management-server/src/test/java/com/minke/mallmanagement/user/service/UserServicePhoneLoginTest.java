package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.web.UserStateLookup;
import com.minke.mallmanagement.user.dao.UserDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 手机号登录（2026-10-31「上线前扫雷」第 ② 条）：{@link UserService#loginByPhone} 的判据表。
 *
 * <p>四条边界各来一发：<b>正例</b>（真能进）、<b>管理员不给进</b>（手机号不开一条绕过
 * 弱口令闸的路）、<b>封禁答 4</b>（与 {@code loginSelect} 同形）、<b>查库炸了答 0</b>
 * （不是 500、也不是 1）。只测「错的不给进」是不够的 —— 把实现写死成 {@code fail()}
 * 同样能全绿（这就是「负向一半不算判据」）。
 */
class UserServicePhoneLoginTest {

    private static final String PHONE = "13800001111";

    private UserDao userDao;
    private UserStateLookup userStateLookup;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        UserCacheService userCacheService = mock(UserCacheService.class);
        UserGovernanceDao userGovernanceDao = mock(UserGovernanceDao.class);
        userStateLookup = mock(UserStateLookup.class);
        userService = new UserService(userDao, userCacheService, userGovernanceDao,
                providerOf(userStateLookup), "target/test-uploads/");
    }

    /** 正例：手机号 + 演示码 → 客户角色 + 登录名（后续要拿它查业务号写会话）。 */
    @Test
    void validPhoneAndDemoCode_returnsUsernameAndRole() {
        when(userDao.selectLoginByPhone(PHONE)).thenReturn(Map.of("username", "phone_user", "Role", 1));

        UserService.PhoneLoginResult result = userService.loginByPhone(PHONE, UserService.DEMO_SMS_CODE);

        assertThat(result.role()).isEqualTo(1);
        assertThat(result.username()).isEqualTo("phone_user");
    }

    /** 短信码不对 / 手机号格式不对 ⇒ 0，且**不查库**。 */
    @Test
    void wrongSmsCodeOrBadPhone_failsWithoutQueryingTheDatabase() {
        assertThat(userService.loginByPhone(PHONE, "000000").role()).isZero();
        assertThat(userService.loginByPhone("1380000", UserService.DEMO_SMS_CODE).role()).isZero();
        assertThat(userService.loginByPhone(null, UserService.DEMO_SMS_CODE).role()).isZero();
    }

    /** 管理员（Role=3）不走手机号登录 —— 只放行 1/2。 */
    @Test
    void adminRow_isNotAllowedThroughPhoneLogin() {
        when(userDao.selectLoginByPhone(PHONE)).thenReturn(Map.of("username", "admin", "Role", 3));

        UserService.PhoneLoginResult result = userService.loginByPhone(PHONE, UserService.DEMO_SMS_CODE);

        assertThat(result.role()).isZero();
        assertThat(result.username()).isNull();
    }

    /** 封禁 ⇒ 4（前端据此说「已被封禁」，而不是落成「未注册」）。 */
    @Test
    void bannedUser_reportsBanned() {
        when(userDao.selectLoginByPhone(PHONE)).thenReturn(Map.of("username", "bad_user", "Role", 1));
        when(userStateLookup.isBannedByUsername("bad_user")).thenReturn(true);

        UserService.PhoneLoginResult result = userService.loginByPhone(PHONE, UserService.DEMO_SMS_CODE);

        assertThat(result.role()).isEqualTo(UserService.LOGIN_BANNED);
        assertThat(result.username()).isNull();
    }

    /** 查不到（singleRow 抛）/ 多行 / 库不可用 —— 一律 0，不掀成 500。 */
    @Test
    void missingRow_orDatabaseError_isTreatedAsFailedLogin() {
        when(userDao.selectLoginByPhone(PHONE)).thenThrow(new EmptyResultDataAccessException(1));
        assertThat(userService.loginByPhone(PHONE, UserService.DEMO_SMS_CODE).role()).isZero();

        when(userDao.selectLoginByPhone(anyString())).thenThrow(new IllegalStateException("db down"));
        assertThat(userService.loginByPhone(PHONE, UserService.DEMO_SMS_CODE).role()).isZero();
    }

    // ── 小工具：只回答 getIfAvailable() 的 ObjectProvider（UserService 只调这一个方法） ──

    private static ObjectProvider<UserStateLookup> providerOf(UserStateLookup lookup) {
        return new ObjectProvider<>() {
            @Override
            public UserStateLookup getObject() {
                return lookup;
            }

            @Override
            public UserStateLookup getObject(Object... args) {
                return lookup;
            }

            @Override
            public UserStateLookup getIfAvailable() {
                return lookup;
            }

            @Override
            public UserStateLookup getIfUnique() {
                return lookup;
            }
        };
    }
}
