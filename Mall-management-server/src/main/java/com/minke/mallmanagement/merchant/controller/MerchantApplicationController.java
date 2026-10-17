package com.minke.mallmanagement.merchant.controller;

import com.minke.mallmanagement.admin.AuditActions;
import com.minke.mallmanagement.admin.service.AuditService;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.shared.text.MaskedInput;
import com.minke.mallmanagement.shared.web.AuthSession;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import com.minke.mallmanagement.shared.web.Result;
import com.minke.mallmanagement.shared.web.UserStateLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 商家入驻的**用户端** Web 层（C3 块 2 用户块）—— 2 个端点：提交申请 / 我的申请。
 * 审核端点在 {@code admin} 域（{@code AdminAccountsController}）：入驻是「用户提请、管理员裁决」，
 * 两侧归属不同端，故**不共用一个 Controller**。
 *
 * <h2>【路径前缀 {@code /merchant} —— 这是后端 API 前缀，不是前端路由】</h2>
 * 前端路由把入驻页挂在**用户端**壳下（未审核的用户还没资格进商家端），
 * 但那与后端资源前缀无关（同 {@code AdminAuditController} 的两层说明）。
 *
 * <h2>【⚠️ 两个端点都挂 {@link IdentityGuard}，且必须在方法体第一行】</h2>
 * {@code username} 是「代表调用者自己」的入参，但它是**登录名**（{@code user} 表主键）、不是业务号
 * ⇒ 走 {@code IdentityGuard.requireSelfOrAdminByUsername}（补丁 A1：原写法直接套业务号版判据 ⇒
 * 登录名永不等于会话里的业务号 ⇒ 对任何正常客户恒 403，实测就是这个）。
 * 闸关（smoke / a3）时 {@code identify == null ⇒ 放行} —— 与其余入参校验端点同一纪律。
 *
 * <h2>【⚠️ 通知与审计在本层（Controller），不在 Service —— 这是刻意的】</h2>
 * 提交申请会**同时**产生两件事：给管理端共享收件箱推一条（{@code ToType='admin', ToId='*'}），
 * 以及给审计表留痕。两者都是 fail-soft、无事务的（见各自类注释）⇒ 必须在业务事务
 * **提交之后**发，否则业务回滚会把它们一起卷走。照 {@code GoodsController.goodsUp} 的四步。
 *
 * <p>⚠️ 管理端通知**不做受众解析**（不查管理员清单）—— 裁决 4 定的是**共享收件箱**：
 * {@code ToId='*'} 是「所有管理员共享的一条」，不是「给每个管理员各扇一条」。
 * 理由：管理员是**角色**不是**个体**，一条待办被谁处理掉就是谁处理掉，扇 N 条反而要 N 次已读。
 */
@RestController
@RequestMapping("/merchant")
public class MerchantApplicationController {

    /** 管理端共享收件箱的收件人标识 —— 见类注释「为什么不扇出」。 */
    private static final String ADMIN_INBOX_ID = "*";
    private static final String TO_TYPE_ADMIN = "admin";

    private final MerchantApplicationService applicationService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    /**
     * 业务号 → 登录名 的反查（补丁 A1）。
     * ⚠️ <b>刻意是 {@link ObjectProvider}、不是裸的 {@code UserStateLookup}</b>：本类是
     * {@code @Controller} ⇒ {@code @WebMvcTest} 切片**一定会构造它**，而 impl
     * （{@code UserStateLookupService}）**刻意不被 {@code AbstractSmokeTest} @Import**
     * （见该文件那条 ⚠️）—— 裸依赖 = 找不到 bean ⇒ 整个切片启动失败（块 1 那次 237 / 77 errors 同型）。
     * 取不到 ⇒ {@code getIfAvailable() == null} ⇒ 闸侧 fail-open（见 {@code IdentityGuard}）。
     */
    private final ObjectProvider<UserStateLookup> userStateLookupProvider;

    public MerchantApplicationController(MerchantApplicationService applicationService,
                                         NotificationService notificationService,
                                         AuditService auditService,
                                         ObjectProvider<UserStateLookup> userStateLookupProvider) {
        this.applicationService = applicationService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.userStateLookupProvider = userStateLookupProvider;
    }

    /**
     * 提交入驻申请（用户端）。
     *
     * <p>⚠️ 所有参数 {@code required = false} + Service 内手判，**不加任何 Bean Validation 注解**
     * —— 见 {@code Result} / {@code GlobalExceptionHandler} 的既有接缝：
     * 约束异常在本仓会逃逸成 400 空体，而全仓惯例是「失败也 200 + {@code success:false}」。
     */
    @PostMapping("/apply")
    public ResponseEntity<Object> apply(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "shopName", required = false) String shopName,
            @RequestParam(value = "licenseNo", required = false) String licenseNo,
            @RequestParam(value = "contactName", required = false) String contactName,
            @RequestParam(value = "contactPhone", required = false) String contactPhone,
            @RequestParam(value = "shopAddress", required = false) String shopAddress,
            @RequestParam(value = "licenseImage", required = false) String licenseImage,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, username, userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        // 块 7 收尾 · A5：联系电话是**展示时会掩码**的字段，带 `*` 的串是页面掩码、不是真值。
        String maskedField = MaskedInput.firstMasked("contactPhone", contactPhone);
        if (maskedField != null) {
            return ResponseEntity.ok(Result.fail(MaskedInput.rejectMessage(maskedField)));
        }

        Map<String, Object> result = applicationService.apply(username, shopName, licenseNo,
                contactName, contactPhone, shopAddress, licenseImage);

        // 四步钩子：业务成功之后才通知 + 审计（见类注释）。
        if (Boolean.TRUE.equals(result.get("success"))) {
            String batchId = result.get("batchId") == null ? null : result.get("batchId").toString();
            String title = "新的商家入驻申请";
            String content = "账号「" + username + "」提交了店铺「" + shopName + "」的入驻申请，待审核。";
            notificationService.send(UUID.randomUUID().toString(),
                    ADMIN_INBOX_ID, TO_TYPE_ADMIN,
                    NotificationEvents.MERCHANT_APPLY_SUBMITTED,
                    title, content, "merchant_application", batchId);
            auditService.record(AuthSession.identify(request),
                    AuditActions.MERCHANT_APPLY_CREATE,
                    "merchant_application", batchId, null, snapshot(username, shopName, licenseNo));
        }
        return ResponseEntity.ok(Result.of(result));
    }

    /** 「我的申请」（用户端）—— 列表 + 每条的状态与驳回理由。 */
    @PostMapping("/myApplications")
    public ResponseEntity<Object> myApplications(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request
    ) {
        if (!IdentityGuard.requireSelfOrAdminByUsername(request, username, userStateLookupProvider.getIfAvailable())) {
            return IdentityGuard.forbidden();
        }
        return ResponseEntity.ok(Result.of(applicationService.myApplications(username, limit)));
    }

    /** 审计快照 —— 只记申请面（不含任何凭据，本域也不接触凭据）。 */
    private static Map<String, Object> snapshot(String username, String shopName, String licenseNo) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("username", username);
        m.put("shopName", shopName);
        m.put("licenseNo", licenseNo);
        return m;
    }
}
