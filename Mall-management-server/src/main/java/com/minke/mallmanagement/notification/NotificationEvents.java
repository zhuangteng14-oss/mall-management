package com.minke.mallmanagement.notification;

/**
 * 系统通知**事件类型 code** 常量 —— {@code notification.EventType} 列的取值域（与建表块注释里
 * 那句「对应 notification 域 NotificationEvents 常量」对应）。
 *
 * <h2>【为什么要有这个类，而不是在各调用点手写字符串】</h2>
 * 与 {@code AuditActions} 同一条理由：code 会被**写入方**（各业务块的触发点）与**读出方**
 * （前端 {@code notify-events.js} 的标签映射、管理端发送页的事件类型下拉）各写一遍。字面量在第二处
 * 抄错，**双方都不报错** —— 写入方写 {@code goods.audit.passed}、读出方按 {@code goods.audit.passd}
 * 映射，结果是「这一行永远显示不出标签」的静默降级。收成常量后，写错只剩「引用错常量」这一种，
 * 而那会同时影响两侧、当场可见。
 *
 * <h2>【命名口径：{@code <域>.<对象>.<结果>}（或 {@code <域>.<对象>}）】</h2>
 * 全小写、点分二到三段：域 = 事件来源块（{@code platform} / {@code system} / {@code comment} /
 * {@code user} / {@code merchant} / {@code goods} / {@code order} / {@code seckill} / {@code report}）。
 * ⚠️ 列宽 {@code varchar(64)}，别写超过这个长度。
 *
 * <h2>【⚠️ 本类只是「登记簿」，**后端不校验它** —— 这是本块最关键的一条口径】</h2>
 * {@code NotificationService.send} **不**把入参 {@code eventType} 与本类比对；未知 code 照样落库。
 * 这样「**加通知类型不改表、不改后端**」才成立：块 2–6 各自开工时，只要在**这里加一条常量**
 * （供写入方与前端引用），表结构、DAO、Service、Controller 一行都不用动。
 * <p>⇒ 代价：库里可能出现本类**没有**的 code。前端映射必须**兜底**（未知 code 原样显示 + 小号
 * chip，见 {@code src/config/notify-events.js} 的 {@code eventLabel}），不许静默成空白。
 *
 * <h2>【清单按「归属块」排 —— 32 条，覆盖到 C3 全部 8 块】</h2>
 * 块 1 本块就用（2 条）；其余 30 条是**预留登记 + 各自块落地时补的**：各块的触发点**随各自那一笔挂**
 * （同 {@code AuditActions} 的 15 个挂钩点口径），本块只把 code 与 {@code send} 原语先留出来。
 * ⚠️ 所以这个数**每块都在长** —— 块 5 收口时是 31，块 6 commit 2 补了 {@link #COMMENT_ACCEPTED} /
 * {@link #QUESTION_TIMEOUT} 两条到 33；块 6「契约纠偏」一笔再**摘除**块 1 预留的
 * {@code comment.on.user}（留言板被评论）一条，回到 32。改这个数时**同笔**改
 * {@code src/config/notify-events.js} 的 {@code EVENT_LABELS} / {@code EVENT_OPTIONS} 两处
 * （那张表文件头写的「三处」里的另两处）。
 * 硬约束来自 {@code docs/agent/c3-modules.md} §9.2：通知必须能被「被评论 / 被回复 / 被 @」三类事件触发
 * —— 块 6 基准文档 {@code docs/agent/context-packs/块6参考.md} §二 A1 定稿的 6 个 code 即
 * {@link #COMMENT_ON_POST} / {@link #COMMENT_REPLY} / {@link #COMMENT_MENTION} /
 * {@link #COMMENT_ACCEPTED} / {@link #QUESTION_TIMEOUT} / {@link #REPORT_HANDLED}。
 * （原预留的 {@code comment.on.user}「留言板被评论」**不在**该 6 code 内，2026-10-13 按用户裁定摘除；
 *  §9.2 的「用户留言」对象本块无前端面，其「被评论」语义由 {@link #COMMENT_ON_POST} 的商品侧覆盖。）
 */
public final class NotificationEvents {

    // ==================== 块 1（本块）：平台自身 ====================

    /** 平台公告（对全站）—— 管理端发送页「全站公告」。 */
    public static final String PLATFORM_ANNOUNCEMENT = "platform.announcement";

    /** 系统通知（对用户 / 商家群发或定向）—— 管理端发送页的默认事件类型。 */
    public static final String SYSTEM_NOTICE = "system.notice";

    // ==================== 块 6（内容 + 消息块）：评论区三类事件（§9.2 硬约束） ====================
    // ⚠️ 块 1 建通知域时此处预留了 4 条（on.post / on.user / reply / mention）；2026-10-13
    //    块 6「契约纠偏」按用户裁定**摘除** `comment.on.user`（留言板被评论）—— 块 6 基准文档
    //    `块6参考.md` §二 A1 定稿的评论区事件恰为 3 条（on.post / reply / mention），不含 on.user。
    //    摘除时全仓零调用点，故只删常量 + 前端两处标签，无行为回归。

    /** 被评论 —— 商品被评论，触达该商品商家。 */
    public static final String COMMENT_ON_POST = "comment.on.post";

    /** 被回复 —— 评论被回复（楼中楼 / 问答式共用同一 {@code parentId}），触达**被回复那条**的作者。 */
    public static final String COMMENT_REPLY = "comment.reply";

    /** 被 @ —— 由 {@code comment_mention} 表记录的提及关系触发。 */
    public static final String COMMENT_MENTION = "comment.mention";

    /** 举报处理结果 —— 触达举报人。 */
    public static final String REPORT_HANDLED = "report.handled";

    // ==================== 块 2（用户块）：用户与商家治理 ====================

    public static final String USER_AUDIT_PASSED = "user.audit.passed";

    public static final String USER_AUDIT_REJECTED = "user.audit.rejected";

    /** 用户封禁。 */
    public static final String USER_BANNED = "user.banned";

    /** 用户解封。 */
    public static final String USER_UNBANNED = "user.unbanned";

    /** 商家入驻审核通过。 */
    public static final String MERCHANT_AUDIT_PASSED = "merchant.audit.passed";

    /** 商家入驻审核驳回。 */
    public static final String MERCHANT_AUDIT_REJECTED = "merchant.audit.rejected";

    // ==================== 块 3（商品块）：商品与类目治理 ====================

    public static final String GOODS_AUDIT_PASSED = "goods.audit.passed";

    public static final String GOODS_AUDIT_REJECTED = "goods.audit.rejected";

    /** 违规商品下架 / 删除。 */
    public static final String GOODS_VIOLATION_REMOVED = "goods.violation.removed";

    /** 低库存提醒 —— 触达商家（同一 SKU 每日最多一条，批四第二批）。 */
    public static final String GOODS_STOCK_LOW = "goods.stock.low";

    // ==================== 块 4（订单块）：订单治理 ====================

    /** 管理员介入订单。 */
    public static final String ORDER_INTERVENED = "order.intervened";

    /** 订单通知相关方（站内信 / 系统消息）。 */
    public static final String ORDER_NOTICE = "order.notice";

    // ==================== 块 5（营销块）：秒杀双向制（§9.1） ====================

    /** 管理端**邀请**商家上秒杀（§9.1 路径 A）。 */
    public static final String SECKILL_INVITED = "seckill.invited";

    /** 商家秒杀申请经管理端审核**通过**（§9.1 路径 B）。 */
    public static final String SECKILL_APPLICATION_APPROVED = "seckill.application.approved";

    /** 商家秒杀申请经管理端审核**驳回**（§9.1 路径 B）。 */
    public static final String SECKILL_APPLICATION_REJECTED = "seckill.application.rejected";

    /**
     * **未领的**临时券即将过期 —— 由 {@code CouponExpiryJob} 扫描后发给目标用户。
     *
     * <p>⚠️ 只管**未领**那一侧：**已领的券快过期不发系统通知**（用户 2026-10-11 裁定 #4 ②），
     * 那一侧走**券包红点 + 排序**（无 code、无通知行）。故本 code 的语义是
     * 「你**还没领**的这张券要过期了」—— 别拿它去做「你的券要过期了」。
     */
    public static final String COUPON_EXPIRING_UNCLAIMED = "coupon.expiring.unclaimed";

    // ==================== 块 5（营销块）：国补双向制（commit 3） ====================

    /**
     * 平台**提名**某件商品参加国补 → 触达**商家**（{@code ToType='merchant'}、
     * {@code ToId=goods.MerchantId}），商家在消息里同意 / 拒绝。
     *
     * <p>⚠️ 这条通知**是那一行 {@code subsidy_goods(pending)} 的收件箱面**：文案与
     * {@code GoodsName} / {@code RuleName} 一起给出，商家据此决定要不要参加 ——
     * 与 {@code goods.apply.submitted} 恰好**反向**（那条是商家 → 管理端，本条的原始动作是
     * 管理端 → 商家，回执见 {@link #SUBSIDY_CONFIRMED}）。
     */
    public static final String SUBSIDY_NOMINATED = "subsidy.nominated";

    /**
     * 商家**确认**参加国补 → 触达**管理端共享收件箱**（{@code ToType='admin'}、{@code ToId='*'}，
     * 同 {@code MerchantApplicationController.ADMIN_INBOX_ID} 的口径）。
     */
    public static final String SUBSIDY_CONFIRMED = "subsidy.confirmed";

    /**
     * 商家**拒绝**参加国补（附理由）→ 同样触达管理端共享收件箱。
     *
     * <p>⚠️ 这一条是**本笔在已批的两条之外补的第三条**（A3-① 批的是
     * {@link #SUBSIDY_NOMINATED} / {@link #SUBSIDY_CONFIRMED}）：拒绝与同意是**两封不同的信**
     * （一封要人跟进、一封只是回执），挤进同一个 code 会让管理端收件箱里「已确认」与「已拒绝」
     * 长得一模一样。加一条**常量**不改表、不改后端（见类注释那条口径），故按自裁报备处理。
     */
    public static final String SUBSIDY_REJECTED = "subsidy.rejected";

    // ==================== 块 2（用户块）：本块新增 3 条（块 1 已预设的 audit/banned 6 条见上） ====================

    /**
     * 商家提交入驻申请 → **触达管理端**（共享收件箱 {@code ToType='admin', ToId='*'}，
     * A 档裁决 4「扩通道」）。
     */
    public static final String MERCHANT_APPLY_SUBMITTED = "merchant.apply.submitted";

    /** 管理员排定降级 → 触达**商家**（通知期开始，1 天）。 */
    public static final String MERCHANT_DOWNGRADE_SCHEDULED = "merchant.downgrade.scheduled";

    /** 降级**已执行**（冻结生效）→ 触达商家；正文带 {@code DowngradeReason}。 */
    public static final String MERCHANT_DOWNGRADE_APPLIED = "merchant.downgrade.applied";

    // ==================== 块 3（商品块）：本块新增 1 条（预留的 3 条见上 §块 3）====================

    /**
     * 商家提交商品上架申请（提审）→ **触达管理端**（共享收件箱 {@code ToType='admin', ToId='*'}，
     * 同 {@link #MERCHANT_APPLY_SUBMITTED} 的「扩通道」口径）。
     *
     * <p>⚠️ 与预留的 {@link #GOODS_AUDIT_PASSED} / {@link #GOODS_AUDIT_REJECTED} 是**一对**：
     * 本条是「商家 → 管理员」的去向，那两条是「管理员 → 商家」的回执。
     */
    public static final String GOODS_APPLY_SUBMITTED = "goods.apply.submitted";

    // ==================== 块 5（营销块）：秒杀双向制的「商家 → 管理端」两条（commit 4）====================
    // ⚠️ 上面那三条 `seckill.invited` / `seckill.application.approved|rejected` 是**管理员 → 商家**的回执，
    //    下面这两条是**反方向**（商家 → 管理端共享收件箱 `ToType='admin', ToId='*'`）——
    //    与国补那一对（`subsidy.nominated` 去 / `subsidy.confirmed|rejected` 回）同一个对称结构。
    //    ⚠️ 别把方向搞反：写反了通知照样入库，只是**收件箱错**（管理员看不见、商家自己收到自己的回执）。

    /**
     * 商家**应答**秒杀邀请（同意 / 拒绝）→ 触达**管理端共享收件箱**。
     *
     * <p>{@code docs/agent/c3-modules.md} §9.1 把它写成一条（不是拆两条）：与
     * {@link #SUBSIDY_CONFIRMED} / {@link #SUBSIDY_REJECTED} 拆开的情形不同 ——
     * 邀请这条路径上，管理端**本来就知道自己邀请了谁**，回执只需要「答没答、答的什么」；
     * 具体结果写在标题里（「商家拒绝秒杀邀请：<商品>」），不必再多一个 code。
     */
    public static final String SECKILL_RESPONDED = "seckill.responded";

    /**
     * 商家**提交**秒杀申请（报名）→ 触达**管理端共享收件箱**，等管理端审核。
     *
     * <p>⚠️ 与预留的 {@link #SECKILL_APPLICATION_APPROVED} / {@link #SECKILL_APPLICATION_REJECTED}
     * 是**一对**（形态逐字照 {@link #GOODS_APPLY_SUBMITTED} 与那两条 audit 的关系）：
     * 本条是「商家 → 管理员」的去向，那两条是「管理员 → 商家」的裁决回执。
     */
    public static final String SECKILL_APPLICATION_SUBMITTED = "seckill.application.submitted";

    // ==================== 块 6（内容+消息块）· commit 2「问答」：本块补 2 条 ====================
    // ⚠️ 上面「块 6」那一节的 4 条（comment.on.post / comment.reply /
    //    comment.mention / report.handled）是**块 1 建通知域时预留**的；下面 2 条是**问答**这一笔
    //    才第一次有人发的 —— 按本类类注释那条口径「各块的触发点**随各自那一笔挂**」，
    //    它们跟着**第一个调用点**（`CommentService.accept` / `QuestionTimeoutJob`）一起落，
    //    而不是先留一个常量在那儿空等。
    //    ⇒ 本节因此比 `_work/c3-block6-landing-plan.md` §四 写的「commit 3 补 2 code」**提前了一笔**，
    //      理由与记账见该笔 commit message（B 档自裁 + 报备）。
    //    ⚠️ 两条都**不是**「管理员手发」的通知（它们是后台动作的产物），但按 `notify-events.js`
    //      的既有口径（`coupon.expiring.unclaimed` 同款）**三处仍然各补一条**：那张下拉是
    //      「code 的全集」，不是「管理员能发的子集」。

    /**
     * 提问被**采纳** → 触达**被采纳那条回答的作者**（{@code ToType} = {@code customer} /
     * {@code merchant}，随回答者的 {@code AuthorRole}）。
     *
     * <p>收件人由 {@code CommentService.accept} 从**被采纳的那行回复**上取，不是提问者自己
     * —— 这正是这条通知存在的原因：回答者并不知道自己的答案被选中了。
     */
    public static final String COMMENT_ACCEPTED = "comment.accepted";

    /**
     * 提问**超过 30 分钟仍未解决** → 触达**管理端共享收件箱**（{@code ToType='admin'}、
     * {@code ToId='*'}，同 {@link #MERCHANT_APPLY_SUBMITTED} 的口径），由
     * {@code QuestionTimeoutJob} 扫描后发出。
     *
     * <p>⚠️ 这是「**待办**」而不是「结果」：管理端据它去问答 tab 人工介入（方案 §4.2 的
     * 「30 分钟未解决 → Job 报管理端」）。故它**必须**带 {@code TargetType}/{@code TargetId}
     * 指回那条提问 —— {@code QuestionTimeoutJob} 的幂等判据正是
     * {@code existsDelivery(收件人, 本 code, "comment", 提问Id)}。
     */
    public static final String QUESTION_TIMEOUT = "question.timeout";

    private NotificationEvents() {
    }
}
