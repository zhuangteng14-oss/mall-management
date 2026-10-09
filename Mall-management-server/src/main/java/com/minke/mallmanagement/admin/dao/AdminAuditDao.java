package com.minke.mallmanagement.admin.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * admin 域的**内联 SQL 唯一出口**（C3 批 A：权限与审计）—— 只碰一张表 {@code admin_audit}。
 *
 * <p>范式照 {@link com.minke.mallmanagement.user.dao.UserDao}：{@code @Repository} +
 * 构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**（让 {@code @WebMvcTest} 切片里既有的
 * {@code @MockitoBean JdbcTemplate} 继续喂给本类）+ 语句以 Java 字符串内联。不用 MyBatis、无 mapper XML
 * —— 全仓没有这两样东西（见 {@code pom.xml}，只有 {@code spring-boot-starter-jdbc}）。
 *
 * <h2>【{@code mall_management_sql.} 前缀：本类**带**前缀，且这是刻意的】</h2>
 * 全仓「硬编码 schema 前缀」的历史口径是 <b>39 处</b>（TD-13，2026-10-02 实测），
 * 且那是一个**被多处文档引用的验收基线**。本类是**新文件**，本可以选择不带前缀、不给那个数字加码；
 * 但本表的**同名库**纪律（TD-12：库名与开发库同名，切库切不走）意味着**不带前缀的 SQL
 * 在 a3 上仍会落到同名库**、行为一致 —— 那会让「带不带前缀」变成一处**看不见的**风格分叉。
 * ⇒ 本类选择**跟随既有全部 SQL**（带前缀），并把这次「39 → 40」的口径变化**登记在案**
 * （{@code docs/agent/db-migration.md} 与 {@code progress.md}），而不是让它悄悄发生。
 * ⚠️ 将来做「跨四域清除 schema 前缀」（方案 A）那一笔时，**本类也是清除对象之一**。
 *
 * <h2>【{@code BeforeJson} / {@code AfterJson} 读出时为什么要 {@code CAST(… AS CHAR)}】</h2>
 * 两列是 MySQL 的 {@code json} 类型。JDBC 读 {@code json} 列时，驱动可能把值交回
 * {@code byte[]}（而非 {@code String}）—— 那样 Jackson 会把它序列化成 **base64**，
 * 前端拿到的「改前/改后」就成了一串乱码。显式 {@code CAST(… AS CHAR)} 把类型钉成字符串，
 * **绕开驱动行为的不确定性**。⚠️ 别「优化」掉这两个 CAST。
 */
@Repository
public class AdminAuditDao {

    /** 共用列清单 —— 两处（列表 / 详情）必须**逐字一致**，否则同一行经两个端点读出来形状不同。 */
    private static final String COLUMNS =
            "Id, ActorId, ActorRole, Action, TargetType, TargetId, "
                    + "CAST(BeforeJson AS CHAR) AS BeforeJson, "
                    + "CAST(AfterJson AS CHAR) AS AfterJson, "
                    + "DATE_FORMAT(OccurredAt, '%Y-%m-%d %H:%i:%s') AS OccurredAt, "
                    + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS CreatedAt ";

    private final JdbcClient jdbcClient;

    public AdminAuditDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：唯一入口 ====================

    /**
     * 追加一行审计记录。**本表只有这一条写语句** —— append-only，没有 UPDATE / DELETE 的出口
     * （见建表块注释「为何 append-only」）。
     *
     * <p>⚠️ {@code beforeJson} / {@code afterJson} 是**已序列化好的 JSON 文本**（或 {@code null}）——
     * 序列化在 {@code AuditService} 里做，本类不引入 Jackson：DAO 只管「把这几列照原样写进去」。
     *
     * @return 受影响行数（正常恒为 1）
     */
    public int insert(String actorId, Integer actorRole, String action, String targetType,
                      String targetId, String beforeJson, String afterJson) {
        String sql = "INSERT INTO mall_management_sql.admin_audit "
                + "(ActorId, ActorRole, Action, TargetType, TargetId, BeforeJson, AfterJson) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(actorId, actorRole, action, targetType, targetId, beforeJson, afterJson)
                .update();
    }

    // ==================== 读：列表 / 详情 / 筛选面 ====================

    /**
     * 按可选筛选条件列审计行（时间倒序）。四个筛选参数**都可为 {@code null}**（= 不筛）。
     *
     * <p>⚠️ 参数**必须按 {@code sql} 的拼接顺序**逐个 {@code param()} 追加 —— 本方法用
     * 「拼一段、加一个参数」的写法，任何一处顺序错位都会造成**静默的参数错配**
     * （同 {@code UserDao.insertUserCustomer} 关于列序的那条警告）。改本方法时**成对改**。
     */
    public List<Map<String, Object>> selectAudits(String action, String targetType, String actorId,
                                                  String from, String to, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append("FROM mall_management_sql.admin_audit WHERE 1 = 1");

        JdbcClient.StatementSpec spec = null;
        // 先把 SQL 拼完再统一绑定参数 —— 顺序即下面 if 块的顺序。
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (notBlank(action)) {
            sql.append(" AND Action = ?");
            params.add(action);
        }
        if (notBlank(targetType)) {
            sql.append(" AND TargetType = ?");
            params.add(targetType);
        }
        if (notBlank(actorId)) {
            sql.append(" AND ActorId = ?");
            params.add(actorId);
        }
        if (notBlank(from)) {
            sql.append(" AND OccurredAt >= ?");
            params.add(from);
        }
        if (notBlank(to)) {
            sql.append(" AND OccurredAt <= ?");
            params.add(to);
        }
        sql.append(" ORDER BY OccurredAt DESC, Id DESC LIMIT ?");
        params.add(limit);

        spec = jdbcClient.sql(sql.toString());
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query().listOfRows();
    }

    /** 同一筛选条件下的总行数（不设 LIMIT）—— 列表页显示「共 N 条」。 */
    public long countAudits(String action, String targetType, String actorId, String from, String to) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM mall_management_sql.admin_audit WHERE 1 = 1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (notBlank(action)) {
            sql.append(" AND Action = ?");
            params.add(action);
        }
        if (notBlank(targetType)) {
            sql.append(" AND TargetType = ?");
            params.add(targetType);
        }
        if (notBlank(actorId)) {
            sql.append(" AND ActorId = ?");
            params.add(actorId);
        }
        if (notBlank(from)) {
            sql.append(" AND OccurredAt >= ?");
            params.add(from);
        }
        if (notBlank(to)) {
            sql.append(" AND OccurredAt <= ?");
            params.add(to);
        }
        JdbcClient.StatementSpec spec = jdbcClient.sql(sql.toString());
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Long.class).single();
    }

    /** 按主键取一行（详情页）。0 行时抛 {@code EmptyResultDataAccessException} —— 调用方 catch 后答 404 语义。 */
    public Map<String, Object> selectAuditById(long id) {
        String sql = "SELECT " + COLUMNS + "FROM mall_management_sql.admin_audit WHERE Id = ?";
        return jdbcClient.sql(sql).param(id).query().singleRow();
    }

    /** 筛选面：库里出现过的全部 {@code Action} 值（去重、升序）—— 供前端下拉。 */
    public List<String> selectDistinctActions() {
        String sql = "SELECT DISTINCT Action FROM mall_management_sql.admin_audit ORDER BY Action";
        return jdbcClient.sql(sql).query(String.class).list();
    }

    /** 筛选面：库里出现过的全部 {@code TargetType} 值（去重、升序）。 */
    public List<String> selectDistinctTargetTypes() {
        String sql = "SELECT DISTINCT TargetType FROM mall_management_sql.admin_audit ORDER BY TargetType";
        return jdbcClient.sql(sql).query(String.class).list();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
