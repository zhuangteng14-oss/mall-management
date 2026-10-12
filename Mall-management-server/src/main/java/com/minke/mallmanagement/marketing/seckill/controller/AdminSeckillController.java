package com.minke.mallmanagement.marketing.seckill.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.marketing.seckill.service.SeckillService;
import com.minke.mallmanagement.notification.NotificationEvents;
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
import java.util.UUID;

/**
 * 管理端「营销运营 · 秒杀」Web 层（C3 块 5 · commit 4）—— 6 个端点：
 * 邀请列表 / 邀请 / 申请列表 / 审核申请 / 活动列表 / 停用活动。
 *
 * <h2>【本类是秒杀**双向制**里管理端的那一半】</h2>
 * <ul>
 *   <li><b>路径 A</b>：{@code /invite} 发起 → 商家在**自己的**消息中心应答
 *       （{@code SeckillController./seckill/respond}）。</li>
 *   <li><b>路径 B</b>：商家在**秒杀页 / 商家端**申请（{@code /seckill/apply}）→ 本类 {@code /review} 裁决。</li>
 * </ul>
 * ⚠️ **没有「管理端单向指定」这条路径**（`docs/agent/c3-modules.md` §9.1 硬口径）：
 * 那样商家会在不知情的情况下被挂上秒杀价。
 *
 * <h2>【⚠️ 不给 {@code /admin/**} 设角色闸（TD-18）】</h2>
 * 同 {@code AdminCouponController} / {@code AdminSubsidyController}：管理员 cookie 铸不出 token
 * ⇒ 加「须 role=3」会让**默认 profile 上管理员自己 401**，而放进白名单是放宽。
 * ⚠️ **别「顺手」补一个 {@code requireAdmin}**。
 *
 * <h2>【参数全 {@code required=false} + 手判，类级不得 {@code @Validated}】</h2>
 * 同前几笔：约束异常会逃逸成 400 空体，且撞 {@code xxxControllerIsNotAopProxied} 断言族。
 * 数字参数一律收 {@code String} 再交给 {@code SeckillService} 解析。
 *
 * <h2>【三个写端点：留痕 + 两个方向的通知】</h2>
 * 邀请 / 审核 / 停用都改库 ⇒ 各落一条 {@code admin_audit}（{@code TargetId} 用**业务串**，
 * 见 {@code AuditActions.SECKILL_*}）。其中**邀请与审核还要通知商家** —— 那是双向制里
 * 「管理端开口、商家得能听见」的那一半；少了一条，{@code seckill_invitation} 会永远停在
 * {@code invited}、{@code seckill_application} 会永远停在 {@code submitted}。
 * <p>通知由 **Controller** 编排（业务成 → 留痕 → 通知），同
 * {@code AdminSubsidyController.nominate} 的既有分工：{@code NotificationService.send} 自己 fail-soft，
 * 发失败**不**把业务翻成失败。
 * <p>⚠️ **停用不发通知**（自裁，见下）：与券 / 国补的停用·启用同口径 —— 那是平台治理动作，
 * 商家在自己的活动列表里**看得到** {@code Status='stopped'}，再寄一封就是同一件事说两遍。
 */
@RestController
@RequestMapping("/admin/marketing/seckill")
public class AdminSeckillController {

    /** 通知的收件人类型：商家（与 {@code NotificationService.TO_TYPE_MERCHANT} 同值）。 */
    private static final String TO_TYPE_MERCHANT = "merchant";

    private final SeckillService seckillService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AdminSeckillController(SeckillService seckillService, AuditService auditService,
                                  NotificationService notificationService) {
        this.seckillService = seckillService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /** 邀请列表。{@code Status} 走白名单（未知值报错，不静默当成「不筛」）。 */
    @PostMapping("/invitations")
    public Result<Map<String, Object>> invitations(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "MerchantAccount", required = false) String MerchantAccount,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(seckillService.invitations(Status, MerchantAccount, limit));
    }

    /**
     * 邀请商家上秒杀 —— 本笔会触达商家的两个写端点之一。
     *
     * <p>⚠️ {@code TargetId} = **业务串 {@code SKI-…}**（不是自增 Id）：通知与审计两处
     * 用的是同一串，两边对得上（doc §八 的口径）。
     */
    @PostMapping("/invite")
    public Result<Map<String, Object>> invite(
            @RequestParam(value = "MerchantAccount", required = false) String MerchantAccount,
            @RequestParam(value = "GoodsId", required = false) Integer GoodsId,
            @RequestParam(value = "SeckillPrice", required = false) String SeckillPrice,
            @RequestParam(value = "Quantity", required = false) String Quantity,
            @RequestParam(value = "ApplyEndAt", required = false) String ApplyEndAt,
            @RequestParam(value = "StartAt", required = false) String StartAt,
            @RequestParam(value = "EndAt", required = false) String EndAt,
            HttpServletRequest request
    ) {
        Map<String, Object> result = seckillService.invite(MerchantAccount, GoodsId, SeckillPrice,
                Quantity, ApplyEndAt, StartAt, EndAt);
        if (Boolean.TRUE.equals(result.get("success"))) {
            String invitationId = str(result.get("InvitationId"));
            Map<String, Object> after = new HashMap<>();
            after.put("MerchantAccount", MerchantAccount);
            after.put("GoodsId", GoodsId);
            after.put("SeckillPrice", result.get("SeckillPrice"));
            after.put("OriginalPrice", result.get("OriginalPrice"));
            after.put("Quantity", Quantity);
            after.put("ApplyEndAt", ApplyEndAt);
            after.put("StartAt", StartAt);
            after.put("EndAt", EndAt);
            auditService.record(AuthSession.identify(request), AuditActions.SECKILL_INVITE,
                    "seckill", invitationId, null, after);

            // ⚠️ 收件人 = 商家业务号；空号 ⇒ 不发（同 `AdminSubsidyController.nominate` 的兜底）。
            String merchant = str(result.get("MerchantAccount"));
            if (merchant != null && !merchant.isBlank()) {
                String goodName = str(result.get("GoodName"));
                // ⚠️ TargetId = **seckill_invitation.Id** —— 商家消息中心那两个按钮
                //    （同意 / 拒绝）要拿它去打 `/marketing/seckill/respond`。
                notificationService.send(UUID.randomUUID().toString(), merchant, TO_TYPE_MERCHANT,
                        NotificationEvents.SECKILL_INVITED,
                        "秒杀邀请：" + (goodName == null ? "商品" : goodName),
                        "平台邀请该商品参加限时秒杀（秒杀价 ¥" + result.get("SeckillPrice")
                                + "，原价 ¥" + result.get("OriginalPrice") + "）。"
                                + "请在消息中心同意或拒绝。",
                        "seckill_invitation", str(result.get("Id")));
            }
        }
        return Result.of(result);
    }

    /** 申请列表（管理端看全量：谁报名了、审没审）。 */
    @PostMapping("/applications")
    public Result<Map<String, Object>> applications(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "MerchantAccount", required = false) String MerchantAccount,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(seckillService.applications(Status, MerchantAccount, limit));
    }

    /**
     * 审核商家的秒杀申请（通过 / 驳回）。
     *
     * @param Approve {@code "true"} = 通过；其余（含缺省）= 驳回 —— ⚠️ 与 {@code /subsidy/confirm}
     *                同口径：**没表态绝不能等同同意**。驳回时 {@code Reason} 必填（Service 判）。
     * @param Name    活动名；留空 ⇒ 回落到商品名（流程表没有 {@code Name} 列，见 {@code SeckillService}）
     */
    @PostMapping("/review")
    public Result<Map<String, Object>> review(
            @RequestParam(value = "Id", required = false) Long Id,
            @RequestParam(value = "Approve", required = false) String Approve,
            @RequestParam(value = "Reason", required = false) String Reason,
            @RequestParam(value = "Name", required = false) String Name,
            @RequestParam(value = "ApplyEndAt", required = false) String ApplyEndAt,
            @RequestParam(value = "StartAt", required = false) String StartAt,
            @RequestParam(value = "EndAt", required = false) String EndAt,
            HttpServletRequest request
    ) {
        boolean approve = "true".equalsIgnoreCase(Approve == null ? "" : Approve.trim());
        Map<String, Object> result = seckillService.review(Id, approve, Reason, Name,
                ApplyEndAt, StartAt, EndAt);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Approve", approve);
            after.put("Name", result.get("Name"));
            after.put("ApplyEndAt", ApplyEndAt);
            after.put("StartAt", StartAt);
            after.put("EndAt", EndAt);
            auditService.record(AuthSession.identify(request), AuditActions.SECKILL_REVIEW,
                    "seckill", str(result.get("ApplicationId")), null, after);

            String merchant = str(result.get("MerchantAccount"));
            if (merchant != null && !merchant.isBlank()) {
                String goodName = str(result.get("GoodName"));
                String reasonText = Reason == null || Reason.isBlank() ? "" : "（理由：" + Reason.trim() + "）";
                notificationService.send(UUID.randomUUID().toString(), merchant, TO_TYPE_MERCHANT,
                        approve ? NotificationEvents.SECKILL_APPLICATION_APPROVED
                                : NotificationEvents.SECKILL_APPLICATION_REJECTED,
                        (approve ? "秒杀申请已通过：" : "秒杀申请被驳回：") + (goodName == null ? "商品" : goodName),
                        approve
                                ? "该商品的秒杀申请已通过，活动已成立（" + str(result.get("ActivityId")) + "）。"
                                : "该商品的秒杀申请被驳回" + reasonText + "。",
                        "seckill_application", str(result.get("Id")));
            }
        }
        return Result.of(result);
    }

    /** 活动列表（**已成立**的那些：哪条路径来的、什么阶段、卖了多少）。 */
    @PostMapping("/activities")
    public Result<Map<String, Object>> activities(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "MerchantAccount", required = false) String MerchantAccount,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(seckillService.activities(Status, MerchantAccount, limit));
    }

    /**
     * 停用一场已成立的秒杀活动（逻辑废弃）。
     *
     * <p>⚠️ 入参是 **{@code ActivityId} 业务串**（不是自增 Id）—— 管理端列表里两列都有，
     * 但业务串是**跨表通用**的那一把钥匙（用户端拿到的也是它，见 {@code /marketing/seckill/live}）。
     */
    @PostMapping("/stop")
    public Result<Map<String, Object>> stop(
            @RequestParam(value = "ActivityId", required = false) String ActivityId,
            HttpServletRequest request
    ) {
        Map<String, Object> result = seckillService.stop(ActivityId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Status", "stopped");
            // `BeforeJson` 用 Service 回带的**改前快照**（同券 / 国补的 statusChange 理由）。
            auditService.record(AuthSession.identify(request), AuditActions.SECKILL_STOP,
                    "seckill", str(result.get("ActivityId")), result.get("before"), after);
        }
        return Result.of(result);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
