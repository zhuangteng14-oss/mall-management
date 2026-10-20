package com.minke.mallmanagement.merchant.brand.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 品牌档案 {@code merchant_brand_profile} 与品牌申请明细
 * {@code merchant_brand_application_detail} 的数据出口（第三批㉗，2026-10-19）。
 *
 * <p>{@code MerchantId} 唯一 ⇒ 一个商家只有一份品牌档案；申请可以有多条（重申链靠
 * {@code ParentId} 串起来），明细表 {@code ApplicationId} 唯一。
 *
 * <p>⚠️ 本域沿用 {@code merchant/} 既有写法：**不带** {@code mall_management_sql.} 前缀
 * （与 {@code MerchantApplicationDao} 同判据，见该类注释）。
 */
@Repository
public class BrandProfileDao {

    private static final String PROFILE_COLUMNS =
            "Id, MerchantId, BrandName, BrandNameEn, TrademarkNo, TrademarkClass, BrandLevel, "
                    + "LogoUrl, LicenseUrl, MainCategory, Status, SourceApplicationId, "
                    + "DATE_FORMAT(ApprovedAt, '%Y-%m-%d %H:%i:%s') AS ApprovedAt, "
                    + "DATE_FORMAT(ValidFrom,  '%Y-%m-%d %H:%i:%s') AS ValidFrom, "
                    + "DATE_FORMAT(ValidTo,    '%Y-%m-%d %H:%i:%s') AS ValidTo, "
                    + "RevokeReason, Version ";

    private final JdbcClient jdbcClient;

    public BrandProfileDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 档案 ====================

    public Map<String, Object> selectByMerchant(String merchantId) {
        String sql = "SELECT " + PROFILE_COLUMNS + "FROM merchant_brand_profile WHERE MerchantId = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(merchantId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 审核通过时落档案。{@code MerchantId} 唯一 ⇒ 用 upsert：
     * 重申通过会覆盖旧档案并恢复 {@code active}、清空撤销理由。
     */
    public int upsertApproved(String merchantId, String brandName, String brandNameEn,
                              String trademarkNo, String trademarkClass, String brandLevel,
                              String logoUrl, String licenseUrl, String mainCategory,
                              Long sourceApplicationId, LocalDateTime approvedAt) {
        String sql = "INSERT INTO merchant_brand_profile "
                + "(MerchantId, BrandName, BrandNameEn, TrademarkNo, TrademarkClass, BrandLevel, "
                + " LogoUrl, LicenseUrl, MainCategory, Status, SourceApplicationId, ApprovedAt, "
                + " ValidFrom, ValidTo, RevokeReason, Version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, NULL, '', 1) "
                + "ON DUPLICATE KEY UPDATE "
                + "BrandName = VALUES(BrandName), BrandNameEn = VALUES(BrandNameEn), "
                + "TrademarkNo = VALUES(TrademarkNo), TrademarkClass = VALUES(TrademarkClass), "
                + "BrandLevel = VALUES(BrandLevel), LogoUrl = VALUES(LogoUrl), LicenseUrl = VALUES(LicenseUrl), "
                + "MainCategory = VALUES(MainCategory), Status = 'active', SourceApplicationId = VALUES(SourceApplicationId), "
                + "ApprovedAt = VALUES(ApprovedAt), ValidFrom = VALUES(ValidFrom), ValidTo = NULL, "
                + "RevokeReason = '', Version = Version + 1";
        return jdbcClient.sql(sql)
                .params(merchantId, brandName, nullToEmpty(brandNameEn), nullToEmpty(trademarkNo),
                        nullToEmpty(trademarkClass), nullToEmpty(brandLevel), nullToEmpty(logoUrl),
                        nullToEmpty(licenseUrl), nullToEmpty(mainCategory), sourceApplicationId,
                        approvedAt, approvedAt)
                .update();
    }

    public int revoke(String merchantId, String reason) {
        String sql = "UPDATE merchant_brand_profile SET Status = 'revoked', RevokeReason = ?, "
                + "Version = Version + 1 WHERE MerchantId = ? AND Status <> 'revoked'";
        return jdbcClient.sql(sql).params(nullToEmpty(reason), merchantId).update();
    }

    /** 续期 / 恢复：{@code validTo == null} ⇒ 长期有效。 */
    public int renew(String merchantId, LocalDateTime validTo) {
        String sql = "UPDATE merchant_brand_profile SET Status = 'active', ValidTo = ?, "
                + "RevokeReason = '', Version = Version + 1 WHERE MerchantId = ?";
        return jdbcClient.sql(sql).params(validTo, merchantId).update();
    }

    // ==================== 申请明细 ====================

    public int insertDetail(long applicationId, String brandName, String brandNameEn,
                            String trademarkNo, String trademarkClass, String brandLevel,
                            String logoUrl, String licenseUrl, String mainCategory, String remark) {
        String sql = "INSERT INTO merchant_brand_application_detail "
                + "(ApplicationId, BrandName, BrandNameEn, TrademarkNo, TrademarkClass, BrandLevel, "
                + " LogoUrl, LicenseUrl, MainCategory, Remark) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(applicationId, brandName, nullToEmpty(brandNameEn), nullToEmpty(trademarkNo),
                        nullToEmpty(trademarkClass), nullToEmpty(brandLevel), nullToEmpty(logoUrl),
                        nullToEmpty(licenseUrl), nullToEmpty(mainCategory), nullToEmpty(remark))
                .update();
    }

    public Map<String, Object> selectDetail(long applicationId) {
        String sql = "SELECT Id, ApplicationId, BrandName, BrandNameEn, TrademarkNo, TrademarkClass, "
                + "BrandLevel, LogoUrl, LicenseUrl, MainCategory, Remark, "
                + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
                + "FROM merchant_brand_application_detail WHERE ApplicationId = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(applicationId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 取某次申请审核通过后要写档案的全部字段（申请明细 + 申请主体）。 */
    public Map<String, Object> selectApprovePayload(long applicationId) {
        String sql = "SELECT a.Id, a.Username, a.ShopName, a.BatchId, "
                + "u.merchantid AS MerchantId, "
                + "d.BrandName, d.BrandNameEn, d.TrademarkNo, d.TrademarkClass, d.BrandLevel, "
                + "d.LogoUrl, d.LicenseUrl, d.MainCategory "
                + "FROM merchant_application a "
                + "JOIN `user` u ON u.username = a.Username AND u.merchantid IS NOT NULL AND u.merchantid <> '' "
                + "LEFT JOIN merchant_brand_application_detail d ON d.ApplicationId = a.Id "
                + "WHERE a.Id = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(applicationId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 冷却 / 次数 ====================

    /** 品牌申请总数（用于 {@code AttemptNo}）。 */
    public int countBrandApplications(String username) {
        return jdbcClient.sql("SELECT COUNT(*) FROM merchant_application "
                        + "WHERE Username = ? AND ApplicationType = 'brand'")
                .param(username).query(Integer.class).single();
    }

    /** 最近 N 天内的品牌申请数（30 天 3 次的判据）。 */
    public int countBrandApplicationsSince(String username, int days) {
        String sql = "SELECT COUNT(*) FROM merchant_application "
                + "WHERE Username = ? AND ApplicationType = 'brand' "
                + "AND SubmittedAt >= DATE_SUB(NOW(), INTERVAL ? DAY)";
        return jdbcClient.sql(sql).params(username, days).query(Integer.class).single();
    }

    public Map<String, Object> selectLatestBrandApplication(String username) {
        String sql = "SELECT Id, Username, ShopName, Status, RejectReason, AttemptNo, ParentId, "
                + "DATE_FORMAT(SubmittedAt, '%Y-%m-%d %H:%i:%s') AS SubmittedAt, "
                + "DATE_FORMAT(ReviewedAt,  '%Y-%m-%d %H:%i:%s') AS ReviewedAt "
                + "FROM merchant_application WHERE Username = ? AND ApplicationType = 'brand' "
                + "ORDER BY Id DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> selectPendingBrandApplication(String username) {
        String sql = "SELECT Id, Status, "
                + "DATE_FORMAT(SubmittedAt, '%Y-%m-%d %H:%i:%s') AS SubmittedAt "
                + "FROM merchant_application WHERE Username = ? AND ApplicationType = 'brand' "
                + "AND Status = 'pending' ORDER BY Id DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 24h 冷却判据：最近一次**被驳回**的品牌申请若在窗口内，则暂不可重申。 */
    public Map<String, Object> selectLatestRejectedBrandApplication(String username) {
        String sql = "SELECT Id, RejectReason, "
                + "DATE_FORMAT(ReviewedAt, '%Y-%m-%d %H:%i:%s') AS ReviewedAt "
                + "FROM merchant_application WHERE Username = ? AND ApplicationType = 'brand' "
                + "AND Status = 'rejected' ORDER BY Id DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
