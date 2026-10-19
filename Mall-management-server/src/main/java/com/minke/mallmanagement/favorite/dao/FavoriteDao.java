package com.minke.mallmanagement.favorite.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 商品收藏的数据出口（2026-10-19 用户令 · 终局清道夫第 7 条）。
 *
 * <p>身份键 {@code UserId} 与会员中心同一口径：客户用 {@code user.customerid}、
 * 商家用 {@code user.merchantid}、登录名兜底 —— 前端经 {@code api/auth.js#getMemberId}
 * 取得，后端只做「这是不是你自己的」判据（{@code IdentityGuard}）。
 *
 * <p>表 {@code user_favorite} 的唯一键 {@code (UserId, GoodsId)} 让「重复点收藏」
 * 天然幂等：{@link #add} 用 {@code INSERT IGNORE}，重复点击不报错也不产生第二行。
 */
@Repository
public class FavoriteDao {

    private final JdbcClient jdbcClient;

    public FavoriteDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /** 收藏一件商品。返回 1 = 新收藏，0 = 此前已收藏（幂等）。 */
    public int add(String userId, int goodsId) {
        String sql = "INSERT IGNORE INTO mall_management_sql.user_favorite (UserId, GoodsId) VALUES (?, ?)";
        return jdbcClient.sql(sql).params(userId, goodsId).update();
    }

    /** 取消收藏。返回 1 = 删除成功，0 = 本来就没收藏。 */
    public int remove(String userId, int goodsId) {
        String sql = "DELETE FROM mall_management_sql.user_favorite WHERE UserId = ? AND GoodsId = ?";
        return jdbcClient.sql(sql).params(userId, goodsId).update();
    }

    public boolean exists(String userId, int goodsId) {
        String sql = "SELECT COUNT(*) FROM mall_management_sql.user_favorite WHERE UserId = ? AND GoodsId = ?";
        Integer n = jdbcClient.sql(sql).params(userId, goodsId).query(Integer.class).single();
        return n != null && n > 0;
    }

    /** 商品是否存在且未删除（收藏前校验；已下架但未删除仍可收藏 —— 与详情页可见性同口径）。 */
    public boolean goodsExists(int goodsId) {
        String sql = "SELECT COUNT(*) FROM mall_management_sql.goods WHERE Id = ? AND DeletedAt IS NULL";
        Integer n = jdbcClient.sql(sql).param(goodsId).query(Integer.class).single();
        return n != null && n > 0;
    }

    public int count(String userId) {
        String sql = "SELECT COUNT(*) FROM mall_management_sql.user_favorite WHERE UserId = ?";
        Integer n = jdbcClient.sql(sql).param(userId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /**
     * 收藏列表。左连 {@code goods}：商品被删（{@code DeletedAt} 非空）仍保留收藏行，
     * 由前端显示「商品已下架」——「收藏过」是用户的事实，不随商家删品消失。
     */
    public List<Map<String, Object>> list(String userId, int limit) {
        String sql = "SELECT f.Id, f.GoodsId, DATE_FORMAT(f.created_at, '%Y-%m-%d %H:%i:%s') AS CreatedAt, "
                + "g.MerchantId, g.GoodName, g.Price, g.OriginalPrice, g.GoodImg, g.GoodType, "
                + "g.ProductCode, g.ShelfState, g.Status, g.DeletedAt "
                + "FROM mall_management_sql.user_favorite f "
                + "LEFT JOIN mall_management_sql.goods g ON g.Id = f.GoodsId "
                + "WHERE f.UserId = ? ORDER BY f.created_at DESC, f.Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(userId, limit).query().listOfRows();
    }
}
