package com.minke.mallmanagement.user.service;

import com.minke.mallmanagement.shared.web.UserStateLookup;
import com.minke.mallmanagement.user.dao.UserDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 掩码回显的**写侧**（2026-10-31「上线前扫雷」第 ③ 条）。
 *
 * <p>背景：{@code /user/me}、{@code /user/getUserInfo} 起答掩码之后，资料页的编辑框
 * 直接绑在这份响应上 —— 用户只改地址、其余原样提交时，会把 {@code a***@qq.com}
 * 写进库。{@link UserService#updateUserInfo} 的判据是「入参恰等于**当前值的掩码**
 * ⇒ 视为没改，用真值替换」。
 *
 * <p>三条断言缺一不可：<b>回显原样提交 ⇒ 落库的是真值</b>（否则掩码污染库）、
 * <b>改过的值 ⇒ 原样落库</b>（否则该功能被这层判据吃掉）、
 * <b>不在当前值掩码上的 {@code *} ⇒ 拒绝且不写库</b>（否则等于给 {@code MaskedInput} 开了口子）。
 */
class UserServiceMaskedUpdateTest {

    private static final String USER = "alice";
    private static final String RAW_EMAIL = "alice@qq.com";
    private static final String MASKED_EMAIL = "a***@qq.com";
    private static final String RAW_ADDRESS = "北京市朝阳区建国路1号";
    private static final String MASKED_ADDRESS = "北京市朝***";

    private UserDao userDao;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        UserCacheService userCacheService = mock(UserCacheService.class);
        UserGovernanceDao userGovernanceDao = mock(UserGovernanceDao.class);
        userService = new UserService(userDao, userCacheService, userGovernanceDao,
                providerOf(mock(UserStateLookup.class)), "target/test-uploads/");

        Map<String, Object> current = new HashMap<>();
        current.put("email", RAW_EMAIL);
        current.put("address", RAW_ADDRESS);
        when(userDao.selectUserByUsername(USER)).thenReturn(current);
    }

    /** 回显原样提交（两格都是掩码）⇒ 落库的是**真值**。 */
    @Test
    void maskedEcho_isReplacedByTheRealValue() {
        Map<String, Object> result = userService.updateUserInfo(USER, MASKED_EMAIL, MASKED_ADDRESS, "你好");

        assertThat(result.get("success")).isEqualTo(true);
        verify(userDao).updateUserProfile(RAW_EMAIL, RAW_ADDRESS, "你好", USER);
    }

    /** 只改一格、另一格原样回显 ⇒ 两格都对（这是真实使用路径）。 */
    @Test
    void unchangedMaskedField_doesNotClobberTheRealValue() {
        Map<String, Object> result = userService.updateUserInfo(USER, MASKED_EMAIL, "上海市浦东新区世纪大道2号", "改地址");

        assertThat(result.get("success")).isEqualTo(true);
        verify(userDao).updateUserProfile(RAW_EMAIL, "上海市浦东新区世纪大道2号", "改地址", USER);
    }

    /** 普通明文照常写入（判据只对含 {@code *} 的入参生效）。 */
    @Test
    void plainValues_passThroughUntouched() {
        Map<String, Object> result = userService.updateUserInfo(USER, "bob@163.com", "广州市天河区体育西路3号", "换号了");

        assertThat(result.get("success")).isEqualTo(true);
        verify(userDao).updateUserProfile("bob@163.com", "广州市天河区体育西路3号", "换号了", USER);
    }

    /** 带 {@code *} 但不是当前值的掩码 ⇒ 拒绝，且**一次写库都没有**。 */
    @Test
    void foreignMaskedValue_isRejectedWithoutWriting() {
        Map<String, Object> emailResult = userService.updateUserInfo(USER, "x***@qq.com", RAW_ADDRESS, "");
        assertThat(emailResult.get("success")).isEqualTo(false);

        Map<String, Object> addressResult = userService.updateUserInfo(USER, RAW_EMAIL, "上海市***", "");
        assertThat(addressResult.get("success")).isEqualTo(false);

        verify(userDao, never()).updateUserProfile(anyString(), anyString(), anyString(), anyString());
    }

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
