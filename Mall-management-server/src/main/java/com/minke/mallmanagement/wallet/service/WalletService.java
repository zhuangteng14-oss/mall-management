package com.minke.mallmanagement.wallet.service;

import com.minke.mallmanagement.shared.Money;
import com.minke.mallmanagement.wallet.dao.WalletDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 钱包域服务（第三批㉙，2026-10-19）。
 *
 * <h2>【权威账户 = {@code user_wallet}，{@code user.Balance} 是同事务镜像】</h2>
 * 所有余额变动都走同一条链：
 * <pre>
 *   开事务 → 懒建账户 → SELECT ... FOR UPDATE（按用户串行）
 *          → 幂等键查流水（已记 ⇒ 原样返回）
 *          → 余额足够？ → 更新账户 + 追加流水 + 镜像 user.Balance
 * </pre>
 * 三步写在同一事务里 ⇒ 不会出现「钱加了、流水没记」或「流水记了、余额没加」。
 * <b>任何绕过本类直接改 {@code user.Balance} 的代码都算破账</b>（旧 {@code OrderRefundDao.creditBalance}
 * 已在本批改为调用 {@link #credit}）。
 *
 * <h2>【沙箱充值风控（用户批准的 11 项决策）】</h2>
 * <ul>
 *   <li>单笔 10–1000 元；</li>
 *   <li>当日成功充值金额 ≤ 5000 元、笔数 ≤ 10 笔；</li>
 *   <li>两次成功充值间隔 ≥ 3 秒；</li>
 *   <li>同一 IP 10 分钟内成功充值 ≤ 5 笔；</li>
 *   <li>{@code (UserId, RequestId)} 唯一 + 流水 {@code IdempotencyKey} 唯一 ⇒ 重复点击只入账一次。</li>
 * </ul>
 *
 * <p>⚠️ 本类<b>不在订单锁临界区内</b>：{@code OrderService.addLocked} 调用 {@link #debit} 时，
 * 事务在 {@code debit} 返回前提交（锁仍持有），不会改变「INSERT 早于锁释放」的既有性质。
 */
@Service
public class WalletService {

    /** 单笔充值下限 / 上限（元）。 */
    public static final BigDecimal MIN_RECHARGE = new BigDecimal("10.00");
    public static final BigDecimal MAX_RECHARGE = new BigDecimal("1000.00");
    /** 当日累计上限 / 笔数上限。 */
    public static final BigDecimal DAILY_RECHARGE_LIMIT = new BigDecimal("5000.00");
    public static final int DAILY_RECHARGE_COUNT = 10;
    /** 两次充值最小间隔（秒）。 */
    public static final int RECHARGE_INTERVAL_SECONDS = 3;
    /** 同一 IP 10 分钟内最多成功充值笔数。 */
    public static final int IP_RECHARGE_LIMIT = 5;
    public static final int IP_WINDOW_MINUTES = 10;

    private final WalletDao walletDao;

    public WalletService(WalletDao walletDao) {
        this.walletDao = walletDao;
    }

    // ==================== 读 ====================

    /** 钱包概览（账户不存在 ⇒ 懒建一条零余额账户，保证响应形状恒定）。 */
    @Transactional
    public Map<String, Object> overview(String customerId) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        walletDao.ensureAccount(customerId);
        Map<String, Object> account = walletDao.selectAccount(customerId);
        result.put("success", true);
        result.put("wallet", account);
        result.put("logs", walletDao.selectLogs(customerId, 5));
        return result;
    }

    /** 余额流水（时间倒序）。 */
    public Map<String, Object> logs(String customerId, Integer limit) {
        Map<String, Object> result = new HashMap<>();
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        int effective = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        List<Map<String, Object>> rows = walletDao.selectLogs(customerId, effective);
        result.put("success", true);
        result.put("list", rows);
        result.put("total", rows.size());
        result.put("limit", effective);
        return result;
    }

    // ==================== 写：沙箱充值 ====================

    /**
     * 沙箱充值。{@code RequestId} 是客户端生成的幂等号（同一号重复提交只入账一次）。
     */
    @Transactional
    public Map<String, Object> recharge(String customerId, BigDecimal amount, String requestId, String clientIp) {
        if (isBlank(customerId)) {
            return fail("缺少参数：CustomerId");
        }
        if (amount == null) {
            return fail("缺少参数：Amount");
        }
        BigDecimal money = Money.norm(amount);
        if (money.compareTo(MIN_RECHARGE) < 0 || money.compareTo(MAX_RECHARGE) > 0) {
            return fail("单笔充值金额需在 " + MIN_RECHARGE.toPlainString()
                    + "–" + MAX_RECHARGE.toPlainString() + " 元之间");
        }
        if (isBlank(requestId) || requestId.trim().length() < 8 || requestId.trim().length() > 64) {
            return fail("请求幂等号不合法（8–64 位）");
        }
        String req = requestId.trim();

        walletDao.ensureAccount(customerId);
        Map<String, Object> locked = walletDao.lockAccount(customerId);
        if (locked == null) {
            return fail("钱包账户不存在");
        }
        if (!"active".equals(str(locked.get("Status")))) {
            return fail("钱包账户已被冻结，请联系客服");
        }

        // 幂等：同一用户 + 同一 RequestId 只入账一次。
        Map<String, Object> existing = walletDao.selectRechargeByRequest(customerId, req);
        if (existing != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("duplicate", true);
            result.put("rechargeId", existing.get("RechargeId"));
            result.put("status", existing.get("Status"));
            result.put("amount", existing.get("Amount"));
            return result;
        }

        // 风控：3 秒间隔 / 当日金额与笔数 / IP 窗口。
        if (walletDao.rechargedWithin(customerId, RECHARGE_INTERVAL_SECONDS)) {
            return fail("操作过于频繁，请 " + RECHARGE_INTERVAL_SECONDS + " 秒后再试");
        }
        int todayCount = walletDao.countRechargeToday(customerId);
        if (todayCount >= DAILY_RECHARGE_COUNT) {
            return fail("今日充值笔数已达上限（" + DAILY_RECHARGE_COUNT + " 笔）");
        }
        BigDecimal todaySum = walletDao.sumRechargeToday(customerId);
        if (Money.norm(todaySum).add(money).compareTo(DAILY_RECHARGE_LIMIT) > 0) {
            return fail("今日充值金额将超过上限（" + DAILY_RECHARGE_LIMIT.toPlainString() + " 元）");
        }
        if (!isBlank(clientIp) && walletDao.countRechargeByIp(clientIp, IP_WINDOW_MINUTES) >= IP_RECHARGE_LIMIT) {
            return fail("当前网络充值过于频繁，请稍后再试");
        }

        String rechargeId = "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        walletDao.insertRecharge(rechargeId, customerId, money, req, clientIp == null ? "" : clientIp);

        BigDecimal before = money(locked.get("Available"));
        BigDecimal after = before.add(money);
        BigDecimal frozen = money(locked.get("Frozen"));
        walletDao.updateAccount(customerId, after, frozen,
                money(locked.get("TotalRecharge")).add(money),
                money(locked.get("TotalRefund")),
                money(locked.get("TotalConsume")),
                money(locked.get("TotalWithdraw")));
        walletDao.insertLog(customerId, "recharge", 1, money, before, after, frozen, frozen,
                "wallet_recharge", rechargeId, "recharge:" + rechargeId,
                "sandbox", "success", "沙箱充值", "customer", customerId);
        walletDao.mirrorUserBalance(customerId, after);
        walletDao.markRechargeSuccess(rechargeId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("rechargeId", rechargeId);
        result.put("amount", money);
        result.put("balanceAfter", after);
        result.put("status", "success");
        return result;
    }

    /**
     * 提现 —— **合规占位**（P2 预留表已建，本期不提供写路径）。
     * 明确回答「未开放」，而不是伪造一条待审核记录。
     */
    public Map<String, Object> withdraw(String customerId, BigDecimal amount) {
        return fail("提现功能正在合规审核中，暂未开放；如需帮助请联系客服");
    }

    // ==================== 账本原语（退款 / 下单复用） ====================

    /**
     * 入账（退款 / 管理员调整）。**幂等**：同一 {@code idempotencyKey} 只记一次。
     *
     * @return {@code true} = 本次记账或此前已记过；{@code false} = 参数不合法 / 账户冻结
     */
    @Transactional
    public boolean credit(String userId, BigDecimal amount, String changeType, String bizType, String bizId,
                          String idempotencyKey, String channel, String remark,
                          String operatorType, String operatorId) {
        return change(userId, amount, 1, changeType, bizType, bizId, idempotencyKey,
                channel, remark, operatorType, operatorId);
    }

    /**
     * 出账（下单余额支付）。余额不足 ⇒ {@code false}，**不写任何行**。
     * 幂等语义同上：同一 {@code idempotencyKey} 已记过 ⇒ 视为成功（不再扣第二次）。
     */
    @Transactional
    public boolean debit(String userId, BigDecimal amount, String changeType, String bizType, String bizId,
                         String idempotencyKey, String channel, String remark,
                         String operatorType, String operatorId) {
        return change(userId, amount, -1, changeType, bizType, bizId, idempotencyKey,
                channel, remark, operatorType, operatorId);
    }

    /**
     * 余额变动唯一实现。
     *
     * @param direction +1 入账 / -1 出账
     */
    @Transactional
    public boolean change(String userId, BigDecimal amount, int direction, String changeType,
                          String bizType, String bizId, String idempotencyKey,
                          String channel, String remark, String operatorType, String operatorId) {
        if (isBlank(userId) || amount == null || !Money.positive(amount) || isBlank(idempotencyKey)) {
            return false;
        }
        BigDecimal money = Money.norm(amount);
        walletDao.ensureAccount(userId);
        Map<String, Object> locked = walletDao.lockAccount(userId);
        if (locked == null || !"active".equals(str(locked.get("Status")))) {
            return false;
        }
        if (walletDao.selectLogByIdempotencyKey(idempotencyKey) != null) {
            return true;   // 幂等命中：这笔业务此前已经记过
        }

        BigDecimal before = money(locked.get("Available"));
        BigDecimal frozen = money(locked.get("Frozen"));
        BigDecimal after;
        if (direction > 0) {
            after = before.add(money);
        } else {
            if (before.compareTo(money) < 0) {
                return false;   // 余额不足 —— 不入账、不记流水
            }
            after = before.subtract(money);
        }

        BigDecimal totalRecharge = money(locked.get("TotalRecharge"));
        BigDecimal totalRefund = money(locked.get("TotalRefund"));
        BigDecimal totalConsume = money(locked.get("TotalConsume"));
        BigDecimal totalWithdraw = money(locked.get("TotalWithdraw"));
        if ("recharge".equals(changeType)) {
            totalRecharge = totalRecharge.add(money);
        } else if ("refund".equals(changeType)) {
            totalRefund = totalRefund.add(money);
        } else if ("consume".equals(changeType)) {
            totalConsume = totalConsume.add(money);
        } else if ("withdraw_settle".equals(changeType)) {
            totalWithdraw = totalWithdraw.add(money);
        }

        walletDao.updateAccount(userId, after, frozen, totalRecharge, totalRefund, totalConsume, totalWithdraw);
        walletDao.insertLog(userId, changeType, direction > 0 ? 1 : -1, money, before, after,
                frozen, frozen, nullToEmpty(bizType), nullToEmpty(bizId), idempotencyKey.trim(),
                nullToEmpty(channel), "success", nullToEmpty(remark),
                isBlank(operatorType) ? "system" : operatorType, nullToEmpty(operatorId));
        walletDao.mirrorUserBalance(userId, after);
        return true;
    }

    // ==================== 内部 ====================

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("msg", msg);
        return result;
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return Money.zero();
        }
        if (value instanceof BigDecimal b) {
            return Money.norm(b);
        }
        if (value instanceof Number n) {
            return Money.of(n.doubleValue());
        }
        return Money.parse(String.valueOf(value));
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
