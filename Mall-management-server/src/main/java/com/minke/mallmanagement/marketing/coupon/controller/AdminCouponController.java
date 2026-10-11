package com.minke.mallmanagement.marketing.coupon.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.marketing.coupon.service.CouponService;
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
 * 管理端「营销运营 · 券」Web 层（C3 块 5 · commit 1）—— 7 个端点：
 * 列表 / 建券 / 停用 / 启用 / 领取明细 / 定向发放 / 图表。
 *
 * <h2>【⚠️ 不给 {@code /admin/**} 设角色闸（TD-18）】</h2>
 * 同 {@code AdminOrderController} / {@code AdminTemplateController}：管理员 cookie 铸不出 token
 * ⇒ 加「须 role=3」会让**默认 profile 上管理员自己 401**，而放进白名单是放宽。
 * ⚠️ **别「顺手」补一个 {@code requireAdmin}**。
 *
 * <h2>【参数全 {@code required=false} + 手判，不加 Bean Validation 注解】</h2>
 * 同前几笔：约束异常会逃逸成 400 空体，且类级 {@code @Validated} 撞
 * {@code xxxControllerIsNotAopProxied} 断言族。数字参数一律收 {@code String} 再交给
 * {@code CouponService} 解析 —— 理由见那个类 {@code create} 的注释。
 *
 * <h2>【四个写端点挂审计，三个读端点不挂】</h2>
 * 建券 / 停用 / 启用 / 定向发放都改库 ⇒ 各落一条。列表 / 明细 / 图表是**读**，不留痕
 * （与既有管理端读端点一致）。⚠️ {@code TargetId} 一律传 {@code CouponId}（业务号）——
 * {@code admin_audit.TargetId} 是 NOT NULL，传 null 会在留痕处炸掉整条链路（TD-20）。
 * <p>⚠️ 领券核销（用户端）的留痕在 **commit 5 接结算时**挂 —— 那时才有 {@code OrderId} 可写。
 */
@RestController
@RequestMapping("/admin/marketing/coupon")
public class AdminCouponController {

    private final CouponService couponService;
    private final AuditService auditService;

    public AdminCouponController(CouponService couponService, AuditService auditService) {
        this.couponService = couponService;
        this.auditService = auditService;
    }

    /** 券列表。{@code Status} 走白名单（未知值报错，不静默当成「不筛」）。 */
    @PostMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(couponService.list(Status, keyword, limit));
    }

    /** 建券。成功回带 {@code CouponId}（前端要据它去查明细 / 停用）。 */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(
            @RequestParam(value = "Name", required = false) String Name,
            @RequestParam(value = "Type", required = false) String Type,
            @RequestParam(value = "FaceValue", required = false) String FaceValue,
            @RequestParam(value = "Threshold", required = false) String Threshold,
            @RequestParam(value = "TotalQty", required = false) String TotalQty,
            @RequestParam(value = "Scope", required = false) String Scope,
            @RequestParam(value = "MerchantAccount", required = false) String MerchantAccount,
            @RequestParam(value = "CategoryMatch", required = false) String CategoryMatch,
            @RequestParam(value = "StartAt", required = false) String StartAt,
            @RequestParam(value = "EndAt", required = false) String EndAt,
            HttpServletRequest request
    ) {
        Map<String, Object> result = couponService.create(Name, Type, FaceValue, Threshold, TotalQty,
                Scope, MerchantAccount, CategoryMatch, StartAt, EndAt);
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.COUPON_CREATE,
                    "coupon", String.valueOf(result.get("CouponId")), null, inputSnapshot(
                            Name, Type, FaceValue, Threshold, TotalQty, Scope, MerchantAccount,
                            CategoryMatch, StartAt, EndAt));
        }
        return Result.of(result);
    }

    /** 停用（逻辑废弃）。 */
    @PostMapping("/stop")
    public Result<Map<String, Object>> stop(
            @RequestParam(value = "CouponId", required = false) String CouponId,
            HttpServletRequest request
    ) {
        return Result.of(statusChange(couponService.stop(CouponId), CouponId, "stopped", request));
    }

    /** 重新启用。 */
    @PostMapping("/activate")
    public Result<Map<String, Object>> activate(
            @RequestParam(value = "CouponId", required = false) String CouponId,
            HttpServletRequest request
    ) {
        return Result.of(statusChange(couponService.activate(CouponId), CouponId, "active", request));
    }

    /** 某张券的领取明细（含券定义本身，供页面并排显示）。 */
    @PostMapping("/grants")
    public Result<Map<String, Object>> grants(
            @RequestParam(value = "CouponId", required = false) String CouponId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(couponService.grants(CouponId, limit));
    }

    /** 定向发放：管理端指定一个人给一张券。 */
    @PostMapping("/grant")
    public Result<Map<String, Object>> grant(
            @RequestParam(value = "CouponId", required = false) String CouponId,
            @RequestParam(value = "ToAccount", required = false) String ToAccount,
            HttpServletRequest request
    ) {
        Map<String, Object> result = couponService.grant(CouponId, ToAccount);
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("ToAccount", ToAccount);
            after.put("Source", "grant");
            auditService.record(AuthSession.identify(request), AuditActions.COUPON_GRANT,
                    "coupon", CouponId, null, after);
        }
        return Result.of(result);
    }

    /** 图表数据：领取 / 核销趋势 + 券类型占比（三件齐里的「图」）。 */
    @PostMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.of(couponService.stats());
    }

    // ==================== 内部 ====================

    /**
     * 停用 / 启用的共用腿：成功才留痕，{@code BeforeJson} 用 Service 回带的**改前快照** ——
     * 它是「读到状态与写之间」的那一次读，与订单域刻意**不**记 before 的理由不同
     * （那里是三位状态机、真实改前态在 {@code order_state_transition} 里；这里没有那张表，
     * 而 {@code before} 里含 {@code Status} 恰好就是这次动作唯一改变的东西）。
     */
    private Map<String, Object> statusChange(Map<String, Object> result, String couponId,
                                             String target, HttpServletRequest request) {
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> after = new HashMap<>();
            after.put("Status", target);
            auditService.record(AuthSession.identify(request),
                    "stopped".equals(target) ? AuditActions.COUPON_STOP : AuditActions.COUPON_ACTIVATE,
                    "coupon", couponId, result.get("before"), after);
        }
        return result;
    }

    /** 建券的 {@code AfterJson}：把**输入**原样收进去（建券没有「改前态」，改后态就是这批入参）。 */
    private static Map<String, Object> inputSnapshot(String name, String type, String faceValue,
                                                     String threshold, String totalQty, String scope,
                                                     String merchantAccount, String categoryMatch,
                                                     String startAt, String endAt) {
        Map<String, Object> after = new HashMap<>();
        after.put("Name", name);
        after.put("Type", type);
        after.put("FaceValue", faceValue);
        after.put("Threshold", threshold);
        after.put("TotalQty", totalQty);
        after.put("Scope", scope);
        after.put("MerchantAccount", merchantAccount);
        after.put("CategoryMatch", categoryMatch);
        after.put("StartAt", startAt);
        after.put("EndAt", endAt);
        return after;
    }
}
