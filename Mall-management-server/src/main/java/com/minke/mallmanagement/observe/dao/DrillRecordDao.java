package com.minke.mallmanagement.observe.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可观测域的**内联 SQL 唯一出口**（块 7 · commit 4）—— 只碰一张表 {@code drill_record}
 * （建表在 {@code mall_management_sql.sql} 的 Migration ⑰-1，12 列；commit 8c 的 ⑲-2 再加
 * {@code BatchId} ⇒ 现 **13 列**）。
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

    /**
     * 共用列清单 —— 列表 / 详情**必须逐字一致**（理由同 {@code AdminAuditDao.COLUMNS}）。
     *
     * <p>⚠️ {@code BatchId} 是**块 7 收尾**补进来的（用户 2026-10-15 裁「甲」）：它是
     * <b>演练回放的入口键</b> —— 回放端点（{@code POST /admin/observe/replay}）按批次读
     * {@code observed_event}，而在此之前这个值**只在库里、从来没往响应里露过**
     * （commit 8c 把它写进了本表与 {@code observed_event} 两处，却没有任何一条端点把它读出来）
     * ⇒ 界面上那个〔回放〕按钮拿不到 `batchId`，功能等于不可达。加在 COLUMNS 里而不是给
     * {@code AttackController} 新开一条查询：**它的入口就是「记录区那一行」**，字段跟着那一行走。
     *
     * <p>⚠️ 顺序：追加在**最后**（{@code CreatedAt} 之后）—— 新字段加在尾部，别插在中间：
     * 前端表格列顺序跟着这个键序走（{@code DrillRecordServiceTest} 钉住了它）。
     * ⚠️ 而 {@code Result} **总是**排在最末：{@code DrillRecordService.split()} 会把它从原位置摘下来、
     * 最后再放回去（见那个方法）⇒ 响应里每行的键序是
     * {@code […, DurationMs, CreatedAt, BatchId, EventCount, HasData, Result]}。
     * <p>⚠️ {@code EventCount}（2026-10-18）同样追加在尾部：它是**派生列**，不该插在业务列中间；
     * {@code HasData} 由服务层紧随其后生成。
     *
     * <p>⚠️ <b>2026-10-19 性能修复：{@code EventCount} 不再是一条相关子查询</b>。
     * 旧写法对列表里的**每一行**各跑一次 {@code COUNT(*)}，500 并发压测时这条语句实测
     * 10,127ms（{@code /attack/records} 10 秒超时的直接原因）。现在列表先取本页行，
     * 再对**本页出现的批次号**一次性 {@code WHERE BatchId IN (…) GROUP BY BatchId}
     * （走 {@code idx_observed_event_batch}）。见 {@link #attachEventCounts(List)}。
     */
    private static final String COLUMNS =
            "r.Id, r.OperatorId, r.OperatorRole, r.Name, r.Scenario, r.TotalRequests, r.BlockedRequests, "
                    + "r.FailedRequests, r.DurationMs, CAST(r.Result AS CHAR) AS Result, "
                    + "DATE_FORMAT(r.created_at, '%Y-%m-%d %H:%i:%s') AS CreatedAt, r.BatchId ";

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
     * @param batchId   这一次演练的**批次 UUID**（块 7 · commit 8c 新增；列由 Migration ⑲-2 加）。
     *                  它是 {@code observed_event.BatchId} 的**同一串值**（引擎在一轮开始时生成、
     *                  同时写进两边）⇒ 「这一轮演练污染出来的事件」与「这一轮演练这一行记录」
     *                  靠它对得上。⚠️ 存量行是 {@code NULL}（不是空串）：空串会被清理谓词读成
     *                  「属于一个叫空的批次」。
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
                       String result, String batchId) {
        String sql = "INSERT INTO mall_management_sql.drill_record "
                + "(OperatorId, OperatorRole, Name, Scenario, TotalRequests, BlockedRequests, "
                + "FailedRequests, DurationMs, Result, BatchId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcClient.sql(sql)
                .params(operatorId, operatorRole, name, scenario, totalRequests, blockedRequests,
                        failedRequests, durationMs, result, batchId)
                .update(keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    // ==================== 读：列表 / 详情 ====================

    /** 按时间倒序列演练记录（{@code Id} 兜底：同一秒内的多行顺序稳定、可复算）。 */
    public List<Map<String, Object>> selectRecords(int limit, int offset) {
        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT " + COLUMNS + "FROM mall_management_sql.drill_record r "
                                + "ORDER BY r.created_at DESC, r.Id DESC LIMIT ? OFFSET ?")
                .params(limit, offset).query().listOfRows();
        attachEventCounts(rows);
        return rows;
    }

    /** 总行数（前端分页要用；不埋点：它是行数不是事件）。 */
    public long countRecords() {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.drill_record")
                .query(Long.class).single();
    }

    /** 单条演练记录（详情用；{@code Result} 已 CAST 成字符串）。没这一行 ⇒ {@code null}。 */
    public Map<String, Object> selectRecordById(long id) {
        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT " + COLUMNS + "FROM mall_management_sql.drill_record r WHERE r.Id = ?")
                .param(id).query().listOfRows();
        attachEventCounts(rows);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 给本页记录补 {@code EventCount}：**一次** {@code GROUP BY} 查完整页的批次，而不是每行一次
     * 相关子查询（2026-10-19，见 {@link #COLUMNS} 的说明）。
     *
     * <p>⚠️ 键序：{@code EventCount} 是 {@code put} 追加的，落在 {@code BatchId} 之后
     * —— 与旧 {@code COLUMNS} 的位置一致（{@code DrillRecordServiceTest} 钉住了这个顺序）。
     *
     * <p>⚠️ 没有 {@code BatchId} 的行（存量记录）与查不到计数的批次都补 {@code 0L}，
     * 与旧子查询的 {@code COUNT(*) = 0} 同义：服务层据此给出 {@code HasData=false}。
     */
    private void attachEventCounts(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> batchIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object batchId = row.get("BatchId");
            if (batchId != null && !String.valueOf(batchId).isBlank()) {
                batchIds.add(String.valueOf(batchId));
            }
        }
        Map<String, Long> counts = new HashMap<>();
        if (!batchIds.isEmpty()) {
            String placeholders = String.join(", ", Collections.nCopies(batchIds.size(), "?"));
            List<Map<String, Object>> counted = jdbcClient.sql(
                            "SELECT BatchId, COUNT(*) AS EventCount "
                                    + "FROM mall_management_sql.observed_event "
                                    + "WHERE BatchId IN (" + placeholders + ") "
                                    + "GROUP BY BatchId")
                    .params(batchIds.toArray()).query().listOfRows();
            for (Map<String, Object> c : counted) {
                Object value = c.get("EventCount");
                counts.put(String.valueOf(c.get("BatchId")),
                        value instanceof Number n ? n.longValue() : 0L);
            }
        }
        for (Map<String, Object> row : rows) {
            Object batchId = row.get("BatchId");
            row.put("EventCount", batchId == null
                    ? 0L : counts.getOrDefault(String.valueOf(batchId), 0L));
        }
    }
}
