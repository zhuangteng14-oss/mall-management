package com.minke.mallmanagement.admin.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.catalog.service.CategoryService;
import com.minke.mallmanagement.catalog.service.GoodsViolationService;
import com.minke.mallmanagement.goods.service.GoodsAuditService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端「商品与类目治理」Web 层（C3 块 3 商品块）—— 三组端点：
 * <ol>
 *   <li><b>商品审核</b>：{@code /applications} · {@code /application/audit}</li>
 *   <li><b>类目</b>：{@code /category/create} · {@code /category/update} · {@code /category/delete}</li>
 *   <li><b>违规</b>：{@code /violation/delist} · {@code /violation/delete}</li>
 * </ol>
 *
 * <h2>【⚠️ 原先的第四组「模板」已**迁出**（2026-10-10 就地改造）】</h2>
 * 块 3 首版的 {@code /admin/catalog/template/{list,create,update,delete}}（4 个端点 + 本类里的
 * {@code GoodsTemplateService} 注入）**已删**，改由 {@code template.controller.AdminTemplateController}
 * 的 {@code /admin/template/*} 承载 —— 因为模板表已通用化（跨商品 / 理由 / 通知 / 将来评论），
 * 再挂在 {@code /admin/catalog} 下是错的前缀。审计动作也由 {@code goods_template.*} 换成
 * {@code template.*}（旧常量在 {@code AuditActions} 里保留 {@code @Deprecated}）。
 *
 * <h2>【⚠️ 与 {@code AdminAccountsController} 同一姿态：**不给 {@code /admin/**} 设角色闸】</h2>
 * TD-18（管理员 cookie 铸不出 token）⇒ 加「须 role=3」的判据会让**默认 profile 上管理员自己也 401**，
 * 而放进白名单是放宽。本类**照旧不动鉴权面**。⚠️ 别「顺手」补一个 {@code requireAdmin}。
 *
 * <h2>【通知 / 审计一律在本层，业务事务提交之后（四步钩子）】</h2>
 * 每组的写法与 {@code AdminAccountsController} 相同：**先取 before 快照 → 调 Service → 成功才通知 + 审计**。
 * 通知是 fail-soft、无事务的，放进业务事务会被回滚一起卷走（见 {@code NotificationService} 类注释）。
 *
 * <h2>【参数全 {@code required = false} + 手判，不加 Bean Validation 注解】</h2>
 * 同 {@code AdminAccountsController}（约束异常在本仓会逃逸成 400 空体）。
 */
@RestController
@RequestMapping("/admin/catalog")
public class AdminCatalogController {

    private static final String TO_TYPE_MERCHANT = "merchant";

    private final GoodsAuditService goodsAuditService;
    private final CategoryService categoryService;
    private final GoodsViolationService violationService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public AdminCatalogController(GoodsAuditService goodsAuditService,
                                  CategoryService categoryService,
                                  GoodsViolationService violationService,
                                  NotificationService notificationService,
                                  AuditService auditService) {
        this.goodsAuditService = goodsAuditService;
        this.categoryService = categoryService;
        this.violationService = violationService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    // ==================== 一、商品审核 ====================

    /** 提审单列表（{@code status} 可空 ⇒ 全部；传 {@code pending} 即待审）。 */
    @PostMapping("/applications")
    public Result<Map<String, Object>> applications(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(goodsAuditService.listForAdmin(status, limit));
    }

    /**
     * 审核一条商品提审单。{@code approve=true} 通过 / {@code false} 驳回（驳回需 {@code rejectReason}）。
     *
     * <p>⚠️ {@code outcome=already-handled}（并发下另一个管理员先处理了）**不是失败**，
     * 也**不发通知、不记审计**（什么都没发生）。
     *
     * <p>⚠️ 通过时 {@code goods} 表**新增 1 行**（由 Service 在同一事务里写）—— 这是本块
     * 「审核 = 通过才上架」的正面证据。
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
        Map<String, Object> result = goodsAuditService.audit(reviewer, id, pass, rejectReason);

        String outcome = result.get("outcome") == null ? null : result.get("outcome").toString();
        if (outcome != null && !"already-handled".equals(outcome)) {
            String merchantId = str(result.get("merchantId"));
            // ⚠️ C3 块 5 · A 组：**带券的提审单**（「更改商品」里挂的商品券）通过时**没有新商品上架**
            //    —— 商品是当场就改好的，这张单只是券的载体。通知照商品的话术会骗人。
            //    事件仍复用 GOODS_AUDIT_*（本块不新增通知事件），只换文案。
            boolean couponOnly = Boolean.TRUE.equals(result.get("isCoupon"));
            if (pass && couponOnly) {
                notifyMerchant(merchantId, NotificationEvents.GOODS_AUDIT_PASSED,
                        "商品券已通过审核",
                        "您为商品提交的商品券已通过审核，现已生效，买家可以领取了。");
            } else if (pass) {
                notifyMerchant(merchantId, NotificationEvents.GOODS_AUDIT_PASSED,
                        "商品已通过审核",
                        "您提交的商品已通过审核，现已上架，买家可以看到了。");
            } else if (couponOnly) {
                notifyMerchant(merchantId, NotificationEvents.GOODS_AUDIT_REJECTED,
                        "商品券未通过审核",
                        "您为商品提交的商品券未通过审核。原因：" + (rejectReason == null ? "（未填写）" : rejectReason));
            } else {
                notifyMerchant(merchantId, NotificationEvents.GOODS_AUDIT_REJECTED,
                        "商品未通过审核",
                        "您提交的商品未通过审核。原因：" + (rejectReason == null ? "（未填写）" : rejectReason));
            }
            Map<String, Object> before = new HashMap<>();
            before.put("Id", id);
            before.put("Status", "pending");
            Map<String, Object> after = new HashMap<>();
            after.put("Id", id);
            after.put("Status", pass ? "approved" : "rejected");
            after.put("outcome", outcome);
            after.put("GoodsId", result.get("goodsId"));
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_APPLY_AUDIT,
                    "goods_audit", id == null ? null : id.toString(), before, after);
        }
        return Result.of(result);
    }

    // ==================== 二、类目 ====================

    /** 新增类目（{@code parentId} 空 ⇒ 一级）。 */
    @PostMapping("/category/create")
    public Result<Map<String, Object>> categoryCreate(
            @RequestParam(value = "GoodType", required = false) String goodType,
            @RequestParam(value = "GoodTypeNew", required = false) Integer goodTypeNew,
            @RequestParam(value = "ParentId", required = false) Integer parentId,
            @RequestParam(value = "Sort", required = false) Integer sort,
            HttpServletRequest request
    ) {
        Map<String, Object> result = categoryService.create(goodType, goodTypeNew, parentId, sort);
        if (Boolean.TRUE.equals(result.get("success"))) {
            // 新增类动作：BeforeJson 恒 NULL（没有「改前态」）。⚠️ TargetId 用 ParentId:GoodType（此处 0 ≡ CategoryService.ROOT_PARENT_ID）；见 §19-9-8。
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_CATEGORY_CREATE,
                    "goodstype", (parentId == null ? 0 : parentId) + ":" + goodType.trim(), null, snapshotCategory(goodType, parentId, sort));
        }
        return Result.of(result);
    }

    /** 改类目（只改传入的非空字段）。 */
    @PostMapping("/category/update")
    public Result<Map<String, Object>> categoryUpdate(
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "GoodType", required = false) String goodType,
            @RequestParam(value = "Sort", required = false) Integer sort,
            @RequestParam(value = "State", required = false) Integer state,
            @RequestParam(value = "ParentId", required = false) Integer parentId,
            HttpServletRequest request
    ) {
        if (id == null) {
            Map<String, Object> bad = new HashMap<>();
            bad.put("success", false);
            bad.put("msg", "缺少参数：id");
            return Result.of(bad);
        }
        Map<String, Object> result = categoryService.update(id, goodType, sort, state, parentId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_CATEGORY_UPDATE,
                    "goodstype", id.toString(), result.get("before"), snapshotCategory(goodType, parentId, sort));
        }
        return Result.of(result);
    }

    /** 删类目（逻辑废弃：{@code State=0}）。 */
    @PostMapping("/category/delete")
    public Result<Map<String, Object>> categoryDelete(
            @RequestParam(value = "id", required = false) Long id,
            HttpServletRequest request
    ) {
        if (id == null) {
            Map<String, Object> bad = new HashMap<>();
            bad.put("success", false);
            bad.put("msg", "缺少参数：id");
            return Result.of(bad);
        }
        Map<String, Object> result = categoryService.delete(id);
        if (Boolean.TRUE.equals(result.get("success"))) {
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_CATEGORY_DELETE,
                    "goodstype", id.toString(), result.get("before"), null);
        }
        return Result.of(result);
    }

    // ==================== 三、违规处置 ====================

    /** 违规下架（{@code ShelfState=0}）—— 触达商家「违规下架」。 */
    @PostMapping("/violation/delist")
    public Result<Map<String, Object>> violationDelist(
            @RequestParam(value = "MerchantId", required = false) String merchantId,
            @RequestParam(value = "GoodName", required = false) String goodName,
            @RequestParam(value = "ProductCode", required = false) String productCode,
            HttpServletRequest request
    ) {
        List<Map<String, Object>> before = violationService.snapshot(merchantId, productCode);
        Map<String, Object> result = violationService.delist(merchantId, goodName, productCode);
        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyMerchant(merchantId, NotificationEvents.GOODS_VIOLATION_REMOVED,
                    "商品已被下架", "您的商品「" + goodName + "」因违规被平台下架，请自查整改。");
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_VIOLATION_DELIST,
                    "goods", targetId(merchantId, productCode), before, null);
        }
        return Result.of(result);
    }

    /** 违规删除（{@code DeletedAt=now}，逻辑删除）—— 触达商家。 */
    @PostMapping("/violation/delete")
    public Result<Map<String, Object>> violationDelete(
            @RequestParam(value = "MerchantId", required = false) String merchantId,
            @RequestParam(value = "GoodName", required = false) String goodName,
            @RequestParam(value = "ProductCode", required = false) String productCode,
            HttpServletRequest request
    ) {
        List<Map<String, Object>> before = violationService.snapshot(merchantId, productCode);
        Map<String, Object> result = violationService.delete(merchantId, goodName, productCode);
        if (Boolean.TRUE.equals(result.get("success"))) {
            notifyMerchant(merchantId, NotificationEvents.GOODS_VIOLATION_REMOVED,
                    "商品已被删除", "您的商品「" + goodName + "」因违规被平台删除，请自查整改。");
            auditService.record(AuthSession.identify(request), AuditActions.GOODS_VIOLATION_DELETE,
                    "goods", targetId(merchantId, productCode), before, null);
        }
        return Result.of(result);
    }

    // ==================== 内部 ====================

    /** 审核者身份 —— 用**识别**口径（cookie 登录的管理员不该被记成匿名），同块 2。 */
    private static String reviewerOf(HttpServletRequest request) {
        AuthSession.Identity identity = AuthSession.identify(request);
        return identity == null ? null : identity.userId();
    }

    /** 给**商家**发通知（{@code ToType='merchant'}）。商家号空 ⇒ 不发。 */
    private void notifyMerchant(String merchantId, String eventType, String title, String content) {
        if (merchantId == null || merchantId.trim().isEmpty()) {
            return;
        }
        notificationService.send(UUID.randomUUID().toString(), merchantId,
                TO_TYPE_MERCHANT, eventType, title, content, "goods_audit", null);
    }

    /** 类目审计的 after 快照（字段少，就地建；新增类动作的 before 为 null）。 */
    private static Map<String, Object> snapshotCategory(String goodType, Integer parentId, Integer sort) {
        Map<String, Object> m = new HashMap<>();
        m.put("GoodType", goodType);
        m.put("ParentId", parentId);
        m.put("Sort", sort);
        return m;
    }

    private static String targetId(String merchantId, String productCode) {
        return merchantId == null ? null : merchantId + ":" + productCode;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
