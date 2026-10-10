package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AdminOrderService;
import com.minke.mallmanagement.admin.service.AuditService;
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

    public AdminOrderController(AdminOrderService adminOrderService, AuditService auditService) {
        this.adminOrderService = adminOrderService;
        this.auditService = auditService;
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
}
