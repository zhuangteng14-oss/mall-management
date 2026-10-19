package com.minke.mallmanagement.shared;

/**
 * 会员价的**唯一公式**（批四第二批 commit 3，2026-10-19）：全场 {@value #RATE_TEXT}。
 *
 * <p>为什么单开一个小类，而不是在每个调用方各写一遍 {@code price * 0.9}：
 * <ul>
 *   <li>下单落库（{@code OrderService.addLocked}）与详情页展示（{@code GoodsService.productCodePrice}）
 *       必须是**同一个数** —— 展示 8.91、落库 8.92 这类差额不会报错，只会让用户觉得被多收了一分；</li>
 *   <li>价格都是 {@code float}/{@code double}，直接乘 {@code 0.9} 会在第三位小数上分叉；
 *       这里统一「乘 90、按分四舍五入、除 100」，两处逐位相同。</li>
 * </ul>
 *
 * <p>⚠️ 本类**不读库、不持状态**：会员等级由调用方先问 {@code UserStateLookup.memberLevel}，
 * 折扣率只此一处。购物车 / 商品卡前端那几处展示也复用同一公式（前端复制一份数字 0.9 ——
 * 最终实付仍以服务端 {@code orderAdd} 为准，前端只负责展示）。
 */
public final class MemberPricing {

    /** 会员折扣率（9 折）—— 数值口径的唯一来源。 */
    public static final double RATE = 0.9d;

    /** 文案用的折扣率（「9 折」中的 9）。 */
    public static final int RATE_TEXT = 9;

    private MemberPricing() {
    }

    /**
     * 会员价 = 原价 × {@link #RATE}，四舍五入到分。
     *
     * <p>⚠️ 用 {@code Math.round(price * 90) / 100} 而不是 {@code price * 0.9}：后者在
     * {@code float} 入参上会带出 {@code 9.9f → 8.909999…} 这类尾数，落库与展示各自 round 时
     * 可能差一分。乘 90 是整数倍的十进制运算，分位只由一次 {@code round} 决定。
     */
    public static double priceOf(double price) {
        return Math.round(price * 90d) / 100d;
    }

    /**
     * 按**等级表折扣率**算会员价（㉘ 起 {@code member_level.DiscountRate}，V0–V6 各档不同）。
     *
     * <p>{@code rate == null} ⇒ 退回 {@link #priceOf(double)}（全场 9 折的老口径兜底）——
     * 等级配置读不到时不能把会员价直接抹成原价。
     *
     * <p>⚠️ 用 {@code BigDecimal} 乘再 {@code HALF_UP} 到分：{@code double} 的
     * {@code 0.88} 在上一位带尾数，展示与落库两处各 round 一次会差一分。
     */
    public static double priceOf(double price, java.math.BigDecimal rate) {
        if (rate == null) {
            return priceOf(price);
        }
        return java.math.BigDecimal.valueOf(price)
                .multiply(rate)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }
}
