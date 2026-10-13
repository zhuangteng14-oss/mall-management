package com.minke.mallmanagement.comment.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 举报域（块 6）的数据访问出口 —— {@code report} 表（本域自己的），
 * 外加**只读**的几支「被举报对象解析」查询（{@code comment} / {@code goods} / {@code user}）。
 *
 * <h2>【⚠️ 为什么本 DAO 会读别的域的表】</h2>
 * 举报的四个对象（评论 / 回复 / 商品 / 用户）分属三张表，而处理一条举报要能：
 * <ol>
 *   <li>把 {@code TargetType}+{@code TargetId} 还原成「被举报的是什么、作者是谁」；</li>
 *   <li>判「不能举报自己」（要先知道被举报内容的作者）；</li>
 *   <li>通过时删内容 + 给作者累计警告。</li>
 * </ol>
 * 这三件事的**写入**仍归各自域（评论走 {@code CommentDao.softDelete}、商品走
 * {@code GoodsDao.delistByTriple}、警告走本类的 {@link #incrWarnCount}）；本类里那几支
 * {@code selectXxxTarget} 是**只读**的解析查询，与 {@code CommentDao.selectMerchantUsernameByGoodsId}
 * （内容域读 {@code goods}+{@code user}）同一性质。**只此一处，且只读。**
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 同 {@code CommentDao} / {@code CartDao}：新域一律**裸表名**。
 * ⚠️ 老域（{@code OrderDao} / {@code GoodsDao}）里的 {@code mall_management_sql.} 前缀是存量债，别照抄。
 *
 * <h2>【{@code report} / {@code comment} 都带反引号】</h2>
 * {@code comment} 与 MySQL 关键字同形（见 {@code CommentDao} 类注释）；本类为一致起见
 * 把 {@code report} / {@code goods} / {@code user} 也一并反引号化。
 *
 * <h2>【时间列一律 {@code DATE_FORMAT} 成无时区文本 —— 全仓读侧口径】</h2>
 * 同 {@code CommentDao}：库里存 UTC，读出来是 {@code 'YYYY-MM-DD HH:MM:SS'} 文本，由前端转。
 */
@Repository
public class ReportDao {

    /**
     * {@code report} 读投影 —— 本域只有这一处投影（列表 / 单行共用）。
     * ⚠️ 改这里就是改**所有**读路径的输出形状。
     *
     * <p>⚠️ <b>{@code ReporterId} 与 {@code HandlerId} 不在同一个空间</b>：
     * <ul>
     *   <li>{@code ReporterId} = **登录名**（{@code login.UserName}，与 {@code comment.AuthorId}
     *       同口径）；</li>
     *   <li>{@code HandlerId} = **会话身份号**（{@code AuthSession.identify().userId()}，
     *       管理员即其 {@code login.Id}）—— 与 {@code AdminAuditDao.ActorId} 同源同口径。</li>
     * </ul>
     * 之所以不把 {@code HandlerId} 也换算成登录名：会话里**只有**业务号 / 身份号，而
     * 「身份号 → 登录名」在管理员这一支上**没有**无歧义的路径（管理员的 {@code customerid}
     * 借存的就是 {@code login.Id}，拿它去 {@code WHERE customerid = ? OR merchantid = ?} 查
     * 会同时命中一个客户行）。存原值、与审计表一致，比存一个可能查错人的「名字」诚实。
     */
    private static final String REPORT_COLUMNS =
            "r.`Id`, r.`TargetType`, r.`TargetId`, r.`ReporterId`, r.`ReporterRole`, "
                    + "r.`Reason`, r.`Description`, r.`Status`, r.`HandlerId`, r.`HandleNote`, "
                    + "DATE_FORMAT(r.`created_at`, '%Y-%m-%d %H:%i:%s') AS `CreatedAt`, "
                    + "DATE_FORMAT(r.`updated_at`, '%Y-%m-%d %H:%i:%s') AS `UpdatedAt`";

    /**
     * 列表 / 单行的**对象摘要**列 —— 三个 {@code LEFT JOIN}，按 {@code TargetType} 各取一边。
     *
     * <p>⚠️ {@code r.TargetId} 是 {@code varchar}、三张表的 {@code Id} 是 {@code bigint}
     * ⇒ 必须 {@code CAST(r.TargetId AS UNSIGNED)}。非数字的 {@code TargetId}（用户业务号那种）
     * 会 CAST 成 0，与任何真 {@code Id} 都不等 ⇒ 那一侧 JOIN 不上，恰好就是我们要的
     * 「不是这个类型就取不到这一列」。**不加这个 CAST 的话 MySQL 会做隐式转换**，
     * 行为随版本而变 —— 别赌。
     */
    private static final String TARGET_COLUMNS =
            "c.`Content` AS `TargetContent`, c.`AuthorId` AS `TargetAuthor`, "
                    + "c.`Kind` AS `TargetKind`, c.`ParentId` AS `TargetParentId`, "
                    + "g.`GoodName` AS `TargetGoodName`, g.`MerchantId` AS `TargetMerchantId`, "
                    + "u.`username` AS `TargetUserName`";

    private static final String TARGET_JOINS =
            " LEFT JOIN `comment` c ON r.`TargetType` = 'comment' AND c.`Id` = CAST(r.`TargetId` AS UNSIGNED)"
                    + " LEFT JOIN `goods` g ON r.`TargetType` = 'goods' AND g.`Id` = CAST(r.`TargetId` AS UNSIGNED)"
                    + " LEFT JOIN `user` u ON r.`TargetType` = 'user' "
                    + "AND (u.`customerid` = r.`TargetId` OR u.`merchantid` = r.`TargetId`)";

    private final JdbcClient jdbcClient;

    public ReportDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：举报单 ====================

    /**
     * 落一条举报。{@code Status} 走列默认 {@code 'pending'}、{@code HandlerId} / {@code HandleNote}
     * 走默认 {@code NULL} —— 不显式写，免得列清单与 DDL 两处漂开（同 {@code CommentDao.insertComment}）。
     *
     * <p>⚠️ 唯一键 {@code uk_report_target_reporter (TargetType, TargetId, ReporterId)} 兜
     * 「同内容不能二次举报」（方案 §4.4）：重复插入会抛
     * {@code DuplicateKeyException}，由 Service **先查后插**避开（见 {@link #selectByTargetReporter}）。
     * 这里不写 {@code INSERT IGNORE} —— 那会把「Service 漏查了」这一种真缺陷一起吃成静默。
     */
    public int insert(String targetType, String targetId, String reporterId, int reporterRole,
                      String reason, String description) {
        String sql = "INSERT INTO `report` "
                + "(`TargetType`, `TargetId`, `ReporterId`, `ReporterRole`, `Reason`, `Description`) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(targetType, targetId, reporterId, reporterRole, reason, description)
                .update();
    }

    /** {@code SELECT LAST_INSERT_ID()} —— 同 {@code CommentDao.lastInsertId}，只在事务里可信。 */
    public Long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /**
     * 「驳回后可再举报」= **UPDATE 同一行复活**（方案 §4.4；建表那一笔的列注释逐字写着）。
     *
     * <p>为什么不是「插新行」：唯一键 {@code (TargetType, TargetId, ReporterId)} 只允许一行 ⇒
     * 同一 (对象, 举报人) 的多次举报在库里**本就是同一条**，复活才与约束自洽。
     * 同时把处理痕迹清空（{@code HandlerId} / {@code HandleNote} = NULL）、
     * 以新理由覆盖旧理由、{@code created_at} 拨到此刻（列表按它倒序，复活的单子该回到最上）。
     *
     * <p>⚠️ 谓词带 {@code AND Status = 'rejected'} —— 只有**已驳回**的能复活。
     * 待处理 / 已处理的被 UI 提前拦住了，这一条是防「并发下同一行被复活两次」的 CAS。
     *
     * @return 受影响行数（{@code 0} = 那行已不在 {@code rejected}，由 Service 翻成 {@code success:false}）
     */
    public int revive(long id, String reason, String description) {
        return jdbcClient.sql("UPDATE `report` SET `Status` = 'pending', `Reason` = ?, "
                        + "`Description` = ?, `HandlerId` = NULL, `HandleNote` = NULL, "
                        + "`created_at` = CURRENT_TIMESTAMP "
                        + "WHERE `Id` = ? AND `Status` = 'rejected'")
                .params(reason, description, id).update();
    }

    /**
     * 处理一条举报（通过 / 驳回）—— CAS：只在 {@code Status = 'pending'} 时改。
     *
     * <p>⚠️ 这条 CAS 是**幂等与并发**的唯一防线：两个管理员同时点「通过」，
     * 第二个的 {@code affected} 会是 0（Service 翻成「该举报已被处理」），
     * 于是**删内容 / 加警告只发生一次**。
     *
     * @return 受影响行数（{@code 0} = 已被别人处理过 / 不存在）
     */
    public int updateHandle(long id, String status, String handlerId, String note) {
        return jdbcClient.sql("UPDATE `report` SET `Status` = ?, `HandlerId` = ?, `HandleNote` = ? "
                        + "WHERE `Id` = ? AND `Status` = 'pending'")
                .params(status, handlerId, note, id).update();
    }

    // ==================== 写：警告累计（{@code user.WarnCount}） ====================

    /**
     * 警告 +1。⚠️ 原子自增（{@code WarnCount = WarnCount + 1}）而不是「读出来 +1 再写回」
     * —— 后者在并发下会丢计数（两次举报只加 1），而「3 次封号」正靠这个计数。
     *
     * @return 受影响行数（{@code 0} = 该账号不在 {@code user} 表）
     */
    public int incrWarnCount(String username) {
        return jdbcClient.sql("UPDATE `user` SET `WarnCount` = `WarnCount` + 1 WHERE `username` = ?")
                .param(username).update();
    }

    /** 解封时由管理端清零（方案 §4.4「解封 = 管理员去用户与商家治理手动解」的口径延伸）。 */
    public int resetWarnCount(String username) {
        return jdbcClient.sql("UPDATE `user` SET `WarnCount` = 0 WHERE `username` = ?")
                .param(username).update();
    }

    /** 当前警告数（{@code user} 表里没有这个账号 ⇒ {@code null}）。 */
    public Integer selectWarnCount(String username) {
        List<Integer> rows = jdbcClient
                .sql("SELECT `WarnCount` FROM `user` WHERE `username` = ? LIMIT 1")
                .param(username).query(Integer.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 读：举报单 ====================

    /**
     * 按 {@code (对象, 举报人)} 取那一行 —— 「同内容不能二次举报」的**先查**那一半
     * （后一半是唯一键）。{@code Status} 随行带出：{@code pending / handled} ⇒ Service 拒；
     * {@code rejected} ⇒ Service 走 {@link #revive}。查无 ⇒ {@code null}。
     */
    public Map<String, Object> selectByTargetReporter(String targetType, String targetId, String reporterId) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + REPORT_COLUMNS + ", " + TARGET_COLUMNS + " FROM `report` r "
                        + TARGET_JOINS
                        + " WHERE r.`TargetType` = ? AND r.`TargetId` = ? AND r.`ReporterId` = ? LIMIT 1")
                .params(targetType, targetId, reporterId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 举报列表（管理端「举报中心」）。{@code status} 空白 = 不筛（同时带出待处理与历史）。
     * 时间倒序 —— 新的在最上（待处理的单子需要先被看见）。
     */
    public List<Map<String, Object>> selectReports(String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(REPORT_COLUMNS).append(", ").append(TARGET_COLUMNS)
                .append(" FROM `report` r ").append(TARGET_JOINS).append(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.`Status` = ?");
        }
        sql.append(" ORDER BY r.`created_at` DESC, r.`Id` DESC LIMIT ? OFFSET ?");
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        if (status != null && !status.isBlank()) {
            spec = spec.param(status);
        }
        return spec.params(limit, offset).query().listOfRows();
    }

    /** 同筛选条件下的举报单数（口径同 {@code CommentDao.countTopLevel}）。 */
    public long countReports(String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM `report` r WHERE 1 = 1");
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.`Status` = ?");
        }
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        if (status != null && !status.isBlank()) {
            spec = spec.param(status);
        }
        Long n = spec.query(Long.class).single();
        return n == null ? 0L : n;
    }

    /** 单行（含对象摘要）—— 处理前取「被举报的是谁」用。查无 ⇒ {@code null}。 */
    public Map<String, Object> selectReport(long id) {
        List<Map<String, Object>> rows = jdbcClient
                .sql("SELECT " + REPORT_COLUMNS + ", " + TARGET_COLUMNS + " FROM `report` r "
                        + TARGET_JOINS + " WHERE r.`Id` = ? LIMIT 1")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 读：对象解析（只读，见类注释） ====================

    /**
     * 一条评论 / 回复的处置所需字段 —— {@code AuthorId}（登录名，举报自己判据 + 警告对象）、
     * {@code AuthorRole}、{@code Kind}、{@code Content}（摘要）、{@code Status}（已删的不再处置）。
     */
    public Map<String, Object> selectCommentTarget(long id) {
        List<Map<String, Object>> rows = jdbcClient.sql("SELECT `Id`, `ParentId`, `Kind`, `AuthorId`, "
                        + "`AuthorRole`, `Status`, `Content` FROM `comment` WHERE `Id` = ? LIMIT 1")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 一件商品的处置三元组 —— 与 {@code GoodsDao.delistByTriple} 的谓词同源
     * （{@code (MerchantId, GoodName, ProductCode)}，不是自增 {@code Id}，见
     * {@code GoodsViolationService} 类注释的理由）。
     */
    public Map<String, Object> selectGoodsTarget(long id) {
        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT `Id`, `MerchantId`, `GoodName`, `ProductCode` FROM `goods` "
                                + "WHERE `Id` = ? LIMIT 1")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 一个用户（业务号）→ 登录名 + 角色。
     *
     * <p>⚠️ {@code customerid} / {@code merchantid} 两个方向都试（同
     * {@code CommentDao.selectUsernameByUserId} 的选列口径）。
     */
    public Map<String, Object> selectUserTargetByBusinessId(String businessId) {
        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT `username`, `usertype`, `customerid`, `merchantid` FROM `user` "
                                + "WHERE `customerid` = ? OR `merchantid` = ? LIMIT 1")
                .params(businessId, businessId).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 读：账号换算（与 CommentDao 同口径，各域各一份） ====================

    /**
     * 登录名 → 角色（{@code login.Role}：1 客户 / 2 商家 / 3 管理员）。查无 ⇒ {@code null}。
     * ⚠️ 与 {@code CommentDao.selectRoleByUsername} 是同一句 SQL 的**两处**实现 ——
     * 各域各持一份是本仓既有做法（{@code CartDao} / {@code CouponDao} 同理），别强行合并。
     */
    public Integer selectRoleByUsername(String username) {
        List<Integer> rows = jdbcClient.sql("SELECT `Role` FROM `login` WHERE `UserName` = ? LIMIT 1")
                .param(username).query(Integer.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** **业务号** → 登录名（{@code customerid} / {@code merchantid} 都试）。查无 ⇒ {@code null}。 */
    public String selectUsernameByUserId(String userId) {
        List<String> rows = jdbcClient.sql("SELECT `username` FROM `user` "
                        + "WHERE `customerid` = ? OR `merchantid` = ? LIMIT 1")
                .params(userId, userId).query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 登录名 → **客户业务号**（{@code user.customerid}）。查无 / 该列为 NULL ⇒ {@code null}。 */
    public String selectCustomerIdByUsername(String username) {
        List<String> rows = jdbcClient.sql("SELECT `customerid` FROM `user` WHERE `username` = ? LIMIT 1")
                .param(username).query(String.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
