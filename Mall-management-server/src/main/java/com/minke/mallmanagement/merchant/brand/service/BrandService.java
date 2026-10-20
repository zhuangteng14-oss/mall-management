package com.minke.mallmanagement.merchant.brand.service;

import com.minke.mallmanagement.merchant.brand.dao.BrandProfileDao;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 品牌加盟状态机（第三批㉗，2026-10-19）—— 四态概览 / 申请 / 审核通过落档案。
 *
 * <h2>【四态】</h2>
 * <table>
 *   <tr><th>state</th><th>判据</th><th>能否再申请</th></tr>
 *   <tr><td>{@code active}</td><td>{@code merchant_brand_profile.Status='active'} 且在有效期内</td><td>否</td></tr>
 *   <tr><td>{@code pending}</td><td>存在 {@code ApplicationType='brand' AND Status='pending'}</td><td>否</td></tr>
 *   <tr><td>{@code rejected}</td><td>最近一条品牌申请被驳回</td><td>冷却期满且 30 天次数未用尽</td></tr>
 *   <tr><td>{@code none}</td><td>从未申请 / 档案被撤销 / 不属于商家</td><td>是（撤销态需重新申请）</td></tr>
 * </table>
 *
 * <h2>【两道闸】</h2>
 * <ol>
 *   <li><b>24h 冷却</b>：最近一条被驳回的申请，其 {@code ReviewedAt} 距今不足 24 小时 ⇒ 拒；</li>
 *   <li><b>30 天 3 次</b>：窗口内品牌申请数 ≥ 3 ⇒ 拒（计数含待审 / 通过 / 驳回，全部算一次）。</li>
 * </ol>
 * 两道闸都在**写申请行之前**判，失败不制造注定被驳回的队列项。并发下由
 * {@code merchant_application.uk_application_pending}（生成列 {@code PendingGuard}）兜底，
 * {@link DuplicateKeyException} 转成同一句「已有待审申请」。
 */
@Service
public class BrandService {

    public static final int COOLDOWN_HOURS = 24;
    public static final int ATTEMPT_WINDOW_DAYS = 30;
    public static final int MAX_ATTEMPTS_IN_WINDOW = 3;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BrandProfileDao brandDao;
    private final MerchantApplicationDao applicationDao;
    private final UserGovernanceDao userGovernanceDao;

    public BrandService(BrandProfileDao brandDao,
                        MerchantApplicationDao applicationDao,
                        UserGovernanceDao userGovernanceDao) {
        this.brandDao = brandDao;
        this.applicationDao = applicationDao;
        this.userGovernanceDao = userGovernanceDao;
    }

    // ==================== 概览（四态） ====================

    public Map<String, Object> overview(String username) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(username)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        String merchantId = userGovernanceDao.selectMerchantId(username);
        Map<String, Object> profile = isBlank(merchantId) ? null : brandDao.selectByMerchant(merchantId);
        Map<String, Object> pending = brandDao.selectPendingBrandApplication(username);
        Map<String, Object> latest = brandDao.selectLatestBrandApplication(username);
        Map<String, Object> rejected = brandDao.selectLatestRejectedBrandApplication(username);
        int attempts = brandDao.countBrandApplicationsSince(username, ATTEMPT_WINDOW_DAYS);

        String state;
        if (profile != null && "active".equals(str(profile.get("Status"))) && notExpired(profile.get("ValidTo"))) {
            state = "active";
        } else if (pending != null) {
            state = "pending";
        } else if (latest != null && "rejected".equals(str(latest.get("Status")))) {
            state = "rejected";
        } else {
            state = "none";
        }

        LocalDateTime nextApplyAt = null;
        boolean canApply = !isBlank(merchantId) && !"active".equals(state) && !"pending".equals(state);
        String blockedReason = null;
        if (isBlank(merchantId)) {
            canApply = false;
            blockedReason = "尚未入驻商家，不能申请品牌加盟";
        } else if ("active".equals(state)) {
            blockedReason = "已是品牌商家";
        } else if ("pending".equals(state)) {
            blockedReason = "已有待审的品牌申请，请等待审核结果";
        } else {
            if (rejected != null) {
                LocalDateTime reviewedAt = parse(rejected.get("ReviewedAt"));
                if (reviewedAt != null) {
                    nextApplyAt = reviewedAt.plusHours(COOLDOWN_HOURS);
                    if (LocalDateTime.now().isBefore(nextApplyAt)) {
                        canApply = false;
                        blockedReason = "被驳回后需等待 " + COOLDOWN_HOURS + " 小时再申请，可在 "
                                + nextApplyAt.format(TIME) + " 后重试";
                    }
                }
            }
            if (canApply && attempts >= MAX_ATTEMPTS_IN_WINDOW) {
                canApply = false;
                blockedReason = ATTEMPT_WINDOW_DAYS + " 天内最多申请 " + MAX_ATTEMPTS_IN_WINDOW + " 次，请稍后再试";
            }
        }

        result.put("success", true);
        result.put("state", state);
        result.put("merchantId", merchantId == null ? "" : merchantId);
        result.put("profile", profile);
        result.put("application", latest);
        result.put("pendingApplication", pending);
        result.put("rejectReason", rejected == null ? null : rejected.get("RejectReason"));
        result.put("attemptsInWindow", attempts);
        result.put("maxAttemptsInWindow", MAX_ATTEMPTS_IN_WINDOW);
        result.put("cooldownHours", COOLDOWN_HOURS);
        result.put("nextApplyAt", nextApplyAt == null ? null : nextApplyAt.format(TIME));
        result.put("canApply", canApply);
        result.put("msg", blockedReason == null ? "" : blockedReason);
        return result;
    }

    // ==================== 申请 ====================

    /**
     * 提交品牌加盟申请。字段全部可选地交给 Controller 做非空文案；本方法只校验收到的业务必填项。
     */
    @Transactional
    public Map<String, Object> apply(String username, String shopName, String licenseNo,
                                     String contactName, String contactPhone, String shopAddress,
                                     String licenseImage, String brandName, String brandNameEn,
                                     String trademarkNo, String trademarkClass, String brandLevel,
                                     String logoUrl, String licenseUrl, String mainCategory, String remark) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(username)) {
            return fail("缺少参数：username");
        }
        if (isBlank(brandName)) {
            return fail("品牌名称不能为空");
        }
        String merchantId = userGovernanceDao.selectMerchantId(username);
        if (isBlank(merchantId)) {
            return fail("该账号尚未入驻，不能申请品牌加盟");
        }

        Map<String, Object> profile = brandDao.selectByMerchant(merchantId);
        if (profile != null && "active".equals(str(profile.get("Status"))) && notExpired(profile.get("ValidTo"))) {
            return fail("该商家已是品牌商家，无需重复申请");
        }
        if (brandDao.selectPendingBrandApplication(username) != null) {
            return fail("已有待审的品牌申请，请等待审核结果");
        }

        Map<String, Object> rejected = brandDao.selectLatestRejectedBrandApplication(username);
        if (rejected != null) {
            LocalDateTime reviewedAt = parse(rejected.get("ReviewedAt"));
            if (reviewedAt != null && LocalDateTime.now().isBefore(reviewedAt.plusHours(COOLDOWN_HOURS))) {
                return fail("被驳回后需等待 " + COOLDOWN_HOURS + " 小时再申请，可在 "
                        + reviewedAt.plusHours(COOLDOWN_HOURS).format(TIME) + " 后重试");
            }
        }
        int attempts = brandDao.countBrandApplicationsSince(username, ATTEMPT_WINDOW_DAYS);
        if (attempts >= MAX_ATTEMPTS_IN_WINDOW) {
            return fail(ATTEMPT_WINDOW_DAYS + " 天内最多申请 " + MAX_ATTEMPTS_IN_WINDOW + " 次，请稍后再试");
        }

        Map<String, Object> latest = brandDao.selectLatestBrandApplication(username);
        Long parentId = latest == null ? null : longOf(latest.get("Id"));
        int attemptNo = brandDao.countBrandApplications(username) + 1;
        String batchId = UUID.randomUUID().toString();
        try {
            applicationDao.insert(username, nullToEmpty(shopName).isEmpty() ? brandName : shopName,
                    nullToEmpty(licenseNo), nullToEmpty(contactName), nullToEmpty(contactPhone),
                    nullToEmpty(shopAddress), nullToEmpty(licenseImage), batchId,
                    LocalDateTime.now(), true, attemptNo, parentId);
        } catch (DuplicateKeyException e) {
            // 并发同提：唯一键 uk_application_pending 兜住
            return fail("已有待审的品牌申请，请等待审核结果");
        }

        Map<String, Object> inserted = brandDao.selectLatestBrandApplication(username);
        long applicationId = inserted == null ? 0L : longOf(inserted.get("Id"));
        if (applicationId <= 0) {
            return fail("品牌申请写入失败，请稍后重试");
        }
        brandDao.insertDetail(applicationId, brandName, brandNameEn, trademarkNo, trademarkClass,
                brandLevel, logoUrl, licenseUrl, mainCategory, remark);

        result.put("success", true);
        result.put("applicationId", applicationId);
        result.put("batchId", batchId);
        result.put("attemptNo", attemptNo);
        result.put("state", "pending");
        result.put("msg", "品牌加盟申请已提交，等待平台审核");
        return result;
    }

    // ==================== 审核联动 ====================

    /**
     * 审核通过后把申请明细写进品牌档案。由 {@code MerchantApplicationService.audit} 在
     * **同一事务**里调用（与 {@code user.IsBrand=1} 一起成功或一起回滚）。
     *
     * @return 落档成功返回 {@code true}；找不到商家 / 申请时 {@code false}（不抛）
     */
    @Transactional
    public boolean approve(long applicationId, LocalDateTime approvedAt) {
        Map<String, Object> payload = brandDao.selectApprovePayload(applicationId);
        if (payload == null || isBlank(str(payload.get("MerchantId")))) {
            return false;
        }
        String brandName = str(payload.get("BrandName"));
        if (isBlank(brandName)) {
            brandName = str(payload.get("ShopName"));
        }
        brandDao.upsertApproved(str(payload.get("MerchantId")), brandName,
                str(payload.get("BrandNameEn")), str(payload.get("TrademarkNo")),
                str(payload.get("TrademarkClass")), str(payload.get("BrandLevel")),
                str(payload.get("LogoUrl")), str(payload.get("LicenseUrl")),
                str(payload.get("MainCategory")), applicationId, approvedAt);
        return true;
    }

    /** 管理员撤销品牌。 */
    @Transactional
    public Map<String, Object> revoke(String merchantId, String reason) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        int affected = brandDao.revoke(merchantId, reason);
        Map<String, Object> result = new HashMap<>();
        result.put("success", affected > 0);
        result.put("msg", affected > 0 ? "品牌已撤销" : "该商家没有可撤销的品牌档案");
        return result;
    }

    /** 管理员续期 / 恢复品牌。{@code validTo} 为空 ⇒ 长期有效。 */
    @Transactional
    public Map<String, Object> renew(String merchantId, String validTo) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        LocalDateTime expiry = null;
        if (!isBlank(validTo)) {
            try {
                expiry = LocalDateTime.parse(validTo.trim().replace(' ', 'T'));
            } catch (Exception e) {
                return fail("ValidTo 格式应为 yyyy-MM-dd HH:mm:ss");
            }
        }
        int affected = brandDao.renew(merchantId, expiry);
        Map<String, Object> result = new HashMap<>();
        result.put("success", affected > 0);
        result.put("msg", affected > 0 ? "品牌已续期" : "品牌档案不存在");
        return result;
    }

    // ==================== 内部 ====================

    private static boolean notExpired(Object validTo) {
        LocalDateTime expiry = parse(validTo);
        return expiry == null || LocalDateTime.now().isBefore(expiry);
    }

    private static LocalDateTime parse(Object value) {
        if (value == null || isBlank(str(value))) {
            return null;
        }
        try {
            return LocalDateTime.parse(str(value).replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }
}
