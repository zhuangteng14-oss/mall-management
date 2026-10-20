package com.minke.mallmanagement.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员弱口令判据（TD-18 第 2 条安全硬约束，2026-10-20）的单测。
 *
 * <h2>【为什么本类两条分支都要有阳性例】</h2>
 * {@link AdminPasswordPolicy#isWeak} 有两条独立判据：长度、摘要黑名单。只测「短口令被拒」
 * 的话，把黑名单整个删掉（摘要那条分支永不命中）本类仍会全绿 —— 而「默认种子口令仍能登录」
 * 正是这条约束要防的那一件事。故黑名单分支必须单独用一条**长度足够**的已知弱口令打到。
 *
 * <p>⚠️ 测试里出现的 {@code "password"} 是**通用弱口令夹具**，不是任何真实账号的口令；
 * 真实管理员口令既不在这里、也不在 {@link AdminPasswordPolicy}（那里只有 SHA-256）。
 */
class AdminPasswordPolicyTest {

    /** 长度足够、但命中摘要黑名单的口令（通用弱值，非任何真实凭据）。 */
    private static final String KNOWN_WEAK = "password";

    /** 长度 8、未命中黑名单 —— 证明「长度判据」不是「一律拒绝」。 */
    private static final String JUST_LONG_ENOUGH = "Ab3$efgh";

    @Test
    @DisplayName("null / 空 / 短于 8 位 ⇒ 弱（长度分支）")
    void tooShort_isWeak() {
        assertThat(AdminPasswordPolicy.isWeak(null)).as("null 不能当强口令放行").isTrue();
        assertThat(AdminPasswordPolicy.isWeak("")).isTrue();
        assertThat(AdminPasswordPolicy.isWeak("a1$B2c")).as("6 位").isTrue();
        assertThat(AdminPasswordPolicy.isWeak("a1$B2c3")).as("7 位，差一位也不放行").isTrue();
    }

    @Test
    @DisplayName("长度足够但命中已知默认弱口令 ⇒ 弱（摘要黑名单分支）")
    void knownDefaultPassword_isWeak() {
        assertThat(KNOWN_WEAK.length()).as("前提：本夹具必须过长度判据，否则测的还是长度分支")
                .isGreaterThanOrEqualTo(8);
        assertThat(AdminPasswordPolicy.isWeak(KNOWN_WEAK)).isTrue();
    }

    @Test
    @DisplayName("长度 8 且不在黑名单 ⇒ 放行（阳性对照）")
    void strongEnoughPassword_isNotWeak() {
        assertThat(AdminPasswordPolicy.isWeak(JUST_LONG_ENOUGH)).isFalse();
    }
}
