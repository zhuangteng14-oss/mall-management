package com.minke.mallmanagement.marketing.discount.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 折扣模板 / 价格快照流水的数据出口（第三批㉖，2026-10-19）。
 *
 * <p>{@code goods_discount_template} 本期只有**平台模板**一个写口（种子写入，管理端暂无 UI），
 * 商家模板位（{@code OwnerType='merchant'}）已在表结构里预留，本类不提供商家写入口。
 *
 * <p>⚠️ {@code goods_price_log} 只追加：提审 / 审核通过 / 改价各记一条，
 * 用于回答「这个划线价是怎么算出来的」—— 模板改配置不影响历史快照。
 */
@Repository
public class DiscountTemplateDao {

    private static final String COLUMNS =
            "Id, TemplateCode, Name, DiscountType, Rate, CutAmount, OwnerType, OwnerId, "
                    + "CategoryScope, MinPrice, MaxPrice, MaxMarkupRate, Status, SortWeight, "
                    + "DATE_FORMAT(ValidFrom, '%Y-%m-%d %H:%i:%s') AS ValidFrom, "
                    + "DATE_FORMAT(ValidTo,   '%Y-%m-%d %H:%i:%s') AS ValidTo ";

    private final JdbcClient jdbcClient;

    public DiscountTemplateDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /** 当前可用的模板（状态 active 且在生效窗口内），按展示权重升序。 */
    public List<Map<String, Object>> selectActive() {
        String sql = "SELECT " + COLUMNS + "FROM goods_discount_template "
                + "WHERE Status = 'active' "
                + "AND (ValidFrom IS NULL OR ValidFrom <= NOW()) "
                + "AND (ValidTo IS NULL OR ValidTo >= NOW()) "
                + "ORDER BY SortWeight, Id";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    /** 按 Id 取模板（**不筛状态** —— 生效判定交给 Service，才能区分「不存在」与「已停用」）。 */
    public Map<String, Object> selectById(long id) {
        String sql = "SELECT " + COLUMNS + "FROM goods_discount_template WHERE Id = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 追加一条价格快照流水（只追加，不更新）。 */
    public int insertPriceLog(Long goodsId, String batchId, String merchantId, String changeType,
                              BigDecimal price, BigDecimal originalPrice,
                              Long discountTemplateId, String discountType, BigDecimal discountRate,
                              BigDecimal discountAmount, String operatorType, String operatorId) {
        String sql = "INSERT INTO goods_price_log "
                + "(GoodsId, BatchId, MerchantId, ChangeType, Price, OriginalPrice, "
                + " DiscountTemplateId, DiscountType, DiscountRate, DiscountAmount, OperatorType, OperatorId, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(goodsId, nullToEmpty(batchId), nullToEmpty(merchantId), nullToEmpty(changeType),
                        price == null ? BigDecimal.ZERO : price,
                        originalPrice == null ? BigDecimal.ZERO : originalPrice,
                        discountTemplateId,
                        nullToEmpty(discountType),
                        discountRate,
                        discountAmount == null ? BigDecimal.ZERO : discountAmount,
                        nullToEmpty(operatorType), nullToEmpty(operatorId), LocalDateTime.now())
                .update();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
