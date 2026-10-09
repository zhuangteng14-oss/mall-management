package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.admin.service.UserGovernanceService;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.merchant.service.MerchantDowngradeService;
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
 * 管理端「用户与商家治理」Web 层（C3 块 2 用户块）—— 8 个端点，四组：
 * <ol>
 *   <li><b>用户治理</b>：{@code /users} · {@code /user/ban} · {@code /user/unban}</li>
 *   <li><b>商家审核</b>：{@code /applications} · {@code /application/audit}</li>
 *   <li><b>降级</b>：{@code /downgrade/schedule} · {@code /downgrade/cancel} · {@code /downgrade/relist}</li>
 * </ol>
 *
 * <h2>【⚠️ 与 {@code AdminAuditController} 同一姿态：**不给 {@code /admin/**} 设角色闸】</h2>
 * TD-18（管理员 cookie 铸不出 token）⇒ 加「须 role=3」的判据会让**默认 profile 上管理员自己也 401**，
 * 而放进白名单是放宽。本类**照旧不动鉴权面**，把这条接缝与 TD-18 绑在一起等铸票路径定案。
 * ⚠️ 别在本类「顺手」补一个 {@code requireAdmin}。
 *
 * <h2>【通知 / 审计一律在本层，业务事务提交之后（四步钩子）】</h2>
 * 每组的写法都一样：**先取 {@code before} 快照 → 调 Service（写库）→ 成功才通知 + 审计**。
 * 理由见 {@code MerchantApplicationService} 与 {@code AuditService} 的类注释：
 * 两者都是 fail-soft、无事务的，放进业务事务里会被回滚一起卷走。
 *
 * <h2>【参数全 {@code required = false} + 手判，不加任何 Bean Validation 注解】</h2>
 * 同 {@code MerchantApplicationController} 的理由（约束异常在本仓会逃逸成 400 空体）。
 */
@RestController
@RequestMapping("/admin/accounts")
public class AdminAccountsController {

    /** {@code user.usertype} 的商家取值（中文魔法串 —— 建表就这么存的）。 */
    private static final String USERTYPE_MERCHANT = "商家";
    private static final String TO_TYPE_MERCHANT = "merchant";
    private static final String TO_TYPE_CUSTOMER = "customer";

    private final UserGovernanceService governanceService;
    private final MerchantApplicationService applicationService;
    private final MerchantDowngradeService downgradeService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public AdminAccountsController(UserGovernanceService governanceService,
                                   MerchantApplicationService applicationService,
                                   MerchantDowngradeService downgradeService,
                                   NotificationService notificationService,
                                   AuditService auditService) {
        this.governanceService = governanceService;
        this.applicationService = applicationService;
        this.downgradeService = downgradeService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    // ==================== 一、用户治理 ====================

    /** 用户列表（含封禁 / 降级状态列）。 */
    @PostMapping("/users")
    public Result<Map<String, Object>> users(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(governanceService.listUsers(keyword, limit));
    }

    /** 封禁（理由必填）。 */
    @PostMapping("/user/ban")
    public Result<Map<String, Object>> ban(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "reason", required = false) String reason,
            HttpServletRequest request
    ) {
        Map<String, Object> before = userRowOrNull(username);
        Map<String, Object> result = governanceService.ban(username, reason);

        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyUser(before, username, NotificationEvents.USER_BANNED,
                    "账号已被封禁", "您的账号已被平台封禁。原因：" + reason + "。如有异议请联系客服。");
            auditService.record(AuthSession.identify(request), AuditActions.USER_BAN,
                    "user", username, before, governanceService.userRow(username).get("user"));
        }
        return Result.of(result);
    }

    /** 解封。 */
    @PostMapping("/user/unban")
    public Result<Map<String, Object>> unban(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request
    ) {
        Map<String, Object> before = userRowOrNull(username);
        Map<String, Object> result = governanceService.unban(username);

        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyUser(before, username, NotificationEvents.USER_UNBANNED,
                    "账号已解封", "您的账号已恢复正常使用。");
            auditService.record(AuthSession.identify(request), AuditActions.USER_UNBAN,
                    "user", username, before, governanceService.userRow(username).get("user"));
        }
        return Result.of(result);
    }

    // ==================== 二、商家审核 ====================

    /** 入驻申请列表（{@code status} 可空 ⇒ 全部；传 {@code pending} 即待审）。 */
    @PostMapping("/applications")
    public Result<Map<String, Object>> applications(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(applicationService.listForAdmin(status, limit));
    }

    /**
     * 审核一条入驻申请。{@code approve=true} 通过 / {@code false} 驳回（驳回需 {@code rejectReason}）。
     *
     * <p>⚠️ {@code outcome=already-handled}（并发下另一个管理员先处理了）**不是失败**，
     * 也**不发通知、不记审计**（什么都没发生）。
     */
    @PostMapping("/application/audit")
    public Result<Map<String, Object>> auditApplication(
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "approve", required = false) Boolean approve,
            @RequestParam(value = "rejectReason", required = false) String rejectReason,
            HttpServletRequest request
    ) {
        boolean pass = Boolean.TRUE.equals(approve);
        String reviewer = reviewerOf(request);
        Map<String, Object> result = applicationService.audit(reviewer, id, pass, rejectReason);

        String outcome = result.get("outcome") == null ? null : result.get("outcome").toString();
        if (outcome != null && !"already-handled".equals(outcome)) {
            String applicant = result.get("username") == null ? null : result.get("username").toString();
            if (pass) {
                notifyApplicant(applicant, NotificationEvents.MERCHANT_AUDIT_PASSED,
                        "入驻申请已通过",
                        "您提交的商家入驻申请已通过审核。现在可以进入商家端管理店铺了。");
            } else {
                notifyApplicant(applicant, NotificationEvents.MERCHANT_AUDIT_REJECTED,
                        "入驻申请未通过",
                        "您提交的商家入驻申请未通过。原因：" + (rejectReason == null ? "（未填写）" : rejectReason));
            }
            Map<String, Object> before = new HashMap<>();
            before.put("Id", id);
            before.put("Status", "pending");
            Map<String, Object> after = new HashMap<>();
            after.put("Id", id);
            after.put("Status", pass ? "approved" : "rejected");
            after.put("outcome", outcome);
            auditService.record(AuthSession.identify(request), AuditActions.MERCHANT_APPLY_AUDIT,
                    "merchant_application", id == null ? null : id.toString(), before, after);
        }
        return Result.of(result);
    }

    // ==================== 三、降级（五阶段的 ① / 取消 / ⑤） ====================

    /** 阶段 ①：排定降级（通知期 1 天）。 */
    @PostMapping("/downgrade/schedule")
    public Result<Map<String, Object>> scheduleDowngrade(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "reason", required = false) String reason,
            HttpServletRequest request
    ) {
        Map<String, Object> before = userRowOrNull(username);
        Map<String, Object> result = downgradeService.schedule(username, reason);

        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyUser(before, username, NotificationEvents.MERCHANT_DOWNGRADE_SCHEDULED,
                    "店铺即将被降级",
                    "您的店铺已被排定降级，将在 1 天后冻结。原因：" + reason
                            + "。24 小时内仍可正常经营，请尽快处理未完成订单。");
            auditService.record(AuthSession.identify(request), AuditActions.MERCHANT_DOWNGRADE_SCHEDULE,
                    "user", username, before, result);
        }
        return Result.of(result);
    }

    /** 取消已排定的降级（尚在通知期内才能取消）。 */
    @PostMapping("/downgrade/cancel")
    public Result<Map<String, Object>> cancelDowngrade(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request
    ) {
        Map<String, Object> before = userRowOrNull(username);
        Map<String, Object> result = downgradeService.cancel(username);

        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.MERCHANT_DOWNGRADE_SCHEDULE,
                    "user", username, before, governanceService.userRow(username).get("user"));
        }
        return Result.of(result);
    }

    /**
     * 阶段 ⑤：**人工确认**重新上架（30 天后重新申请通过后的残留决定，见
     * {@code MerchantDowngradeService.relist}）。
     */
    @PostMapping("/downgrade/relist")
    public Result<Map<String, Object>> relistDowngrade(
            @RequestParam(value = "username", required = false) String username,
            HttpServletRequest request
    ) {
        Map<String, Object> before = userRowOrNull(username);
        Map<String, Object> result = downgradeService.relist(username);

        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.MERCHANT_DOWNGRADE_APPLY,
                    "user", username, before, result);
        }
        return Result.of(result);
    }

    // ==================== 内部 ====================

    /** 取 {@code before} 快照；用户不存在 / 参数空 ⇒ {@code null}（审计允许 before 为 NULL）。 */
    private Map<String, Object> userRowOrNull(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        return governanceService.userRow(username).get("user") instanceof Map<?, ?> m
                ? castMap(m) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** 审核者身份 —— 用**识别**口径（{@code identify}）而不是授权口径，否则 cookie 登录的管理员会被记成匿名。 */
    private static String reviewerOf(HttpServletRequest request) {
        AuthSession.Identity identity = AuthSession.identify(request);
        return identity == null ? null : identity.userId();
    }

    /** 给**当事人**发通知：按其 {@code usertype} 选收件人类型与业务号。拿不到行 ⇒ 不发。 */
    private void notifyUser(Map<String, Object> userRow, String username, String eventType,
                            String title, String content) {
        if (userRow == null) {
            return;
        }
        boolean merchant = USERTYPE_MERCHANT.equals(str(userRow.get("usertype")));
        String toId = str(merchant ? userRow.get("merchantid") : userRow.get("customerid"));
        if (toId == null) {
            return;
        }
        notificationService.send(UUID.randomUUID().toString(), toId,
                merchant ? TO_TYPE_MERCHANT : TO_TYPE_CUSTOMER,
                eventType, title, content, "user", username);
    }

    /** 给申请**当事人**（一定是客户态）发审核结果通知。 */
    private void notifyApplicant(String username, String eventType, String title, String content) {
        if (username == null) {
            return;
        }
        Map<String, Object> row = userRowOrNull(username);
        notifyUser(row, username, eventType, title, content);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
