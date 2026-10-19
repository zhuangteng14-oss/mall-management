package com.minke.mallmanagement.member.service;

import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import com.minke.mallmanagement.member.dao.MemberLevelDao;
import com.minke.mallmanagement.member.dao.PointsDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会员 / 积分域服务（第三批㉘，2026-10-19）。
 *
 * <h2>【权威账户与流水】</h2>
 * {@code user_points_account} 是权威余额；{@code user_points_log} 只追加；
 * {@code user.MemberLevelNo} / {@code user.GrowthValue} 是同事务镜像。
 * 每条变动都带幂等键 {@code (UserId, BizType, BizId)}：
 * <ul>
 *   <li>签到：{@code signin + 2026-10-19}</li>
 *   <li>订单收货：{@code order_earn + OrderId}</li>
 *   <li>下单抵扣：{@code order_spend + OrderId}</li>
 *   <li>过期：{@code expire + lot:&lt;流水Id&gt;}</li>
 * </ul>
 *
 * <h2>【FIFO 过期】</h2>
 * 获得积分时在流水上记 {@code RemainAmount} 与 {@code ExpireAt}（365 天）。
 * 消费按「先过期先扣」递减 {@code RemainAmount}；每天 03:00 的
 * {@link com.minke.mallmanagement.member.job.PointsExpireJob} 把已过期且仍有剩余的批次清零并记负流水。
 *
 * <h2>【成长值 → 等级】</h2>
 * 成长值只增不减（积分消费不回退成长值）。每次获得积分后按
 * {@code member_level.MinGrowth} 重算应得等级；升级写 {@code member_level_log} 并镜像
 * {@code user.MemberLevelNo}。V0–V6 的折扣率 / 积分倍率全部读表，不在代码里硬编码。
 */
@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    /** 签到基础积分。 */
    public static final int SIGNIN_BASE_POINTS = 5;
    /** 连续签到每满 7 天的额外奖励。 */
    public static final int SIGNIN_STREAK_BONUS = 20;
    /** 积分有效期（天）。 */
    public static final int POINTS_EXPIRE_DAYS = 365;
    /** 100 积分 = 1 元。 */
    public static final int POINTS_PER_YUAN = 100;
    /** 单笔订单积分抵扣上限 = 折后价的 20%。 */
    public static final int MAX_POINTS_DISCOUNT_PERCENT = 20;

    private final PointsDao pointsDao;
    private final MemberLevelDao levelDao;
    private final CouponDao couponDao;

    public MemberService(PointsDao pointsDao, MemberLevelDao levelDao, CouponDao couponDao) {
        this.pointsDao = pointsDao;
        this.levelDao = levelDao;
        this.couponDao = couponDao;
    }

    // ==================== 积分商城（2026-10-19 用户令）====================

    /**
     * 积分商城固定货架：三档**不限时**优惠券。前端只渲染本表，不在页面里另抄一份价格
     * （抄一份就等着两处慢慢分叉）。
     */
    public List<Map<String, Object>> exchangeProducts() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(product("coupon5", "满 ¥50 减 ¥5 券", 5, 50, 200));
        list.add(product("coupon10", "满 ¥100 减 ¥10 券", 10, 100, 500));
        list.add(product("coupon20", "满 ¥200 减 ¥20 券", 20, 200, 1000));
        return list;
    }

    private static Map<String, Object> product(String key, String name, int face, int threshold, int cost) {
        Map<String, Object> row = new HashMap<>();
        row.put("key", key);
        row.put("name", name);
        row.put("face", face);
        row.put("threshold", threshold);
        row.put("cost", cost);
        row.put("unlimited", true);
        return row;
    }

    /**
     * 用积分兑换一张**不限时**优惠券。
     *
     * <p>券是**每次兑换新建一行定义**（{@code CouponId} 随机）而不是共用一张 —— 共用的那张会撞
     * {@code coupon_grant} 的唯一键 {@code (CouponId, ToAccount)}，同一个人第二次兑换就领不了。
     * {@code StartAt}/{@code EndAt} 都传 {@code null} ⇒ 立即生效、永不过期。
     *
     * @param requestId 客户端幂等号（可空；空则每次视为新的一次兑换）
     */
    @Transactional
    public Map<String, Object> exchangeCoupon(String customerId, String productKey, String requestId) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        Map<String, Object> product = null;
        for (Map<String, Object> p : exchangeProducts()) {
            if (str(p.get("key")).equals(str(productKey))) {
                product = p;
                break;
            }
        }
        if (product == null) {
            return fail("积分商品不存在");
        }
        String username = pointsDao.selectUsername(customerId);
        if (isBlank(username)) {
            return fail("未找到该客户对应的登录账号");
        }
        int cost = intOf(product.get("cost"));
        String bizId = isBlank(requestId) ? "exchange:" + UUID.randomUUID().toString().replace("-", "") : requestId.trim();
        if (pointsDao.selectLogByBiz(customerId, "points_exchange", bizId) != null) {
            Map<String, Object> duplicate = new HashMap<>();
            duplicate.put("success", true);
            duplicate.put("duplicate", true);
            duplicate.put("msg", "该兑换请求已处理");
            return duplicate;
        }
        Boolean spent = spendInternal(customerId, cost, "points_exchange", bizId,
                "积分兑换：" + str(product.get("name")), "customer", customerId);
        if (!Boolean.TRUE.equals(spent)) {
            return fail("积分不足，需要 " + cost + " 积分");
        }
        String couponId = "PS" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        couponDao.insertCoupon(couponId, str(product.get("name")), "threshold",
                (double) intOf(product.get("face")), (double) intOf(product.get("threshold")),
                0, "all", "", "", null, null, "active");
        couponDao.insertGrant(couponId, username, "points_exchange");

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "兑换成功，优惠券已放入券包");
        result.put("couponId", couponId);
        result.put("cost", cost);
        result.put("balance", accountBalance(customerId));
        return result;
    }

    /**
     * 积分 1:1 兑换成长值（用户令「1 积分抵消 1 成长值」）。
     * 积分走 FIFO 扣除（同下单抵扣），成长值只增不减，兑换后立即按新成长值重算等级。
     */
    @Transactional
    public Map<String, Object> exchangeGrowth(String customerId, int points, String requestId) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        if (points <= 0) {
            return fail("兑换成长值的积分必须大于 0");
        }
        if (points > 100000) {
            return fail("单次兑换积分过多（上限 100000）");
        }
        String bizId = isBlank(requestId) ? "growth:" + UUID.randomUUID().toString().replace("-", "") : requestId.trim();
        if (pointsDao.selectLogByBiz(customerId, "points_growth", bizId) != null) {
            Map<String, Object> duplicate = new HashMap<>();
            duplicate.put("success", true);
            duplicate.put("duplicate", true);
            duplicate.put("msg", "该兑换请求已处理");
            return duplicate;
        }
        Boolean spent = spendInternal(customerId, points, "points_growth", bizId,
                "积分兑换成长值 " + points, "customer", customerId);
        if (!Boolean.TRUE.equals(spent)) {
            return fail("积分不足，需要 " + points + " 积分");
        }
        Map<String, Object> locked = pointsDao.lockAccount(customerId);
        if (locked == null) {
            return fail("积分账户不存在");
        }
        long newGrowth = longOf(locked.get("GrowthValue")) + points;
        pointsDao.updateAccount(customerId, longOf(locked.get("Balance")), longOf(locked.get("Frozen")),
                newGrowth, longOf(locked.get("TotalEarned")), longOf(locked.get("TotalSpent")));
        applyLevel(customerId, newGrowth, "points_growth", bizId);

        int levelNo = pointsDao.selectUserLevelNo(customerId);
        Map<String, Object> level = levelDao.selectByLevelNo(levelNo);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("msg", "已兑换 " + points + " 成长值");
        result.put("growth", newGrowth);
        result.put("growthAdded", points);
        result.put("level", level);
        result.put("balance", accountBalance(customerId));
        return result;
    }

    // ==================== 读 ====================

    /** 等级矩阵（前端权益矩阵 / 等级阶梯）。 */
    public Map<String, Object> levels() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", levelDao.selectLevels());
        return result;
    }

    /** 会员中心概览：积分账户 + 当前等级 + 下一等级 + 今日签到态。 */
    @Transactional
    public Map<String, Object> overview(String customerId) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        pointsDao.ensureAccount(customerId);
        Map<String, Object> account = pointsDao.selectAccount(customerId);
        int levelNo = pointsDao.selectUserLevelNo(customerId);
        Map<String, Object> level = levelDao.selectByLevelNo(levelNo);
        Map<String, Object> next = levelDao.selectNextLevel(levelNo);

        long growth = account == null ? 0L : longOf(account.get("GrowthValue"));
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("account", account);
        result.put("level", level);
        result.put("nextLevel", next);
        result.put("growthToNext", next == null ? 0L : Math.max(0L, longOf(next.get("MinGrowth")) - growth));
        result.put("signedToday", pointsDao.selectSignin(customerId, pointsDao.currentDate()) != null);
        result.put("exchangeProducts", exchangeProducts());
        return result;
    }

    public Map<String, Object> logs(String customerId, Integer limit) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        int effective = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        List<Map<String, Object>> rows = pointsDao.selectLogs(customerId, effective);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", rows);
        result.put("total", rows.size());
        result.put("limit", effective);
        return result;
    }

    // ==================== 签到 ====================

    /**
     * 签到。唯一键 {@code (UserId, SignDate)} 防刷；同日重复 ⇒ 明确回答「今日已签到」。
     */
    @Transactional
    public Map<String, Object> signin(String customerId) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        String today = pointsDao.currentDate();
        pointsDao.ensureAccount(customerId);
        if (pointsDao.selectSignin(customerId, today) != null) {
            Map<String, Object> result = fail("今日已签到，明天再来");
            result.put("signedToday", true);
            return result;
        }
        Integer days = pointsDao.daysSinceLastSignin(customerId);
        int streak = (days != null && days == 1)
                ? intOf(pointsDao.selectLatestSigninBefore(customerId, today).get("Streak")) + 1
                : 1;
        int points = SIGNIN_BASE_POINTS + (streak % 7 == 0 ? SIGNIN_STREAK_BONUS : 0);
        pointsDao.insertSignin(customerId, today, points, streak);

        Boolean applied = earnInternal(customerId, points, "signin", today,
                "签到奖励（连续 " + streak + " 天）", "customer", customerId, true);
        Map<String, Object> account = pointsDao.selectAccount(customerId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", applied == null || applied);
        result.put("msg", "签到成功");
        result.put("points", points);
        result.put("streak", streak);
        result.put("balance", account == null ? 0L : longOf(account.get("Balance")));
        result.put("signedToday", true);
        return result;
    }

    // ==================== 订单联动 ====================

    /**
     * 收货后发放积分。判据 {@code order.PointsSettled 0→1} 是唯一入口：
     * CAS 命中才发，重复调用不会重复发。
     */
    @Transactional
    public Map<String, Object> earnForOrder(String orderId) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(orderId)) {
            return fail("缺少参数：OrderId");
        }
        Map<String, Object> order = pointsDao.selectOrderForEarn(orderId);
        if (order == null) {
            return fail("订单不存在");
        }
        if (longOf(order.get("PointsSettled")) != 0L) {
            result.put("success", true);
            result.put("duplicate", true);
            result.put("points", 0);
            return result;
        }
        if (pointsDao.markPointsSettled(orderId) == 0) {
            result.put("success", true);
            result.put("duplicate", true);
            result.put("points", 0);
            return result;
        }

        String customerId = str(order.get("CustomerId"));
        double price = doubleOf(order.get("Price"));
        double rate = pointsRate(customerId);
        int points = (int) Math.floor(price * rate + 1e-9);
        boolean ok = points <= 0 || Boolean.TRUE.equals(earnInternal(customerId, points,
                "order_earn", orderId, "订单收货积分", "system", orderId, true));
        if (!ok) {
            pointsDao.unmarkPointsSettled(orderId);   // 补偿：允许重试
            return fail("积分发放失败，请稍后重试");
        }
        result.put("success", true);
        result.put("points", points);
        result.put("balance", accountBalance(customerId));
        return result;
    }

    /**
     * 下单积分抵扣。{@code points} 由调用方按「折后价 20% 上限」夹过；
     * 这里再校验余额，余额不足 ⇒ {@code false}（调用方整单不建）。
     */
    @Transactional
    public boolean spendForOrder(String customerId, int points, String orderId) {
        if (isBlank(customerId) || points <= 0 || isBlank(orderId)) {
            return points <= 0;   // 不用积分 = 天然成功
        }
        return Boolean.TRUE.equals(spendInternal(customerId, points, "order_spend", orderId,
                "订单积分抵扣", "customer", customerId));
    }

    /** 下单失败时的补偿：把已扣积分加回，**不重复计成长值**。 */
    @Transactional
    public boolean refundSpend(String customerId, int points, String orderId) {
        if (isBlank(customerId) || points <= 0) {
            return true;
        }
        Boolean applied = earnInternal(customerId, points, "order_refund", orderId,
                "订单未建成，积分退回", "system", orderId, false);
        return applied == null || applied;
    }

    /** 每单最多可抵扣的积分（折后价 20% 对应的积分数，按 100 积分 = 1 元）。 */
    public static int maxDiscountPoints(BigDecimal postCouponPrice) {
        if (postCouponPrice == null || postCouponPrice.signum() <= 0) {
            return 0;
        }
        BigDecimal cap = postCouponPrice
                .multiply(BigDecimal.valueOf(MAX_POINTS_DISCOUNT_PERCENT))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
        return cap.multiply(BigDecimal.valueOf(POINTS_PER_YUAN)).intValue();
    }

    /** 积分抵扣金额（元，2 位 HALF_UP；100 积分 = 1 元）。 */
    public static BigDecimal discountOf(int points) {
        if (points <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(points)
                .divide(BigDecimal.valueOf(POINTS_PER_YUAN), 2, RoundingMode.HALF_UP);
    }

    // ==================== 积分原语 ====================

    /**
     * 发放积分（幂等）。返回 {@code null} = 参数不合法；{@code true} = 本次或此前已发；
     * {@code false} = 账户不可用。
     */
    @Transactional
    public Boolean earn(String userId, int points, String bizType, String bizId, String remark) {
        return earnInternal(userId, points, bizType, bizId, remark, "system", bizId, true);
    }

    /**
     * @param countGrowth 是否把本次积分计入成长值（退回类业务传 {@code false}）
     */
    private Boolean earnInternal(String userId, int points, String bizType, String bizId,
                                 String remark, String operatorType, String operatorId,
                                 boolean countGrowth) {
        if (isBlank(userId) || points <= 0 || isBlank(bizType) || isBlank(bizId)) {
            return null;
        }
        pointsDao.ensureAccount(userId);
        Map<String, Object> locked = pointsDao.lockAccount(userId);
        if (locked == null || !"active".equals(str(locked.get("Status")))) {
            return false;
        }
        if (pointsDao.selectLogByBiz(userId, bizType, bizId) != null) {
            return true;   // 幂等命中
        }
        long balance = longOf(locked.get("Balance"));
        long growth = longOf(locked.get("GrowthValue"));
        long earned = longOf(locked.get("TotalEarned"));
        long spent = longOf(locked.get("TotalSpent"));
        long newBalance = balance + points;
        long growthDelta = countGrowth ? points : 0L;
        long newGrowth = growth + growthDelta;

        int inserted = pointsDao.insertLog(userId, points, newBalance, growthDelta, bizType, bizId,
                points, "DATE_ADD(NOW(), INTERVAL " + POINTS_EXPIRE_DAYS + " DAY)",
                nullToEmpty(remark), operatorType, nullToEmpty(operatorId));
        if (inserted == 0) {
            return true;   // 并发下唯一键已落，视作已发
        }
        pointsDao.updateAccount(userId, newBalance, longOf(locked.get("Frozen")), newGrowth,
                earned + points, spent);
        if (countGrowth) {
            applyLevel(userId, newGrowth, bizType, bizId);
        }
        return true;
    }

    /** 扣积分（FIFO）。返回 {@code null} = 参数不合法；{@code true} = 成功或此前已扣；{@code false} = 余额不足。 */
    @Transactional
    public Boolean spend(String userId, int points, String bizType, String bizId, String remark) {
        return spendInternal(userId, points, bizType, bizId, remark, "customer", userId);
    }

    private Boolean spendInternal(String userId, int points, String bizType, String bizId,
                                  String remark, String operatorType, String operatorId) {
        if (isBlank(userId) || points <= 0 || isBlank(bizType) || isBlank(bizId)) {
            return null;
        }
        pointsDao.ensureAccount(userId);
        Map<String, Object> locked = pointsDao.lockAccount(userId);
        if (locked == null || !"active".equals(str(locked.get("Status")))) {
            return false;
        }
        if (pointsDao.selectLogByBiz(userId, bizType, bizId) != null) {
            return true;   // 幂等命中
        }
        long balance = longOf(locked.get("Balance"));
        if (balance < points) {
            return false;
        }

        // FIFO：先过期先扣，递减每个批次的 RemainAmount。
        long remaining = points;
        List<Map<String, Object>> lots = new ArrayList<>(pointsDao.selectConsumableLots(userId));
        for (Map<String, Object> lot : lots) {
            if (remaining <= 0) {
                break;
            }
            long lotRemain = longOf(lot.get("RemainAmount"));
            long take = Math.min(lotRemain, remaining);
            pointsDao.updateLogRemain(longOf(lot.get("Id")), lotRemain - take);
            remaining -= take;
        }
        long newBalance = balance - points;
        int inserted = pointsDao.insertLog(userId, -points, newBalance, 0, bizType, bizId,
                0, "NULL", nullToEmpty(remark), operatorType, nullToEmpty(operatorId));
        if (inserted == 0) {
            return true;
        }
        pointsDao.updateAccount(userId, newBalance, longOf(locked.get("Frozen")),
                longOf(locked.get("GrowthValue")), longOf(locked.get("TotalEarned")),
                longOf(locked.get("TotalSpent")) + points);
        return true;
    }

    /**
     * 按成长值重算等级并镜像。只升不降（成长值只增不减 ⇒ 天然单调）。
     */
    private void applyLevel(String userId, long growth, String bizType, String bizId) {
        Map<String, Object> target = levelDao.selectByGrowth(growth);
        if (target == null) {
            return;
        }
        int targetNo = intOf(target.get("LevelNo"));
        int currentNo = pointsDao.selectUserLevelNo(userId);
        if (targetNo <= currentNo) {
            return;
        }
        pointsDao.mirrorUser(userId, targetNo, growth);
        pointsDao.insertLevelLog(userId, currentNo, targetNo, bizType, bizId, growth,
                "成长值达到 " + growth + "，升级为 " + str(target.get("Name")));
    }

    // ==================== 过期 ====================

    /**
     * 处理所有已过期批次。由 {@code PointsExpireJob} 每日 03:00 调用；也可手工触发。
     *
     * @return 处理的批次数（0 = 没有过期积分）
     */
    @Transactional
    public int expireAll() {
        List<Map<String, Object>> lots = pointsDao.selectExpiredLots();
        int handled = 0;
        for (Map<String, Object> lot : lots) {
            long id = longOf(lot.get("Id"));
            String userId = str(lot.get("UserId"));
            long remain = longOf(lot.get("RemainAmount"));
            if (userId.isEmpty() || remain <= 0) {
                continue;
            }
            Map<String, Object> locked = pointsDao.lockAccount(userId);
            if (locked == null) {
                continue;
            }
            String bizId = "lot:" + id;
            if (pointsDao.selectLogByBiz(userId, "expire", bizId) != null) {
                pointsDao.updateLogRemain(id, 0);
                continue;
            }
            long balance = longOf(locked.get("Balance"));
            long deduct = Math.min(balance, remain);
            long newBalance = balance - deduct;
            pointsDao.updateLogRemain(id, 0);
            if (deduct > 0) {
                pointsDao.insertLog(userId, -deduct, newBalance, 0, "expire", bizId,
                        0, "NULL", "积分过期", "system", "expire-job");
                pointsDao.updateAccount(userId, newBalance, longOf(locked.get("Frozen")),
                        longOf(locked.get("GrowthValue")), longOf(locked.get("TotalEarned")),
                        longOf(locked.get("TotalSpent")));
            }
            handled++;
        }
        if (handled > 0) {
            log.info("积分过期处理完成：{} 个批次", handled);
        }
        return handled;
    }

    // ==================== 内部 ====================

    private double pointsRate(String customerId) {
        try {
            int levelNo = pointsDao.selectUserLevelNo(customerId);
            Map<String, Object> level = levelDao.selectByLevelNo(levelNo);
            if (level != null && level.get("PointsRate") instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Exception e) {
            log.warn("积分倍率读取失败（MEMBER）：customerId={} —— 本次按 1.0 发放", customerId, e);
        }
        return 1.0d;
    }

    private long accountBalance(String userId) {
        Map<String, Object> account = pointsDao.selectAccount(userId);
        return account == null ? 0L : longOf(account.get("Balance"));
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0d;
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
}
