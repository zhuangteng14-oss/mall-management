package com.minke.mallmanagement.order.model;

/**
 * 订单状态 —— 三个状态位（PayIf / DeliveryIf / ReceiveIf）的 8 种取值组合，一一对应。
 *
 * <h2>【合法 / 非法是按哪条线切的】</h2>
 * 切的依据不是命名直觉，是 A3 **已门禁**的不变式（{@code ConcurrencyRunner} 的
 * {@code A-S4a-8}，S4b 的 {@code A-S4b-3} 是同一条）：
 * <pre>
 *   NOT (PayIf = 0 AND (DeliveryIf = 1 OR ReceiveIf = 1))
 * </pre>
 * 即「没付款就不该已经发货或收货」。8 种组合按这条线切成 <b>5 合法 + 3 非法</b>，
 * 而 {@link #legal()} 就是这条线本身 —— 不另立一套判断，否则日后必与 A3 漂移。
 *
 * <h2>【为什么 RECEIVED_NODELIVERY 是合法的】</h2>
 * 因为它满足上面那条不变式。后端**确实放行**「未发货即收货」：接收端点的守卫是
 * {@code PayIf = 1 AND ReceiveIf = 0}（{@link OrderStateMachine} 的 {@code RECEIVE} 守卫；
 * 原 {@code MallManagementGoodsController:584}），
 * 只看两位、不看 {@code DeliveryIf}。前端事实上一路挡着
 * （{@code ShoppingCart.vue:183-186} 点下去才弹「商家还在备货，不允许现在收货」），
 * 但那是**客户端在替后端兜规则**，不是后端的约束 —— 所以它必须作为一个显式状态存在，
 * 而不是被当作不可能情形忽略掉。把它藏起来，就是让「前端拦得住」冒充「后端不允许」。
 *
 * <h2>【3 个非法态从哪来】</h2>
 * 只有一个入口：{@code orderAdd} 不校验三个状态位（TD-17），调用方可以直插 {@code (0,1,0)}。
 * 三个 CAS 端点自己的守卫拦不住这种**出生即非法**的行 —— 它们只保证「流转」不乱，
 * 管不了「入土」时是什么。
 *
 * <p>本枚举**不含**「未知 / 无法归类」这个哨兵值：{@link #of} 只承认 {0,1}，
 * 越界取值由调用方用 {@link #isCanonical} 判掉（观测路径请走
 * {@code OrderStateMachine.stateName(...)}，它永不抛）。
 */
public enum OrderState {

    // ===================== 合法态：5 个，满足 A-S4a-8 的不变式 =====================

    /** (0,0,0) 下单未付款。前端「是否现在付款？」点「否」即此态（{@code ProductPage.vue:354}）。 */
    CREATED(0, 0, 0),

    /**
     * (1,0,0) 已付款待发货。
     * <p>⚠️ **两条来路**：{@code orderPayIfUp}（{@code G:556}），以及 {@code orderAdd} 直接造出来
     * （前端「是否现在付款？」点「是」）。后者意味着「初始态」不是**一个**状态而是
     * {@code {CREATED, PAID}} —— TD-17 登记时写的「一律写 0」会打断这条生产路径。
     */
    PAID(1, 0, 0),

    /** (1,1,0) 已发货待收货。 */
    SHIPPED(1, 1, 0),

    /** (1,0,1) 未发货即收货 —— 后端放行、前端拦着，见类注释。 */
    RECEIVED_NODELIVERY(1, 0, 1),

    /** (1,1,1) 已完成。 */
    COMPLETED(1, 1, 1),

    // ===================== 非法态：3 个，只能由 TD-17 的伪造入口造出 =====================

    /** (0,1,0) 未付款已发货。 */
    ILLEGAL_DELIVERED_UNPAID(0, 1, 0),

    /** (0,0,1) 未付款已收货。 */
    ILLEGAL_RECEIVED_UNPAID(0, 0, 1),

    /** (0,1,1) 未付款已发货已收货 —— TD-17 正文举的就是这个例子。 */
    ILLEGAL_DELIVERED_RECEIVED_UNPAID(0, 1, 1);

    private final int payIf;
    private final int deliveryIf;
    private final int receiveIf;

    OrderState(int payIf, int deliveryIf, int receiveIf) {
        this.payIf = payIf;
        this.deliveryIf = deliveryIf;
        this.receiveIf = receiveIf;
    }

    public int payIf() {
        return payIf;
    }

    public int deliveryIf() {
        return deliveryIf;
    }

    public int receiveIf() {
        return receiveIf;
    }

    /**
     * 是否满足 A-S4a-8 的不变式 {@code NOT (PayIf=0 AND (DeliveryIf=1 OR ReceiveIf=1))}。
     * <p>{@code PayIf=1} 时另两位任意，都合法。
     */
    public boolean legal() {
        return payIf != 0 || (deliveryIf == 0 && receiveIf == 0);
    }

    /** 三个状态位是否都落在 {0,1} 内 —— {@link #of} 的前置条件。 */
    public static boolean isCanonical(int payIf, int deliveryIf, int receiveIf) {
        return isBit(payIf) && isBit(deliveryIf) && isBit(receiveIf);
    }

    /**
     * 位组合 → 状态。
     *
     * <p>⚠️ **只接受 {0,1}，越界值抛异常，不归一化。**这不是洁癖：把 {@code (2,2,2)} 归一化成
     * {@code COMPLETED} 等于替伪造数据洗白，而观测路径恰恰需要看见「它不合法」。
     * 观测路径请先用 {@link #isCanonical} 判，或直接用 {@code OrderStateMachine.stateName(...)}。
     *
     * @throws IllegalArgumentException 任一状态位不在 {0,1} 内
     */
    public static OrderState of(int payIf, int deliveryIf, int receiveIf) {
        for (OrderState s : values()) {
            if (s.payIf == payIf && s.deliveryIf == deliveryIf && s.receiveIf == receiveIf) {
                return s;
            }
        }
        throw new IllegalArgumentException("不是 {0,1} 内的三位组合：PayIf=" + payIf
                + " DeliveryIf=" + deliveryIf + " ReceiveIf=" + receiveIf);
    }

    private static boolean isBit(int v) {
        return v == 0 || v == 1;
    }
}
