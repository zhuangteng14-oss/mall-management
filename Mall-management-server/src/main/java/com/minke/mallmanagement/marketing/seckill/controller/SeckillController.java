package com.minke.mallmanagement.marketing.seckill.controller;

import com.minke.mallmanagement.marketing.seckill.service.SeckillService;
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

import java.util.Map;
import java.util.UUID;

/**
 * 秒杀的**商家侧 + 用户侧** Web 层（C3 块 5 · commit 4）—— 5 个端点：
 * 发给我的邀请 / 应答邀请 / 发给我的申请 / 提交申请 / 成立活动列表。
 * 挂 {@code /marketing}（与 {@code CouponController} / {@code SubsidyController} 同前缀同族：
 * 这是**业务域**，不是壳）。
 *
 * <h2>【四个端点要身份、一个端点只要「已登录」】</h2>
 * <ul>
 *   <li>{@code /seckill/invitations} / {@code /seckill/respond} / {@code /seckill/applications} /
 *       {@code /seckill/apply} / {@code /seckill/activities}：走
 *       {@link IdentityGuard#requireSelfOrAdmin}，判 {@code MerchantId} 入参（商家业务号 =
 *       {@code goods.MerchantId} = {@code login.Id}）—— 同国补那条，用的是**两参版**
 *       （不比登录名 ⇒ 不需要 {@code UserStateLookup}，切片上下文少一处依赖）。</li>
 *   <li>{@code /seckill/live}：**不跑 IdentityGuard**，也**刻意不进白名单**（口径逐字同
 *       {@code SubsidyController./subsidy/goods}）：登录可见、匿名 401。
 *       <p>⚠️ 这是**有意为之的取舍**：做成人人可读要往 {@code WebAuthConfig.AUTH_WHITELIST}
 *       再加一条，那是**放宽安全边界**；而本端点在产品上只服务「主页秒杀模块 / 秒杀页」，
 *       那两处本来就要求已登录。代价如实登记：**未登录访客看不到秒杀模块**。</li>
 * </ul>
 *
 * <h2>【应答 / 报名要回一条通知给管理端】</h2>
 * 与 {@code AdminSeckillController} 的两个方向**互为反向**（那是管理端 → 商家，这是商家 → 管理端）：
 * <ul>
 *   <li>{@code /respond} 商家答了邀请（同意 / 拒绝）⇒ {@code seckill.responded} → 管理端共享收件箱；</li>
 *   <li>{@code /apply} 商家报了名 ⇒ {@code seckill.application.submitted} → 管理端共享收件箱（等审核）。</li>
 * </ul>
 * ⚠️ {@code already-handled} 时**不发**（那件事早就通知过了）。
 *
 * <h2>【⚠️ 类级不得有 {@code @Validated}；参数一律 {@code required=false} + Service 手判】</h2>
 * 同前几笔：约束异常会逃逸成 400 空体，且撞 {@code xxxControllerIsNotAopProxied} 断言族。
 *
 * <h2>【⚠️ 本类必须进 {@code AbstractSmokeTest} 的 {@code @Import}】</h2>
 * 依赖链 {@code SeckillController → SeckillService → SeckillDao}；{@code AdminSeckillController}
 * 还多吃 {@code AuditService} 与 {@code NotificationService}（两者块 1 已在清单里）。
 * 两个新 {@code @Service}/{@code @Repository} 要手工进清单，否则整个 {@code @WebMvcTest} 切片
 * 在**上下文加载期**就红（不是本类单独红）—— 同一处地雷的**第十二次**。
 */
@RestController
@RequestMapping("/marketing")
public class SeckillController {

    /** 管理端共享收件箱的哨兵值 —— 与 {@code SubsidyController.ADMIN_INBOX_ID} 逐字一致。 */
    private static final String ADMIN_INBOX_ID = "*";

    private static final String TO_TYPE_ADMIN = "admin";

    private final SeckillService seckillService;
    private final NotificationService notificationService;

    public SeckillController(SeckillService seckillService, NotificationService notificationService) {
        this.seckillService = seckillService;
        this.notificationService = notificationService;
    }

    /** 发给我的秒杀邀请。{@code Status} 省略 ⇒ 全部（页面自己分「待应答 / 已处理」）。 */
    @PostMapping("/seckill/invitations")
    public ResponseEntity<Object> invitations(
            @RequestParam("MerchantAccount") String MerchantAccount,
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantAccount)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(seckillService.invitations(Status, MerchantAccount, limit)));
    }

    /**
     * 同意 / 拒绝一条秒杀邀请。
     *
     * @param Accept {@code "true"} = 同意；其余（含缺省）= 拒绝 —— ⚠️ 与 {@code /subsidy/confirm}
     *               同口径：**没表态绝不能等同同意**。拒绝时 {@code Reason} 必填（Service 判）。
     */
    @PostMapping("/seckill/respond")
    public ResponseEntity<Object> respond(
            @RequestParam("MerchantAccount") String MerchantAccount,
            @RequestParam("Id") Long Id,
            @RequestParam(value = "Accept", required = false) String Accept,
            @RequestParam(value = "Reason", required = false) String Reason,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantAccount)) {
            return IdentityGuard.forbidden();
        }
        boolean accept = "true".equalsIgnoreCase(Accept == null ? "" : Accept.trim());
        Map<String, Object> result = seckillService.respond(MerchantAccount, Id, accept, Reason);
        // ⚠️ 只在**真的翻了状态**时回信：`already-handled` 是「这行早处理过了」，再发一条就是
        //    同一个事实寄两封（同国补那条）。
        if (Boolean.TRUE.equals(result.get("success"))
                && !"already-handled".equals(result.get("outcome"))) {
            boolean declined = "declined".equals(result.get("outcome"));
            String goodName = result.get("GoodName") == null ? "" : String.valueOf(result.get("GoodName"));
            notificationService.send(UUID.randomUUID().toString(), ADMIN_INBOX_ID, TO_TYPE_ADMIN,
                    NotificationEvents.SECKILL_RESPONDED,
                    (declined ? "商家拒绝秒杀邀请：" : "商家同意秒杀邀请：") + goodName,
                    "商家 " + MerchantAccount + (declined ? " 拒绝参加秒杀" : " 已同意参加秒杀，活动已成立")
                            + (declined && Reason != null && !Reason.isBlank()
                                    ? "（理由：" + Reason.trim() + "）" : "")
                            + "。",
                    "seckill_invitation", String.valueOf(result.get("Id")));
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /** 我提交过的秒杀申请（含审核结果）。 */
    @PostMapping("/seckill/applications")
    public ResponseEntity<Object> applications(
            @RequestParam("MerchantAccount") String MerchantAccount,
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantAccount)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(seckillService.applications(Status, MerchantAccount, limit)));
    }

    /**
     * 商家报名秒杀（路径 B 的发起端）。
     *
     * <p>⚠️ **不带时间点**：申请路径的活动时间由**管理端在审核通过时**确认
     * （`b5-rule-seckill.md` §二）—— 商家只说「我拿这件商品、这个价、这个量报名」。
     */
    @PostMapping("/seckill/apply")
    public ResponseEntity<Object> apply(
            @RequestParam("MerchantAccount") String MerchantAccount,
            @RequestParam(value = "GoodsId", required = false) Integer GoodsId,
            @RequestParam(value = "SeckillPrice", required = false) String SeckillPrice,
            @RequestParam(value = "Quantity", required = false) String Quantity,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantAccount)) {
            return IdentityGuard.forbidden();
        }
        Map<String, Object> result = seckillService.apply(MerchantAccount, GoodsId, SeckillPrice, Quantity);
        if (Boolean.TRUE.equals(result.get("success"))) {
            String goodName = result.get("GoodName") == null ? "" : String.valueOf(result.get("GoodName"));
            notificationService.send(UUID.randomUUID().toString(), ADMIN_INBOX_ID, TO_TYPE_ADMIN,
                    NotificationEvents.SECKILL_APPLICATION_SUBMITTED,
                    "商家报名秒杀：" + goodName,
                    "商家 " + MerchantAccount + " 提交了秒杀申请（秒杀价 ¥" + result.get("SeckillPrice")
                            + "，原价 ¥" + result.get("OriginalPrice") + "）。请在营销运营 · 秒杀里审核。",
                    "seckill_application", String.valueOf(result.get("Id")));
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /** 我参与（已成立）的秒杀活动 —— 商家自己在活动列表里看得到状态（含被停用）。 */
    @PostMapping("/seckill/activities")
    public ResponseEntity<Object> activities(
            @RequestParam("MerchantAccount") String MerchantAccount,
            @RequestParam(value = "Status", required = false) String Status,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdmin(request, MerchantAccount)) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(seckillService.activities(Status, MerchantAccount, limit)));
    }

    /**
     * 用户端：进行中 / 未开启的秒杀活动（每行带 {@code Stage} 与现算的 {@code SoldOut}）。
     *
     * <p>⚠️ 见类注释：**不挂 IdentityGuard，但也不在白名单** ⇒ 登录可见、匿名 401。
     */
    @PostMapping("/seckill/live")
    public Result<Map<String, Object>> live(
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return Result.of(seckillService.live(limit));
    }
}
