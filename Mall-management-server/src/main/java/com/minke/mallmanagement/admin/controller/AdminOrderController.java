package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AdminOrderService;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端「订单治理」Web 层（C3 块 4）—— 4 个端点：列表 / 详情 / 异常监控 / 介入。
 *
 * <h2>【⚠️ 不给 {@code /admin/**} 设角色闸（TD-18）】</h2>
 * 管理员 cookie 铸不出 token ⇒ 加「须 role=3」的判据会让**默认 profile 上管理员自己也 401**，
 * 而放进白名单是放宽。⚠️ **别「顺手」补一个 {@code requireAdmin}**（同 {@code AdminTemplateController}）。
 *
 * <h2>【参数全 {@code required=false} + 手判，不加 Bean Validation 注解】</h2>
 * 同 {@code AdminTemplateController}：约束异常会逃逸成 400 空体，且类级 {@code @Validated}
 * 撞 {@code xxxControllerIsNotAopProxied} 断言族。参数名沿用 PascalCase（{@code OrderId} / {@code Action}…）。
 *
 * <h2>【只有 {@code intervene} 一个写端点 ⇒ 只有它挂审计】</h2>
 * {@code list} / {@code detail} / {@code monitor} 是**读**，不留痕（与既有三个管理端读端点一致）。
 * ⚠️ {@code TargetId} 一律传 {@code OrderId}（业务号）—— {@code admin_audit.TargetId} 是 NOT NULL，
 * 传 null 会在留痕处炸掉整条链路（块 3 在这里丢光过留痕，见 TD-20）。
 * <p>⚠️ {@code BeforeJson} 恒 NULL：介入前的三位**不另查一次库**（那会多一条 SELECT 且与 CAS 之间
 * 出现 TOCTOU —— 记下来的「改前态」可能已经不是改前态了）。真实的改前态在
 * {@code order_state_transition} 的 {@code FromState} 里，那是**在临界区内读的**、不会漂。
 */
@RestController
@RequestMapping("/admin/order")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;
    private final AuditService auditService;
    /** 售后状态机（批四第二批 commit 4）—— 平台侧两条端点直接转发它，**不经过 AdminOrderService**。 */
    private final OrderRefundService refundService;
    private final NotificationService notificationService;

    public AdminOrderController(AdminOrderService adminOrderService, AuditService auditService,
                                OrderRefundService refundService, NotificationService notificationService) {
        this.adminOrderService = adminOrderService;
        this.auditService = auditService;
        this.refundService = refundService;
        this.notificationService = notificationService;
    }

    /** 全量视图。筛选项全可空；{@code status} 走白名单（未知值报错，不静默当成「不筛」）。 */
    @PostMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "CustomerId", required = false) String CustomerId,
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(adminOrderService.list(CustomerId, MerchantId, status, from, to, limit));
    }

    /** 单条详情：订单行 + 状态流转时间线。 */
    @PostMapping("/detail")
    public Result<Map<String, Object>> detail(@RequestParam(value = "OrderId", required = false) String OrderId) {
        return Result.of(adminOrderService.detail(OrderId));
    }

    /** 异常监控（**只读识别**：超时 / 非法态 / 未完成）。阈值随响应回传，前端不必自己写死。 */
    @PostMapping("/monitor")
    public Result<Map<String, Object>> monitor() {
        return Result.of(adminOrderService.monitor());
    }

    /**
     * 介入：沿既有 CAS 边强制推进（{@code Action} = {@code pay} / {@code deliver} / {@code receive}）。
     *
     * <p>⚠️ {@code Action = refund} 会在 Service 层被**明确拒绝**（不是「未知动作」）——
     * 理由见 {@code AdminOrderService} 类注释。
     */
    @PostMapping("/intervene")
    public Result<Map<String, Object>> intervene(
            @RequestParam(value = "OrderId", required = false) String OrderId,
            @RequestParam(value = "Action", required = false) String Action,
            HttpServletRequest request
    ) {
        Map<String, Object> result = adminOrderService.intervene(OrderId, Action);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Action", Action);
            after.put("success", true);
            auditService.record(AuthSession.identify(request), AuditActions.ORDER_INTERVENE,
                    "order", OrderId, null, after);
        }
        return Result.of(result);
    }

    // ==================== 平台售后（批四第二批 commit 4） ====================
    // ⚠️ 售后是**旁路状态域**，所以它不走 `/intervene`（那里 `Action=refund` 仍按原口径拒绝）：
    //    介入沿三位状态机的 CAS 边推进；售后处理的是 RefundStatus 自己的六态机。
    // ⚠️ 平台队列按**会员等级降序**（SQL 在 `OrderRefundDao.selectPlatformRefunds`），
    //    这是「VIP 优先售后」的落点，不在 Java 里排序（否则分页一截就失真）。

    /** 平台售后队列；{@code status} 省略 ⇒ 全部有售后记录的单（3 = 待平台审核）。 */
    @PostMapping("/refund/list")
    public Result<Map<String, Object>> refundList(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(refundService.platformRefunds(status, limit));
    }

    /**
     * 平台裁决已升级的售后（{@code RefundStatus=3}）。
     *
     * @param Approve {@code "true"} = 同意退款（入余额 + 回补库存）；{@code "false"} = 驳回（终态）；
     *                缺省 / 其它 ⇒ 报缺参，绝不默认任何一侧。
     */
    @PostMapping("/refund/handle")
    public Result<Map<String, Object>> refundHandle(
            @RequestParam(value = "OrderId", required = false) String OrderId,
            @RequestParam(value = "Approve", required = false) String Approve,
            @RequestParam(value = "Note", required = false) String Note,
            HttpServletRequest request
    ) {
        Boolean approve = parseApprove(Approve);
        Map<String, Object> result = refundService.platformHandle(
                AuthSession.identify(request) == null ? null : AuthSession.identify(request).userId(),
                OrderId, approve, Note);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("Approve", approve);
            after.put("Outcome", result.get("outcome"));
            after.put("Note", Note);
            auditService.record(AuthSession.identify(request), AuditActions.REFUND_PLATFORM_HANDLE,
                    "order", OrderId, null, after);

            String customerId = result.get("customerId") == null ? null : String.valueOf(result.get("customerId"));
            if (customerId != null && !customerId.isBlank()) {
                boolean refunded = "refunded".equals(result.get("outcome"));
                notificationService.send(UUID.randomUUID().toString(), customerId,
                        NotificationService.TO_TYPE_CUSTOMER,
                        refunded ? NotificationEvents.REFUND_COMPLETED : NotificationEvents.REFUND_PLATFORM_REJECTED,
                        (refunded ? "退款已完成：" : "平台已驳回售后：") + OrderId,
                        refunded
                                ? "平台已同意订单 " + OrderId + " 的售后，退款 ¥" + result.get("refundAmount")
                                        + " 已退回您的账户余额。"
                                : "平台驳回了订单 " + OrderId + " 的售后申请（终态）"
                                        + (Note == null || Note.isBlank() ? "。" : "（理由：" + Note.trim() + "）。"),
                        "order", OrderId);
            }
        }
        return Result.of(result);
    }

    /** {@code "true"}/{@code "false"}（大小写不敏感）⇒ 布尔；缺省或其它 ⇒ {@code null}。 */
    private static Boolean parseApprove(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
