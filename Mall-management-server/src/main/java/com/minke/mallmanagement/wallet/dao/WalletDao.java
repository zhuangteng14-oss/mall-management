package com.minke.mallmanagement.wallet.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 钱包域的数据出口（第三批㉙，2026-10-19）。
 *
 * <h2>【表名前缀】</h2>
 * {@code user_wallet} / {@code user_balance_log} / {@code wallet_recharge_order} 都是本批新表
 * ⇒ 裸表名（同 {@code merchant} / {@code marketing} 新域口径）。{@code user} 是旧表 ⇒ 保留
 * {@code mall_management_sql.} 前缀（与 {@code OrderRefundDao.creditBalance} 同源）。
 *
 * <h2>【账本优先：余额变动必须走 {@link #lockAccount} + {@link #insertLog}】</h2>
 * 本类只提供「行锁 → 更新 → 记流水」三段原语，**不在这里组合**：
 * 组合是 {@code WalletService} 的事务边界。任何直接 {@code UPDATE user_wallet SET Available=...}
 * 而不记流水的写法都算破账。
 */
@Repository
public class WalletDao {

    private final JdbcClient jdbcClient;

    public WalletDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 账户 ====================

    /** 懒建账户：已存在则零行（{@code INSERT IGNORE}）。 */
    public int ensureAccount(String userId) {
        String sql = "INSERT IGNORE INTO user_wallet "
                + "(UserId, Available, Frozen, TotalRecharge, TotalRefund, TotalConsume, TotalWithdraw, Version, Status) "
                + "VALUES (?, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0, 'active')";
        return jdbcClient.sql(sql).param(userId).update();
    }

    /** 读账户（不存在 ⇒ {@code null}）。 */
    public Map<String, Object> selectAccount(String userId) {
        String sql = "SELECT UserId, Available, Frozen, TotalRecharge, TotalRefund, TotalConsume, "
                + "TotalWithdraw, Version, Status, "
                + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
                + "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at "
                + "FROM user_wallet WHERE UserId = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(userId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行锁读账户。**必须在事务内调用**，否则 {@code FOR UPDATE} 在 autocommit 下等于没锁。
     * 所有余额写路径都先过这里 —— 同一用户的并发充值 / 扣款 / 退款由此串行。
     */
    public Map<String, Object> lockAccount(String userId) {
        String sql = "SELECT UserId, Available, Frozen, TotalRecharge, TotalRefund, TotalConsume, "
                + "TotalWithdraw, Version, Status "
                + "FROM user_wallet WHERE UserId = ? FOR UPDATE";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(userId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 账户余额与累计项一次性更新（行已锁，无需 version CAS）。 */
    public int updateAccount(String userId, BigDecimal available, BigDecimal frozen,
                             BigDecimal totalRecharge, BigDecimal totalRefund,
                             BigDecimal totalConsume, BigDecimal totalWithdraw) {
        String sql = "UPDATE user_wallet SET Available = ?, Frozen = ?, TotalRecharge = ?, "
                + "TotalRefund = ?, TotalConsume = ?, TotalWithdraw = ?, Version = Version + 1 "
                + "WHERE UserId = ?";
        return jdbcClient.sql(sql)
                .params(available, frozen, totalRecharge, totalRefund, totalConsume, totalWithdraw, userId)
                .update();
    }

    /** 权威账户 → {@code user.Balance} 的事务内镜像。 */
    public int mirrorUserBalance(String userId, BigDecimal available) {
        String sql = "UPDATE mall_management_sql.`user` SET Balance = ? WHERE customerid = ?";
        return jdbcClient.sql(sql).params(available, userId).update();
    }

    // ==================== 流水 ====================

    /** 幂等键查流水（已记账 ⇒ 返回原记录，调用方不再动余额）。 */
    public Map<String, Object> selectLogByIdempotencyKey(String key) {
        String sql = "SELECT Id, UserId, ChangeType, Direction, Amount, BalanceBefore, BalanceAfter, "
                + "BizType, BizId, IdempotencyKey, Status "
                + "FROM user_balance_log WHERE IdempotencyKey = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(key).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 追加一条流水（{@code IdempotencyKey} 唯一键是最后一道防线）。 */
    public int insertLog(String userId, String changeType, int direction, BigDecimal amount,
                         BigDecimal balanceBefore, BigDecimal balanceAfter,
                         BigDecimal frozenBefore, BigDecimal frozenAfter,
                         String bizType, String bizId, String idempotencyKey,
                         String channel, String status, String remark,
                         String operatorType, String operatorId) {
        String sql = "INSERT INTO user_balance_log "
                + "(UserId, ChangeType, Direction, Amount, BalanceBefore, BalanceAfter, "
                + " FrozenBefore, FrozenAfter, BizType, BizId, IdempotencyKey, Channel, Status, "
                + " Remark, OperatorType, OperatorId) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(userId, changeType, direction, amount, balanceBefore, balanceAfter,
                        frozenBefore, frozenAfter, bizType, bizId, idempotencyKey, channel, status,
                        remark, operatorType, operatorId)
                .update();
    }

    /** 用户流水（时间倒序）。 */
    public List<Map<String, Object>> selectLogs(String userId, int limit) {
        String sql = "SELECT Id, ChangeType, Direction, Amount, BalanceBefore, BalanceAfter, "
                + "BizType, BizId, Channel, Status, Remark, "
                + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
                + "FROM user_balance_log WHERE UserId = ? ORDER BY Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(userId, limit).query().listOfRows();
    }

    // ==================== 沙箱充值单 ====================

    /** 同一用户 + 同一客户端 RequestId 的充值单（幂等的判据）。 */
    public Map<String, Object> selectRechargeByRequest(String userId, String requestId) {
        String sql = "SELECT Id, RechargeId, UserId, Amount, Channel, Status, RequestId, ClientIp, "
                + "DATE_FORMAT(CompletedAt, '%Y-%m-%d %H:%i:%s') AS CompletedAt, FailReason, "
                + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
                + "FROM wallet_recharge_order WHERE UserId = ? AND RequestId = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).params(userId, requestId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int insertRecharge(String rechargeId, String userId, BigDecimal amount,
                              String requestId, String clientIp) {
        String sql = "INSERT INTO wallet_recharge_order "
                + "(RechargeId, UserId, Amount, Channel, Status, RequestId, ClientIp) "
                + "VALUES (?, ?, ?, 'sandbox', 'pending', ?, ?)";
        return jdbcClient.sql(sql).params(rechargeId, userId, amount, requestId, clientIp).update();
    }

    public int markRechargeSuccess(String rechargeId) {
        String sql = "UPDATE wallet_recharge_order SET Status = 'success', CompletedAt = NOW(3) "
                + "WHERE RechargeId = ? AND Status = 'pending'";
        return jdbcClient.sql(sql).param(rechargeId).update();
    }

    /** 当日成功充值笔数（库时钟）。 */
    public int countRechargeToday(String userId) {
        String sql = "SELECT COUNT(*) FROM wallet_recharge_order "
                + "WHERE UserId = ? AND Status = 'success' AND created_at >= CURDATE()";
        return jdbcClient.sql(sql).param(userId).query(Integer.class).single();
    }

    /** 当日成功充值金额合计。 */
    public BigDecimal sumRechargeToday(String userId) {
        String sql = "SELECT COALESCE(SUM(Amount), 0.00) FROM wallet_recharge_order "
                + "WHERE UserId = ? AND Status = 'success' AND created_at >= CURDATE()";
        return jdbcClient.sql(sql).param(userId).query(BigDecimal.class).single();
    }

    /** 距上一次成功充值是否不足 {@code seconds} 秒。 */
    public boolean rechargedWithin(String userId, int seconds) {
        String sql = "SELECT COUNT(*) FROM wallet_recharge_order "
                + "WHERE UserId = ? AND Status = 'success' "
                + "AND created_at >= NOW() - INTERVAL ? SECOND";
        return jdbcClient.sql(sql).params(userId, seconds).query(Integer.class).single() > 0;
    }

    /** 同一 IP 在窗口内的成功充值笔数（简易风控）。 */
    public int countRechargeByIp(String clientIp, int minutes) {
        String sql = "SELECT COUNT(*) FROM wallet_recharge_order "
                + "WHERE ClientIp = ? AND Status = 'success' "
                + "AND created_at >= NOW() - INTERVAL ? MINUTE";
        return jdbcClient.sql(sql).params(clientIp, minutes).query(Integer.class).single();
    }
}
