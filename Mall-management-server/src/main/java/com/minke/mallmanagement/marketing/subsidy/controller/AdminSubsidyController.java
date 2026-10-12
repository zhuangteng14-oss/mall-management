package com.minke.mallmanagement.marketing.subsidy.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.marketing.subsidy.service.SubsidyService;
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
 * 管理端「营销运营 · 国补」Web 层（C3 块 5 · commit 3）—— 6 个端点：
 * 规则列表 / 建规则 / 停用 / 启用 / 提名商品 / 提名列表。
 *
 * <h2>【⚠️ 不给 {@code /admin/**} 设角色闸（TD-18）】</h2>
 * 同 {@code AdminCouponController} / {@code AdminTemplateController}：管理员 cookie 铸不出 token
 * ⇒ 加「须 role=3」会让**默认 profile 上管理员自己 401**，而放进白名单是放宽。
 * ⚠️ **别「顺手」补一个 {@code requireAdmin}**。
 *
 * <h2>【参数全 {@code required=false} + 手判，不加 Bean Validation 注解】</h2>
 * 同前几笔：约束异常会逃逸成 400 空体，且类级 {@code @Validated} 撞
 * {@code xxxControllerIsNotAopProxied} 断言族。数字参数一律收 {@code String} 再交给
 * {@code SubsidyService} 解析。
 *
 * <h2>【四个写端点挂审计 + 提名那条挂通知】</h2>
 * 建规则 / 停用 / 启用 / 提名都改库 ⇒ 各落一条 {@code admin_audit}。
 * ⚠️ **提名还要给商家发通知** —— 那是本域「双向制」的**一整个方向**（没有它，商家永远不知道
 * 自己被提名了，{@code subsidy_goods} 会永远停在 {@code pending}）。发通知由 **Controller**
 * 编排，同 {@code AdminCatalogController.notifyMerchant} / {@code GoodsController} 的既有分工：
 * 业务成功之后才发、发失败**不**把业务翻成失败（{@code NotificationService.send} 自己 fail-soft）。
 * <p>⚠️ {@code TargetId} 一律：建规则传 {@code RuleId}、提名传 {@code RuleId + ":" + GoodsId}
 * —— {@code admin_audit.TargetId} 是 NOT NULL，传 null 会在留痕处炸掉整条链路（TD-20）。
 */
@RestController
@RequestMapping("/admin/marketing/subsidy")
public class AdminSubsidyController {

    /** 通知的收件人类型：商家（与 {@code NotificationService.TO_TYPE_MERCHANT} 同值）。 */
    private static final String TO_TYPE_MERCHANT = "merchant";

    private final SubsidyService subsidyService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AdminSubsidyController(SubsidyService subsidyService, AuditService auditService,
                                  NotificationService notificationService) {
        this.subsidyService = subsidyService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /** 规则列表。{@code Status} 走白名单（未知值报错，不静默当成「不筛」）。 */
    @PostMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(subsidyService.rules(Status, limit));
    }

    /** 建规则。成功回带 {@code RuleId}（前端要据它去提名 / 停用）。 */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(
            @RequestParam(value = "Name", required = false) String Name,
            @RequestParam(value = "CategoryMatch", required = false) String CategoryMatch,
            @RequestParam(value = "Ratio", required = false) String Ratio,
            @RequestParam(value = "Cap", required = false) String Cap,
            @RequestParam(value = "StartAt", required = false) String StartAt,
            @RequestParam(value = "EndAt", required = false) String EndAt,
            HttpServletRequest request
    ) {
        Map<String, Object> result = subsidyService.ruleCreate(Name, CategoryMatch, Ratio, Cap, StartAt, EndAt);
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.SUBSIDY_RULE_CREATE,
                    "subsidy", String.valueOf(result.get("RuleId")), null,
                    snapshot(Name, CategoryMatch, Ratio, Cap, StartAt, EndAt));
        }
        return Result.of(result);
    }

    /** 停用（逻辑废弃）。 */
    @PostMapping("/stop")
    public Result<Map<String, Object>> stop(
            @RequestParam(value = "RuleId", required = false) String RuleId,
            HttpServletRequest request
    ) {
        return Result.of(statusChange(subsidyService.ruleStop(RuleId), RuleId, "stopped", request));
    }

    /** 重新启用。 */
    @PostMapping("/activate")
    public Result<Map<String, Object>> activate(
            @RequestParam(value = "RuleId", required = false) String RuleId,
            HttpServletRequest request
    ) {
        return Result.of(statusChange(subsidyService.ruleActivate(RuleId), RuleId, "active", request));
    }

    /**
     * 提名一件商品参加国补 —— 本域**唯一**会触达商家的写端点。
     *
     * <p>⚠️ 通知只在业务成功后发，且发在**留痕之后**：先记「管理员提了名」，再告诉商家。
     * 反过来（先通知后留痕）会出现「商家收到了、审计里查不到是谁提的」。
     */
    @PostMapping("/nominate")
    public Result<Map<String, Object>> nominate(
            @RequestParam(value = "RuleId", required = false) String RuleId,
            @RequestParam(value = "GoodsId", required = false) Integer GoodsId,
            HttpServletRequest request
    ) {
        Map<String, Object> result = subsidyService.nominate(RuleId, GoodsId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            String targetId = RuleId == null ? String.valueOf(GoodsId) : RuleId.trim() + ":" + GoodsId;
            Map<String, Object> after = new HashMap<>();
            after.put("RuleId", RuleId);
            after.put("GoodsId", GoodsId);
            after.put("MerchantId", result.get("MerchantId"));
            auditService.record(AuthSession.identify(request), AuditActions.SUBSIDY_NOMINATE,
                    "subsidy", targetId, null, after);

            // ⚠️ 收件人 = 商家业务号（`goods.MerchantId`）。空号 ⇒ 不发（同 `notifyMerchant` 的兜底）。
            String merchantId = str(result.get("MerchantId"));
            if (merchantId != null && !merchantId.isBlank()) {
                String ruleName = str(result.get("RuleName"));
                String goodName = str(result.get("GoodName"));
                // ⚠️ TargetId = **subsidy_goods.Id**（不是 GoodsId）—— 消息中心那两个按钮要拿它去
                //    打 `/marketing/subsidy/confirm`。TargetType 与它同指一张表，两者必须配套。
                notificationService.send(UUID.randomUUID().toString(), merchantId, TO_TYPE_MERCHANT,
                        NotificationEvents.SUBSIDY_NOMINATED,
                        "国补邀请：" + (goodName == null ? "商品" : goodName),
                        "平台邀请该商品参加国家补贴活动"
                                + (ruleName == null || ruleName.isBlank() ? "" : "（规则：" + ruleName + "）")
                                + "。请在消息中心同意或拒绝。",
                        "subsidy_goods", str(result.get("Id")));
            }
        }
        return Result.of(result);
    }

    /** 提名列表（管理端看全量：谁被提名了、商家点头没有）。 */
    @PostMapping("/nominations")
    public Result<Map<String, Object>> nominations(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(subsidyService.nominations(Status, MerchantId, limit));
    }

    // ==================== 内部 ====================

    /**
     * 停用 / 启用的共用腿：成功才留痕，{@code BeforeJson} 用 Service 回带的**改前快照** ——
     * 同 {@code AdminCouponController.statusChange} 的理由（那里没有状态流水表，而
     * {@code before} 里的 {@code Status} 恰好就是这次动作唯一改变的东西）。
     */
    private Map<String, Object> statusChange(Map<String, Object> result, String ruleId,
                                             String target, HttpServletRequest request) {
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Status", target);
            auditService.record(AuthSession.identify(request),
                    "stopped".equals(target) ? AuditActions.SUBSIDY_RULE_STOP : AuditActions.SUBSIDY_RULE_ACTIVATE,
                    "subsidy", ruleId, result.get("before"), after);
        }
        return result;
    }

    /** 建规则的 {@code AfterJson}：把**输入**原样收进去（新建没有「改前态」）。 */
    private static Map<String, Object> snapshot(String name, String categoryMatch, String ratio,
                                                String cap, String startAt, String endAt) {
        Map<String, Object> after = new HashMap<>();
        after.put("Name", name);
        after.put("CategoryMatch", categoryMatch);
        after.put("Ratio", ratio);
        after.put("Cap", cap);
        after.put("StartAt", startAt);
        after.put("EndAt", endAt);
        return after;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
