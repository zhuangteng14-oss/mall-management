package com.minke.mallmanagement.order.refund.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 售后状态机的**用户侧 + 商家侧** Web 层（批四第二批 commit 4）—— 4 个端点：
 * 申请 / 我的售后 / 商家队列 / 商家处理。平台侧两条在 {@code AdminOrderController}。
 *
 * <h2>【通知与审计放在 Controller，不放在 Service】</h2>
 * 与 {@code SubsidyController.confirm} 同一条口径：Service 只管**状态与账**（CAS + 入余额 + 回补），
 * 通知/审计是「事情已经落库之后」的旁路动作，{@code send} / {@code record} 都 fail-soft
 * —— 放在 Service 的事务里会让它们的失败语义与退款事务纠缠在一起。
 *
 * <h2>【只在 CAS 命中（{@code success=true}）之后发信留痕】</h2>
 * 重复点击 / 竞态输掉时 Service 回 {@code success=false} —— 那件事早就通知过了，
 * 再发一条就是同一个事实寄两封。
 *
 * <h2>【身份判据】</h2>
 * 四个端点的入参都是**业务号**（{@code CustomerId} / {@code MerchantId}），
 * 走两参版 {@link IdentityGuard#requireSelfOrAdmin}，与 {@code /goods/goodsAdd} 同类；
 * 管理员可代处理（第 2 档），其余人指向别人的号一律 403。
 */
@RestController
@RequestMapping("/order/refund")
public class OrderRefundController {

    /** 管理端共享收件箱的哨兵值 —— 与 {@code MerchantApplicationController.ADMIN_INBOX_ID} 逐字一致。 */
    private static final String ADMIN_INBOX_ID = "*";

    private static final String TO_TYPE_ADMIN = "admin";

    private final OrderRefundService refundService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public OrderRefundController(OrderRefundService refundService,
                                 NotificationService notificationService,
                                 AuditService auditService) {
        this.refundService = refundService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    // ==================== 用户 ====================

    /**
     * 申请 / 重申售后。{@code ReceiveIf=1} 且 {@code RefundStatus IN (0,2)} 才受理；
     * 金额由服务端取 {@code order.Price}，客户端**不传金额**。
     */
    @PostMapping("/apply")
    public ResponseEntity<Object> apply(
            @RequestParam(value = "CustomerId", required = false) String CustomerId,
            @RequestParam(value = "OrderId", required = false) String OrderId,
            @RequestParam(value = "Reason", required = false) String Reason,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        Map<String, Object> result = refundService.apply(CustomerId, OrderId, Reason);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("RefundStatus", result.get("refundStatus"));
            after.put("RefundRetryCount", result.get("retryCount"));
            after.put("Reason", Reason);
            auditService.record(AuthSession.identify(request), AuditActions.REFUND_APPLY,
                    "order", OrderId, null, after);

            String merchantId = result.get("merchantId") == null ? null : String.valueOf(result.get("merchantId"));
            if (merchantId != null && !merchantId.isBlank()) {
                notificationService.send(UUID.randomUUID().toString(), merchantId,
                        NotificationService.TO_TYPE_MERCHANT, NotificationEvents.REFUND_APPLIED,
                        "新的售后申请：" + OrderId,
                        "用户 " + CustomerId + " 对订单 " + OrderId + " 申请售后"
                                + (Reason == null || Reason.isBlank() ? "。" : "（原因：" + Reason.trim() + "）。"),
                        "order", OrderId);
            }
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /** 我的售后单（{@code RefundStatus <> 0}，最近申请在前）。 */
    @PostMapping("/my")
    public ResponseEntity<Object> my(
            @RequestParam(value = "CustomerId", required = false) String CustomerId,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, CustomerId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(refundService.myRefunds(CustomerId, limit)));
    }

    // ==================== 商家 ====================

    /** 商家售后队列；{@code status} 省略 ⇒ 全部有售后记录的单。 */
    @PostMapping("/merchant/list")
    public ResponseEntity<Object> merchantList(
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(refundService.merchantRefunds(MerchantId, status, limit)));
    }

    /**
     * 商家同意 / 拒绝。
     *
     * @param Approve {@code "true"} = 同意；{@code "false"} = 拒绝；**缺省 ⇒ 报缺参**（不默认任何一侧）。
     *                ⚠️ 与 {@code SubsidyController.confirm} 的「缺省是拒绝」不同：那里的缺省仍是一个
     *                明确的否定表态，这里**没表态就不许动账**。
     */
    @PostMapping("/merchant/handle")
    public ResponseEntity<Object> merchantHandle(
            @RequestParam(value = "MerchantId", required = false) String MerchantId,
            @RequestParam(value = "OrderId", required = false) String OrderId,
            @RequestParam(value = "Approve", required = false) String Approve,
            @RequestParam(value = "Note", required = false) String Note,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        Boolean approve = parseApprove(Approve);
        Map<String, Object> result = refundService.merchantHandle(MerchantId, OrderId, approve, Note);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("Approve", approve);
            after.put("Outcome", result.get("outcome"));
            after.put("Note", Note);
            auditService.record(AuthSession.identify(request), AuditActions.REFUND_MERCHANT_HANDLE,
                    "order", OrderId, null, after);

            String customerId = result.get("customerId") == null ? null : String.valueOf(result.get("customerId"));
            String outcome = String.valueOf(result.get("outcome"));
            if (customerId != null && !customerId.isBlank()) {
                switch (outcome) {
                    case "refunded" -> notificationService.send(UUID.randomUUID().toString(), customerId,
                            NotificationService.TO_TYPE_CUSTOMER, NotificationEvents.REFUND_COMPLETED,
                            "退款已完成：" + OrderId,
                            "商家已同意订单 " + OrderId + " 的售后，退款 ¥" + result.get("refundAmount")
                                    + " 已退回您的账户余额。",
                            "order", OrderId);
                    case "rejected" -> notificationService.send(UUID.randomUUID().toString(), customerId,
                            NotificationService.TO_TYPE_CUSTOMER, NotificationEvents.REFUND_MERCHANT_REJECTED,
                            "售后被商家拒绝：" + OrderId,
                            "商家拒绝了订单 " + OrderId + " 的售后申请"
                                    + (Note == null || Note.isBlank() ? "，您可以再次申请（共 2 次）。" : "（理由：" + Note.trim() + "），您可以再次申请（共 2 次）。"),
                            "order", OrderId);
                    case "escalated" -> {
                        notificationService.send(UUID.randomUUID().toString(), customerId,
                                NotificationService.TO_TYPE_CUSTOMER, NotificationEvents.REFUND_ESCALATED,
                                "售后已升级平台处理：" + OrderId,
                                "商家第二次拒绝了订单 " + OrderId + " 的售后申请，已转交平台处理。",
                                "order", OrderId);
                        notificationService.send(UUID.randomUUID().toString(), ADMIN_INBOX_ID,
                                TO_TYPE_ADMIN, NotificationEvents.REFUND_ESCALATED,
                                "售后升级待处理：" + OrderId,
                                "用户 " + customerId + " 的订单 " + OrderId + " 经商家二次拒绝，等待平台裁决。",
                                "order", OrderId);
                    }
                    default -> {
                        // 未知 outcome（防御）：不发信，业务结果照回
                    }
                }
            }
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /** {@code "true"}/{@code "false"}（大小写不敏感）⇒ 布尔；缺省或其它 ⇒ {@code null}（Service 报缺参）。 */
    static Boolean parseApprove(String raw) {
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
