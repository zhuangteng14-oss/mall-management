package com.minke.mallmanagement.order;

import java.util.Map;

/**
 * 售后状态 0–5 的**唯一中文文案源**（2026-10-22，N5 / Q8=A）。
 *
 * <p>文案逐字对齐 {@code order.RefundStatus} 的列注释与前端两份 {@code REFUND_TEXT}：
 * <ol>
 *   <li>{@code HistoricalOrders.vue} 的六态展示（含 0 态「已经结单」）；</li>
 *   <li>{@code OrderMine.vue} 的已完成单只读售后态（0 态不画 chip）。</li>
 * </ol>
 * 前端两份**本轮保留**（跨端单一源另开需求），但两处注释已写明「与后端 RefundStatusText 必须同步」。
 *
 * <p>⚠️ 状态码来自数据库列 {@code RefundStatus}（0–5），不是枚举。越界值一律给
 * {@link #UNKNOWN}，不抛异常 —— 客服回复不能因为一条脏数据整句失败。
 */
public final class RefundStatusText {

    /** 0：无售后 / 已结单。 */
    public static final String CLOSED = "已经结单";
    /** 1：买家已申请，等商家审核。 */
    public static final String PENDING_MERCHANT = "待商家审核";
    /** 2：商家拒绝，买家可重申（上限 2 次）。 */
    public static final String MERCHANT_REJECTED = "商家已拒绝";
    /** 3：进入平台处理。 */
    public static final String PENDING_PLATFORM = "平台处理中";
    /** 4：平台拒绝，终态。 */
    public static final String PLATFORM_REJECTED = "平台已拒绝";
    /** 5：已退款。 */
    public static final String REFUNDED = "已退款";
    /** 越界 / 脏数据。 */
    public static final String UNKNOWN = "未知状态";

    private static final Map<Integer, String> BY_STATUS = Map.of(
            0, CLOSED,
            1, PENDING_MERCHANT,
            2, MERCHANT_REJECTED,
            3, PENDING_PLATFORM,
            4, PLATFORM_REJECTED,
            5, REFUNDED);

    private RefundStatusText() {
    }

    /** @param status {@code RefundStatus} 列；越界回 {@link #UNKNOWN} */
    public static String of(int status) {
        return BY_STATUS.getOrDefault(status, UNKNOWN);
    }
}
