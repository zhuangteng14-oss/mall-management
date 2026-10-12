package com.minke.mallmanagement.shop.follow.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 店铺域「关注关系」的数据访问出口（C3 块 5 · B 组）。表 {@code shop_follow} 一行 =
 * 一个买家关注一个商家（{@code UNIQUE (CustomerId, MerchantId)}）。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CartDao} / {@code CouponDao} / {@code TemplateDao}：新域一律**裸表名**。
 * ⚠️ 老域（{@code OrderDao} / {@code GoodsDao}）里的 {@code mall_management_sql.} 前缀是 F#1 的
 * 存量债，**别照抄**。
 *
 * <h2>⚠️ 【本表允许**物理 DELETE**（{@link #delete}）—— 又一处对规则 v2 的明文边界】</h2>
 * 规则 v2 是「**逻辑废弃**，不物理删」。关注关系与购物车行同族：它**不是业务记录**，
 * 是**两方之间的瞬时关系**（没有「被谁改过」的审计价值，也 {@code admin_audit} 不记它），
 * 用户点「已关注 → 取关」的语义**就是**「别再留着」。软删在这里还要付一笔**功能代价**：
 * 唯一键是 {@code (CustomerId, MerchantId)} ⇒ 软删后重新关注会撞键，每条写路径都得先处理「复活」。
 * <p>这条是**用户 2026-10-12 裁定 8**（「取关语义 = 物理 DELETE」），不是我自行破例；
 * 与本类同族的先例是 {@code CartDao.remove}。除这两处外，全仓仍守规则 v2。
 *
 * <h2>【本类不做 {@code @Transactional}】</h2>
 * 三条方法各自是**一条语句**，没有「先占位、再插行」那种两步写（对照 {@code CouponDao.claim}）
 * ⇒ 无补偿腿可写，也无需事务。
 */
@Repository
public class ShopFollowDao {

    private final JdbcClient jdbcClient;

    public ShopFollowDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 关注。⚠️ 撞唯一键（这个人已经关注过这家店）会抛 {@code DuplicateKeyException} ——
     * **不在这里吞**：幂等语义归 {@code ShopFollowService.follow}，本方法只如实反映「插了几行」。
     */
    public int insert(String customerId, String merchantId) {
        return jdbcClient.sql("INSERT INTO shop_follow (CustomerId, MerchantId) VALUES (?, ?)")
                .params(customerId, merchantId)
                .update();
    }

    /** 取关（**物理 DELETE**，理由见类注释）。返回删掉的行数：0 = 本来就没关注。 */
    public int delete(String customerId, String merchantId) {
        return jdbcClient.sql("DELETE FROM shop_follow WHERE CustomerId = ? AND MerchantId = ?")
                .params(customerId, merchantId)
                .update();
    }

    /** 我关不关注这家店。 */
    public boolean exists(String customerId, String merchantId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM shop_follow WHERE CustomerId = ? AND MerchantId = ?")
                .params(customerId, merchantId)
                .query(Long.class).single() > 0;
    }

    /** 这家店的粉丝数（全表口径，不分人）。 */
    public long countFollowers(String merchantId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM shop_follow WHERE MerchantId = ?")
                .param(merchantId)
                .query(Long.class).single();
    }
}
