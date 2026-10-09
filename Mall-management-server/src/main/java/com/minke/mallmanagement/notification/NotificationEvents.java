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
 * <h2>【清单按「归属块」排 —— 21 条，覆盖到 C3 全部 8 块】</h2>
 * 块 1 本块就用（2 条）；其余 19 条是**预留登记**：各块的触发点**随各自那一笔挂**
 * （同 {@code AuditActions} 的 15 个挂钩点口径），本块只把 code 与 {@code send} 原语先留出来。
 * 硬约束来自 {@code docs/agent/c3-modules.md} §9.2：通知必须能被「被评论 / 被回复 / 被 @」三类事件触发
 * —— 对应 {@link #COMMENT_ON_POST} / {@link #COMMENT_ON_USER} / {@link #COMMENT_REPLY} /
 * {@link #COMMENT_MENTION} 四条。
 */
public final class NotificationEvents {

    // ==================== 块 1（本块）：平台自身 ====================

    /** 平台公告（对全站）—— 管理端发送页「全站公告」。 */
    public static final String PLATFORM_ANNOUNCEMENT = "platform.announcement";

    /** 系统通知（对用户 / 商家群发或定向）—— 管理端发送页的默认事件类型。 */
    public static final String SYSTEM_NOTICE = "system.notice";

    // ==================== 块 6（内容 + 消息块）：评论区三类事件（§9.2 硬约束） ====================

    /** 被评论 —— 商品 / 内容被评论，触达当事人。 */
    public static final String COMMENT_ON_POST = "comment.on.post";

    /** 被留言 —— 用户留言板被评论，触达当事人。 */
    public static final String COMMENT_ON_USER = "comment.on.user";

    /** 被回复 —— 评论被回复（楼中楼 / 问答式共用同一 {@code parentId}）。 */
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

    private NotificationEvents() {
    }
}
