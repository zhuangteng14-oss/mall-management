package com.minke.mallmanagement.observe.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 可观测域的**内联 SQL 唯一出口**（块 7 · commit 4）—— 只碰一张表 {@code drill_record}
 * （建表在 {@code mall_management_sql.sql} 的 Migration ⑰-1，12 列）。
 *
 * <p>范式照 {@link com.minke.mallmanagement.admin.dao.AdminAuditDao}：{@code @Repository} +
 * 构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**（让 {@code @WebMvcTest} 切片里既有的
 * {@code @MockitoBean JdbcTemplate} 继续喂给本类）+ 语句以 Java 字符串内联。不用 MyBatis、无 mapper XML。
 *
 * <h2>【表名带 {@code mall_management_sql.} 前缀 —— 跟随块 7 这一批的新 SQL】</h2>
 * 本仓这条口径**本来就不统一**（块 7 commit 1 新增的两条走 {@code GoodsDao} / {@code OrderDao}
 * 的带前缀写法，而 {@code SeckillDao} 的同批不加前缀 —— 见 {@code GoodsDao.deductStock} 与
 * {@code SeckillDao.deductSoldStock}）。本类取**带前缀**：与同批、同域、刚刚写下的
 * {@code OrderDao.selectItemStocksByOrder} 逐字同款。⚠️ 这条不统一是**既有**的（TD-13），
 * 本笔不借机改别的文件 —— 只在自己的新文件里选一边，并写在这里让人下次不用猜。
 *
 * <h2>【{@code Result} 列为什么要 {@code CAST(… AS CHAR)}】</h2>
 * {@code Result} 是 {@code mediumtext}，本不需要 CAST；但读出来的 {@code Map} 会被 Jackson
 * 直接序列化给前端，**列类型万一被改成 {@code json}** 就会退化成 base64（同
 * {@code AdminAuditDao} 那两列踩过的坑）⇒ 这里显式钉成字符串，让读出的形状与列类型无关。
 * 代价是一次转换，收益是「将来改列类型不会静默改变响应体」。
 *
 * <h2>【本表是 append-only 的】</h2>
 * 只写不改不删（{@code Id} 自增、会话记录）⇒ 本类只有一条 INSERT 与三条读（列表 / 计数 / 单条）。
 * 清理属运维动作（{@code DELETE … WHERE created_at < ?}），**不给端点**。
 */
@Repository
public class DrillRecordDao {

    /** 共用列清单 —— 列表 / 详情**必须逐字一致**（理由同 {@code AdminAuditDao.COLUMNS}）。 */
    private static final String COLUMNS =
            "Id, OperatorId, OperatorRole, Name, Scenario, TotalRequests, BlockedRequests, "
                    + "FailedRequests, DurationMs, CAST(Result AS CHAR) AS Result, "
                    + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS CreatedAt ";

    private final JdbcClient jdbcClient;

    public DrillRecordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：唯一入口 ====================

    /**
     * 追加一行演练记录。**本表只有这一条写语句**。
     *
     * <p>⚠️ 调用方（{@code DrillRecordService}）负责把结构化结果**序列化成 JSON 文本**再传进来
     * —— 本类不引 Jackson（同 {@code AdminAuditDao.insert} 的分工）。
     *
     * @param scenario  场景配置 JSON（勾选 + 权重 + 总并发 + 持续秒数）
     * @param result    长文本结果 JSON（后端解析后分字段给前端，不是让前端展示原文）
     * @return **新行的 {@code Id}**（不是受影响行数）
     *
     * <p>⚠️ <b>为什么这里不走本仓惯用的 {@code SELECT LAST_INSERT_ID()}</b>
     * （{@code SensitiveWordDao.lastInsertId} 那一支）：那个写法**只在 {@code @Transactional}
     * 里可信**（两条语句可能落在不同连接上，于是读到别人的自增值），而本方法的调用场景是
     * 「一次演练跑完（数十秒）之后落一行」—— 为了取个 Id 把事务开在演练前后是不划算的。
     * 改用 JDBC 的**自增键回传**（{@link org.springframework.jdbc.support.GeneratedKeyHolder}）：
     * 一条语句同时拿到「插了几行」与「新行的 Id」，与事务无关。
     * <p>⚠️ {@code JdbcClient} 的 {@code StatementSpec} <b>没有</b> {@code update(Class<T>)} 这个
     * 重载（本仓版本实测如此）—— 别照 {@code query(Class)} 的形状想当然写。
     */
    public long insert(String operatorId, Integer operatorRole, String name, String scenario,
                       int totalRequests, int blockedRequests, int failedRequests, long durationMs,
                       String result) {
        String sql = "INSERT INTO mall_management_sql.drill_record "
                + "(OperatorId, OperatorRole, Name, Scenario, TotalRequests, BlockedRequests, "
                + "FailedRequests, DurationMs, Result) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcClient.sql(sql)
                .params(operatorId, operatorRole, name, scenario, totalRequests, blockedRequests,
                        failedRequests, durationMs, result)
                .update(keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    // ==================== 读：列表 / 详情 ====================

    /** 按时间倒序列演练记录（{@code Id} 兜底：同一秒内的多行顺序稳定、可复算）。 */
    public List<Map<String, Object>> selectRecords(int limit, int offset) {
        return jdbcClient.sql("SELECT " + COLUMNS + "FROM mall_management_sql.drill_record "
                        + "ORDER BY created_at DESC, Id DESC LIMIT ? OFFSET ?")
                .params(limit, offset).query().listOfRows();
    }

    /** 总行数（前端分页要用；不埋点：它是行数不是事件）。 */
    public long countRecords() {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.drill_record")
                .query(Long.class).single();
    }

    /** 单条演练记录（详情用；{@code Result} 已 CAST 成字符串）。没这一行 ⇒ {@code null}。 */
    public Map<String, Object> selectRecordById(long id) {
        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT " + COLUMNS + "FROM mall_management_sql.drill_record WHERE Id = ?")
                .param(id).query().listOfRows();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
