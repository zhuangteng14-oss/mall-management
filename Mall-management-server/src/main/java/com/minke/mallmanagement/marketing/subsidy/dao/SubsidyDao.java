package com.minke.mallmanagement.marketing.subsidy.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 营销域**国补**的数据访问出口（C3 块 5 · commit 3）。
 *
 * <h2>【两张表都是块 5 A 组就建好的，本笔零建表、零加列】</h2>
 * {@code subsidy_rule}（国补规则：类目 + 比例 + 单笔上限 + 有效期）与
 * {@code subsidy_goods}（提名 / 商家确认：{@code pending} → {@code approved} / {@code rejected}）。
 * 两表本轮入库前均为 **0 行**（2026-10-12 在 a3 库实测），规则由管理端页面现建。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CouponDao} / {@code CartDao} / {@code TemplateDao}：新域一律**裸表名**。
 * ⚠️ 老域（{@code OrderDao} / {@code GoodsDao}）里的 {@code mall_management_sql.} 前缀是 F#1 的
 * 存量债，**别照抄**。唯一破例的是 {@link #selectGoodsById} —— 它读的是**老域的 {@code goods} 表**，
 * 照抄老域写法带前缀（与 {@code GoodsDao} 的既有语句同形）。
 *
 * <h2>【时间列：读侧 {@code DATE_FORMAT} 成无时区文本，判据用库时钟】</h2>
 * 与 {@code CouponDao} 同款：库里存 UTC，读出来是 {@code 'YYYY-MM-DD HH:MM:SS'} 文本，由前端按
 * 浏览器时区转。**「规则在不在期内」的判定必须在 SQL 里做**（{@link #IN_EFFECT}）——
 * 若读成文本再回 Java 判，就会出现「库里的 NOW()」与「JVM 的 NOW()」两个时钟。
 *
 * <p>⚠️ {@link #LOCAL_NOW} = 库时钟（UTC）+ 固定 8 小时，**逐字照抄 {@code CouponDao}** ——
 * {@code StartAt} / {@code EndAt} 是**业务配置的时间**（人按本地时区填的），判据必须跟它同一个钟。
 * 不用 {@code CONVERT_TZ(...,'UTC','Asia/Shanghai')} 那种命名时区写法：它依赖
 * {@code mysql.time_zone_name} 被灌过，默认安装常是空表 ⇒ 返回 {@code NULL}（**不报错**）⇒
 * 整条谓词变 {@code NULL}、谁都不匹配。
 *
 * <h2>【补贴额**不入库**，读侧现算】</h2>
 * {@code subsidy_goods} 里只有「哪件商品挂在哪条规则下」，**没有**补贴金额列。金额由
 * {@code price × ratio} 封顶 {@code Cap} 现算（{@code SubsidyService} 里那一步）。
 * 理由与折扣那边同源：商品侧存的是**数字**，规则改了比例，已提名的商品按**新比例**重算 ——
 * 这正是「改配置要立刻生效」想要的语义（折扣那边相反：那里存的是折算好的 {@code OriginalPrice}，
 * 因为划线价是**商家填的**、不是平台算的）。
 */
@Repository
public class SubsidyDao {

    /**
     * 规则投影。⚠️ 与 {@code NotificationDao.COLUMNS} / {@code CouponDao.COUPON_COLUMNS} 同款：
     * 本域只有这一处投影，故只需自洽。别名 {@code r} 由各方法自己拼。
     */
    private static final String RULE_COLUMNS =
            "r.Id, r.RuleId, r.Name, r.CategoryMatch, r.Ratio, r.Cap, r.Status, "
                    + "DATE_FORMAT(r.StartAt, '%Y-%m-%d %H:%i:%s') AS StartAt, "
                    + "DATE_FORMAT(r.EndAt, '%Y-%m-%d %H:%i:%s') AS EndAt, "
                    + "DATE_FORMAT(r.created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
                    + "DATE_FORMAT(r.updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at ";

    /**
     * 提名 / 确认投影。{@code RuleName} / {@code Ratio} / {@code Cap} 是 JOIN 出来的**展示用**列
     * （管理端列表要显示「挂在哪条规则下、补多少」）—— 写侧一行都不读它们，别拿它们回写。
     */
    private static final String GOODS_COLUMNS =
            "sg.Id, sg.RuleId, sg.GoodsId, sg.GoodName, sg.MerchantId, sg.Status, sg.Reason, "
                    + "DATE_FORMAT(sg.created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
                    + "DATE_FORMAT(sg.updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at ";

    /**
     * 本地墙钟 = **库时钟（UTC）+ 固定 8 小时**。逐字照抄 {@code CouponDao.LOCAL_NOW}
     * （那一处的长注释解释了为什么不用命名时区），理由见类注释。
     */
    private static final String LOCAL_NOW = "(NOW() + INTERVAL 8 HOUR)";

    /**
     * 规则**在期内**的判据（{@link #selectRulesInEffect} 与 {@link #selectSubsidyGoods} 共用）。
     *
     * <p>⚠️ {@code StartAt} / {@code EndAt} 为 {@code NULL} = **不限**（建表默认值就是 {@code NULL}）
     * —— 与「{@code Cap = 0} = 不封顶」同一个口径：**没填 ≠ 填了 0**。
     */
    private static final String IN_EFFECT =
            "r.Status = 'active' "
                    + "AND (r.StartAt IS NULL OR r.StartAt <= " + LOCAL_NOW + ") "
                    + "AND (r.EndAt IS NULL OR r.EndAt >= " + LOCAL_NOW + ")";

    private final JdbcClient jdbc;

    public SubsidyDao(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 规则（subsidy_rule） ====================

    /**
     * 规则列表，**时间倒序**。{@code status} 非空则只回该状态。
     * ⚠️ 管理端要看得见「已停用」的规则（那是它自己停的），故这里**不做** in-effect 过滤 ——
     * 过滤只在 {@link #selectRulesInEffect} / {@link #selectSubsidyGoods}。
     */
    public List<Map<String, Object>> selectRules(String status, int limit) {
        String sql = "SELECT " + RULE_COLUMNS + "FROM subsidy_rule r WHERE 1 = 1 "
                + (isBlank(status) ? "" : "AND r.Status = :status ")
                + "ORDER BY r.Id DESC LIMIT :limit";
        return jdbc.sql(sql)
                .param("status", status == null ? "" : status)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    public int countRules(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM subsidy_rule r WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND r.Status = :status"))
                .param("status", status == null ? "" : status)
                .query(Integer.class)
                .single();
    }

    /** 单条规则 —— 停用 / 启用的**改前快照**（{@code admin_audit.BeforeJson}）读它。0 行回 {@code null}。 */
    public Map<String, Object> selectRule(String ruleId) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT " + RULE_COLUMNS + "FROM subsidy_rule r WHERE r.RuleId = :ruleId")
                .param("ruleId", ruleId)
                .query()
                .listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 在期内的规则（用户端提名选商品 / 国补商品列表用）。 */
    public List<Map<String, Object>> selectRulesInEffect() {
        return jdbc.sql("SELECT " + RULE_COLUMNS + "FROM subsidy_rule r WHERE " + IN_EFFECT
                        + "ORDER BY r.Id DESC")
                .query()
                .listOfRows();
    }

    public int insertRule(String ruleId, String name, String categoryMatch, double ratio,
                          double cap, String startAt, String endAt) {
        return jdbc.sql("INSERT INTO subsidy_rule "
                        + "(RuleId, Name, CategoryMatch, Ratio, Cap, StartAt, EndAt, Status) "
                        + "VALUES (:ruleId, :name, :categoryMatch, :ratio, :cap, :startAt, :endAt, 'active')")
                .param("ruleId", ruleId)
                .param("name", name)
                .param("categoryMatch", categoryMatch)
                .param("ratio", ratio)
                .param("cap", cap)
                // ⚠️ 空串要转成 NULL —— `datetime` 列收 `''` 会在严格模式下炸（列允许 NULL，
                //    「没填」的语义就是 NULL，见 IN_EFFECT 的 ⚠️）。
                .param("startAt", isBlank(startAt) ? null : startAt)
                .param("endAt", isBlank(endAt) ? null : endAt)
                .update();
    }

    /** 改规则状态。{@code WHERE ... AND Status <> :status} ⇒ 重复停用回 {@code updated=0}（不是失败）。 */
    public int updateRuleStatus(String ruleId, String status) {
        return jdbc.sql("UPDATE subsidy_rule SET Status = :status "
                        + "WHERE RuleId = :ruleId AND Status <> :status")
                .param("status", status)
                .param("ruleId", ruleId)
                .update();
    }

    // ==================== 提名 / 确认（subsidy_goods） ====================

    /** 老域 {@code goods} 表的单行读 —— 提名时取商家号 / 商品名快照用（带 schema 前缀，同 {@code GoodsDao}）。 */
    public Map<String, Object> selectGoodsById(int goodsId) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT Id, MerchantId, GoodName, Price, GoodType, ProductCode "
                                + "FROM mall_management_sql.goods WHERE Id = :id AND DeletedAt IS NULL")
                .param("id", goodsId)
                .query()
                .listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 提名一件商品，**回新建那一行的 {@code Id}**。
     *
     * <p>⚠️ 撞 {@code uk_subsidy_goods_rule_goods(RuleId, GoodsId)} ⇒ {@code DuplicateKeyException}
     * —— **不吞**，由 {@code SubsidyService.nominate} 翻成「该商品已在这条规则下」。
     *
     * <p>⚠️ 回 Id 不是为了好看：那条发给商家的通知要把 {@code subsidy_goods.Id} 写进
     * {@code notification.TargetId} —— 商家在消息里点「同意」时，手里必须有**这一行的主键**
     * 才能打 {@code /marketing/subsidy/confirm}。回读一条 {@code SELECT} 比
     * {@code getGeneratedKeys} 更直白（且本仓既有写法就是「一条语句一件事」）。
     */
    public int insertNomination(String ruleId, int goodsId, String goodName, String merchantId) {
        jdbc.sql("INSERT INTO subsidy_goods (RuleId, GoodsId, GoodName, MerchantId, Status) "
                        + "VALUES (:ruleId, :goodsId, :goodName, :merchantId, 'pending')")
                .param("ruleId", ruleId)
                .param("goodsId", goodsId)
                .param("goodName", goodName)
                .param("merchantId", merchantId)
                .update();
        return jdbc.sql("SELECT sg.Id FROM subsidy_goods sg "
                        + "WHERE sg.RuleId = :ruleId AND sg.GoodsId = :goodsId")
                .param("ruleId", ruleId)
                .param("goodsId", goodsId)
                .query(Integer.class)
                .single();
    }

    /** 管理端：提名列表（JOIN 规则名与补率，供页面显示「挂在哪儿、补多少」）。 */
    public List<Map<String, Object>> selectNominations(String status, String merchantId, int limit) {
        return jdbc.sql("SELECT " + GOODS_COLUMNS + ", r.Name AS RuleName, r.Ratio, r.Cap "
                        + "FROM subsidy_goods sg JOIN subsidy_rule r ON r.RuleId = sg.RuleId "
                        + "WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND sg.Status = :status ")
                        + (isBlank(merchantId) ? "" : "AND sg.MerchantId = :merchantId ")
                        + "ORDER BY sg.Id DESC LIMIT :limit")
                .param("status", status == null ? "" : status)
                .param("merchantId", merchantId == null ? "" : merchantId)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    public int countNominations(String status, String merchantId) {
        return jdbc.sql("SELECT COUNT(*) FROM subsidy_goods sg WHERE 1 = 1 "
                        + (isBlank(status) ? "" : "AND sg.Status = :status ")
                        + (isBlank(merchantId) ? "" : "AND sg.MerchantId = :merchantId"))
                .param("status", status == null ? "" : status)
                .param("merchantId", merchantId == null ? "" : merchantId)
                .query(Integer.class)
                .single();
    }

    /** 商家端：**发给我的**提名（{@code ToId} 那一侧，按商家业务号）。 */
    public List<Map<String, Object>> selectInvitations(String merchantId, String status, int limit) {
        return jdbc.sql("SELECT " + GOODS_COLUMNS + ", r.Name AS RuleName, r.Ratio, r.Cap, "
                        + "DATE_FORMAT(r.EndAt, '%Y-%m-%d %H:%i:%s') AS RuleEndAt "
                        + "FROM subsidy_goods sg JOIN subsidy_rule r ON r.RuleId = sg.RuleId "
                        + "WHERE sg.MerchantId = :merchantId "
                        + (isBlank(status) ? "" : "AND sg.Status = :status ")
                        + "ORDER BY sg.Id DESC LIMIT :limit")
                .param("merchantId", merchantId)
                .param("status", status == null ? "" : status)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    /** 单条提名 —— {@code confirm} 的改前快照 + 「这行是不是你的」判据都读它。 */
    public Map<String, Object> selectNomination(long id) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT " + GOODS_COLUMNS + "FROM subsidy_goods sg WHERE sg.Id = :id")
                .param("id", id)
                .query()
                .listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 商家确认 / 拒绝 —— **CAS**：{@code WHERE Id = ? AND MerchantId = ? AND Status = 'pending'}。
     *
     * <p>⚠️ 三条谓词缺一不可：{@code Id} 定位行、{@code MerchantId} 挡「改别人的提名」、
     * {@code Status='pending'} 挡「同一行被处理两次」（第二次回 {@code updated=0}，
     * 由 Service 翻成 {@code already-handled}，与提审制那条 CAS 同形）。
     */
    public int casConfirm(long id, String merchantId, String status, String reason) {
        return jdbc.sql("UPDATE subsidy_goods SET Status = :status, Reason = :reason "
                        + "WHERE Id = :id AND MerchantId = :merchantId AND Status = 'pending'")
                .param("status", status)
                .param("reason", reason == null ? "" : reason)
                .param("id", id)
                .param("merchantId", merchantId)
                .update();
    }

    /**
     * 用户端：**已确认且在期**的国补商品（含现价与图片，供详情页画「国家补贴」标签 + 补贴后价）。
     *
     * <p>⚠️ 三处 JOIN 各自挡一件事：{@code subsidy_rule} 挡「规则已停用 / 不在期」、
     * {@code goods} 挡「商品已下架或已删」、{@code sg.Status='approved'} 挡「商家还没点头」。
     * 少了任何一条，「标签还在、但东西已经不能买」就会出现。
     *
     * <p>⚠️ {@code Cap} 与 {@code Ratio} 一起回 —— 金额**在 Java 侧现算**（见类注释）。
     */
    public List<Map<String, Object>> selectSubsidyGoods(String goodType, int limit) {
        return jdbc.sql("SELECT sg.Id, sg.GoodsId, sg.GoodName, sg.MerchantId, sg.RuleId, "
                        + "r.Name AS RuleName, r.Ratio, r.Cap, "
                        + "g.Price AS Price, g.GoodImg, g.GoodType, g.ProductCode "
                        + "FROM subsidy_goods sg "
                        + "JOIN subsidy_rule r ON r.RuleId = sg.RuleId "
                        + "JOIN mall_management_sql.goods g ON g.Id = sg.GoodsId "
                        + "WHERE sg.Status = 'approved' AND " + IN_EFFECT + " "
                        + "AND g.ShelfState = 1 AND g.DeletedAt IS NULL "
                        + (isBlank(goodType) ? "" : "AND g.GoodType = :goodType ")
                        + "ORDER BY sg.Id DESC LIMIT :limit")
                .param("goodType", goodType == null ? "" : goodType)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
