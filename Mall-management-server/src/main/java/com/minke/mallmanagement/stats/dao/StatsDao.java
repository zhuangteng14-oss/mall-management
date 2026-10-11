package com.minke.mallmanagement.stats.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 经营数据看板的数据出口（可视化段 commit 2）。
 *
 * <h2>【本类吃两张表，方向相反 —— 别把两组方法看成同一件事】</h2>
 * <ul>
 *   <li><b>写侧 / 重算侧</b>（{@code aggregate*} / {@code upsert*} / {@code deleteAll}）：源头是
 *       <b>{@code order}</b>，产出 {@code stats_daily} 的行。只有 {@link com.minke.mallmanagement.stats.service.StatsService}
 *       与 {@link com.minke.mallmanagement.stats.job.StatsAggregateJob} 调。</li>
 *   <li><b>读侧</b>（{@code selectDaily} / {@code sumDaily} / {@code selectMerchantSummary}）：
 *       源头是 <b>{@code stats_daily}</b>，**一次都不碰 {@code order}** —— 这正是本笔建这张表的全部意义
 *       （端点响应不随订单表增长而变慢）。</li>
 * </ul>
 * ⚠️ 两组都叫「按日」，但**日切表达不同**：写侧切的是 {@code DATE(order.CreateTime)}
 * （varchar 隐式转日期），读侧切的是真 {@code DATE} 列 {@code stats_daily.StatDate}。
 * 看起来一样所以容易改错一边 —— 改任一侧前先确认自己在哪一组。
 *
 * <h2>【⚠️ 日切列是 varchar，{@code DATE()} 依赖格式】</h2>
 * {@code order.CreateTime} 是 {@code varchar(50)}（{@code mall_management_sql.sql}），
 * 靠 ISO 式零填充 {@code 'YYYY-MM-DD HH:MM:SS'} 才能被 {@code DATE()} 解出。非零填充
 * （如 {@code '2026-5-1'}）会解成 {@code NULL} ⇒ {@code WHERE ... IS NOT NULL} 把它**静默漏掉**。
 * 这与 {@code AdminOrderDao} 的时间筛是同一条依赖，理由与风险逐字相同。
 *
 * <h2>【⚠️ 输出日期一律走 {@code DATE_FORMAT}，交回 String】</h2>
 * 不让 JDBC 交回 {@code java.sql.Date} / {@code LocalDate}：那会落进 Jackson 的日期序列化
 * （默认按 epoch 毫秒出数），前端拿到的会是 {@code 1781740800000} 而不是 {@code '2026-06-26'}。
 * 在 SQL 里格式化成字串 ⇒ 序列化路径上**没有日期类型**，与全仓「响应体就是 Map 里几个 String/Number」
 * 的形态一致，也不必去动全局 ObjectMapper（那会波及 33 个既有端点的字节）。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CartDao} / {@code AdminOrderDao} / {@code TemplateDao}。别照抄 {@code OrderDao} 的
 * {@code mall_management_sql.} 前缀（那是 F#1 存量债）。
 */
@Repository
public class StatsDao {

    /** {@code Scope} 的全站口径值（另一口径是 {@code merchant}）。两处拼串都用它，别手打。 */
    public static final String SCOPE_SITE = "site";

    /** 按商家口径。 */
    public static final String SCOPE_MERCHANT = "merchant";

    private final JdbcClient jdbcClient;

    public StatsDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 读侧（源头 = stats_daily，不碰 order） ====================

    /**
     * 某一口径下、指定日期区间内的**逐日**行（升序）。
     *
     * <p>{@code merchantId} 只在 {@code scope = merchant} 时有意义；全站口径传 {@code null} 或空串
     * ⇒ 不加该谓词（全站行的 {@code MerchantId} 恒为 {@code ''}，加不加结果一样，但不加更直白）。
     */
    public List<Map<String, Object>> selectDaily(String scope, String merchantId, String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT DATE_FORMAT(StatDate, '%Y-%m-%d') AS StatDate, MerchantId, Orders, Gmv, Items, Buyers"
                        + " FROM stats_daily WHERE Scope = ?");
        List<Object> args = new ArrayList<>();
        args.add(scope);
        if (notBlank(merchantId)) {
            sql.append(" AND MerchantId = ?");
            args.add(merchantId.trim());
        }
        appendDateRange(sql, args, from, to);
        sql.append(" ORDER BY StatDate ASC, MerchantId ASC");
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows();
    }

    /**
     * 同上区间的**合计**（不是逐日）。一行四列。
     *
     * <p>⚠️ {@code Buyers} 在这里是**各日去重买家数的和**，而**不是**跨日去重 —— 理由：
     * {@code stats_daily} 只存了「每天去重了几人」（{@code COUNT(DISTINCT CustomerId)} 按日算），
     * 「这一个月一共来了几个不同的人」这个信息**在日聚合里已经丢了**（同一人两天下单算 2 次），
     * 本方法**不假装能算出来**。要想拿到真值只能回 {@code order} 现算 —— 那正是本表要避免的事
     * ⇒ 折中是：日粒度真、**区间合计的买家数按日相加**，页面把它标成「人日」而不是「人数」。
     */
    public Map<String, Object> sumDaily(String scope, String merchantId, String from, String to) {
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(Orders), 0) AS Orders,"
                + " CAST(COALESCE(SUM(Gmv), 0) AS DECIMAL(14,2)) AS Gmv,"
                + " COALESCE(SUM(Items), 0) AS Items,"
                + " COALESCE(SUM(Buyers), 0) AS Buyers"
                + " FROM stats_daily WHERE Scope = ?");
        List<Object> args = new ArrayList<>();
        args.add(scope);
        if (notBlank(merchantId)) {
            sql.append(" AND MerchantId = ?");
            args.add(merchantId.trim());
        }
        appendDateRange(sql, args, from, to);
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows()
                .stream().findFirst().orElse(Map.of());
    }

    /** 区间内**各商家**的汇总（降序：先按 GMV、再按单数），供管理端「商家榜」。 */
    public List<Map<String, Object>> selectMerchantSummary(String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT MerchantId, COALESCE(SUM(Orders), 0) AS Orders,"
                        + " CAST(COALESCE(SUM(Gmv), 0) AS DECIMAL(14,2)) AS Gmv,"
                        + " COALESCE(SUM(Items), 0) AS Items,"
                        + " COALESCE(SUM(Buyers), 0) AS Buyers"
                        + " FROM stats_daily WHERE Scope = ?");
        List<Object> args = new ArrayList<>();
        args.add(SCOPE_MERCHANT);
        appendDateRange(sql, args, from, to);
        sql.append(" GROUP BY MerchantId ORDER BY Gmv DESC, Orders DESC, MerchantId ASC");
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows();
    }

    /**
     * 商品榜（**实时**聚合 {@code order_item}，不走 {@code stats_daily}）。
     *
     * <p>用户裁定（方案 §八-3）：商品维度**粒度不同**（按商品而非按日），硬塞进日聚合表会让
     * 「一天卖了多少个 SKU」变成表宽问题。故本方法直读 {@code order_item}。
     *
     * <p>⚠️ <b>上线即空是预期，不是故障</b>：{@code order_item} 按裁定 §三 **不回填存量单**
     * ⇒ 建表那一刻 0 行，商品榜必然空数组。前端按「历史单无明细」呈现（裁定原文），
     * <b>不要</b>在这里补一个「读不到就回退去解析 {@code ProductInformation}」的兜底 —— 那正是被否掉的乙案。
     */
    public List<Map<String, Object>> selectTopGoods(String merchantId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT ProductCode, MAX(GoodName) AS GoodName,"
                        + " CAST(COALESCE(SUM(Quantity), 0) AS SIGNED) AS Quantity,"
                        + " CAST(COALESCE(SUM(Subtotal), 0) AS DECIMAL(14,2)) AS Subtotal,"
                        // ⚠️ 别名**必须**加反引号：`LINES` 是 MySQL 的**保留字**
                        //    （`LOAD DATA … LINES TERMINATED BY` 那个），裸写 `AS Lines` 直接
                        //    `ERROR 1064`（实测 2026-10-11，见 `docs/agent/evidence/vis-dashboard/`）。
                        + " COUNT(*) AS `Lines`"
                        + " FROM order_item WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (notBlank(merchantId)) {
            sql.append(" AND MerchantId = ?");
            args.add(merchantId.trim());
        }
        // ⚠️ 只统计解析出商品码的行：ProductCode 为空串的是「明细没解析出来」的落库形态
        //    （OrderService 的兜底分支），把它们合成一个空码的「爆款」会是最误导的那种榜。
        sql.append(" AND ProductCode <> '' GROUP BY ProductCode ORDER BY Subtotal DESC, Quantity DESC, ProductCode ASC LIMIT ?");
        args.add(clampLimit(limit));
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows();
    }

    /** {@code stats_daily} 现有行数。供端点回报「有没有预聚合过」—— 0 ⇒ 看板是空态，不是错。 */
    public int countAll() {
        return jdbcClient.sql("SELECT COUNT(*) FROM stats_daily").query(Integer.class).single();
    }

    // ==================== 库时钟（本类是全仓唯一「相对日期」的来源） ====================

    /**
     * 今天的日期（{@code 'YYYY-MM-DD'}），**由库时钟算**。
     *
     * <p>⚠️ 刻意不在 Java 侧用 {@code LocalDate.now()}：本仓「时刻一律由库时钟产生、Java 侧不构造
     * 任何时间点」是 2026-10-10 定下的统一口径（见 {@code ShippingTrackAdvanceJob} 类注释与
     * {@code tech-debt.md} TD-32）。相对窗口（「最近 7 天」）也是相对**谁的**今天 —— 应用机与数据库机
     * 时区不同时，用 Java 的今天会在跨零点前算出错一天，而**不会报错**。
     */
    public String currentDate() {
        return jdbcClient.sql("SELECT DATE_FORMAT(CURDATE(), '%Y-%m-%d')").query(String.class).single();
    }

    /** {@code 今天 - days 天}（{@code 'YYYY-MM-DD'}），同样走库时钟。{@code days = 0} ⇒ 今天。 */
    public String dateMinus(int days) {
        return jdbcClient.sql("SELECT DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL ? DAY), '%Y-%m-%d')")
                .param(days).query(String.class).single();
    }

    // ==================== 写侧 / 重算侧（源头 = order） ====================

    /**
     * 从 {@code order} 重算**全站按日**，返回待写入的行（**不落库**）。
     *
     * <p>{@code Gmv} 显式 {@code CAST(... AS DECIMAL(14,2))}：{@code order.Price} 是 float，
     * 不转的话 float 的显示舍入会渗进 {@code stats_daily}（实测：同一值裸选 {@code 10000000}
     * 而 {@code CAST(... AS DOUBLE)} 是 {@code 9999999}）。
     */
    public List<Map<String, Object>> aggregateSite(String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT DATE_FORMAT(DATE(CreateTime), '%Y-%m-%d') AS StatDate,"
                        + " COUNT(*) AS Orders,"
                        + " CAST(COALESCE(SUM(Price), 0) AS DECIMAL(14,2)) AS Gmv,"
                        + " CAST(COALESCE(SUM(Quantity), 0) AS SIGNED) AS Items,"
                        + " COUNT(DISTINCT CustomerId) AS Buyers"
                        + " FROM `order` WHERE DATE(CreateTime) IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendDateRangeOnCreateTime(sql, args, from, to);
        // ⚠️ `GROUP BY StatDate`（**别名**），不能写成 `GROUP BY DATE(CreateTime)`：本机 MySQL 8 的
        //    `sql_mode` 带 `only_full_group_by`，而它**不认为** `DATE_FORMAT(DATE(CreateTime), …)`
        //    函数依赖于 `DATE(CreateTime)` ⇒ 后者直接 `ERROR 1055`（实测 2026-10-11，见
        //    `docs/agent/evidence/vis-dashboard/`）。按别名分组时，分组表达式与 SELECT 里那一项
        //    逐字相同，谓词才成立。`order` 表没有 `StatDate` 列 ⇒ 别名不会被真列遮蔽。
        sql.append(" GROUP BY StatDate ORDER BY StatDate ASC");
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows();
    }

    /**
     * 从 {@code order} 重算**按商家 × 按日**。
     *
     * <p>⚠️ 与 {@link #aggregateSite} 是**两次独立的扫描**，不是「把上面的结果再 GROUP BY」——
     * 全站行的 {@code Buyers} 是**全站去重**，商家行的 {@code Buyers} 是该商家内去重，
     * 两者不可加（同 {@code stats_daily}` 注释里那条 ROLLUP 的坑）。所以商家口径**必须自己扫一遍**。
     * 两次扫的是同一天的数据 ⇒ 口径天然对齐。
     */
    public List<Map<String, Object>> aggregateMerchant(String from, String to) {
        StringBuilder sql = new StringBuilder(
                "SELECT DATE_FORMAT(DATE(CreateTime), '%Y-%m-%d') AS StatDate, MerchantId,"
                        + " COUNT(*) AS Orders,"
                        + " CAST(COALESCE(SUM(Price), 0) AS DECIMAL(14,2)) AS Gmv,"
                        + " CAST(COALESCE(SUM(Quantity), 0) AS SIGNED) AS Items,"
                        + " COUNT(DISTINCT CustomerId) AS Buyers"
                        + " FROM `order` WHERE DATE(CreateTime) IS NOT NULL AND MerchantId IS NOT NULL AND MerchantId <> ''");
        List<Object> args = new ArrayList<>();
        appendDateRangeOnCreateTime(sql, args, from, to);
        // ⚠️ 同 `aggregateSite` 那条：按**别名** `StatDate` 分组，不写 `GROUP BY DATE(CreateTime), MerchantId`
        //    （`only_full_group_by` 会判 1055；理由与实测见上一条）。
        sql.append(" GROUP BY StatDate, MerchantId ORDER BY StatDate ASC, MerchantId ASC");
        return jdbcClient.sql(sql.toString()).params(args.toArray()).query().listOfRows();
    }

    /**
     * 幂等写入一行（撞唯一键 ⇒ 改）。**job 的滚动重算与 rebuild 的全量重算走的是同一条语句**
     * ⇒ 两个入口不可能算出两个口径（这是 {@code stats_daily}` 建表注释里承诺的那条）。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE` 而不是「先删后插」：滚动窗口每轮都会重碰**同几天**，
     * 删了再插会有一个可被并发读到的空窗；upsert 是单语句原子的。
     */
    public int upsert(String scope, String statDate, String merchantId, Object orders, Object gmv, Object items, Object buyers) {
        String sql = "INSERT INTO stats_daily (StatDate, Scope, MerchantId, Orders, Gmv, Items, Buyers)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE Orders = VALUES(Orders), Gmv = VALUES(Gmv),"
                + " Items = VALUES(Items), Buyers = VALUES(Buyers)";
        return jdbcClient.sql(sql)
                .params(statDate, scope, merchantId == null ? "" : merchantId,
                        orders, gmv, items, buyers)
                .update();
    }

    /**
     * ⚠️ **破坏性**：清空 {@code stats_daily}。只给 {@code rebuild} 用（整体重建的第一步）。
     *
     * <p>调它的方法必须能接受「清空与重填之间，读到的是空看板」—— 故 {@code StatsService.rebuild}
     * 把两步包在**同一个事务**里（{@code @Transactional}）；本类自己不开事务，DAO 层不决定这个。
     */
    public int deleteAll() {
        return jdbcClient.sql("DELETE FROM stats_daily").update();
    }

    // ==================== 内部 ====================

    /** 在 {@code stats_daily} 的真 DATE 列上加区间（闭区间）。 */
    private static void appendDateRange(StringBuilder sql, List<Object> args, String from, String to) {
        appendBound(sql, args, "StatDate >= ?", from);
        appendBound(sql, args, "StatDate <= ?", to);
    }

    /** 在派生列 {@code DATE(order.CreateTime)} 上加区间（闭区间）。 */
    private static void appendDateRangeOnCreateTime(StringBuilder sql, List<Object> args, String from, String to) {
        appendBound(sql, args, "DATE(CreateTime) >= ?", from);
        appendBound(sql, args, "DATE(CreateTime) <= ?", to);
    }

    private static void appendBound(StringBuilder sql, List<Object> args, String fragment, String value) {
        if (notBlank(value)) {
            sql.append(" AND ").append(fragment);
            args.add(value.trim());
        }
    }

    /** 生效上限：{@code limit <= 0 ⇒ 10}，否则 {@code min(limit, }{@link #MAX_LIMIT}{@code )}。 */
    public static int clampLimit(int limit) {
        if (limit <= 0) {
            return 10;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 商品榜一次最多回多少行（别把看板变成全表导出，同 {@code AdminOrderDao.MAX_LIMIT} 的理由）。 */
    public static final int MAX_LIMIT = 50;

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
