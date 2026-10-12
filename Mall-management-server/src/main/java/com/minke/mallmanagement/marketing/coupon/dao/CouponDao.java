package com.minke.mallmanagement.marketing.coupon.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 营销域的**优惠券数据访问出口**（C3 块 5 · A1 甲：新表 {@code coupon} + {@code coupon_grant}）。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CartDao} / {@code TemplateDao} / {@code CategoryDao}：新域一律**裸表名**。
 * ⚠️ 老域（{@code OrderDao} / {@code GoodsDao}）里的 {@code mall_management_sql.} 前缀是 F#1 的
 * 存量债，**别照抄**（照抄会让「一半带前缀、一半不带」的中间态扩散）。
 *
 * <h2>【时间列一律 {@code DATE_FORMAT} 成无时区文本 —— 全仓读侧口径】</h2>
 * 与 {@code NotificationDao} / {@code AdminAuditDao} 同款：库里存 UTC，读出来是
 * {@code 'YYYY-MM-DD HH:MM:SS'} 文本，由**前端**按浏览器时区转。⚠️ 但本域**另有**一处例外：
 * 「快过期」/「已过期」的判定**必须在 SQL 里做**（见 {@link #selectMyGrants}）——
 * 若读成文本再回 Java 判，就会出现「库里的 NOW()」与「JVM 的 NOW()」两个时钟，
 * 而本表的 {@code StartAt}/{@code EndAt} 是**业务配置的时间**，判据必须与写侧同一个时钟。
 *
 * <h2>【本类不做 @Transactional —— 领券用「先占名额、撞键补偿」两条语句】</h2>
 * 与 {@code CartDao} 的取舍同源：本仓的写路径惯用「catch 撞唯一键再改另一条语句」，
 * 而不是靠事务回滚（{@code TemplateService} / {@code CartService.add} 都是这么写的）。
 * 领券的补偿语义见 {@code CouponService.claim}（{@link #releaseClaim} 是它的补偿腿）。
 */
@Repository
public class CouponDao {

    /**
     * 券定义投影。⚠️ 与 {@code NotificationDao.COLUMNS} 同款：本域只有这一处投影，故只需自洽。
     * 别名 {@code c} 由各方法自己拼 —— 单独查（{@link #selectCoupon}）时也沿用它，
     * 免得同一列在两条语句里出现两种名字。
     */
    private static final String COUPON_COLUMNS =
            "c.Id, c.CouponId, c.Name, c.Type, c.FaceValue, c.Threshold, c.TotalQty, c.ClaimedQty, "
                    + "c.Scope, c.MerchantAccount, c.CategoryMatch, c.Status, "
                    + "DATE_FORMAT(c.StartAt, '%Y-%m-%d %H:%i:%s') AS StartAt, "
                    + "DATE_FORMAT(c.EndAt, '%Y-%m-%d %H:%i:%s') AS EndAt, "
                    + "DATE_FORMAT(c.created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
                    + "DATE_FORMAT(c.updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at ";

    /**
     * 「快过期」阈值表达式（**单一口径**，三处共用：{@link #selectMyGrants} 的 {@code ExpiringSoon}、
     * {@link #selectExpiringUnclaimed} 的扫描窗口、前端拿到的那两个布尔）。
     *
     * <p>口径（用户 2026-10-11 裁定 #4）：到期前 **24h** 算「快过期」；若券的有效期**本身 < 24h**
     * ⇒ 用「**有效期的一半**」。写死常量，不做配置。
     * <pre>
     *   LEAST(86400,
     *         GREATEST(TIMESTAMPDIFF(SECOND, 有效期起, EndAt) DIV 2, 1))
     * </pre>
     * ⚠️ {@code StartAt} 为空时**当成 48h 的窗口**（⇒ 阈值回落到 24h）：若直接取
     * {@code COALESCE(StartAt, EndAt)}，窗口算成 0 秒，阈值就成了「最后 1 秒」——
     * 那不是「快过期」，那是「来不及了」。
     */
    private static final String EXPIRING_THRESHOLD =
            "LEAST(86400, GREATEST(TIMESTAMPDIFF(SECOND, "
                    + "COALESCE(c.StartAt, DATE_SUB(c.EndAt, INTERVAL 172800 SECOND)), c.EndAt) DIV 2, 1))";

    /**
     * 本地墙钟 = **库时钟（UTC）+ 固定 8 小时**。
     *
     * <p>⚠️ 时刻一律由**库时钟**产生，Java 侧不构造任何时间点（本仓 2026-10-10 定下的硬口径，
     * 见 {@code tech-debt.md} TD-32；唯一「相对日期」来源是 {@code StatsDao.currentDate()}）。
     * 用 {@code LocalDate.now()} 会拿 JVM 的钟 —— 与库 {@code NOW()} 差 8 小时、且**不报错**，
     * 只是整段筛错一天（{@code CouponService.isExpired} 的注释记着同族事故）。
     *
     * <p>⚠️ 也**不用** {@code CONVERT_TZ(NOW(),'UTC','Asia/Shanghai')} 这种**命名时区**写法：
     * 它依赖 {@code mysql.time_zone_name} 被 {@code mysql_tzinfo_to_sql} 灌过，而默认安装
     * 常常是**空表** ⇒ 返回 {@code NULL}（**不报错**）⇒ 整条谓词变 {@code NULL}、谁都不匹配。
     * 数字偏移（{@code '+08:00'}）不受此影响，但本仓已实测库时钟就是 UTC
     * （{@code @@system_time_zone='UTC'}）⇒ 直接加 8 小时最省依赖、也最好读。
     */
    private static final String LOCAL_NOW = "(NOW() + INTERVAL 8 HOUR)";

    /**
     * 本地**自然周**的周一 00:00（{@code WEEKDAY}：周一 = 0 … 周日 = 6）。
     * 用户 2026-10-11 裁定：本周 = 自然周、周一起、按本地（+08:00）时区。
     */
    private static final String WEEK_START =
            "DATE_SUB(DATE(" + LOCAL_NOW + "), INTERVAL WEEKDAY(" + LOCAL_NOW + ") DAY)";

    /**
     * 「本周新建」：{@code created_at} 是 {@code DEFAULT CURRENT_TIMESTAMP} ⇒ 由**库时钟**产生（UTC），
     * 故先 +8h 折成本地墙钟，再与本地周一比。
     */
    private static final String THIS_WEEK = "(c.created_at + INTERVAL 8 HOUR) >= " + WEEK_START;

    /**
     * 「近 3 天新建」（用户 2026-10-11 裁定 ⑥：NEW 角标只打近 3 天的）。
     * ⚠️ 这是**滚动窗口**：两边同为 UTC ⇒ 8 小时相消、与上面那条不同，**与时区无关**，故不折。
     */
    private static final String IS_NEW = "(c.created_at >= NOW() - INTERVAL 3 DAY)";

    private final JdbcClient jdbcClient;

    public CouponDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：券定义 ====================

    /** 新建券。{@code StartAt} / {@code EndAt} 是 {@code 'YYYY-MM-DD HH:MM:SS'} 文本或 {@code null}。 */
    public int insertCoupon(String couponId, String name, String type, Double faceValue, Double threshold,
                            int totalQty, String scope, String merchantAccount, String categoryMatch,
                            String startAt, String endAt) {
        return insertCoupon(couponId, name, type, faceValue, threshold, totalQty,
                scope, merchantAccount, categoryMatch, startAt, endAt, "active");
    }

    /**
     * 新建券（**状态可指定**）—— 商家券提审专用（C3 块 5 · A 组）。
     *
     * <p>为什么要有这一版：商家在「更改商品」里挂的商品券，**提交时即落库**（业务号由提审批次号派生，
     * 见 {@code CouponService#pendingCouponId}），但 {@code Status='stopped'} ——
     * 领券中心（{@code selectClaimable}）与券包（{@code selectMyGrants}）都只读 {@code active}
     * ⇒ **用户侧整条链路都看不见它**，直到管理端审核通过把它翻 {@code active}。
     * 这样「待审」这个状态**不用新增列**（零 DDL）。
     */
    public int insertCoupon(String couponId, String name, String type, Double faceValue, Double threshold,
                            int totalQty, String scope, String merchantAccount, String categoryMatch,
                            String startAt, String endAt, String status) {
        String sql = "INSERT INTO coupon "
                + "(CouponId, Name, Type, FaceValue, Threshold, TotalQty, ClaimedQty, "
                + "Scope, MerchantAccount, CategoryMatch, StartAt, EndAt, Status) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(couponId, name, type, faceValue, threshold, totalQty,
                        scope, merchantAccount, categoryMatch, startAt, endAt, status)
                .update();
    }

    /**
     * 改状态（{@code active} ⇄ {@code stopped}）。{@code Status <> ?} 让重复停用是**幂等无操作**
     * （{@code updated=0} 是正常结果，不是失败 —— 同 {@code NotificationDao.markRead} 的口径）。
     */
    public int updateStatus(String couponId, String status) {
        return jdbcClient.sql("UPDATE coupon SET Status = ? WHERE CouponId = ? AND Status <> ?")
                .params(status, couponId, status).update();
    }

    // ==================== 写：领取 / 发放 ====================

    /**
     * **占一个名额**（CAS）。四条判据全在 {@code WHERE} 里，逐条都要过：
     * 券在启用中 / 还有余量（{@code TotalQty = 0} 视作**不限量**）/ 已到有效期起 / 未过期。
     *
     * <p>⚠️ 这是领券链路的**真闸**：{@code rows = 0} 就是「这一张领不到了」，原因由调用方
     * 在**拿到 0 之后**用 {@link #selectClaimBlock} 逐条问出来（判据同款、同一个库时钟），
     * 这里只负责「原子地占住名额」。
     */
    public int tryClaim(String couponId) {
        String sql = "UPDATE coupon SET ClaimedQty = ClaimedQty + 1 "
                + "WHERE CouponId = ? AND Status = 'active' "
                + "AND (TotalQty = 0 OR ClaimedQty < TotalQty) "
                + "AND (StartAt IS NULL OR StartAt <= NOW()) "
                + "AND (EndAt IS NULL OR EndAt > NOW())";
        return jdbcClient.sql(sql).param(couponId).update();
    }

    /**
     * **{@link #tryClaim} 返回 0 时，到底是哪一条判据没过** —— 逐条按**库时钟**算出来。
     *
     * <p>为什么要单独读一次：{@code rows = 0} 只说明「没占上名额」，而它的原因是**三**种
     * （还没开始 / 已过期 / 已领完），三者对用户的下一步完全不同。此前它们被合并成一句
     * 「该券已过期或已领完」⇒ 用户 2026-10-11 手验时就撞上了这条**指错方向**的话术
     * （真因是「还没开始」，见下面那条时区注释），白查一轮。
     *
     * <p>⚠️ **判据表达式必须与 {@link #tryClaim} 的 {@code WHERE} 逐条同款、同一个时钟**
     * （都是库内 {@code NOW()}）：换个写法或拿 JVM 的时钟来判，就会出现「话说的是 A、闸卡的是 B」。
     * 这里刻意照抄那四条的否定式。
     *
     * <p>返回三键（键名即结论）：{@code NotStarted} / {@code Expired} / {@code SoldOut}。
     * 券不存在时返回 {@code null}（调用方自己会说「券不存在」）。
     */
    public Map<String, Object> selectClaimBlock(String couponId) {
        String sql = "SELECT (StartAt IS NOT NULL AND StartAt > NOW()) AS NotStarted, "
                + "(EndAt IS NOT NULL AND EndAt <= NOW()) AS Expired, "
                + "(TotalQty > 0 AND ClaimedQty >= TotalQty) AS SoldOut "
                + "FROM coupon WHERE CouponId = ? LIMIT 1";
        List<Map<String, Object>> list = jdbcClient.sql(sql).param(couponId).query().listOfRows();
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * **退还一个名额** —— {@link #tryClaim} 的补偿腿。用于「名额占住了，但写领取行撞唯一键
     * （这个人已经领过）」这一支：不补偿就会每被重复点一次就少一个名额。
     *
     * <p>{@code GREATEST(..., 0)} 是防御：理论不会到 0 以下，但把「计数跑负」这种一眼看不出的
     * 脏数据挡在库里，比事后去查容易。
     */
    public int releaseClaim(String couponId) {
        return jdbcClient.sql("UPDATE coupon SET ClaimedQty = GREATEST(ClaimedQty - 1, 0) WHERE CouponId = ?")
                .param(couponId).update();
    }

    /**
     * 写一行领取/发放记录。**每人每券限 1 张**由 {@code uk_coupon_grant_coupon_acct} 兜
     * ⇒ 重复领取抛 {@code DuplicateKeyException}（由 Service 接住并回「您已领取过该券」）。
     */
    public int insertGrant(String couponId, String toAccount, String source) {
        return jdbcClient.sql("INSERT INTO coupon_grant (CouponId, ToAccount, Source, Status) "
                        + "VALUES (?, ?, ?, 'unused')")
                .params(couponId, toAccount, source).update();
    }

    /** 核销（{@code unused → used}）。CAS：重复核销 {@code updated=0}。**块 5 commit 5 接结算**。 */
    public int markUsed(String couponId, String toAccount, String orderId) {
        return jdbcClient.sql("UPDATE coupon_grant SET Status = 'used', UsedAt = NOW(), OrderId = ? "
                        + "WHERE CouponId = ? AND ToAccount = ? AND Status = 'unused'")
                .params(orderId, couponId, toAccount).update();
    }

    // ==================== 读：券定义 ====================

    /** 管理端券列表（{@code Status} / 券名关键字 均可为空）。 */
    public List<Map<String, Object>> selectCoupons(String status, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COUPON_COLUMNS)
                .append("FROM coupon c WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendCouponFilters(sql, params, status, keyword);
        sql.append(" ORDER BY c.Id DESC LIMIT ?");
        params.add(limit);
        return bind(sql.toString(), params).query().listOfRows();
    }

    /** 同筛选条件下的总数（谓词与 {@link #selectCoupons} **逐字一致** —— 改一处必须成对改）。 */
    public long countCoupons(String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM coupon c WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendCouponFilters(sql, params, status, keyword);
        return bind(sql.toString(), params).query(Long.class).single();
    }

    /** 单条券；不存在 ⇒ {@code null}（**不抛** —— 「查不到」是返回值不是异常，同全仓读侧口径）。 */
    public Map<String, Object> selectCoupon(String couponId) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + COUPON_COLUMNS + "FROM coupon c WHERE c.CouponId = ? LIMIT 1")
                .param(couponId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 读：用户端（领券中心 / 券包） ====================

    /**
     * 领券中心：启用中且**未过期**的券，附两个**给这个人**算出来的布尔。
     *
     * <p>⚠️ 已领的**照样列出**（带 {@code Claimed=1} 让前端把它画成「已领取」）：
     * 直接筛掉会让「我明明领过怎么不见了」变成一次没有痕迹的困惑。
     * 领完的也照样列出（{@code SoldOut=1} → 「已领完」）。
     *
     * <h3>【一个查询、两个筛法 —— 用户 2026-10-11 裁定「甲」】</h3>
     * {@code thisWeekOnly=true} 只回**本周新发布**的（主页领券中心）；
     * {@code false} 回**全部可领**的（用户中心「可领」tab，含旧券）。
     * 主页那批是「可领」的**子集** ⇒ 两者是**同一个 {@code SELECT} 加一个 {@code AND}**，
     * 不是两条各写一遍的 SQL（否则「什么算可领」就有了两处可抄错）。
     * <p>{@code IsNew}（近 3 天新建）**两种筛法都回** —— 「可领」tab 也想要那个 NEW 角标。
     *
     * <h3>【排序：按更新时间**升序** —— 最早更新的在最左】</h3>
     * 用户 2026-10-11 裁定：领券中心是**横向**券条（从左到右铺），左端 = 最早更新的那张。
     * ⚠️ **与券包不是一套口径，别合并**：券包走 {@link #selectMyGrants} 的 {@code SortIdx}
     * （快过期最上 / 已过期沉底 —— 按**紧迫度**），这里按**新旧**。两处都定在 SQL 里
     * ⇒ 前端一律按数组原序渲染，别在 view 里再排一遍。
     * <p>⚠️ 用的是 {@code updated_at}（列定义带 {@code ON UPDATE CURRENT_TIMESTAMP}）⇒
     * **领走一张会把那张挪到最右**（占名额那条 UPDATE 也会碰它）。这是「最早更新」的字面语义；
     * 若哪天要的是「最早**建**的、领了也不动」，把这里换成 {@code c.created_at} 即可 —— 只此一处。
     * <p>{@code c.Id ASC} 只是**同秒并列时的确定性兜底**（否则同秒两行的先后由存储引擎定，
     * 同一份数据两次查询可能给出两种顺序）。
     */
    public List<Map<String, Object>> selectClaimable(String account, int limit, boolean thisWeekOnly) {
        return selectClaimable(account, limit, thisWeekOnly, null);
    }

    /**
     * 同上，另加一个**商家筛**（C3 块 5 · A 组 · 商品详情页领券区）：
     * {@code merchantAccount} 非空 ⇒ 只回**该商家的商家券**（{@code Scope='merchant'} 且
     * {@code MerchantAccount} 相符）；平台券（{@code Scope='all'}）**一条都不回** ——
     * 详情页那块要的是「这件商品的店发的券」，平台券归主页 / 用户中心。
     *
     * <p>⚠️ 多一个 {@code ?} ⇒ 绑参三元变四元，顺序 = {@code ?} 的**出现序**：
     * {@code account}（JOIN 里）→ {@code merchantAccount}（WHERE 里）→ {@code limit}（LIMIT）。
     * 改本方法时**成对改**（同 {@code selectByStatus} 的警告）。
     */
    public List<Map<String, Object>> selectClaimable(String account, int limit, boolean thisWeekOnly,
                                                     String merchantAccount) {
        boolean byMerchant = merchantAccount != null && !merchantAccount.trim().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT ").append(COUPON_COLUMNS)
                .append(", (g.Id IS NOT NULL) AS Claimed ")
                .append(", ((c.TotalQty > 0 AND c.ClaimedQty >= c.TotalQty)) AS SoldOut ")
                .append(", (").append(IS_NEW).append(") AS IsNew ")
                .append("FROM coupon c LEFT JOIN coupon_grant g ")
                .append("ON g.CouponId = c.CouponId AND g.ToAccount = ? ")
                .append("WHERE c.Status = 'active' AND (c.EndAt IS NULL OR c.EndAt > NOW()) ");
        if (byMerchant) {
            sql.append("AND c.Scope = 'merchant' AND c.MerchantAccount = ? ");
        }
        if (thisWeekOnly) {
            // ⚠️ 只多一个 AND、**不新增占位符** ⇒ 不改变下面绑参的个数。
            sql.append("AND ").append(THIS_WEEK).append(' ');
        }
        sql.append("ORDER BY c.updated_at ASC, c.Id ASC LIMIT ?");
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString()).param(account);
        if (byMerchant) {
            spec = spec.param(merchantAccount.trim());
        }
        return spec.param(limit).query().listOfRows();
    }

    /**
     * 本周窗口（本地 +08:00）—— 「本周还剩 N 天」那个**区块级**小字。
     *
     * <p>{@code DaysLeft = 7 - WEEKDAY(本地今天)}：周一 = 7、周日 = 1，**恒 ≥ 1**（整日粒度）。
     * <p>⚠️ 为什么由**后端**算而不是前端本地减：前端只能用**浏览器**的钟，而本仓硬口径是
     * 「时刻一律由库时钟产生」（见 {@link #LOCAL_NOW}）。给一个**整数**也顺手把
     * 「带不带偏移的 ISO 串」这类坑焊死了（逐秒 tick 没被采纳 ⇒ 不需要时刻串）。
     */
    public Map<String, Object> selectWeekWindow() {
        String sql = "SELECT (7 - WEEKDAY(" + LOCAL_NOW + ")) AS DaysLeft";
        return jdbcClient.sql(sql).query().singleRow();
    }

    /**
     * 券包（我的券）：领取行联券定义。**排序与分桶全在 SQL 里做** —— 见类注释「时间列」那条：
     * 判据必须与写侧同一个时钟（{@code NOW()}）。
     *
     * <p>分桶（{@code Bucket}，前端据此上色/加标签）：
     * <ol>
     *   <li>{@code expiring} —— 未使用 + 未过期 + 命中快过期阈值（标「快过期」）</li>
     *   <li>{@code usable} —— 未使用 + 未过期 + 还早</li>
     *   <li>{@code used} —— 已核销</li>
     *   <li>{@code expired} —— 已过有效期（**沉底**，用户 2026-10-11 裁定 #4）</li>
     * </ol>
     * {@code SortIdx} 就是 {@code Bucket} 的序号，{@code ORDER BY} 直接用它 ⇒ 与分桶**不可能走岔**。
     *
     * <p>⚠️ 「已过期」与「已核销」是**不同**的两件事，别合并：前者是券没用上（用户的损失），
     * 后者是用掉了。合并成一个「不可用」会把两种完全不同的历史画成一样。
     * <p>⚠️ 券被管理端**停用**（{@code CouponStatus='stopped'}）的表：已领的券不复原、也不删，
     * 仍按上面的分桶显示（{@code stopped} 只挡住**新的**领取，见 {@link #tryClaim}）。
     *
     * <h3>【桶内次序：前两桶「快过期最上」、后两桶「新 → 旧」】</h3>
     * 用户 2026-10-11 裁定（用户中心 4 tab）：未使用 = 快过期最上；**已使用 / 已过期 = 新 → 旧**。
     * ⚠️ 这**推翻**了本节原来的 {@code c.EndAt ASC}（对后两桶是**旧 → 新**）—— 四个桶的键与方向
     * 各不相同，故写成四个 {@code CASE}：每个对**不匹配的桶恒 {@code NULL}** ⇒ 互不干扰，
     * 而 {@code SortIdx} 仍是第一排序键 ⇒ 与分桶**不可能走岔**。
     * <ul>
     *   <li>桶 0/1（未使用）：{@code EndAt IS NULL} 先排（**无期限的沉底**，照旧），再 {@code EndAt ASC}。</li>
     *   <li>桶 2（已使用）：{@code g.UsedAt DESC} —— 最近用掉的在前。</li>
     *   <li>桶 3（已过期）：{@code c.EndAt DESC} —— 最近过期的在前。</li>
     * </ul>
     *
     * <p>{@code EndDaysLeft} = {@code TIMESTAMPDIFF(DAY, NOW(), c.EndAt)}，给前端的「X 天后过期」标用。
     * ⚠️ 判据刻意与 {@code Bucket='expiring'} / 红点**同轴**（都是拿 {@code NOW()} 比 {@code c.EndAt}）——
     * {@code EndAt} 是用户填的本地墙钟原样入库，本域已按这条口径判「快过期」；两处必须同一个表达式，
     * 否则「快过期」的标会与红点打架。{@code TIMESTAMPDIFF} 是**截断**（1.5 天 → 1）⇒ 前端对 {@code <= 0}
     * 显示「今天过期」而不是「0 天后过期」。
     */
    public List<Map<String, Object>> selectMyGrants(String account) {
        String sql = "SELECT g.Id AS GrantId, g.CouponId, g.Source, g.Status AS GrantStatus, g.OrderId, "
                + "DATE_FORMAT(g.UsedAt, '%Y-%m-%d %H:%i:%s') AS UsedAt, "
                + "DATE_FORMAT(g.created_at, '%Y-%m-%d %H:%i:%s') AS GrantedAt, "
                + "c.Name, c.Type, c.FaceValue, c.Threshold, c.Scope, c.MerchantAccount, c.CategoryMatch, "
                + "c.Status AS CouponStatus, "
                + "DATE_FORMAT(c.StartAt, '%Y-%m-%d %H:%i:%s') AS StartAt, "
                + "DATE_FORMAT(c.EndAt, '%Y-%m-%d %H:%i:%s') AS EndAt, "
                + "TIMESTAMPDIFF(DAY, NOW(), c.EndAt) AS EndDaysLeft, "
                + "CASE "
                + "  WHEN g.Status = 'used' THEN 'used' "
                + "  WHEN c.EndAt IS NOT NULL AND c.EndAt <= NOW() THEN 'expired' "
                + "  WHEN c.EndAt IS NOT NULL AND NOW() >= DATE_SUB(c.EndAt, INTERVAL " + EXPIRING_THRESHOLD + " SECOND) "
                + "       THEN 'expiring' "
                + "  ELSE 'usable' END AS Bucket, "
                + "CASE "
                + "  WHEN g.Status = 'used' THEN 2 "
                + "  WHEN c.EndAt IS NOT NULL AND c.EndAt <= NOW() THEN 3 "
                + "  WHEN c.EndAt IS NOT NULL AND NOW() >= DATE_SUB(c.EndAt, INTERVAL " + EXPIRING_THRESHOLD + " SECOND) "
                + "       THEN 0 "
                + "  ELSE 1 END AS SortIdx "
                + "FROM coupon_grant g JOIN coupon c ON c.CouponId = g.CouponId "
                + "WHERE g.ToAccount = ? "
                + "ORDER BY SortIdx ASC, "
                + "CASE WHEN SortIdx IN (0, 1) THEN c.EndAt IS NULL END ASC, "
                + "CASE WHEN SortIdx IN (0, 1) THEN c.EndAt END ASC, "
                + "CASE WHEN SortIdx = 2 THEN g.UsedAt END DESC, "
                + "CASE WHEN SortIdx = 3 THEN c.EndAt END DESC, "
                + "g.Id DESC";
        return jdbcClient.sql(sql).param(account).query().listOfRows();
    }

    // ==================== 读：管理端（领取明细 / 图表） ====================

    /** 某张券的领取明细（券包「谁领了」那一块）。 */
    public List<Map<String, Object>> selectGrantsByCoupon(String couponId, int limit) {
        String sql = "SELECT g.Id AS GrantId, g.CouponId, g.ToAccount, g.Source, g.Status, g.OrderId, "
                + "DATE_FORMAT(g.UsedAt, '%Y-%m-%d %H:%i:%s') AS UsedAt, "
                + "DATE_FORMAT(g.created_at, '%Y-%m-%d %H:%i:%s') AS GrantedAt "
                + "FROM coupon_grant g WHERE g.CouponId = ? ORDER BY g.Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(couponId, limit).query().listOfRows();
    }

    /**
     * 每日**领取**数（近 {@code days} 天）—— 「领取 / 核销趋势」那条线的上半截。
     * ⚠️ {@code days} 是**内联**进 SQL 的整数（不是占位符）：`INTERVAL ? DAY` 在某些
     * MySQL 版本上会拒占位符，而它是调用方传进来的**有界整数**（Service 夹在 1–90），
     * 不存在注入面。
     */
    public List<Map<String, Object>> selectClaimDaily(int days) {
        String sql = "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS Day, COUNT(*) AS Claimed "
                + "FROM coupon_grant WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL " + days + " DAY) "
                + "GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d') ORDER BY Day ASC";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    /** 每日**核销**数（近 {@code days} 天）—— 同一条线的下半截。 */
    public List<Map<String, Object>> selectUsedDaily(int days) {
        String sql = "SELECT DATE_FORMAT(UsedAt, '%Y-%m-%d') AS Day, COUNT(*) AS Used "
                + "FROM coupon_grant WHERE UsedAt IS NOT NULL "
                + "AND UsedAt >= DATE_SUB(CURDATE(), INTERVAL " + days + " DAY) "
                + "GROUP BY DATE_FORMAT(UsedAt, '%Y-%m-%d') ORDER BY Day ASC";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    /** 券类型占比（饼图）：按 {@code Type} 数券定义。 */
    public List<Map<String, Object>> selectTypeShare() {
        return jdbcClient.sql("SELECT Type, COUNT(*) AS Cnt FROM coupon "
                        + "GROUP BY Type ORDER BY Cnt DESC, Type ASC")
                .query().listOfRows();
    }

    // ==================== 读：定时任务（未领 + 快过期） ====================

    /**
     * 「**未领 + 快过期**」的券 —— {@code CouponExpiryJob} 的扫描目标（裁定 #4 ①）。
     *
     * <p>「未领」的判据是**还有余量**（{@code TotalQty = 0} 视作不限量 ⇒ 永远「还有」）：
     * 一张领光的券再喊「快来领」是骗人。
     * 「快过期」用 {@link #EXPIRING_THRESHOLD} 那一个表达式 ⇒ 与券包的红点**同一个口径**。
     *
     * <p>⚠️ 只取 {@code EndAt} 非空的 —— 没有到期时间的券不会「即将过期」。
     */
    public List<Map<String, Object>> selectExpiringUnclaimed(int limit) {
        String sql = "SELECT " + COUPON_COLUMNS
                + "FROM coupon c WHERE c.Status = 'active' AND c.EndAt IS NOT NULL AND c.EndAt > NOW() "
                + "AND (c.TotalQty = 0 OR c.ClaimedQty < c.TotalQty) "
                + "AND NOW() >= DATE_SUB(c.EndAt, INTERVAL " + EXPIRING_THRESHOLD + " SECOND) "
                + "ORDER BY c.EndAt ASC LIMIT ?";
        return jdbcClient.sql(sql).param(limit).query().listOfRows();
    }

    /** 某张券**已经领过**的人（账号集）—— 定时任务据此把「已领的」从受众里剔掉。 */
    public Set<String> selectClaimantAccounts(String couponId) {
        return new HashSet<>(jdbcClient.sql("SELECT ToAccount FROM coupon_grant WHERE CouponId = ?")
                .param(couponId).query(String.class).list());
    }

    /** 某人某券的领取行（{@code unused} 的那条）；没有 ⇒ {@code null}。**块 5 commit 5 结算用券要读它**。 */
    public Map<String, Object> selectGrant(String couponId, String toAccount) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT g.Id AS GrantId, g.CouponId, g.ToAccount, g.Source, g.Status, g.OrderId "
                        + "FROM coupon_grant g WHERE g.CouponId = ? AND g.ToAccount = ? LIMIT 1")
                .params(couponId, toAccount).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 内部 ====================

    /** 列表与计数的共用谓词 —— 两处必须**逐字一致**（同 {@code AdminAuditDao} 的成对改要求）。 */
    private static void appendCouponFilters(StringBuilder sql, List<Object> params, String status, String keyword) {
        if (notBlank(status)) {
            sql.append(" AND c.Status = ?");
            params.add(status);
        }
        if (notBlank(keyword)) {
            sql.append(" AND c.Name LIKE ?");
            params.add("%" + keyword + "%");
        }
    }

    /** 按顺序逐个绑定 —— 与 {@code NotificationDao} / {@code AdminAuditDao} 同写法，避免参数错配。 */
    private JdbcClient.StatementSpec bind(String sql, List<Object> params) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
