package com.minke.mallmanagement.member.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 会员等级矩阵（{@code member_level}，V0–V6）的数据出口（第三批㉘，2026-10-19）。
 *
 * <p>等级是**配置**不是**状态**：{@code user.MemberLevelNo} 是状态，{@code member_level}
 * 是那张「多少成长值升到哪一级、每级几折」的表。折扣率、积分倍率的权威都在这张表，
 * 调用方不要自己写 {@code 0.9}（{@code MemberPricing} 只保留旧 9 折公式作为兼容兜底）。
 */
@Repository
public class MemberLevelDao {

    private final JdbcClient jdbcClient;

    public MemberLevelDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /** 全部启用等级（升序）—— 前端权益矩阵 / 等级阶梯读它。 */
    public List<Map<String, Object>> selectLevels() {
        String sql = "SELECT Id, LevelNo, Code, Name, MinGrowth, DiscountRate, PointsRate, "
                + "MonthlyCoupons, PriorityWeight, Theme, BenefitsJson, Status "
                + "FROM member_level WHERE Status = 'active' ORDER BY LevelNo";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    public Map<String, Object> selectByLevelNo(int levelNo) {
        String sql = "SELECT Id, LevelNo, Code, Name, MinGrowth, DiscountRate, PointsRate, "
                + "MonthlyCoupons, PriorityWeight, Theme, BenefitsJson, Status "
                + "FROM member_level WHERE LevelNo = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(levelNo).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按成长值取**应得**的最高等级（用该用户当前等级作兜底）。 */
    public Map<String, Object> selectByGrowth(long growth) {
        String sql = "SELECT Id, LevelNo, Code, Name, MinGrowth, DiscountRate, PointsRate, "
                + "MonthlyCoupons, PriorityWeight, Theme, BenefitsJson "
                + "FROM member_level WHERE Status = 'active' AND MinGrowth <= ? "
                + "ORDER BY LevelNo DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(growth).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 下一等级（用于「还差 N 成长值」）。 */
    public Map<String, Object> selectNextLevel(int currentLevelNo) {
        String sql = "SELECT LevelNo, Code, Name, MinGrowth FROM member_level "
                + "WHERE Status = 'active' AND LevelNo > ? ORDER BY LevelNo LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(currentLevelNo).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
