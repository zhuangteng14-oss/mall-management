package com.minke.mallmanagement.marketing.subsidy.controller;

import com.minke.mallmanagement.marketing.subsidy.service.SubsidyService;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 国补的**商家侧 + 用户侧** Web 层（C3 块 5 · commit 3）—— 3 个端点：
 * 发给我的提名 / 确认或拒绝 / 国补商品列表。挂 {@code /marketing}（与 {@code CouponController} 同前缀、
 * 同族：这是**业务域**，不是壳）。
 *
 * <h2>【两个端点要身份、一个端点只要「已登录」】</h2>
 * <ul>
 *   <li>{@code /subsidy/invitations} / {@code /subsidy/confirm}：走
 *       {@link IdentityGuard#requireSelfOrAdmin}，判的是 {@code MerchantId} 入参
 *       （「提名只能看/处理我自己的」）—— 与 {@code goodsAdd} 量的是**同一种号**
 *       （商家业务号 = {@code goods.MerchantId} = {@code login.Id}）。</li>
 *   <li>{@code /subsidy/goods}：**不跑 IdentityGuard**，但它也**不在白名单**里 ⇒ 未登录必 401。
 *       <p>⚠️ <b>这是刻意的取舍</b>：把它做成「人人可读」要往
 *       {@code WebAuthConfig.AUTH_WHITELIST} 里再加一条（12 → 13 → 14），那是**放宽安全边界**；
 *       而本端点在**产品上**只服务「商品详情页 / 搜索页的国补标签」——
 *       这两处要看得见国补标签，前提是用户已经登录看商品（本仓的领券块
 *       {@code /marketing/coupons} 就是同一条先例：**登录后才拉**）。故本端点选**不动白名单**。
 *       代价如实登记：**未登录访客看不到国补标签**（不是「没有国补」，是「不展示」）。</li>
 * </ul>
 *
 * <h2>【确认 / 拒绝要回一条通知给管理端】</h2>
 * 与 {@code AdminSubsidyController.nominate} 对称：商家点头是「双向制」的另一半，管理端不知道
 * 就没人跟进。收件人是**共享收件箱**（{@code ToType='admin'} / {@code ToId='*'}，
 * 同 {@code MerchantApplicationController.ADMIN_INBOX_ID}）。
 * ⚠️ {@code already-handled} 时**不发**（那件事早就通知过了）。
 *
 * <h2>【⚠️ 本类不吃 {@code UserStateLookup}】</h2>
 * 与本仓「登录名入参」那一族（{@code CouponController} / {@code MerchantApplicationController}）
 * 不同：本类两个受闸端点的入参 {@code MerchantId} 量的是**业务号**，故走
 * {@link IdentityGuard#requireSelfOrAdmin(HttpServletRequest, String)} 两参版即可
 * —— 不引入 {@code ObjectProvider<UserStateLookup>}，切片上下文也就少一处依赖。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}】</h2>
 * 同 {@code CouponController}：约束异常会逃逸成 400 空体，且撞
 * {@code xxxControllerIsNotAopProxied} 断言族。参数一律 {@code required=false} + Service 手判。
 *
 * <h2>【⚠️ 本类必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 依赖链 {@code SubsidyController → SubsidyService → SubsidyDao}；{@code AdminSubsidyController}
 * 还多吃 {@code AuditService}（块 1 已在清单里）与 {@code NotificationService}（块 1 已在）。
 * 两个新 {@code @Service}/{@code @Repository} 都要手工进清单，否则整个 {@code @WebMvcTest} 切片
 * 在**上下文加载期**就红（不是本类单独红）。
 */
@RestController
@RequestMapping("/marketing")
public class SubsidyController {

    /** 管理端共享收件箱的哨兵值 —— 与 {@code MerchantApplicationController.ADMIN_INBOX_ID} 逐字一致。 */
    private static final String ADMIN_INBOX_ID = "*";

    private static final String TO_TYPE_ADMIN = "admin";

    private final SubsidyService subsidyService;
    private final NotificationService notificationService;

    public SubsidyController(SubsidyService subsidyService, NotificationService notificationService) {
        this.subsidyService = subsidyService;
        this.notificationService = notificationService;
    }

    /**
     * 发给我的国补提名。{@code Status} 省略 ⇒ 全部（页面自己分「待处理 / 已处理」）。
     *
     * @param OnlyOpen {@code "true"} ⇒ 只回**规则还没到期**的行（店铺主页「平台活动」tab 用）；
     *                 其余（**含缺省**）⇒ 与加这个参数之前**逐字节同行为**。
     *                 ⚠️ 只认字面 {@code "true"}（同 {@code Approve} 那条的口径：没表态 ≠ 表态）。
     */
    @PostMapping("/subsidy/invitations")
    public ResponseEntity<Object> invitations(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "OnlyOpen", required = false) String OnlyOpen,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(
                subsidyService.invitations(MerchantId, Status, limit, "true".equals(OnlyOpen))));
    }

    /**
     * 同意 / 拒绝一条提名。
     *
     * @param Approve {@code "true"} = 同意；其余（含缺省）= 拒绝 —— ⚠️ 缺省**是拒绝**而不是同意：
     *                「没表态」绝不能等同「同意参加」。拒绝时 {@code Reason} 必填（Service 判）。
     */
    @PostMapping("/subsidy/confirm")
    public ResponseEntity<Object> confirm(
            @RequestParam("MerchantId") String MerchantId,
            @RequestParam("Id") Long Id,
            @RequestParam(value = "Approve", required = false) String Approve,
            @RequestParam(value = "Reason", required = false) String Reason,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantId)) {
            return IdentityGuard.forbidden();
        }
        boolean approve = "true".equalsIgnoreCase(Approve == null ? "" : Approve.trim());
        var result = subsidyService.confirm(MerchantId, Id, approve, Reason);
        // ⚠️ 只在**真的翻了状态**时回信给管理端：`already-handled` 是「这行早处理过了」，
        //    再发一条就是同一个事实寄两封。
        if (Boolean.TRUE.equals(result.get("success"))
                && !"already-handled".equals(result.get("outcome"))) {
            boolean rejected = "rejected".equals(result.get("outcome"));
            String goodName = result.get("GoodName") == null ? "" : String.valueOf(result.get("GoodName"));
            notificationService.send(UUID.randomUUID().toString(), ADMIN_INBOX_ID, TO_TYPE_ADMIN,
                    rejected ? NotificationEvents.SUBSIDY_REJECTED : NotificationEvents.SUBSIDY_CONFIRMED,
                    (rejected ? "商家拒绝国补：" : "商家确认国补：") + goodName,
                    "商家 " + MerchantId + (rejected ? " 拒绝参加国补" : " 已确认参加国补")
                            + (rejected && Reason != null && !Reason.isBlank() ? "（理由：" + Reason.trim() + "）" : "")
                            + "。",
                    "subsidy_goods", String.valueOf(result.get("Id")));
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /**
     * 国补商品列表（用户端标签 / 筛选的数据源）。每行带现算的 {@code SubsidyAmount} 与
     * {@code PayAmount} —— 前端不重算封顶。
     *
     * <p>⚠️ 见类注释：**不挂 IdentityGuard，但也不在白名单** ⇒ 登录可见、匿名 401。
     */
    @PostMapping("/subsidy/goods")
    public Result<java.util.Map<String, Object>> goods(
            @RequestParam(value = "GoodType", required = false) String GoodType,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(subsidyService.subsidyGoods(GoodType, limit));
    }
}
