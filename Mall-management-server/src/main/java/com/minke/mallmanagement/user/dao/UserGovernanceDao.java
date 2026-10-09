package com.minke.mallmanagement.user.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 「用户治理」列面的内联 SQL 出口（C3 块 2 用户块新增）—— 封禁 / 解封 / 资料扩写 /
 * 商家降级五阶段的状态读写，全部落在 {@code mall_management_sql.user} 的那 8 个新列上。
 *
 * <h2>【⚠️ 为什么另开一个 DAO，而不是往 {@code UserDao} 里加方法】</h2>
 * {@link UserDao} 是本仓**被行号引用最密的文件之一**（全仓有 {@code UserDao.java:95} /
 * {@code :140} / {@code :238} 等多处互引）。往它中间插方法会让这些引用集体漂移，
 * 而本笔改动的每一行本来就够多了。新开一个只装**本块新 SQL** 的类 ⇒ {@code UserDao}
 * <b>一行不动</b>（它现有的 16 处前缀字面量与全部行号保持稳定）。
 *
 * <p>⚠️ 代价（登记）：本域的 SQL 出口从 1 个变 2 个。判据是「同一个调用点语义」——
 * {@code UserDao} 服务 {@code UserController} 的 5 个既有端点；本类服务块 2 的
 * 治理端点与两处跨域探针（{@link com.minke.mallmanagement.shared.web.UserStateLookup}）。
 * 两者不重叠、不互相调用。
 *
 * <h2>【前缀与时间口径】</h2>
 * <ul>
 *   <li>⚠️ <b>本类的 SQL **一律不带** {@code mall_management_sql.} 前缀 —— 与同域的 {@code UserDao}
 *       （16 处前缀）取的是**相反**的写法，这是刻意的</b>。全仓「硬编码 schema 前缀 39 处」
 *       （TD-13）是一个多文档引用的基线；<b>新增语句不带前缀，那条基线就不因本块而变</b>
 *       （同 {@code notification} / {@code merchant} 两个块 1/块 2 新域，以及 {@code MessageDao}）。
 *       不带前缀依赖 datasource 的默认库名（三段配置——默认 / smoke / a3——都指向
 *       {@code mall_management_sql}，见 {@code application*.properties}）。
 *       ⚠️ 这与 {@code UserDao} 的「同域同风格」是两条不同的取舍，取舍点是
 *       「**动老文件 vs 开新文件**」：老文件里逐字保留（避免夹带口径变更），新文件里不再复制。
 *       已登记（本块的 TD-13 按语）。</li>
 *   <li><b>时间列一律由 Java 侧传 {@link LocalDateTime} 写入与比较，不用 SQL 的 {@code NOW(3)}</b>。
 *       理由：写与读在同一条 JVM 上（降级调度是「Java 算 +1 天，稍后 Java 再比一次」），
 *       两侧同源 ⇒ 不可能出现「服务器时区 vs JVM 时区」的半拍不一致。读出来的时间列仍照
 *       全仓口径用 {@code DATE_FORMAT(…, '%Y-%m-%d %H:%i:%s')} 交回**无时区字符串**，
 *       由前端按浏览器时区显示（见 {@code NotificationDao} 类注释的同一条理由）。</li>
 *   <li>⚠️ <b>{@code banned} 是 {@code tinyint(1)}，Connector/J 默认把它映射成 {@link Boolean}</b>
 *       （{@code tinyInt1isBit=true}）——故读侧**不能**直接强转 {@code Integer}，
 *       统一走 {@link #truthy(Object)} 兜住 Boolean / Number / 字符串三种形态。</li>
 * </ul>
 */
@Repository
public class UserGovernanceDao {

    /** 管理员列表的共用列清单 —— 只此一处投影，自洽即可。⚠️ 时间列全部经 DATE_FORMAT（见类注释）。 */
    private static final String USER_COLUMNS =
            "username, usertype, customerid, merchantid, gender, age, email, "
                    + "banned, banReason, "
                    + "DATE_FORMAT(bannedAt, '%Y-%m-%d %H:%i:%s') AS bannedAt, "
                    + "DowngradeState, DowngradeReason, "
                    + "DATE_FORMAT(ScheduledDowngradeAt, '%Y-%m-%d %H:%i:%s') AS ScheduledDowngradeAt, "
                    + "DATE_FORMAT(FrozenAt, '%Y-%m-%d %H:%i:%s') AS FrozenAt, "
                    + "DATE_FORMAT(FreezeUntil, '%Y-%m-%d %H:%i:%s') AS FreezeUntil ";

    private final JdbcClient jdbcClient;

    public UserGovernanceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 读：跨域状态探针（两条闸共用） ====================

    /**
     * 按**登录名**判封禁。给 {@code UserService.loginSelect} 那一跳用。
     *
     * <p>⚠️ 用 {@code listOfRows()} 而**不是** {@code .single()}：后者 0 行抛异常，而「查不到该行」
     * 与「未封禁」对调用方是同一件事 —— 不该让一个不存在的账号把登录打成 500。
     */
    public boolean isBannedByUsername(String username) {
        String sql = "SELECT banned FROM `user` WHERE username = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        return !rows.isEmpty() && truthy(rows.get(0).get("banned"));
    }

    /**
     * 按**业务号**判封禁。给 {@link com.minke.mallmanagement.shared.web.AuthInterceptor}
     * 的第二道闸用（它手里只有会话里的业务号，没有登录名）。
     *
     * @param useMerchantColumn {@code true} ⇒ 查 {@code merchantid} 列（商家）；否则查
     *                          {@code customerid}。⚠️ 与 {@code UserService.businessId} 的选列判据同源
     */
    public boolean isBannedByBusinessId(String businessId, boolean useMerchantColumn) {
        String column = useMerchantColumn ? "merchantid" : "customerid";
        String sql = "SELECT banned FROM `user` WHERE " + column + " = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(businessId).query().listOfRows();
        return !rows.isEmpty() && truthy(rows.get(0).get("banned"));
    }

    /**
     * 这个商家是否处于**降级冻结态**（{@code DowngradeState='frozen'}）—— 下单链路的拒单判据。
     *
     * <p>{@code COUNT(*)} 恒有行 ⇒ 这里用 {@code .single()} 是安全的（与上面两条判据不同）。
     */
    public boolean isMerchantFrozen(String merchantId) {
        String sql = "SELECT COUNT(*) FROM `user` "
                + "WHERE merchantid = ? AND DowngradeState = 'frozen'";
        return jdbcClient.sql(sql).param(merchantId).query(Integer.class).single() > 0;
    }

    // ==================== 读：管理端用户列表 ====================

    /**
     * 用户列表（管理员用）。{@code keyword} 非空时按 {@code username} / {@code customerid} /
     * {@code merchantid} 模糊匹配（三种都试，管理员常只记得其中一种）。
     */
    public List<Map<String, Object>> selectUsers(String keyword, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(USER_COLUMNS)
                .append("FROM `user` WHERE 1 = 1 ");
        if (!isBlank(keyword)) {
            sql.append("AND (username LIKE ? OR customerid LIKE ? OR merchantid LIKE ?) ");
        }
        sql.append("ORDER BY banned DESC, username ASC LIMIT ?");

        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        if (!isBlank(keyword)) {
            String like = "%" + keyword + "%";
            spec = spec.params(like, like, like);
        }
        return spec.param(limit).query().listOfRows();
    }

    /** 单账号整行（封禁/解封/降级的审计 {@code before}/{@code after} 快照用）。查不到 ⇒ {@code null}。 */
    public Map<String, Object> selectUserRow(String username) {
        String sql = "SELECT " + USER_COLUMNS + "FROM `user` WHERE username = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 与 {@link #selectUsers} **同一筛选条件**的总数（前端「已截断」判据用）。 */
    public long countUsers(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM `user` WHERE 1 = 1 ");
        if (!isBlank(keyword)) {
            sql.append("AND (username LIKE ? OR customerid LIKE ? OR merchantid LIKE ?)");
        }
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        if (!isBlank(keyword)) {
            String like = "%" + keyword + "%";
            spec = spec.params(like, like, like);
        }
        return spec.query(Long.class).single();
    }

    // ==================== 写：封禁 / 解封 ====================

    /**
     * 置封禁态。{@code banned=true} ⇒ 写 {@code banned=1, banReason, bannedAt}；
     * {@code false} ⇒ 三列一起清回未封禁形状（{@code 0 / NULL / NULL}）。
     *
     * @return 受影响行数（{@code 0} = 该账号不存在，由 Service 翻成 {@code success:false}）
     */
    public int updateBan(String username, boolean banned, String reason, LocalDateTime at) {
        if (banned) {
            String sql = "UPDATE `user` "
                    + "SET banned = 1, banReason = ?, bannedAt = ? WHERE username = ?";
            return jdbcClient.sql(sql).params(reason, at, username).update();
        }
        String sql = "UPDATE `user` "
                + "SET banned = 0, banReason = NULL, bannedAt = NULL WHERE username = ?";
        return jdbcClient.sql(sql).param(username).update();
    }

    // ==================== 写：资料扩写（gender / age） ====================

    /**
     * 更新可编辑资料（{@code gender} / {@code age}）—— 与既有的
     * {@code UserDao.updateUserProfile}（email / address / selfintroduction）分工不重叠。
     *
     * <p>⚠️ <b>调用方必须清 {@code userPublic} 缓存</b>：{@code gender} 在缓存负载的 5 列里
     * （见 {@code UserCacheService}），这与 {@code updateUserProfile}「三列与缓存无交集、故免清」
     * 的论证**正好相反**。别照抄那一条。
     */
    public int updateProfile(String username, String gender, Integer age) {
        String sql = "UPDATE `user` SET gender = ?, age = ? WHERE username = ?";
        return jdbcClient.sql(sql).params(gender, age, username).update();
    }

    // ==================== 写 / 读：降级五阶段 ====================

    /** ① 管理员发起降级：置 {@code scheduled} + 通知期截止时刻（= 发起时刻 + 1 天）。 */
    public int markScheduled(String username, String reason, LocalDateTime scheduledAt) {
        String sql = "UPDATE `user` "
                + "SET DowngradeState = 'scheduled', DowngradeReason = ?, ScheduledDowngradeAt = ?, "
                + "    FrozenAt = NULL, FreezeUntil = NULL "
                + "WHERE username = ?";
        return jdbcClient.sql(sql).params(reason, scheduledAt, username).update();
    }

    /** ③ 到期执行：置 {@code frozen} + 记录冻结点与 30 天窗口末端。 */
    public int markFrozen(String username, LocalDateTime frozenAt, LocalDateTime freezeUntil) {
        String sql = "UPDATE `user` "
                + "SET DowngradeState = 'frozen', ScheduledDowngradeAt = NULL, "
                + "    FrozenAt = ?, FreezeUntil = ? "
                + "WHERE username = ?";
        return jdbcClient.sql(sql).params(frozenAt, freezeUntil, username).update();
    }

    /** ④⑤ 恢复 / 取消：降级六列一起清回 {@code none} —— 一条路径收口两种「回到常态」。 */
    public int clearDowngrade(String username) {
        String sql = "UPDATE `user` "
                + "SET DowngradeState = 'none', ScheduledDowngradeAt = NULL, DowngradeReason = NULL, "
                + "    FrozenAt = NULL, FreezeUntil = NULL "
                + "WHERE username = ?";
        return jdbcClient.sql(sql).param(username).update();
    }

    /**
     * 扫描「通知期已过、该执行降级」的商家 —— {@code @Scheduled} 任务唯一的一条读。
     *
     * <p>⚠️ 比较值由**调用方传入**（Java 侧 {@code now()}），不写 SQL 的 {@code NOW()} ——
     * 理由见类注释「时间口径」：写 {@code ScheduledDowngradeAt} 的也是 Java 侧，
     * 同源才不会有时区半拍。
     */
    public List<Map<String, Object>> selectDueDowngrades(LocalDateTime now) {
        String sql = "SELECT username, merchantid, DowngradeReason FROM `user` "
                + "WHERE DowngradeState = 'scheduled' AND ScheduledDowngradeAt IS NOT NULL "
                + "  AND ScheduledDowngradeAt <= ?";
        return jdbcClient.sql(sql).param(now).query().listOfRows();
    }

    /** 单个账号的降级状态（审核通过后判「该不该自动恢复」用）。查不到 ⇒ {@code null}。 */
    public Map<String, Object> selectDowngradeRow(String username) {
        String sql = "SELECT DowngradeState, DowngradeReason, "
                + "DATE_FORMAT(FrozenAt, '%Y-%m-%d %H:%i:%s') AS FrozenAt, "
                + "DATE_FORMAT(FreezeUntil, '%Y-%m-%d %H:%i:%s') AS FreezeUntil "
                + "FROM `user` WHERE username = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 写 / 读：客户 ⇄ 商家（入驻升级 / 降级执行） ====================

    /** 取该账号当前的 {@code merchantid}（可能为 NULL —— 客户行、或从未升级过）。 */
    public String selectMerchantId(String username) {
        return selectIdColumn(username, "merchantid");
    }

    /** 取该账号的 {@code customerid}（升级时用它清 {@code userPublic::<customerid>} 那条缓存）。 */
    public String selectCustomerId(String username) {
        return selectIdColumn(username, "customerid");
    }

    private String selectIdColumn(String username, String column) {
        String sql = "SELECT " + column + " FROM `user` WHERE username = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        if (rows.isEmpty() || rows.get(0).get(column) == null) {
            return null;
        }
        return rows.get(0).get(column).toString();
    }

    /** 取该账号的客户头像 URL（升级为商家时把它复制进 {@code merchantimage}）。查不到 ⇒ {@code null}。 */
    public String selectCustomerImage(String username) {
        String sql = "SELECT image FROM customerimage WHERE username = ?";
        List<Map<String, Object>> rows = jdbcClient.sql(sql).param(username).query().listOfRows();
        if (rows.isEmpty() || rows.get(0).get("image") == null) {
            return null;
        }
        return rows.get(0).get("image").toString();
    }

    /**
     * 升级：{@code user} 行置商家形状。⚠️ {@code customerid} **刻意保留**（升级的是**增列**，不是换行）——
     * 这正是前端 {@code UserInfoPage.vue} 商家/客户版判据不能再靠 {@code !customerid && merchantid} 的原因。
     */
    public int promoteToMerchant(String username, String merchantId) {
        String sql = "UPDATE `user` "
                + "SET usertype = '商家', merchantid = ? WHERE username = ?";
        return jdbcClient.sql(sql).params(merchantId, username).update();
    }

    /**
     * 改 {@code login.Role}（升级 1→2 / 降级执行 2→1）。{@code id} 一并写 —— 商家行的
     * {@code login.Id} 与 {@code user.merchantid} 同值（注册时 {@code insertLoginMerchant} 就是这么写的）。
     */
    public int updateLoginRole(String username, int role, String id) {
        String sql = "UPDATE `login` SET Role = ?, Id = ? WHERE UserName = ?";
        return jdbcClient.sql(sql).params(role, id, username).update();
    }

    /**
     * 确保 {@code merchantimage} 有一行（升级时把客户头像复制过来）。<b>upsert</b> ——
     * 「降级后再升级」会二次走到这里，而 PK 是 {@code UserName}，纯 INSERT 会撞键。
     *
     * <p>⚠️ 用 {@code ON DUPLICATE KEY UPDATE 列 = ?} 的**重复传参**写法，不用 {@code VALUES(列)}
     * —— 后者在 MySQL 8.0.20+ 已弃用，会在日志里刷 warning。
     */
    public int upsertMerchantImage(String username, String merchantId, String image) {
        String sql = "INSERT INTO merchantimage (UserName, MerchantId, image) "
                + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE MerchantId = ?, image = ?";
        return jdbcClient.sql(sql).params(username, merchantId, image, merchantId, image).update();
    }

    // ==================== 内部 ====================

    /** {@code banned} 三形态兜底 —— 见类注释「tinyint(1) 映射成 Boolean」。 */
    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = v.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
