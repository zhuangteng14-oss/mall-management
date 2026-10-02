package com.minke.mallmanagement.order.model;

/**
 * 订单状态流转的**动作** —— 一次转移「做了什么」，与「从哪到哪」正交。
 *
 * <p>四个取值里只有三个是真正的转移（{@code PAY} / {@code DELIVER} / {@code RECEIVE}），
 * 各自**置位一个状态位**且不碰另外两位 —— 这是本案能成立的全部原因：
 * <pre>
 *   from = to 把本动作那一位清零
 * </pre>
 * 因为守卫（见 {@code OrderStateMachine}）已经要求「本动作那一位必须是 0」，
 * 所以这个反推是**精确的**，不需要在 UPDATE 前后额外 SELECT 旧态。
 * 这条前提由 {@code OrderStateMachineTest.everyGuardRequiresItsOwnBitToBeZero} 钉死 ——
 * 谁把某条守卫改成不约束本位，时间线的 {@code from} 会静默失真。
 *
 * <p>{@link #CREATE} 不是转移（订单**出生**，没有前态），它在时间线里的意义是给时间线一个
 * 起点：没有它，C3 拿到的每条时间线都从「付款」开始，而「下单未付款」与「下单即付款」
 * 这两种出生方式（前端弹窗的「是 / 否」）根本无从分辨。
 */
public enum OrderAction {

    /** 订单出生。{@code orderAdd}，无守卫、无前态。 */
    CREATE("create", -1),

    /** 付款。置 {@code PayIf}。 */
    PAY("pay", 0),

    /** 发货。置 {@code DeliveryIf}。 */
    DELIVER("deliver", 1),

    /** 收货。置 {@code ReceiveIf}。 */
    RECEIVE("receive", 2);

    /** 位下标 → 列名。下标与 {@code OrderState} 的构造参数顺序（PayIf, DeliveryIf, ReceiveIf）一致。 */
    private static final String[] COLUMNS = {"PayIf", "DeliveryIf", "ReceiveIf"};

    private final String tag;
    private final int bitIndex;

    OrderAction(String tag, int bitIndex) {
        this.tag = tag;
        this.bitIndex = bitIndex;
    }

    /** 事件流 / 指标 tag 的取值（有界，4 个）。 */
    public String tag() {
        return tag;
    }

    /** 本动作置位的那一位在 {@code OrderState} 三位组里的下标；{@link #CREATE} 为 -1。 */
    public int bitIndex() {
        return bitIndex;
    }

    /** 本动作置位的列名（{@code PayIf} / {@code DeliveryIf} / {@code ReceiveIf}）；{@link #CREATE} 为 {@code null}。 */
    public String column() {
        return bitIndex < 0 ? null : COLUMNS[bitIndex];
    }

    /** 是否是真正的状态转移（即是否需要守卫、是否需要前态）。 */
    public boolean isTransition() {
        return bitIndex >= 0;
    }
}
