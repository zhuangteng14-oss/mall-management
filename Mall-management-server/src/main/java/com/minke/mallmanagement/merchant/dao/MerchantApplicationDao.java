package com.minke.mallmanagement.merchant.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code merchant_application} 表的内联 SQL 出口（C3 块 2 用户块，**新域 {@code merchant}**）。
 *
 * <h2>【⚠️ 本域**不带** {@code mall_management_sql.} 前缀 —— 与块 1 的 notification 域同口径】</h2>
 * 全仓「硬编码 schema 前缀 39 处」（TD-13）是一个被多处文档引用的基线。新增域若**跟旧域**
 * 带前缀，每加一条语句就把那个数往上推；若**不跟**，基线不动。
 * <p>判据（用户 B 档授权，「复用既有结构」）：<b>前缀口径跟「本文件所属的域」</b>——
 * {@code user/} 已于块 2 新增的 {@code UserGovernanceDao} 跟本域旧写法（带前缀，因同域既有 16 处皆带）；
 * 而本类所在的 {@code merchant/} 是**全新域**（全仓此前无 merchant 包），没有「本域旧写法」可跟，
 * 故取 message 域那一支（**不带**）。两处相反的决定**不是**自相矛盾，是同一个判据的两个结果。
 *
 * <h2>【CAS 是这层的核心：审核的幂等靠 {@code WHERE Status='pending'}】</h2>
 * {@link #casApprove} / {@link #casReject} 的条件 UPDATE 照 {@code OrderDao} 的范式
 * （{@code 执行一条条件 UPDATE（CAS），返回影响行数}）：{@code >=1} 才算「我这次是第一次处理」，
 * {@code 0} = 已被别的管理员处理过 ⇒ Service 据此回「该申请已被处理」，**不抛**。
 * 这一步必须在**数据库**里做（不能先 SELECT 再判断再 UPDATE）—— 后者的两个管理员会同时看到 pending。
 */
@Repository
public class MerchantApplicationDao {

    /** 列表共用列清单（管理端待审列表 + 我的申请共用一份投影）。时间列经 DATE_FORMAT（见下）。 */
    private static final String COLUMNS =
            "Id, Username, ShopName, LicenseNo, ContactName, ContactPhone, ShopAddress, LicenseImage, "
                    + "IsBrand, ApplicationType, AttemptNo, ParentId, Status, RejectReason, BatchId, ReviewedBy, "
                    + "DATE_FORMAT(SubmittedAt, '%Y-%m-%d %H:%i:%s') AS SubmittedAt, "
                    + "DATE_FORMAT(ReviewedAt,  '%Y-%m-%d %H:%i:%s') AS ReviewedAt ";

    private final JdbcClient jdbcClient;

    public MerchantApplicationDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：提交一条申请 ====================

    /**
     * 插入一条待审申请。{@code Status} 固定 {@code 'pending'}、{@code SubmittedAt} 由 Java 侧给
     * —— 不靠列的 DEFAULT，理由同 {@code UserGovernanceDao} 的时间口径（读写同源）。
     *
     * @return 受影响行数（正常恒为 1）
     */
    public int insert(String username, String shopName, String licenseNo,
                      String contactName, String contactPhone, String shopAddress,
                      String licenseImage, String batchId, LocalDateTime submittedAt) {
        return insert(username, shopName, licenseNo, contactName, contactPhone, shopAddress,
                licenseImage, batchId, submittedAt, false);
    }

    /**
     * 同 {@link #insert} 的 9 参版本，多一个 {@code isBrand}（批四第二批 2026-10-19）。
     * {@code true} = 品牌加盟申请：**不改身份**，通过时只置 {@code user.IsBrand=1}。
     * 旧签名委托到这里（{@code false}）⇒ 既有入驻路径逐字不变。
     */
    public int insert(String username, String shopName, String licenseNo,
                      String contactName, String contactPhone, String shopAddress,
                      String licenseImage, String batchId, LocalDateTime submittedAt,
                      boolean isBrand) {
        return insert(username, shopName, licenseNo, contactName, contactPhone, shopAddress,
                licenseImage, batchId, submittedAt, isBrand, 1, null);
    }

    /**
     * 第三批㉗：品牌加盟的完整写入——多带 {@code attemptNo}（第几次）与 {@code parentId}
     * （上一版申请 Id）。{@code ApplicationType} 由 {@code isBrand} 推出并**显式落库**：
     * 生成列 {@code PendingGuard = Username:ApplicationType} 的唯一键依赖它，
     * 漏写会让品牌申请与入驻申请在「同账号待审唯一」上撞车。
     *
     * <p>委托链：8 参 → 9 参（{@code isBrand}）→ 本方法。既有入驻路径经过前两跳，行为不变。
     */
    public int insert(String username, String shopName, String licenseNo,
                      String contactName, String contactPhone, String shopAddress,
                      String licenseImage, String batchId, LocalDateTime submittedAt,
                      boolean isBrand, int attemptNo, Long parentId) {
        String sql = "INSERT INTO merchant_application "
                + "(Username, ShopName, LicenseNo, ContactName, ContactPhone, ShopAddress, LicenseImage, "
                + " IsBrand, ApplicationType, AttemptNo, ParentId, Status, BatchId, SubmittedAt) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)";
        return jdbcClient.sql(sql)
                .params(username, shopName, licenseNo, contactName, contactPhone, shopAddress,
                        licenseImage, isBrand ? 1 : 0, isBrand ? "brand" : "settle",
                        attemptNo, parentId, batchId, submittedAt)
                .update();
    }

    // ==================== 读：申请 ====================

    /** 「我的申请」（按登录名，时间倒序）—— 用户端入驻页用。 */
    public List<Map<String, Object>> selectByUsername(String username, int limit) {
        String sql = "SELECT " + COLUMNS + "FROM merchant_application "
                + "WHERE Username = ? ORDER BY SubmittedAt DESC, Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(username, limit).query().listOfRows();
    }

    /**
     * 按状态列出（管理端待审 / 全部）。{@code status} 为 {@code null} / 空 ⇒ 不筛。
     *
     * <p>⚠️ <b>先把 SQL 文本定稿、再按 {@code ?} 出现顺序绑参</b>（同 {@code AdminAuditDao} 的警告）——
     * 改本方法时成对改。写成「先建 spec 再改 StringBuilder」会得到一句 {@code LIMIT} 排在
     * {@code ORDER BY} 之前的错序 SQL。
     */
    public List<Map<String, Object>> selectByStatus(String status, int limit) {
        boolean filtered = !isBlank(status);
        String sql = "SELECT " + COLUMNS + "FROM merchant_application "
                + (filtered ? "WHERE Status = ? " : "")
                // 批四第二批：品牌加盟申请排前（加性排序，同一批里仍按提交时间倒序）。
                + "ORDER BY IsBrand DESC, SubmittedAt DESC, Id DESC LIMIT ?";
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        if (filtered) {
            spec = spec.param(status);
        }
        return spec.param(limit).query().listOfRows();
    }

    /** 与 {@link #selectByStatus} 同筛选的计数。 */
    public long countByStatus(String status) {
        String sql = isBlank(status)
                ? "SELECT COUNT(*) FROM merchant_application"
                : "SELECT COUNT(*) FROM merchant_application WHERE Status = ?";
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql);
        if (!isBlank(status)) {
            spec = spec.param(status);
        }
        return spec.query(Long.class).single();
    }

    /** 按主键取一条（审核前的快照读，也是审计 {@code before} 的来源）。查不到 ⇒ {@code null}。 */
    public Map<String, Object> selectById(long id) {
        String sql = "SELECT " + COLUMNS + "FROM merchant_application WHERE Id = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 写：审核（CAS 两条） ====================

    /**
     * 审核**通过**的 CAS。{@code WHERE Id = ? AND Status = 'pending'} 是幂等判据。
     *
     * @return 受影响行数：{@code 1} = 我这次拿下了；{@code 0} = 不存在或已被处理
     */
    public int casApprove(long id, String reviewedBy, LocalDateTime reviewedAt) {
        String sql = "UPDATE merchant_application "
                + "SET Status = 'approved', ReviewedBy = ?, ReviewedAt = ?, RejectReason = NULL "
                + "WHERE Id = ? AND Status = 'pending'";
        return jdbcClient.sql(sql).params(reviewedBy, reviewedAt, id).update();
    }

    /** 审核**驳回**的 CAS，形状同 {@link #casApprove}，多写一个 {@code RejectReason}。 */
    public int casReject(long id, String reviewedBy, LocalDateTime reviewedAt, String rejectReason) {
        String sql = "UPDATE merchant_application "
                + "SET Status = 'rejected', ReviewedBy = ?, ReviewedAt = ?, RejectReason = ? "
                + "WHERE Id = ? AND Status = 'pending'";
        return jdbcClient.sql(sql).params(reviewedBy, reviewedAt, rejectReason, id).update();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 某账号**某一种**申请（{@code settle} / {@code brand}）里最新的一条（2026-10-22，N1）。
     *
     * <p>为什么不能用 {@link #selectByUsername}：那条把 settle 与 brand 混在一起按时间倒序，
     * 一个刚做过品牌加盟的商家会被判成「最近申请是 brand」，从而丢掉入驻状态。身份解析只关心
     * {@code settle}，必须带 {@code ApplicationType} 过滤（R2）。
     *
     * <p>排序与既有两个读方法同口径（{@code SubmittedAt DESC, Id DESC}）—— 同一秒提交的两条
     * 靠自增主键稳定先后，避免「审核中」与「已驳回」在同一次解析里来回跳。
     *
     * <p>⚠️ 方法体追加在**文件末尾**（{@code isBlank} 之后），不移动上面任何被文档按行号引用的位置。
     *
     * @param username        登录名（不是业务号）
     * @param applicationType {@code settle} 或 {@code brand}
     * @return 查不到 ⇒ {@code null}
     */
    public Map<String, Object> selectLatestByUsernameAndType(String username, String applicationType) {
        String sql = "SELECT " + COLUMNS + "FROM merchant_application "
                + "WHERE Username = ? AND ApplicationType = ? "
                + "ORDER BY SubmittedAt DESC, Id DESC LIMIT 1";
        List<Map<String, Object>> rows = jdbcClient.sql(sql)
                .params(username, applicationType)
                .query()
                .listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
