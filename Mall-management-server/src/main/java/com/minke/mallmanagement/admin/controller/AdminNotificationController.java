package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.admin.service.NotificationBroadcastService;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * admin 域的**通知发送面**（C3 块 1 · I2）—— 4 个端点：发送 / 通知历史 / 模板列表 / 模板保存。
 *
 * <p>对应前端侧边栏「业务域 → 系统通知」（前端路由 {@code /admin/business/notify}；本类是后端前缀
 * {@code /admin/notify}，两者不同名也不该强求同名 —— 同 {@code AdminAuditController} 的理由）。
 * 全 POST，随全仓。
 *
 * <h2>【依赖两个 Service（+ 审计），各自管一半】</h2>
 * <ul>
 *   <li>{@link NotificationBroadcastService} —— **发送工作流**（受众解析 + 扇出 + 批次），admin 专属；</li>
 *   <li>{@link NotificationService} —— **通知域的业务动作**（历史 / 模板），与三端收件箱同一个类
 *       —— 因为它们读写同两张表；「管理端读历史」不过是同一条时间线的另一种归组方式。</li>
 *   <li>{@link AuditService} —— **只服务 {@code /send} 一个端点**（块 1 · I2 的审计挂钩，
 *       用户 2026-10-09 裁决「发送公告是典型平台动作，必须留痕」）。⚠️ 只在**发送**上挂，
 *       历史 / 模板两个端点不挂：读不产生可审的动作，模板 CRUD 是配置而非平台动作
 *       （⚠️ 这一条是**推断**，若你要模板也留痕，说一声，加一条常量即可）。</li>
 * </ul>
 *
 * <h2>⚠️【本批**不**给 {@code /admin/**} 设服务端门禁 —— 已知接缝，登记不修】</h2>
 * 与 {@code AdminAuditController} **逐字同因**，见该类注释的三条（a3 闸关 / 管理员铸不出 token
 * TD-18 / 加角色判据会让默认 profile 的管理员自己 401）。⚠️ 尤其**别在本类「顺手」加一个
 * {@code requireAdmin}** —— 它会让默认 profile（闸开）上的管理员进不来。
 *
 * <h2>【参数一律 {@code required=false} + Service 内手判】</h2>
 * 同 {@code NotificationController} 的两条理由（{@code GlobalExceptionHandler} 只白名单
 * Message/Goods 两个类 ⇒ 约束异常逃逸成 400 空体；类级 {@code @Validated} 会撞
 * {@code xxxControllerIsNotAopProxied} 断言族）。
 */
@RestController
@RequestMapping("/admin/notify")
public class AdminNotificationController {

    private final NotificationBroadcastService broadcastService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public AdminNotificationController(NotificationBroadcastService broadcastService,
                                       NotificationService notificationService,
                                       AuditService auditService) {
        this.broadcastService = broadcastService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * 发送一条通知。
     *
     * <p>⚠️ {@code audience} 与 {@code toId} **必须给一个**（都空 ⇒ <b>报错</b>，不默认全站）——
     * 理由见 {@link NotificationBroadcastService} 的类注释。
     *
     * <p>⚠️ {@code eventType} **不校验白名单**：不传 ⇒ 默认 {@code system.notice}；
     * 传未知 code 也照落（「加类型不改表」的兑现点）。
     *
     * <p>返回体在 {@code {success, sent, batchId}} 之外还带 **{@code eventType}**（生效值，
     * 2026-10-09 加）—— 供本方法的审计挂钩写「实际用了哪个 code」用，见下。
     */
    @PostMapping("/send")
    public Result<Map<String, Object>> send(
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "audience", required = false) String audience,
            @RequestParam(value = "toId", required = false) String toId,
            @RequestParam(value = "toType", required = false) String toType,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) String targetId,
            HttpServletRequest request) {
        Map<String, Object> result =
                broadcastService.send(title, body, eventType, audience, toId, toType, targetType, targetId);

        // ═══ C3 块 1 · I2：审计挂钩（全仓**第二个**挂钩点，形状照 GoodsController.goodsUp 的四步）═══
        // 用户 2026-10-09 裁决：发送公告 / 群发是**典型平台动作**，必须留痕。
        // （1）**无「改前」态** —— 发送是**新增类**动作 ⇒ before 传 null（建表注释：「新增类动作（无改前态）为 NULL」）。
        // （2）**只有 success=true 才记** —— 缺 title / 缺 body / 缺受众或 toType / 未知受众或 toType
        //     这几条早退路径一条也没投出去，无「动作」可记（同 goodsUp「图空/落盘失败不记」的判据）。
        // （3）**TargetId 用 batchId**（UUID），不用 notification.Id —— 本表**跨全量重导保留**，
        //     而 notification.Id 会随重导变（同 goodsUp 用 MerchantId:ProductCode 的理由）。
        // （4）**正文不抄进审计** —— AfterJson 只回答「谁、何时、发了什么性质的一条/一批、几个人收到」；
        //     被投递的正文本身在 `notification` 行上，用 batchId 一 join 就到。审计做**索引**，正文留域表。
        //     ⚠️ title 抄进去（短，≤128），因为它常常就是「这次公告叫什么」的唯一线索。
        // ⚠️ 本类**无事务** ⇒ 记账是一条独立的自动提交 INSERT：投递若没走到这里（早退 / 抛异常），
        //    审计不会留下一条「其实没发生」的行；审计自身失败也只记 ERROR 日志（AUDIT），
        //    **不会**把已成功的投递翻成 500。见 AuditService 类注释「为什么吞异常」。
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("batchId", result.get("batchId"));
            snapshot.put("eventType", result.get("eventType"));
            snapshot.put("title", title);
            snapshot.put("audience", audience);
            snapshot.put("toId", toId);
            snapshot.put("toType", toType);
            snapshot.put("sent", result.get("sent"));
            auditService.record(AuthSession.identify(request), AuditActions.NOTIFICATION_SEND, "notification",
                    String.valueOf(result.get("batchId")), null, snapshot);
        }
        return Result.of(result);
    }

    /**
     * 通知历史（**按 {@code BatchId} 归组**，不是逐行）—— 一次群发在列表里占一行，
     * 带 {@code recipientCount} / {@code unreadCount}。
     *
     * <p>{@code from} / {@code to} 收 {@code 'YYYY-MM-DD HH:MM:SS'} 文本，与
     * {@code AuditPage.vue} 的 {@code toSqlTime} 同口径（前端给 {@code datetime-local} 补秒）。
     */
    @PostMapping("/history")
    public Result<Map<String, Object>> history(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.of(notificationService.history(eventType, from, to, limit));
    }

    /** 模板列表。块 1 只做**存取** CRUD，不做占位符渲染（见 {@code NotificationService#saveTemplate}）。 */
    @PostMapping("/template/list")
    public Result<Map<String, Object>> templateList() {
        return Result.of(notificationService.listTemplates());
    }

    /** 模板 upsert（按 {@code templateKey} 唯一键，有则改、无则插）。 */
    @PostMapping("/template/save")
    public Result<Map<String, Object>> templateSave(
            @RequestParam(value = "templateKey", required = false) String templateKey,
            @RequestParam(value = "titleTemplate", required = false) String titleTemplate,
            @RequestParam(value = "bodyTemplate", required = false) String bodyTemplate,
            @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.of(notificationService.saveTemplate(templateKey, titleTemplate, bodyTemplate, enabled));
    }
}
