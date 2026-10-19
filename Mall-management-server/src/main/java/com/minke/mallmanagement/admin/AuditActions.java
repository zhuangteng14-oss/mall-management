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
 * 与 {@link #NOTIFICATION_SEND}（块 1 · I2 的**第二个**挂钩点）。⚠️ 上面那两句说的是**开笔当时**的实况，别拿它当今天的清单：此后块 2～6 各自把自己那一笔挂了上来，**今天共 48 条**（块 7 · commit 8 跨域补挂 {@link #GOODS_DELETE} / {@link #COMMENT_DELETE} 两条；commit 8b 加 {@link #OBSERVE_ATTACK_CLEANUP}；2026-10-18 加 {@link #USER_VIEW_PLAINTEXT}；2026-10-19 加 {@link #OBSERVE_ATTACK_EVENTS_PURGE}）（数常量、不数调用点 —— {@code coupon.stop} 等一条 tag 覆盖两个端点，{@code seckill.*} 三个 tag 挂在同一个 Controller 的三个方法上）；**清单就是下面那些常量**，本段只是「为什么一开始只有 1 条」的由来。
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

    // ==================== C3 块 5（营销块，2026-10-11）====================
    // 券的四个挂钩点（commit 1）。域段是 `coupon`（**表名**，也是调用点传的 TargetType 字面量）。
    // ⚠️ **TargetId 一律传 `CouponId`**（业务号，不是自增 Id）—— 同块 4 的理由，
    //    `admin_audit.TargetId` 是 NOT NULL，传 null 会在留痕处炸掉整条链路（TD-20 的教训）。
    // ⚠️ 核销（`coupon.redeem`）**不在这里**：它发生在**结算**（用户端下单），
    //    由 orderAdd 带 couponId 触发、写自己的 OrderId 作 TargetId ⇒ 随 commit 5 一起挂。
    // ⚠️ 块 5 的四个子域至此全部挂完：券（本批）/ 秒杀（commit 4，见下）/ 结算用券（commit 5，见下）
    //    / 折扣（commit 2）
    //    —— 折扣（commit 2）**刻意零新挂钩点**：它的写口是既有的 `goodsAdd` / `goodsUp`
    //    与通用 `/admin/template/*`，留痕已由那两处自己的动作覆盖，再挂一条就是同一件事记两遍。

    /** 管理员建券 —— {@code POST /admin/marketing/coupon/create}。**新增类**动作 ⇒ {@code BeforeJson} 恒 NULL。 */
    public static final String COUPON_CREATE = "coupon.create";

    /** 管理员停用券（逻辑废弃）—— {@code POST /admin/marketing/coupon/stop}。 */
    public static final String COUPON_STOP = "coupon.stop";

    /** 管理员重新启用券 —— {@code POST /admin/marketing/coupon/activate}。 */
    public static final String COUPON_ACTIVATE = "coupon.activate";

    /** 管理员定向发放券 —— {@code POST /admin/marketing/coupon/grant}。**新增类**动作 ⇒ {@code BeforeJson} 恒 NULL。 */
    public static final String COUPON_GRANT = "coupon.grant";

    // ---- 国补的四个挂钩点（commit 3，2026-10-12）。域段是 `subsidy`（**表名族**，
    //      也是调用点传的 TargetType 字面量）。⚠️ TargetId 建规则传 `RuleId`、提名传
    //      `RuleId:GoodsId` —— 同券那条的理由（`admin_audit.TargetId` 是 NOT NULL，传 null
    //      会在留痕处炸掉整条链路，TD-20）。
    //      ⚠️ **商家的确认 / 拒绝不在这里**：那一侧是**用户动作**，走 `notification` 回管理端，
    //      管理端的「谁批的」由通知留档；`admin_audit` 只记**管理员自己**做的事。

    /** 管理员建国补规则 —— {@code POST /admin/marketing/subsidy/create}。**新增类** ⇒ {@code BeforeJson} 恒 NULL。 */
    public static final String SUBSIDY_RULE_CREATE = "subsidy.rule.create";

    /** 管理员停用国补规则 —— {@code POST /admin/marketing/subsidy/stop}。 */
    public static final String SUBSIDY_RULE_STOP = "subsidy.rule.stop";

    /** 管理员重新启用国补规则 —— {@code POST /admin/marketing/subsidy/activate}。 */
    public static final String SUBSIDY_RULE_ACTIVATE = "subsidy.rule.activate";

    /** 管理员提名商品参加国补 —— {@code POST /admin/marketing/subsidy/nominate}。**新增类** ⇒ {@code BeforeJson} 恒 NULL。 */
    public static final String SUBSIDY_NOMINATE = "subsidy.nominate";

    // ---- 秒杀的三个挂钩点（commit 4，2026-10-12）。域段是 `seckill`（**表名族**，
    //      也是调用点传的 TargetType 字面量）。⚠️ TargetId 一律传**业务串**
    //      （邀请 `SKI-…` / 申请 `SKA-…` / 活动 `SKA-…`），不传自增 Id、更不传 null
    //      —— 同券 / 国补那条的理由（`admin_audit.TargetId` 是 NOT NULL，传 null 会在
    //      留痕处炸掉整条链路，TD-20）；业务串还与通知的 `TargetId` 同族，两处能对得上。
    //      ⚠️ **商家的应答 / 报名不在这里**：那一侧是**用户动作**，走 `notification` 回管理端
    //      （`seckill.responded` / `seckill.application.submitted`），`admin_audit`
    //      只记**管理员自己**做的事 —— 同国补那条口径。

    /** 管理员邀请商家上秒杀 —— {@code POST /admin/marketing/seckill/invite}。**新增类** ⇒ {@code BeforeJson} 恒 NULL。 */
    public static final String SECKILL_INVITE = "seckill.invite";

    /** 管理员审核商家秒杀申请（通过 / 驳回）—— {@code POST /admin/marketing/seckill/review}。 */
    public static final String SECKILL_REVIEW = "seckill.review";

    /** 管理员停用已成立的秒杀活动（逻辑废弃）—— {@code POST /admin/marketing/seckill/stop}。 */
    public static final String SECKILL_STOP = "seckill.stop";

    // ---- 结算用券的唯一挂钩点（commit 5，2026-10-12）------------------------------
    // ⚠️ 这是全表里**唯一一条「用户（买家）自己的动作」**：其余都是管理员做的事。
    //    它由 `OrderController.orderAdd` 在**券真的核销掉之后**写（判据 = 回体里带 `Discount`）。
    // ⚠️ `TargetId` 是**订单业务串**、`TargetType` 是 `coupon` —— 看着别扭，理由是可查性：
    //    `coupon_grant.OrderId` 一列就能反查回是哪张券，而券号 `CPxxx` 反过来查不出任何上下文。
    //    `AfterJson` 里同时带着 `CouponId` 与 `Discount`，两个方向都查得到。

    /** 买家**下单时核销**一张优惠券 —— {@code POST /order/orderAdd}（带 {@code CouponId}）。 */
    public static final String COUPON_REDEEM = "coupon.redeem";

    // ==================== C3 块 6（内容块，2026-10-13 整改补笔）====================
    // 五个审核 / 治理动作。域段是**表名**（`comment_image` / `sensitive_word` / `report`），
    // 同块 2～5 的口径。
    // ⚠️ 这一批里有三条**不可逆**动作（驳回图 = 真删仓库文件 / 删词 / 处理举报 = 真删内容）
    //    —— 审计行是它们**事后唯一**的追溯依据：东西被删了，只有这张表还记着「谁在什么时候
    //    删了什么」，以及删之前的快照。
    // ⚠️ TargetId 一律传**业务键**（同块 2～5、TD-20 那条），三处各不相同、各有理由：
    //    · 词 → {@code Word} 词面本身：`sensitive_word.Word` 上有唯一键，**它就是这个词的身份**；
    //      自增 `Id` 反而会在「删了再加同一个词」时指向另一行；
    //    · 图 → `ImageUrl` 的**尾段（落盘名）**：那张图唯一的稳定标识（URL 前缀会随部署变）；
    //    · 举报单 → 自增 `Id`。⚠️ **这一处是例外，如实记下**：`report` 表没有任何业务串
    //      （只有举报人 / 被举报对象 / 理由三列，后两者都在 AfterJson 里），
    //      而管理端页面上认的就是这个 Id ⇒ 用它与列表对得上。举报单是运行期数据、
    //      从不被重新播种 ⇒ 这里没有块 2 那种「重导换号」的风险（那是业务键规则的由来）。
    // ⚠️ `report.handle` **一条 tag 覆盖通过 / 驳回两条路**（口径同 `seckill.review` /
    //    `merchant.apply.audit`：那两张单的「审」也是一个动作、结论进 AfterJson）。
    //    驳回**不**另立 tag 的理由：它改的是同一个对象的同一个字段（`report.Status`），
    //    分不分都能从 AfterJson 的 `Approve` 读出来；而图片那两条**分**，是因为驳回
    //    多了一个不可逆的副作用（删文件）—— 要能一条筛选查出「删过哪些文件」。

    /** 管理员**通过**一张评论图（{@code pending → approved}，图从此对外可见）—— {@code POST /comment/imageReview}。 */
    public static final String COMMENT_IMAGE_APPROVE = "comment.image.approve";

    /** 管理员**驳回**一张评论图（{@code pending → rejected}，**并真删仓库文件**）—— {@code POST /comment/imageReview}。 */
    public static final String COMMENT_IMAGE_REJECT = "comment.image.reject";

    /**
     * 管理员加一个敏感词 —— {@code POST /sensitive/add}。**新增类**动作 ⇒ {@code BeforeJson} 恒 NULL。
     *
     * <p>⚠️ 它**有副作用**：同一个请求里会扫一遍历史评论并就地打码（方案 §4.5）。
     * 那个条数进 {@code AfterJson.Masked} —— 事后问「这条词当时屏蔽了多少条」只有这里答得出。
     */
    public static final String SENSITIVE_WORD_ADD = "sensitive_word.add";

    /** 管理员删一个敏感词 —— {@code POST /sensitive/delete}。⚠️ 历史评论里的 {@code ***} **不回填**。 */
    public static final String SENSITIVE_WORD_DELETE = "sensitive_word.delete";

    /** 管理员处理一条举报（通过 = 删内容 + 警告 + 可能封号；驳回 = 留待再举报）—— {@code POST /report/handle}。 */
    public static final String REPORT_HANDLE = "report.handle";

    // ==================== 块 7 · commit 8（实时日志笔）跨域补挂的 2 条 ====================
    // 由来：这两条**不在块 7 自己的域里**，是块 7 逐端点核对「谁改了库却没留痕」时查出来的
    // **跨域负债**（用户 2026-10-14 批文：「跨域补 2 处审计漏挂」）。两条都追加在**文件尾**
    // ⇒ 本文件既有常量的行号一个不挪。

    /**
     * 商品下架（**两条真 DELETE**：{@code goods} + {@code productsales}）—— {@code POST /goods/goodsDe}。
     *
     * <p>⚠️ 它与 {@link #GOODS_VIOLATION_DELIST} / {@link #GOODS_VIOLATION_DELETE} **是两回事**：
     * 那两条是平台违规处置（改 {@code ShelfState} / 置 {@code DeletedAt}），本条是**商家自己删货**、
     * 行**从表里真的没了**。故 {@code AfterJson} 恒 {@code NULL}（没有「改后」可读），
     * 能记的「改前」= 删除**之前**那一次快照 —— 调用点必须**先快照后删**（顺序见调用点注释）。
     *
     * <p>{@code TargetId} 用 {@code MerchantId:ProductCode}（同 {@link #GOODS_UPDATE} 的业务键口径，
     * 不用自增 {@code Id}：本表跨重导保留）。
     */
    public static final String GOODS_DELETE = "goods.delete";

    /**
     * 删除一条评论（软删 {@code Status='deleted'}；顶层**连带**其下回复一并软删）—— {@code POST /comment/delete}。
     *
     * <p>⚠️ 它是**用户侧**动作、不是管理端专属：作者删自己的评论走同一端点，管理员删任意评论也走它
     * ⇒ 审计行的 {@code ActorId} 两种都可能出现。这正是要留痕的理由（删评论是内容治理里争议最大的一类动作，
     * 事后必须答得出「谁删的」）。
     *
     * <p>{@code TargetId} 用自增 {@code Id}。⚠️ 这是**块 5 {@code report.handle} 那条例外的同款**
     * （见上面那段注释）：{@code comment} 表没有稳定业务串，而它同样是**运行期数据、从不被重新播种**
     * ⇒ 不存在块 2 那种「重导换号」的风险。被审的对象类型 {@code TargetType} 传 {@code "comment"}，
     * 与 {@link #COMMENT_IMAGE_APPROVE} 的 {@code "comment_image"} **刻意不同**（一条评论 ≠ 一张评论图）。
     *
     * <p>⚠️ {@code BeforeJson} 传 {@code null}：软删的「改前」是那一行的原样，而取它要再读一次
     * {@code comment} 表 —— 本笔**不做**（同一 id 在 {@code AfterJson} 里已可定位）。
     */
    public static final String COMMENT_DELETE = "comment.delete";

    // ==================== 块 7 · commit 8b（落库/清理笔）新增的 1 条 ====================
    // 由来：本笔新增了**本仓第一个破坏性管理端点** `POST /admin/observe/attack/cleanup`
    // （按 `SIM-%` 与 `Source='attack'` 两种谓词真删库）。本仓对管理端写操作一律挂审计
    // （块 7 · commit 8 刚把跨域漏挂的两条补齐）⇒ 删数据的动作**更不能是例外**：
    // 事后必须答得出「谁在什么时候清掉了多少行」，否则一次误清无从追。仍追加在**文件尾**
    // ⇒ 本文件既有常量的行号一个不挪。

    /**
     * 清理攻击演练产生的数据（**真 DELETE**，两种谓词：`OrderId LIKE 'SIM-%'` / `Source='attack'`）
     * —— {@code POST /admin/observe/attack/cleanup}。
     *
     * <p>⚠️ 它是本仓**唯一**一个「删的不是一行、而是一整批」的审计动作 ⇒ {@code TargetId} 不用业务键，
     * 用**本次清理的摘要**（`共 N 行`/各表计数）—— 那才是这次动作真正的「被改对象标识」。
     * {@code AfterJson} 传服务层回的那张**按表计数**的表（删了哪几张表各多少行，事后可复算）；
     * {@code BeforeJson} 传 {@code null}（「改前」是整批行的原样，取它等于把要删的东西先全读一遍，
     * 本笔不做 —— 与 {@link #COMMENT_DELETE} 同一处置）。
     *
     * <p>⚠️ {@code TargetType} 传 {@code "attack"}（不是表名）：被改的是一**批**跨表的行，
     * 没有一个表名配得上它。
     */
    public static final String OBSERVE_ATTACK_CLEANUP = "observe.attack.cleanup";

    // ==================== 2026-10-18：演练环境复位新增的 1 条 ====================
    // 由来：攻击模拟器新增 `POST /admin/observe/attack/reset` —— 把压测打空的种子库存 /
    // 下架的父商品 / 冻结的演示商家复位回可演练状态。它**不删行**，但改的是真行的值
    // （库存、在架、冻结），且响应只回计数、原值不保留 ⇒ 与 `/cleanup` 同一档，必须留痕。

    /**
     * 复位演练环境 —— {@code POST /admin/observe/attack/reset}（种子 SKU 库存 / 父商品在架 /
     * 商家解冻 / 秒杀已售）。
     *
     * <p>{@code TargetType="attack"}（跨表的一批种子行，没有单个表名配得上）；
     * {@code TargetId} 用服务层回的中文摘要（「演练环境已复位：共 N 行（…）」）；
     * {@code AfterJson} 存按表计数；{@code BeforeJson} 传 {@code null}（同 `/cleanup`，
     * 不先把整批原值读一遍）。
     */
    public static final String OBSERVE_ATTACK_RESET = "observe.attack.reset";

    // ==================== 2026-10-19：彻底清空事件台账新增的 1 条 ====================
    // 由来：攻击模拟器新增 `POST /admin/observe/attack/purge` —— 不是按演练标记清，而是把
    // `observed_event` **整张表**清空（用户点名的【彻底清空可观测历史数据】）。
    // 它比 `/cleanup` 更强：日常事件行也会被删 ⇒ 必须与清理同档留痕（谁、删了多少、清前清后）。

    /**
     * 彻底清空可观测历史数据 —— {@code POST /admin/observe/attack/purge}。
     *
     * <p>{@code TargetType="attack"}（跨表域的一批台账行）；{@code TargetId} 用服务层回的中文摘要
     * （「已彻底清空事件台账：共删 N 行…」）；{@code AfterJson} 存
     * {@code {observed_event: 删除行数}}；{@code BeforeJson} 传 {@code null}。
     */
    public static final String OBSERVE_ATTACK_EVENTS_PURGE = "observe.attack.events.purge";

    // ==================== 2026-10-18：用户治理「查看明文」新增的 1 条 ====================
    // 由来：D3-4 的读侧掩码保留在用户列表（email 仍是 a***@x.com），但业务要求客服能按次看到
    // 真实联系方式 ⇒ 新增单行详情端点。明文导出是敏感动作，必须留下「谁在什么时候看了谁」。
    // ⚠️ 这是**读**动作，BeforeJson/AfterJson 都没有「改前/改后」语义：本钩子把当前整行放进
    //    AfterJson（含明文 email），BeforeJson 传 null；TargetId 用 username。

    /**
     * 查看用户明文联系方式 —— {@code POST /admin/accounts/user/detail}。
     */
    public static final String USER_VIEW_PLAINTEXT = "user.viewplaintext";

    /**
     * 提交**品牌加盟申请** —— {@code POST /merchant/brandApply}（批四第二批，2026-10-19）。
     * 与 {@link #MERCHANT_APPLY_CREATE} 分开：前者只写 {@code user.IsBrand}，后者升级身份。
     */
    public static final String MERCHANT_BRAND_APPLY_CREATE = "merchant.brand.apply.create";

    /** 审核品牌加盟申请 —— 复用 {@code /admin/accounts/application/audit}，按 outcome 区分。 */
    public static final String MERCHANT_BRAND_APPLY_AUDIT = "merchant.brand.apply.audit";

    /**
     * 客户**自助开通会员** —— {@code POST /user/member/open}（批四第二批 commit 3）。
     * 保存的 {@code before/after} 只有等级，不含余额等无关列。
     */
    public static final String USER_MEMBER_OPEN = "user.member.open";

    /** 管理员**设置会员等级** —— {@code POST /admin/accounts/user/member}。 */
    public static final String USER_MEMBER_SET = "user.member.set";

    // ==================== 批四第二批 · 售后状态机（2026-10-19）====================
    // 域段是 `order`（表名，也是调用点传的 TargetType 字面量）；TargetId 一律传业务号 OrderId
    // （`admin_audit.TargetId` 是 NOT NULL —— TD-20 的教训）。
    // ⚠️ 三条的 actor 不同：申请是**用户**、商家处理是**商家**、平台处理是**管理员**。
    //    合成一条就分不出「谁把这张单推到了终态」—— 那正是退款争议时要回答的问题。
    // ⚠️ BeforeJson 恒 NULL：改前态（旧 RefundStatus/RetryCount）在 Service 读到后没回传，
    //    重新读一次会多一条 SELECT 且与 CAS 之间存在 TOCTOU；结论与申请次数已足够定位。
    //    真实的「改前态」可从 RefundRetryCount + outcome 反推（见各调用点的 after 快照）。

    /** 用户申请 / 重申售后 —— {@code POST /order/refund/apply}。 */
    public static final String REFUND_APPLY = "order.refund.apply";

    /** 商家处理售后（同意 / 拒绝，拒绝二次时自动升级平台）—— {@code POST /order/refund/merchant/handle}。 */
    public static final String REFUND_MERCHANT_HANDLE = "order.refund.merchant.handle";

    /** 平台处理已升级的售后（同意 / 驳回）—— {@code POST /admin/order/refund/handle}。 */
    public static final String REFUND_PLATFORM_HANDLE = "order.refund.platform.handle";

    private AuditActions() {
    }
}
