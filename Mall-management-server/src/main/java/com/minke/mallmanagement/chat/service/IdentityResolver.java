package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ResolvedIdentity;
import com.minke.mallmanagement.merchant.brand.dao.BrandProfileDao;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.merchant.service.MerchantApplicationService;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 把「会话里的业务号 + 角色」解析成**六态身份**（2026-10-22 增量-A / N1）。
 *
 * <h2>【三条硬规则】</h2>
 * <ol>
 *   <li>不反问用户「是商家还是用户」—— 主动查库，再把结论告诉用户；</li>
 *   <li>不缓存解析结果：每次 {@link #resolve} 都重新查（{@code ask()} 每次也重新调），
 *       审核状态在两次提问之间变化时，第二句就能反映出来；</li>
 *   <li>role=2 时 {@code userId} 是 {@code merchantid}，同一套查询能命中；
 *       role=1 时 {@code userId} 是 {@code customerid}，必须先经
 *       {@link UserService#publicInfo(String)} 换成登录名，申请表的 {@code Username}
 *       才能对上（R3：会话只存业务号，没有登录名）。</li>
 * </ol>
 *
 * <h2>【品牌判定用档案，不用 {@code user.IsBrand}（Q2=A）】</h2>
 * {@code user.IsBrand} 在撤销后**不清零**，拿它判品牌会把已撤销的账号继续当品牌商家。
 * 这里只认 {@code merchant_brand_profile.Status = 'active'} 且 {@code ValidTo} 未过期
 * （{@code ValidTo = NULL} 表示长期有效）。
 *
 * <h2>【六态之外不扩枚举（Q3=A）】</h2>
 * role=3 走 {@link ResolvedIdentity.Boundary#ADMIN}；role=1 但最近 settle 申请已 approved
 * 走 {@link ResolvedIdentity.Boundary#DEGRADED_MERCHANT}（身份未刷新 / 已降级冻结）。
 */
@Service
public class IdentityResolver {

    /** {@code merchant_application.ApplicationType} 的入驻值（品牌值是 {@code brand}）。 */
    private static final String TYPE_SETTLE = "settle";

    /** {@code merchant_brand_profile.Status} 的有效值。 */
    private static final String BRAND_ACTIVE = "active";

    /** DAO 的 {@code DATE_FORMAT} 产出格式，读写同源。 */
    private static final DateTimeFormatter DB_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MerchantApplicationDao applicationDao;
    private final BrandProfileDao brandProfileDao;
    private final UserService userService;
    private final UserGovernanceDao userGovernanceDao;

    public IdentityResolver(MerchantApplicationDao applicationDao,
                            BrandProfileDao brandProfileDao,
                            UserService userService,
                            UserGovernanceDao userGovernanceDao) {
        this.applicationDao = applicationDao;
        this.brandProfileDao = brandProfileDao;
        this.userService = userService;
        this.userGovernanceDao = userGovernanceDao;
    }

    /**
     * 解析一次身份。**不缓存** —— 调用方每次提问都应当重新调用。
     *
     * @param userId 会话里的业务号；未登录为 {@code null}
     * @param role   1 客户 / 2 商家 / 3 管理员；未登录为 {@code null}
     */
    public ResolvedIdentity resolve(String userId, Integer role) {
        if (isBlank(userId)) {
            return ResolvedIdentity.anonymous();
        }
        if (role != null && role == 3) {
            return ResolvedIdentity.admin();
        }
        if (role != null && role == 2) {
            return resolveMerchant(userId);
        }
        if (role != null && role == 1) {
            return resolveCustomer(userId);
        }
        // 业务号在、角色不在：按「判不出来」处理，绝不冒充客户去查别人的申请。
        return ResolvedIdentity.anonymous();
    }

    private ResolvedIdentity resolveMerchant(String merchantId) {
        Map<String, Object> profile = brandProfileDao.selectByMerchant(merchantId);
        if (profile != null
                && BRAND_ACTIVE.equalsIgnoreCase(str(profile.get("Status")).trim())
                && notExpired(profile.get("ValidTo"))) {
            return ResolvedIdentity.brand(merchantId,
                    str(profile.get("BrandName")).trim(),
                    str(profile.get("ValidTo")).trim());
        }
        return ResolvedIdentity.merchant(merchantId);
    }

    private ResolvedIdentity resolveCustomer(String customerId) {
        Map<String, Object> user = userService.publicInfo(customerId);
        String username = user == null ? "" : str(user.get("username")).trim();
        if (username.isEmpty()) {
            // 会话里的业务号查不到人 ⇒ 与未登录同口径，不拿空登录名去查申请表。
            return ResolvedIdentity.anonymous();
        }

        Map<String, Object> application =
                applicationDao.selectLatestByUsernameAndType(username, TYPE_SETTLE);
        if (application == null) {
            return ResolvedIdentity.noApplication(username);
        }

        String status = str(application.get("Status")).trim();
        if (MerchantApplicationService.STATUS_PENDING.equals(status)) {
            return ResolvedIdentity.pending(username,
                    str(application.get("ShopName")).trim(),
                    str(application.get("SubmittedAt")).trim());
        }
        if (MerchantApplicationService.STATUS_REJECTED.equals(status)) {
            return ResolvedIdentity.rejected(username,
                    str(application.get("RejectReason")).trim());
        }
        if (MerchantApplicationService.STATUS_APPROVED.equals(status)) {
            // 申请已通过但 role 还是 1：身份没刷新 / 已被降级冻结，话术单独兜底。
            return ResolvedIdentity.degradedMerchant(username,
                    userGovernanceDao.selectMerchantId(username));
        }
        // 未知状态：按「无有效申请」回答，绝不凭空宣称审核中或已通过。
        return ResolvedIdentity.noApplication(username);
    }

    /** {@code ValidTo} 为空 = 长期有效；解析不了 = **不宣称**品牌（宁可按普通商家答）。 */
    private static boolean notExpired(Object validTo) {
        String value = str(validTo).trim();
        if (value.isEmpty()) {
            return true;
        }
        try {
            return LocalDateTime.parse(value, DB_TIME).isAfter(LocalDateTime.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
