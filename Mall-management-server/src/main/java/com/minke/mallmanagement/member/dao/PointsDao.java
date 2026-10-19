package com.minke.mallmanagement.member.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 积分账户 / 流水 / 签到 / 等级变更的数据出口（第三批㉘，2026-10-19）。
 *
 * <h2>【幂等键 {@code (UserId, BizType, BizId)}】</h2>
 * 同一笔业务（同一订单收货、同一天签到）只允许记一次。{@link #insertLog} 用
 * {@code INSERT IGNORE} 返回影响行数：0 = 已记过，Service 据此**不再动余额**。
 *
 * <h2>【余额与流水同事务】</h2>
 * 与钱包同款：先 {@link #lockAccount} 再改余额、再记流水。本类不组合事务，
 * 事务边界在 {@code PointsService}。
 */
@Repository
public class PointsDao {

    private final JdbcClient jdbcClient;

    public PointsDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 账户 ====================

    public int ensureAccount(String userId) {
        String sql = "INSERT IGNORE INTO user_points_account "
                + "(UserId, Balance, Frozen, GrowthValue, TotalEarned, TotalSpent, Version, Status) "
                + "VALUES (?, 0, 0, 0, 0, 0, 0, 'active')";
        return jdbcClient.sql(sql).param(userId).update();
    }

    public Map<String, Object> selectAccount(String userId) {
        String sql = "SELECT UserId, Balance, Frozen, GrowthValue, TotalEarned, TotalSpent, Version, Status, "
                + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
                + "FROM user_points_account WHERE UserId = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(userId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> lockAccount(String userId) {
        String sql = "SELECT UserId, Balance, Frozen, GrowthValue, TotalEarned, TotalSpent, Version, Status "
                + "FROM user_points_account WHERE UserId = ? FOR UPDATE";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(userId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int updateAccount(String userId, long balance, long frozen, long growth,
                             long totalEarned, long totalSpent) {
        String sql = "UPDATE user_points_account SET Balance = ?, Frozen = ?, GrowthValue = ?, "
                + "TotalEarned = ?, TotalSpent = ?, Version = Version + 1 WHERE UserId = ?";
        return jdbcClient.sql(sql)
                .params(balance, frozen, growth, totalEarned, totalSpent, userId).update();
    }

    /** {@code user} 侧的等级 / 成长值镜像（权威仍在 {@code user_points_account}）。 */
    public int mirrorUser(String userId, int levelNo, long growth) {
        String sql = "UPDATE mall_management_sql.`user` SET MemberLevelNo = ?, GrowthValue = ?, "
                + "MemberLevel = CASE WHEN ? > 0 THEN 1 ELSE MemberLevel END, "
                + "MemberSince = CASE WHEN ? > 0 AND MemberSince IS NULL THEN NOW() ELSE MemberSince END "
                + "WHERE customerid = ?";
        return jdbcClient.sql(sql).params(levelNo, growth, levelNo, levelNo, userId).update();
    }

    // ==================== 流水 ====================

    /**
     * 追加流水。返回 0 = 幂等键已存在（本次不记账）。
     */
    public int insertLog(String userId, long changeAmount, long balanceAfter, long growthDelta,
                         String bizType, String bizId, long remainAmount, String expireAtExpression,
                         String remark, String operatorType, String operatorId) {
        String sql = "INSERT IGNORE INTO user_points_log "
                + "(UserId, ChangeAmount, BalanceAfter, GrowthDelta, BizType, BizId, "
                + " RemainAmount, ExpireAt, Remark, OperatorType, OperatorId) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, " + expireAtExpression + ", ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(userId, changeAmount, balanceAfter, growthDelta, bizType, bizId,
                        remainAmount, remark, operatorType, operatorId)
                .update();
    }

    /** 同一业务幂等键的流水（已记 ⇒ 非空）。 */
    public Map<String, Object> selectLogByBiz(String userId, String bizType, String bizId) {
        String sql = "SELECT Id, ChangeAmount, BalanceAfter, GrowthDelta, BizType, BizId, RemainAmount, "
                + "DATE_FORMAT(ExpireAt, '%Y-%m-%d %H:%i:%s') AS ExpireAt "
                + "FROM user_points_log WHERE UserId = ? AND BizType = ? AND BizId = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql)
                .params(userId, bizType, bizId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> selectLogs(String userId, int limit) {
        String sql = "SELECT Id, ChangeAmount, BalanceAfter, GrowthDelta, BizType, BizId, "
                + "RemainAmount, DATE_FORMAT(ExpireAt, '%Y-%m-%d %H:%i:%s') AS ExpireAt, "
                + "Remark, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
                + "FROM user_points_log WHERE UserId = ? ORDER BY Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(userId, limit).query().listOfRows();
    }

    /** FIFO 可消费的获得流水（{@code RemainAmount > 0}，按过期时刻、Id 升序）。 */
    public List<Map<String, Object>> selectConsumableLots(String userId) {
        String sql = "SELECT Id, RemainAmount, DATE_FORMAT(ExpireAt, '%Y-%m-%d %H:%i:%s') AS ExpireAt "
                + "FROM user_points_log WHERE UserId = ? AND ChangeAmount > 0 AND RemainAmount > 0 "
                + "AND (ExpireAt IS NULL OR ExpireAt > NOW()) ORDER BY ExpireAt IS NULL, ExpireAt, Id";
        return jdbcClient.sql(sql).param(userId).query().listOfRows();
    }

    public int updateLogRemain(long id, long remainAmount) {
        return jdbcClient.sql("UPDATE user_points_log SET RemainAmount = ? WHERE Id = ?")
                .params(remainAmount, id).update();
    }

    /** 已过期但仍有剩余的获得流水。 */
    public List<Map<String, Object>> selectExpiredLots() {
        String sql = "SELECT Id, UserId, RemainAmount FROM user_points_log "
                + "WHERE ChangeAmount > 0 AND RemainAmount > 0 AND ExpireAt IS NOT NULL AND ExpireAt <= NOW() "
                + "ORDER BY UserId, Id LIMIT 500";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    // ==================== 签到 ====================

    public Map<String, Object> selectSignin(String userId, String date) {
        String sql = "SELECT Id, Points, Streak, DATE_FORMAT(SignDate, '%Y-%m-%d') AS SignDate "
                + "FROM user_signin WHERE UserId = ? AND SignDate = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).params(userId, date).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该用户在指定日期之前最近一次签到（用于算连续天数）。 */
    public Map<String, Object> selectLatestSigninBefore(String userId, String date) {
        String sql = "SELECT Points, Streak, DATE_FORMAT(SignDate, '%Y-%m-%d') AS SignDate "
                + "FROM user_signin WHERE UserId = ? AND SignDate < ? ORDER BY SignDate DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).params(userId, date).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int insertSignin(String userId, String date, int points, int streak) {
        return jdbcClient.sql("INSERT INTO user_signin (UserId, SignDate, Points, Streak) VALUES (?, ?, ?, ?)")
                .params(userId, date, points, streak).update();
    }

    public int countSigninSince(String userId, String date) {
        return jdbcClient.sql("SELECT COUNT(*) FROM user_signin WHERE UserId = ? AND SignDate >= ?")
                .params(userId, date).query(Integer.class).single();
    }

    /** 距上一次签到过了几天（今天已签 ⇒ 0；从未签 ⇒ {@code null}）。 */
    public Integer daysSinceLastSignin(String userId) {
        String sql = "SELECT DATEDIFF(CURDATE(), MAX(SignDate)) FROM user_signin WHERE UserId = ?";
        return jdbcClient.sql(sql).param(userId).query(Integer.class).optional().orElse(null);
    }

    /** 库时钟的今天（{@code yyyy-MM-dd}）—— 不用 JVM 的钟（口径同 {@code CouponDao.LOCAL_NOW}）。 */
    public String currentDate() {
        return jdbcClient.sql("SELECT DATE_FORMAT(CURDATE(), '%Y-%m-%d')")
                .query(String.class).single();
    }

    // ==================== 订单联动 ====================

    /** 收货发积分所需的订单列（{@code PointsSettled=0} 且已收货）。 */
    public Map<String, Object> selectOrderForEarn(String orderId) {
        String sql = "SELECT OrderId, CustomerId, Price, PointsSettled "
                + "FROM mall_management_sql.`order` WHERE OrderId = ? LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(orderId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 积分结算标记 CAS：{@code 0 → 1}，命中 0 行 = 已结算过。 */
    public int markPointsSettled(String orderId) {
        return jdbcClient.sql("UPDATE mall_management_sql.`order` SET PointsSettled = 1 "
                        + "WHERE OrderId = ? AND PointsSettled = 0")
                .param(orderId).update();
    }

    /** 发积分失败时的补偿腿（把标记放回 0，允许下次重试）。 */
    public int unmarkPointsSettled(String orderId) {
        return jdbcClient.sql("UPDATE mall_management_sql.`order` SET PointsSettled = 0 "
                        + "WHERE OrderId = ? AND PointsSettled = 1")
                .param(orderId).update();
    }

    /** 客户业务号 → 登录名（积分商城发券要用登录名挂 `coupon_grant`）。 */
    public String selectUsername(String userId) {
        String sql = "SELECT username FROM mall_management_sql.`user` WHERE customerid = ? LIMIT 1";
        List<String> rows = jdbcClient.sql(sql).param(userId).query(String.class).list();
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    /** 用户当前等级序号（{@code user.MemberLevelNo}；旧列 {@code MemberLevel} 作兜底）。 */
    public int selectUserLevelNo(String userId) {
        String sql = "SELECT COALESCE(NULLIF(MemberLevelNo, 0), MemberLevel, 0) FROM mall_management_sql.`user` "
                + "WHERE customerid = ? LIMIT 1";
        List<Integer> rows = jdbcClient.sql(sql).param(userId).query(Integer.class).list();
        return rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
    }

    // ==================== 等级变更日志 ====================

    public int insertLevelLog(String userId, int fromLevel, int toLevel, String bizType, String bizId,
                              long growth, String remark) {
        String sql = "INSERT IGNORE INTO member_level_log "
                + "(UserId, FromLevel, ToLevel, BizType, BizId, GrowthValue, Remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(userId, fromLevel, toLevel, bizType, bizId, growth, remark).update();
    }
}
