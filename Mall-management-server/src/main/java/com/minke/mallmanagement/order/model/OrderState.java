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
 * <p>⚠️ <b>该入口已于 C#6（2026-10-04 `fix/order-state-guard`）关闭</b>：
 * {@code OrderService.orderAdd} 在取锁之前用 {@link #isInitial} 挡掉非初始态
 * ⇒ **API 路径再也造不出这 3 个非法态**。三个枚举常量**一个都不删**（它们仍是
 * {@code OrderStateMachine.stateName(...)} 与时间线重放的词表，且存量脏数据可能已是这些态），
 * 保留即「读得懂历史上出现过的值」。
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
     * 是否为**下单可接受的初始态** —— 全仓**唯一**的判据处，由 {@code OrderService.orderAdd}
     * 的出生闸（C#6，2026-10-04 `fix/order-state-guard`）调用。
     *
     * <p>取值是**恰好两个**：{@link #CREATED} {@code (0,0,0)} 与 {@link #PAID} {@code (1,0,0)}。
     *
     * <h2>⚠️ 为什么必须收 {@code (1,0,0)} —— 别把它当"多收了一个"，拒它会弄坏生产</h2>
     * 下单页问「是否现在付款？」（{@code ProductPage.vue:354} 的 {@code PayIf: payIf ? 1 : 0}），
     * 点「是」时**直接传 {@code PayIf=1}**，{@code DeliveryIf} / {@code ReceiveIf} 恒为 0
     * （同文件 {@code :342-343}）。⇒ 生产环境**实际只会产生这两个组合**，「初始态」不是**一个**
     * 状态而是 {@code {CREATED, PAID}} 两个 —— 这不是推断，是 {@link #PAID} 常量的 javadoc
     * 早就写下的既成事实（它记着「{@code orderAdd} 直接造出 PAID」）。
     * <p>因此**拒 {@code (1,0,0)} 会当场断掉「下单即付款」这条主流程**，而它并不是伪造态。
     * 谁若认为「初始态只该有 (0,0,0)」（TD-17 建议①「一律写 0」的原话）请先改前端，
     * 那是**另一笔**，不是收紧这道闸的理由。
     *
     * <h2>⚠️ 为什么只收这两个 —— 拒 {@code (1,1,0)} / {@code (1,0,1)} / {@code (1,1,1)}</h2>
     * 这三条**是合法态**（满足 {@link #legal()}，即 A-S4a-8 的不变式），所以「只收合法态」
     * 那种更宽的规则（= 把 {@code legal()} 前移）**拦不住它们** —— 而 TD-17 正文举的正是
     * {@code (1,1,1)}「已付款+已发货+已收货」：客户端一个 POST 就能凭空造出终态订单，
     * 全程不经过三个 CAS 端点。⇒ 判据必须是**初始态白名单**，不能是不变式。
     * <p>这三条被拒是**刻意的**，{@code OrderAddStateGuardSmokeTest} 有 3 条用例逐条钉住
     * —— 将来谁想把规则放宽成 {@code legal()}，会先红。
     *
     * <p>本判据与 {@link #legal()} 是**两个不同的规则，不可互相替代**：
     * {@code legal()} 管「流转后的状态合不合法」（A-S4a-8 门禁），{@link #isInitial} 管
     * 「出生时允许从哪几个状态开始」（C#6 出生闸）。前者是后者的**超集**。
     */
    public static boolean isInitial(int payIf, int deliveryIf, int receiveIf) {
        return isCanonical(payIf, deliveryIf, receiveIf)
                && deliveryIf == 0 && receiveIf == 0;
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
