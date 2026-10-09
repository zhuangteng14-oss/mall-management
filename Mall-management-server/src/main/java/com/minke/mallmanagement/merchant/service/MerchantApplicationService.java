package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.merchant.dao.MerchantApplicationDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商家入驻：**提交 / 我的申请 / 审核**（C3 块 2 用户块）。
 *
 * <h2>【审核 = 批准与改身份**同一事务**（A 档裁决 2）】</h2>
 * {@link #audit} 把三件事放进<b>一个</b> {@code @Transactional}：
 * <ol>
 *   <li>CAS 改申请状态（{@code pending → approved/rejected}，见 {@code MerchantApplicationDao}）；</li>
 *   <li>批准时把账号**升级为商家**（{@code user.usertype/merchantid} + {@code login.Role 1→2}
 *       + {@code merchantimage} upsert）；</li>
 *   <li>若该账号正处于降级冻结态，按 30 天窗口决定**是否自动恢复**（阶段 ④/⑤）。</li>
 * </ol>
 * 不放进同一个事务就会出现「申请已批准、但账号还是客户」的**半完成态** —— 用户端看到的
 * 是「通过了却进不去商家端」，而库里的 {@code Status} 已经不可回退（CAS 已消费）。
 *
 * <h2>【⚠️ 通知与审计**不在这里** —— 它们在 Controller，事务提交之后】</h2>
 * 照 {@code GoodsController.goodsUp} 的四步：{@code NotificationService.send} 与
 * {@code AuditService.record} 都是 fail-soft、且**无事务**（见各自类注释）。
 * 若把它们放进本方法的事务里，业务一回滚会把通知/审计**一起卷走** —— 那正是
 * 「审计记录了一次并未发生的操作」。故本方法只**返回**「该通知谁、通知什么」，由 Controller 发。
 */
@Service
public class MerchantApplicationService {

    /** 申请状态码 —— 与建表块里 {@code Status} 列的注释同源（小写）。 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    /** {@code login.Role} 的取值（与 {@code UserService.businessId} 的判据同源）。 */
    private static final int ROLE_MERCHANT = 2;

    /** 列表默认条数 / 上限（与 {@code AuditService} 同口径）。 */
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private final MerchantApplicationDao applicationDao;
    private final UserGovernanceDao userGovernanceDao;
    private final GoodsShelfDao goodsShelfDao;
    private final UserCacheService userCacheService;

    public MerchantApplicationService(MerchantApplicationDao applicationDao,
                                      UserGovernanceDao userGovernanceDao,
                                      GoodsShelfDao goodsShelfDao,
                                      UserCacheService userCacheService) {
        this.applicationDao = applicationDao;
        this.userGovernanceDao = userGovernanceDao;
        this.goodsShelfDao = goodsShelfDao;
        this.userCacheService = userCacheService;
    }

    // ==================== 提交 ====================

    /**
     * 提交一条入驻申请。
     *
     * <p>⚠️ <b>不做「一个账号只能有一条 pending」的约束</b>：建表块**刻意不加
     * {@code UNIQUE(Username)}**（驳回后要能重申）。同一账号连提两条 pending 会怎样？
     * 审核时**先被处理的那条**改动身份，后一条再批准时 CAS 仍会成功（它自己还是 pending），
     * 只是重复执行一次幂等的升级 —— 不产生错误状态。⚠️ 这是**已知的宽松**，不是漏洞：
     * 升级是幂等的（见 {@link #promote}），代价只是多一条冗余申请行。
     */
    public Map<String, Object> apply(String username, String shopName, String licenseNo,
                                     String contactName, String contactPhone, String shopAddress,
                                     String licenseImage) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(username)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        if (isBlank(shopName) || isBlank(licenseNo)) {
            result.put("success", false);
            result.put("msg", "店铺名称与营业执照号为必填");
            return result;
        }

        String batchId = UUID.randomUUID().toString();
        applicationDao.insert(username, shopName, licenseNo, contactName, contactPhone,
                shopAddress, licenseImage, batchId, LocalDateTime.now());

        result.put("success", true);
        result.put("batchId", batchId);
        return result;
    }

    // ==================== 读 ====================

    /** 「我的申请」（登录名维度，时间倒序）。 */
    public Map<String, Object> myApplications(String username, Integer limit) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(username)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        int effective = normalizeLimit(limit);
        List<Map<String, Object>> rows = applicationDao.selectByUsername(username, effective);
        result.put("success", true);
        result.put("list", rows);
        result.put("total", rows.size());
        result.put("limit", effective);
        return result;
    }

    /** 管理端申请列表（{@code status} 可空 ⇒ 全部）。 */
    public Map<String, Object> listForAdmin(String status, Integer limit) {
        int effective = normalizeLimit(limit);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", applicationDao.selectByStatus(status, effective));
        result.put("total", applicationDao.countByStatus(status));
        result.put("limit", effective);
        return result;
    }

    // ==================== 审核 ====================

    /**
     * 审核一条申请。批准 ⇒ {@code approve=true}；驳回 ⇒ {@code approve=false} 且
     * {@code rejectReason} 必填（会原样落 {@code RejectReason}，并在通知里带给商家 ——
     * 这一列**是给商家看的**，不是内部备注）。
     *
     * <p>返回值里的 {@code outcome} 是给 Controller 决定「发什么通知」用的：
     * {@code approved} / {@code approved-restored} / {@code rejected} / {@code already-handled}。
     */
    @Transactional
    public Map<String, Object> audit(String reviewer, Long id, boolean approve, String rejectReason) {
        Map<String, Object> result = new HashMap<>();
        if (id == null || id <= 0) {
            result.put("success", false);
            result.put("msg", "缺少参数：id");
            return result;
        }
        if (!approve && isBlank(rejectReason)) {
            result.put("success", false);
            result.put("msg", "驳回必须填写理由");
            return result;
        }

        Map<String, Object> before = applicationDao.selectById(id);
        if (before == null) {
            result.put("success", false);
            result.put("msg", "申请不存在");
            return result;
        }
        String applicant = before.get("Username") == null ? null : before.get("Username").toString();

        LocalDateTime now = LocalDateTime.now();
        int affected = approve
                ? applicationDao.casApprove(id, reviewer, now)
                : applicationDao.casReject(id, reviewer, now, rejectReason);

        if (affected == 0) {
            // CAS 落空 —— 已被别的管理员处理过（或状态本就不是 pending）。**不是错误**。
            result.put("success", true);
            result.put("outcome", "already-handled");
            result.put("username", applicant);
            return result;
        }

        if (!approve) {
            result.put("success", true);
            result.put("outcome", "rejected");
            result.put("username", applicant);
            result.put("rejectReason", rejectReason);
            return result;
        }

        boolean restored = promote(applicant);
        result.put("success", true);
        result.put("outcome", restored ? "approved-restored" : "approved");
        result.put("username", applicant);
        return result;
    }

    // ==================== 内部：升级（批准路径的执行体） ====================

    /**
     * 把账号升级成商家。**幂等**：重复调用只是再写一遍同样的值。
     *
     * <p>如果该账号正处在降级**冻结态**，这里顺带做阶段 ④/⑤ 的分流：
     * <ul>
     *   <li>仍在 30 天窗口内（{@code now <= FreezeUntil}）⇒ **自动恢复**：清降级态 + 商品重新上架
     *       （{@code ShelfState=1}），返回 {@code true}；</li>
     *   <li>已过窗口 ⇒ 只清降级态、**不动商品**（需管理员另行确认后才重新上架），返回 {@code false}。</li>
     * </ul>
     */
    private boolean promote(String username) {
        if (isBlank(username)) {
            return false;
        }
        String customerId = userGovernanceDao.selectCustomerId(username);
        String merchantId = userGovernanceDao.selectMerchantId(username);
        if (merchantId == null || merchantId.trim().isEmpty()) {
            merchantId = mintMerchantId();
        }

        userGovernanceDao.promoteToMerchant(username, merchantId);
        userGovernanceDao.updateLoginRole(username, ROLE_MERCHANT, merchantId);

        String customerImage = userGovernanceDao.selectCustomerImage(username);
        if (customerImage != null) {
            userGovernanceDao.upsertMerchantImage(username, merchantId, customerImage);
        }

        // 升级动了 user 的三列（usertype / merchantid），其中两列在 userPublic 缓存的 5 列里
        // ⇒ 必须清缓存（同 updateProfile；与 updateUserProfile 那条「无交集故免清」相反）。
        // ⚠️ 清**两个**键：升级后业务号由 customerid 换成 merchantid（login.Role 1→2），
        //    旧键若留着，同一标签页在 TTL 内还会读到升级前的 usertype/merchantid；
        //    新键可能存着「不存在」的负条目（缓存穿透保护），也要一并清掉。
        userCacheService.evictPublic(customerId);
        userCacheService.evictPublic(merchantId);

        return restoreIfFrozenWithinWindow(username, merchantId);
    }

    /** 阶段 ④/⑤ 分流 —— 见 {@link #promote}。 */
    private boolean restoreIfFrozenWithinWindow(String username, String merchantId) {
        Map<String, Object> row = userGovernanceDao.selectDowngradeRow(username);
        if (row == null || !"frozen".equals(String.valueOf(row.get("DowngradeState")))) {
            return false;   // 不是冻结态（含 none / scheduled）⇒ 无「恢复」可言
        }
        String freezeUntil = row.get("FreezeUntil") == null ? null : row.get("FreezeUntil").toString();
        boolean withinWindow = freezeUntil != null
                && LocalDateTime.now().isBefore(LocalDateTime.parse(freezeUntil.replace(' ', 'T')));
        userGovernanceDao.clearDowngrade(username);
        if (withinWindow) {
            goodsShelfDao.relistAllByMerchant(merchantId);
            return true;
        }
        return false;   // 阶段 ⑤：清了降级态，但商品要管理员另行确认才重新上架
    }

    // ==================== 内部 ====================

    /** 铸一个 10 位业务号 —— 与既有种子形态（{@code 5N1k5K2guh}）等长，列宽 {@code char(20)} 容得下。 */
    private static String mintMerchantId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
