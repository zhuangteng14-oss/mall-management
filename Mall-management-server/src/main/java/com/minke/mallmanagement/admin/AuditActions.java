package com.minke.mallmanagement.admin;

/**
 * 审计**动作 tag** 常量 —— {@code admin_audit.Action} 列的取值域（与建表块注释里那句
 * 「与 admin 域 AuditActions 常量一一对应」对应）。
 *
 * <h2>【为什么要有这个类，而不是在各调用点手写字符串】</h2>
 * 与 {@code AuthSession.SESSION_USER_ID} 同一条理由：动作 tag 会被**写入方**（各业务端的挂钩点）
 * 与**读出方**（{@code /admin/audit/list} 的筛选、前端下拉）各写一遍。一旦字面量在第二处被抄错，
 * <b>双方都不报错</b> —— 写入方写了 {@code goods.update}、筛选方按 {@code goods.updtae} 查，
 * 结果是「一条也筛不出来」的静默空集。收成常量后，写错的方式只剩「引用错常量」，
 * 而那会同时影响两侧、当场可见。
 *
 * <h2>【命名口径：{@code <域>.<动作>}】</h2>
 * 全小写、点分两段：域 = {@code TargetType}（{@code goods} / {@code user} / {@code order} …），
 * 动作 = 该域上的一个动词（{@code update} / {@code create} / {@code delete} / {@code state-change}）。
 * ⚠️ 列宽 {@code varchar(64)}，别写超过这个长度的 tag。
 *
 * <h2>【为什么一开始只有 1 条常量 —— 这不是遗漏】</h2>
 * C3 批 A 的裁决是「**基础设施 + 1 个样板钩子**」（用户 2026-10-08 选定）：先把
 * 「表 → 写入口 → 读 API → 页面」这条链打通、并用**一个**真实挂钩点证明它通，
 * 其余 15 个改库端点**随各自那一笔**挂（横切本来就该跟着业务改动走，见
 * {@code docs/agent/c3-modules.md} §11 与 {@code slot-registry.md} 末条）。
 * ⇒ 每挂一个新勾，**在这里加一条**。今天有 2 条：{@link #GOODS_UPDATE}（批 A 的样板）
 * 与 {@link #NOTIFICATION_SEND}（块 1 · I2 的**第二个**挂钩点）。
 */
public final class AuditActions {

    /**
     * 商品更新（改价 / 改名 / 换图）—— {@code POST /goods/goodsUp} 的挂钩点。
     *
     * <p>选它当样板的理由：它的「改前 / 改后」差异**最大**（价格 + 名称 + 图片三列都可能变），
     * 最能同时验证审计表的 {@code BeforeJson} / {@code AfterJson} 两列与页面的并排 diff 是否真的有用。
     */
    public static final String GOODS_UPDATE = "goods.update";

    /**
     * 通知发送（公告 / 群发 / 定向）—— {@code POST /admin/notify/send} 的挂钩点
     * （C3 块 1 · I2 新增；用户 2026-10-09 裁决：「发送公告是典型平台动作，**必须留痕**」）。
     *
     * <p>⚠️ 它**不是**「改某个已有对象」的钩子 ⇒ {@code BeforeJson} 恒为 {@code NULL}
     * （发送是**新增类**动作，无改前态）；被审计的「对象」是**这一次投递批次**，
     * 故 {@code TargetId} 用 {@code BatchId}（UUID），**不用** {@code notification.Id}
     * —— 后者会随全量重导变，而本表跨重导保留（同 {@link #GOODS_UPDATE} 用
     * {@code MerchantId:ProductCode} 而不用自增 Id 的理由）。
     *
     * <p>tag 前段 {@code notification} 必须与调用点传的 {@code TargetType} 字面量一致。
     * 注意它与 {@code NotificationEvents} 的**事件** code 是两套东西：那套是
     * 「这条通知是什么性质」（可扩展、后端不校验白名单），这套是「管理员做了什么动作」。
     */
    public static final String NOTIFICATION_SEND = "notification.send";

    // ==================== C3 块 2（用户块）逐笔挂上来的 7 个挂钩点 ====================

    /** 封禁用户 —— {@code POST /admin/accounts/user/ban}。 */
    public static final String USER_BAN = "user.ban";

    /** 解封用户 —— {@code POST /admin/accounts/user/unban}。 */
    public static final String USER_UNBAN = "user.unban";

    /** 用户改自己的可编辑资料（{@code gender} / {@code age}）—— {@code POST /user/updateProfile}。 */
    public static final String USER_UPDATE = "user.update";

    /**
     * 提交入驻申请 —— {@code POST /merchant/apply}。
     * ⚠️ 它是**新增类**动作 ⇒ {@code BeforeJson} 恒 {@code NULL}，{@code TargetId} 用 {@code BatchId}。
     */
    public static final String MERCHANT_APPLY_CREATE = "merchant.apply.create";

    /** 审核入驻申请（通过 / 驳回）—— {@code POST /admin/accounts/application/audit}。 */
    public static final String MERCHANT_APPLY_AUDIT = "merchant.apply.audit";

    /** 排定商家降级 —— {@code POST /admin/accounts/downgrade/schedule}（含取消）。 */
    public static final String MERCHANT_DOWNGRADE_SCHEDULE = "merchant.downgrade.schedule";

    /** 降级**执行**（定时任务触发，非 HTTP）—— 由 {@code MerchantDowngradeJob} 记。 */
    public static final String MERCHANT_DOWNGRADE_APPLY = "merchant.downgrade.apply";

    // ==================== C3 块 3（商品块）逐笔挂上来的 10 个挂钩点 ====================
    // 口径同块 2：域 = TargetType（表名），tag = `<TargetType>.<动作>`，点号前段必须与
    // 调用点传的 TargetType 字面量一致。
    // ⚠️ 三个「新增类」动作（apply.create / category.create / template.create）没有「改前态」
    //    ⇒ BeforeJson 恒 NULL；其中提审的 TargetId 用 BatchId（同块 2 的 merchant.apply.create）。

    /** 商家提交商品上架申请（提审）—— {@code POST /goods/goodsAdd}。**新增类**动作。 */
    public static final String GOODS_APPLY_CREATE = "goods.apply.create";

    /** 管理员审核商品提审单（通过 / 驳回）—— {@code POST /admin/catalog/application/audit}。 */
    public static final String GOODS_APPLY_AUDIT = "goods.apply.audit";

    /** 管理员新增类目 —— {@code POST /admin/catalog/category/create}。**新增类**动作。 */
    public static final String GOODS_CATEGORY_CREATE = "goodstype.create";

    /** 管理员改类目（改名 / 排序 / 启用停用 / 换父级）—— {@code POST /admin/catalog/category/update}。 */
    public static final String GOODS_CATEGORY_UPDATE = "goodstype.update";

    /** 管理员删类目（逻辑废弃：{@code State=0}）—— {@code POST /admin/catalog/category/delete}。 */
    public static final String GOODS_CATEGORY_DELETE = "goodstype.delete";

    /**
     * 管理员新增模板 —— {@code POST /admin/catalog/template/create}。**新增类**动作。
     *
     * @deprecated C3 块 3 就地改造（2026-10-10）后由 {@link #TEMPLATE_CREATE} 取代。
     *     ⚠️ **保留不删**：与 {@link #GOODS_UPDATE} 同因 —— 存量 {@code admin_audit} 行里仍有这个字面量，
     *     删掉常量不会删掉那些行，只会让「这条值还能是哪些」在代码里失去枚举依据。
     */
    @Deprecated
    public static final String GOODS_TEMPLATE_CREATE = "goods_template.create";

    /**
     * 管理员改模板 —— {@code POST /admin/catalog/template/update}。
     *
     * @deprecated 同 {@link #GOODS_TEMPLATE_CREATE}（由 {@link #TEMPLATE_UPDATE} 取代）。
     */
    @Deprecated
    public static final String GOODS_TEMPLATE_UPDATE = "goods_template.update";

    /**
     * 管理员删模板（逻辑废弃：{@code Enabled=0}）—— {@code POST /admin/catalog/template/delete}。
     *
     * @deprecated 同 {@link #GOODS_TEMPLATE_CREATE}（由 {@link #TEMPLATE_DELETE} 取代）。
     */
    @Deprecated
    public static final String GOODS_TEMPLATE_DELETE = "goods_template.delete";

    /** 管理员违规下架商品（{@code ShelfState=0}）—— {@code POST /admin/catalog/violation/delist}。 */
    public static final String GOODS_VIOLATION_DELIST = "goods.violation.delist";

    /** 管理员违规删除商品（{@code DeletedAt=now}，逻辑删除）—— {@code POST /admin/catalog/violation/delete}。 */
    public static final String GOODS_VIOLATION_DELETE = "goods.violation.delete";

    // ==================== C3 块 3 就地改造（模板表通用化，2026-10-10）====================
    // 三个模板 CRUD 钩子，`@RequestMapping("/admin/template")` 的三个写端点。
    // ⚠️ 域段是 `template`（**表名**，也是调用点传的 TargetType 字面量）—— 不再是 `goods_template`。
    // ⚠️ 对**通知模板**是**新增留痕**：块 1 的 `/admin/notify/template/save` 从未挂审计
    //    （块 1 类注释里「模板 CRUD 是配置而非平台动作」那条是**推断**）；合并成一张表后统一挂，
    //    通知侧从「无痕」变成「有痕」。这不是回归，是覆盖面扩大。

    /** 管理员新增模板 —— {@code POST /admin/template/create}。**新增类**动作 ⇒ {@code BeforeJson} 恒 NULL。 */
    public static final String TEMPLATE_CREATE = "template.create";

    /** 管理员改模板（改名 / 改标题 / 改正文 / 启停 / 改排序）—— {@code POST /admin/template/update}。 */
    public static final String TEMPLATE_UPDATE = "template.update";

    /** 管理员删模板（逻辑废弃：{@code Enabled=0}）—— {@code POST /admin/template/delete}。 */
    public static final String TEMPLATE_DELETE = "template.delete";

    // ==================== C3 块 4（订单块，2026-10-10）====================
    // 三个订单治理动作。域段是 `order`（**表名**，也是调用点传的 TargetType 字面量）。
    // ⚠️ **TargetId 一律传 `OrderId`**（业务号，不是自增 `Id`）—— `admin_audit.TargetId` 是 NOT NULL，
    //    传 null 会在留痕处炸掉整条链路（块 3 在这里丢光过留痕，见 TD-20 的教训）。
    // ⚠️ 三条**互不覆盖**：介入 / 发货 / 收货的 **actor 不同**（管理员 / 商家 / 买家），
    //    合成一条就分不出「是谁把这张单推了一步」—— 那正是审计要回答的问题。

    /** 管理员介入订单（沿既有 CAS 边强制推进）—— {@code POST /admin/order/intervene}。 */
    public static final String ORDER_INTERVENE = "order.intervene";

    /** 商家发货 —— {@code POST /order/orderDeliveryIfUp}。 */
    public static final String ORDER_DELIVER = "order.deliver";

    /** 买家收货 —— {@code POST /order/orderReceiveIfUp}。 */
    public static final String ORDER_RECEIVE = "order.receive";

    private AuditActions() {
    }
}
