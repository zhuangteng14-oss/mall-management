package com.minke.mallmanagement.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RefundStatusText} 的 0–5 六态与越界兜底（N5 / Q8=A）。
 *
 * <p>参数化覆盖每一个状态码：改文案时这里会逐条变红，避免后端常量与前端两份
 * {@code REFUND_TEXT} 悄悄分叉。
 */
class RefundStatusTextTest {

    @ParameterizedTest(name = "状态 {0} → {1}")
    @CsvSource({
            "0, 已经结单",
            "1, 待商家审核",
            "2, 商家已拒绝",
            "3, 平台处理中",
            "4, 平台已拒绝",
            "5, 已退款"
    })
    @DisplayName("0–5 六态逐条对齐")
    void labels(int status, String expected) {
        assertThat(RefundStatusText.of(status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("越界 / 脏数据：回「未知状态」，不抛")
    void unknown() {
        assertThat(RefundStatusText.of(-1)).isEqualTo(RefundStatusText.UNKNOWN);
        assertThat(RefundStatusText.of(6)).isEqualTo(RefundStatusText.UNKNOWN);
        assertThat(RefundStatusText.of(99)).isEqualTo(RefundStatusText.UNKNOWN);
    }
}
