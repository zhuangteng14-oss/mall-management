package com.minke.mallmanagement.shop.profile.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 店铺域「店铺档案」的数据访问出口（C3 块 5 · B 组续笔）。**纯读**，两条 SQL，各自一张表。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code ShopFollowDao} / {@code CartDao} / {@code CouponDao}：新域一律**裸表名**。
 * ⚠️ 老域里的 {@code mall_management_sql.} 前缀是 F#1 的存量债，**别照抄**。
 *
 * <h2>【⚠️ {@code login} 的业务号在 {@code Id} 列，不在主键上】</h2>
 * {@code login} 的主键是 {@code UserName}（登录名），业务号在 {@code Id}（{@code char(20)}）——
 * 见 {@code mall_management_sql.sql:89-95}。这条极易读反：{@code WHERE UserName = 'dRD3gqtbFr'}
 * **永远 0 行**，而 {@code GoodsCacheService.listHome} 读的是 {@code WHERE id = ?}（那里传的正是
 * 商品的 {@code MerchantId}）。本类沿用后者。
 *
 * <h2>【⚠️ {@code login.Id} 没有唯一约束 ⇒ 本类不对「>1 行」抛异常】</h2>
 * {@code GoodsCacheService.listHome()} 用 {@code queryForObject} 且**刻意只接 0 行**
 * （>1 行抛 {@code IncorrectResultSizeDataAccessException} 上抛）。本类**不照抄**那一条：
 * 一个公开读端点因为脏数据答 500 是把「数据重复」升级成了「页面打不开」。
 * ⇒ 取 {@code ORDER BY UserName LIMIT 1}（**确定性**：同一入参永远同一行，不是随机那一行）。
 *
 * <h2>【{@code CreateTime} 可以是 {@code NULL}】</h2>
 * 该列是后补的，迁移注释明写「补列之前的存量行恒为 NULL（无第二来源，不可回填）」
 * （{@code mall_management_sql.sql:912-919}）⇒ 读出来就是 {@code null}，**不在这里编一个值**。
 * 时间列一律 {@code DATE_FORMAT(...,'%Y-%m-%d %H:%i:%s')} 读成**无时区文本**（全仓口径）。
 */
@Repository
public class ShopProfileDao {

    private final JdbcClient jdbcClient;

    public ShopProfileDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 按商家业务号取「登录名 + 注册时刻」。
     *
     * @return {@code {UserName, RegisteredAt}}；该业务号在 {@code login} 无对应行 ⇒ {@code null}。
     *         {@code RegisteredAt} 可以是 {@code null}（列本身可为 NULL，见类注释）。
     */
    public Map<String, Object> selectMerchant(String merchantId) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT UserName, DATE_FORMAT(CreateTime, '%Y-%m-%d %H:%i:%s') AS RegisteredAt "
                        + "FROM login WHERE Id = ? ORDER BY UserName ASC LIMIT 1")
                .param(merchantId)
                .query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 该登录名**最新一条**入驻申请的审核状态。
     *
     * <p>排序照 {@code MerchantApplicationDao.selectByUsername}（{@code SubmittedAt DESC, Id DESC}）——
     * 同一家店可能提交过多次，「最新一条」才是它现在的状态。
     *
     * @return {@code pending} / {@code approved} / {@code rejected}；**一条申请都没有 ⇒ {@code null}**
     *         （⚠️ 不是 {@code 'pending'} —— 没提交过 ≠ 在审核中，那是两句不同的话）。
     */
    public String selectCertStatus(String userName) {
        List<String> rows = jdbcClient
                .sql("SELECT Status FROM merchant_application WHERE Username = ? "
                        + "ORDER BY SubmittedAt DESC, Id DESC LIMIT 1")
                .param(userName)
                .query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
