package com.minke.mallmanagement.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额口径的唯一实现（第三批㉖–㉙，2026-10-19）。
 *
 * <h2>【为什么必须收口】</h2>
 * 第三批涉及钱包、积分抵扣、退款入账、折扣反算四处资金路径。旧代码里
 * {@code goods.Price} 是 {@code double}、{@code order.Price} 是 {@code float}、
 * 余额列是 {@code decimal(12,2)} —— 同一个量三种精度。若各调用点自己
 * {@code Math.round(x * 100) / 100.0}，展示值与落库值会在第三位小数上分叉且**不报错**。
 *
 * <p>本类只做三件事：① {@code double/String → BigDecimal}；② 统一
 * {@link RoundingMode#HALF_UP} + {@link #SCALE}；③ 回写数据库前的 {@code BigDecimal} 归一。
 * <b>最终定价与入账一律走 {@code BigDecimal}</b>，{@code double} 只允许出现在
 * 「读旧列 / 组响应」这两种边界上。
 *
 * <p>⚠️ 不用 {@code new BigDecimal(double)}：{@code 0.1} 会展开成
 * {@code 0.1000000000000000055…}。必须经 {@link BigDecimal#valueOf(double)}（走
 * {@code Double.toString} 的十进制表示）。
 */
public final class Money {

    /** 金额小数位（数据库中所有金额列都是 2 位）。 */
    public static final int SCALE = 2;

    /** 金额舍入规则 —— 全仓唯一，别在调用点另写。 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Money() {
    }

    /** {@code double → 2 位 HALF_UP BigDecimal}；{@code null} 视同 0。 */
    public static BigDecimal of(Double value) {
        return value == null ? zero() : of(value.doubleValue());
    }

    /** {@code double → 2 位 HALF_UP BigDecimal}。 */
    public static BigDecimal of(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, ROUNDING);
    }

    /** 字符串 → 2 位 HALF_UP；空 / 非数字 ⇒ {@code null}（由调用方判缺参）。 */
    public static BigDecimal parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim()).setScale(SCALE, ROUNDING);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 0.00。 */
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    }

    /** 归一（任何来源的 BigDecimal 落库前都过这里）。 */
    public static BigDecimal norm(BigDecimal value) {
        return value == null ? zero() : value.setScale(SCALE, ROUNDING);
    }

    /** 回写 {@code double} 边界用。 */
    public static double doubleOf(BigDecimal value) {
        return norm(value).doubleValue();
    }

    /** 打折：{@code amount × rate}，2 位 HALF_UP。 */
    public static BigDecimal multiply(BigDecimal amount, BigDecimal rate) {
        return norm(norm(amount).multiply(normRate(rate)));
    }

    /** 折扣率本身保留 4 位（{@code 0.9000}），不按金额的 2 位截断。 */
    public static BigDecimal normRate(BigDecimal rate) {
        return rate == null ? BigDecimal.ONE.setScale(4, ROUNDING) : rate.setScale(4, ROUNDING);
    }

    /** 正数且非零（充值 / 入账判据）。 */
    public static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
