package com.minke.mallmanagement.merchant.service;

import com.minke.mallmanagement.goods.dao.GoodsShelfDao;
import com.minke.mallmanagement.user.dao.UserGovernanceDao;
import com.minke.mallmanagement.user.service.UserCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家降级的**状态机执行体**（C3 块 2 用户块）—— 五阶段里的 ①③④⑤ 四步落在这里，
 * ②（通知期）是「什么都不做、等一天」的被动阶段，没有代码。
 *
 * <h2>【五阶段与本类的方法对映】</h2>
 * <ol>
 *   <li><b>管理员发起</b> ⇒ {@link #schedule}：写 {@code scheduled} + {@code ScheduledDowngradeAt=now+1d}。
 *       通知由 Controller 在事务提交后发。</li>
 *   <li><b>通知期（1 天）</b> ⇒ 无代码。⚠️ 此阶段商家**仍可正常接单**（下单闸只认
 *       {@code DowngradeStates.FROZEN}），这是刻意留的收尾窗口。</li>
 *   <li><b>到期执行</b> ⇒ {@link #runDue}（由 {@code @Scheduled} 任务调用）：
 *       {@code login.Role 2→1} + 商品全下架（{@code ShelfState=0}，**不删行**）+ 写 {@code frozen}
 *       + {@code FreezeUntil=now+30d}。</li>
 *   <li><b>30 天内重新申请通过 ⇒ 自动恢复</b> ⇒ 落在
 *       {@link MerchantApplicationService#audit} 的批准路径（它读 {@code FreezeUntil} 判窗口）。</li>
 *   <li><b>30 天后通过 ⇒ 需人工确认</b> ⇒ {@link #relist}（管理员显式把商品重新上架）。</li>
 * </ol>
 *
 * <h2>【⚠️ 降级期间「冻结新单、允许收尾旧单」怎么实现】</h2>
 * 「冻结新单」= 下单链路（{@code OrderService.orderAdd}）上的冻结闸，见
 * {@code UserStateLookup#isMerchantFrozen}。
 * 「允许收尾旧单」= <b>本类不碰 {@code order} 表的任何行</b> —— 已有的订单照常可支付/发货/收货
 * （那三个状态迁移不经过下单链路）。这是「冻结」二字的精确含义：不接新活，不砸旧活。
 *
 * <h2>【⚠️ 本类**不删任何行**（规则 v2：回滚 = 逻辑废弃）】</h2>
 * 降级只改状态列。30 天窗口末端 {@code FreezeUntil} 到了也**不物理删** —— 它只是
 * 「此后恢复需要人工确认」的时间判据，不是 TTL。
 */
@Service
public class MerchantDowngradeService {

    private static final Logger log = LoggerFactory.getLogger(MerchantDowngradeService.class);

    /** 通知期天数（阶段 ②的窗口）—— 用户裁决：1 天。 */
    public static final int NOTICE_DAYS = 1;

    /** 冻结期天数（{@code FreezeUntil} = 执行时刻 + 它）—— 用户裁决：30 天。 */
    public static final int FREEZE_DAYS = 30;

    /** {@code login.Role} 取值（与 {@code UserService.businessId} 同源）。 */
    private static final int ROLE_CUSTOMER = 1;
    private static final int ROLE_MERCHANT = 2;

    private final UserGovernanceDao userGovernanceDao;
    private final GoodsShelfDao goodsShelfDao;
    private final UserCacheService userCacheService;

    public MerchantDowngradeService(UserGovernanceDao userGovernanceDao,
                                    GoodsShelfDao goodsShelfDao,
                                    UserCacheService userCacheService) {
        this.userGovernanceDao = userGovernanceDao;
        this.goodsShelfDao = goodsShelfDao;
        this.userCacheService = userCacheService;
    }

    // ==================== 阶段 ①：发起 ====================

    /**
     * 发起降级（{@code scheduled}）。{@code reason} **必填** —— 它会写进
     * {@code DowngradeReason}，而那一列是**给商家看的**（降级通知正文里就带着它，
     * 见用户裁决「DowngradeReason：保留，不剥」）。故不许留空。
     */
    public Map<String, Object> schedule(String targetUsername, String reason) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(targetUsername)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        if (isBlank(reason)) {
            result.put("success", false);
            result.put("msg", "降级必须填写原因（该原因会展示给商家）");
            return result;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueAt = now.plusDays(NOTICE_DAYS);
        int affected = userGovernanceDao.markScheduled(targetUsername, reason, dueAt);
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            return result;
        }
        result.put("success", true);
        result.put("username", targetUsername);
        result.put("scheduledAt", dueAt.toString());
        return result;
    }

    /** 取消已排定的降级（仅 {@code scheduled} 有意义；对 {@code none} 是幂等空操作）。 */
    public Map<String, Object> cancel(String targetUsername) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(targetUsername)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        int affected = userGovernanceDao.clearDowngrade(targetUsername);
        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "用户不存在");
            return result;
        }
        result.put("success", true);
        result.put("username", targetUsername);
        return result;
    }

    // ==================== 阶段 ③：到期执行（定时任务调它） ====================

    /**
     * 执行所有「通知期已过」的降级。**逐条 fail-soft**：一条失败不拖累其余，该条保持
     * {@code scheduled} 等下一轮重试（见 {@link #applyFreeze} 的「顺序不能改」）。
     *
     * <p>⚠️ <b>本方法刻意**不加** {@code @Transactional}</b>：批内一条失败若回滚全部，
     * 就会让「另一条本来能成功的」跟着白做；而逐条自动提交 + 末位翻状态，得到的是
     * **至少一次 + 幂等收敛**，正是定时任务想要的。代价：不保证批的原子性（不需要）。
     *
     * <p>⚠️ 返回的是「这次真执行了哪些商家」—— 调用方（定时任务）据此发通知。
     * 通知**不在这里发**：本方法可能被事务内的调用方复用，而通知与审计都必须在
     * **提交之后**（见 {@code MerchantApplicationService} 的同类论证）。
     *
     * @return 每个元素含 {@code username} / {@code merchantId} / {@code reason} / {@code delisted}
     */
    public List<Map<String, Object>> runDue() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> due = userGovernanceDao.selectDueDowngrades(now);
        List<Map<String, Object>> done = new ArrayList<>();

        for (Map<String, Object> row : due) {
            String username = str(row.get("username"));
            String merchantId = str(row.get("merchantid"));
            String reason = str(row.get("DowngradeReason"));
            if (isBlank(username)) {
                continue;
            }
            try {
                int delisted = applyFreeze(username, merchantId, now);
                Map<String, Object> one = new HashMap<>();
                one.put("username", username);
                one.put("merchantId", merchantId);
                one.put("reason", reason);
                one.put("delisted", delisted);
                done.add(one);
            } catch (Exception e) {
                // 单条失败不拖累其余 —— 下一轮扫描（fixedDelay）会再试这条（DowngradeState 仍是 scheduled）。
                log.warn("降级执行失败（DOWNGRADE）：username={} —— 保持 scheduled，下一轮重试", username, e);
            }
        }
        return done;
    }

    /**
     * 单条冻结：{@code role 2→1} + 商品下架 + 写 {@code frozen} + 清缓存。
     *
     * <p>⚠️ <b>三条写语句的**顺序不能改**：状态翻转（{@code markFrozen}）必须**最后**做</b>。
     * 这样一次中断（部分成功）留下的状态是 {@code DowngradeState='scheduled'} ⇒ 下一轮扫描
     * <b>还会捞到这条</b>、把前两条幂等地重做一遍，最终收敛。反过来先写 {@code frozen}，
     * 中断后的那条就再也进不了扫描集合 —— 会永久停在「角色变了但商品还在架上」的半成品态。
     */
    private int applyFreeze(String username, String merchantId, LocalDateTime now) {
        String effectiveMerchantId = isBlank(merchantId)
                ? userGovernanceDao.selectMerchantId(username) : merchantId;
        String customerId = userGovernanceDao.selectCustomerId(username);

        // 先：降级后进不去商家壳（login.Role 2→1，login.Id 换回 customerid，与业务号口径一致）。
        userGovernanceDao.updateLoginRole(username, ROLE_CUSTOMER,
                isBlank(customerId) ? effectiveMerchantId : customerId);

        // 次：商品全下架（逻辑下架，不删行）。
        int delisted = 0;
        if (!isBlank(effectiveMerchantId)) {
            delisted = goodsShelfDao.delistAllByMerchant(effectiveMerchantId, now);
            userCacheService.evictPublic(effectiveMerchantId);
        }

        // 末：翻状态（见方法注释「顺序不能改」）。
        userGovernanceDao.markFrozen(username, now, now.plusDays(FREEZE_DAYS));
        return delisted;
    }

    // ==================== 阶段 ⑤：人工确认重新上架 ====================

    /**
     * 管理员**人工确认**恢复上架（阶段 ⑤）。⚠️ 它**只重新上架商品**：
     * 账号身份在「30 天后重新申请通过」那一步已经由
     * {@link MerchantApplicationService#audit} 升回商家了（那一步是 human-in-the-loop 的确认点），
     * 只剩「商品要不要重新上架」这一个残留决定 —— 30 天后默认**不自动**上架，故需要这一下。
     *
     * <p>若账号当前**不在**商家态（{@code login.Role != 2}），本方法仍只上架商品、不改身份
     * —— 「改身份」永远只由审核路径做，避免出现「绕过审核直接变商家」的第二条路。
     */
    @Transactional
    public Map<String, Object> relist(String targetUsername) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(targetUsername)) {
            result.put("success", false);
            result.put("msg", "缺少参数：username");
            return result;
        }
        String merchantId = userGovernanceDao.selectMerchantId(targetUsername);
        if (isBlank(merchantId)) {
            result.put("success", false);
            result.put("msg", "该账号没有商家号（从未入驻）");
            return result;
        }
        int relisted = goodsShelfDao.relistAllByMerchant(merchantId);
        userGovernanceDao.clearDowngrade(targetUsername);
        userCacheService.evictPublic(merchantId);

        result.put("success", true);
        result.put("username", targetUsername);
        result.put("relisted", relisted);
        return result;
    }

    // ==================== 内部 ====================

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
