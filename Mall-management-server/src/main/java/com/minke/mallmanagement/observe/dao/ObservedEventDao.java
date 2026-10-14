package com.minke.mallmanagement.observe.dao;

import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code observed_event} 的**写入出口**（块 7 · commit 8c）—— 全仓唯一往这张表 INSERT 的地方。
 *
 * <p>范式照 {@link ObservedEventCleanupDao}（同域、同批）：{@code @Repository} + 构造器收
 * {@code JdbcTemplate}（让 {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate}
 * 继续喂给本类）+ 语句以 Java 字符串内联；表名带 {@code mall_management_sql.} 前缀。
 * 建表在 {@code mall_management_sql.sql} 的 Migration ⑲-1（11 列，4 个非空之外的都允许 NULL）。
 *
 * <h2>【⚠️ 只落 8 列：{@code detail} 与 {@code key} 两个字段**没有列可落**】</h2>
 * 传进来的 {@link ObservedEvent} 有 11 个分量，本表只有 8 个能接
 * （{@code Kind / Severity / Title / ObjectType / ObjectId / TraceId / BatchId / OccurredAt}）。
 * 缺的两列是<b>刻意缺的</b>（见 ⑲-1 的列清单）：
 * {@code detail} 是给人读的一行过程串（含 {@code src=环名#seq}，只对<b>当下那一拍</b>有意义，
 * 事后读它反而会被误导）；{@code key} 的业务标识已经在 {@code ObjectId} 里。
 * ⇒ 别给本表加这两列然后在这里补两个 {@code ?} —— 那会让台账变成日志页的副本。
 *
 * <h2>【⚠️ 截断：列宽是本类的责任，不是调用方的】</h2>
 * {@code Title varchar(255)} / {@code ObjectId varchar(128)} / {@code ObjectType varchar(32)} …
 * 而事件里的值来自业务键与请求路径（**长度不受控**，{@code identity} 环的 objectId 就是请求路径）。
 * MySQL 严格模式下超长是**报错**，一条超长会把**整批**打回 DLQ。
 * 故本类在写之前统一按列宽截断 —— 与 {@code AttackEngine} 对 {@code drill_record.Name} 的做法同源。
 * ⚠️ 截断会**悄无声息地丢尾巴**，这是取舍：宁可台账里的标题短一截，不可整批事件进死信。
 *
 * <h2>【批写：一条多行 INSERT】</h2>
 * 消费者的批（{@link com.minke.mallmanagement.observe.mq.ObservedEventConsumer}）一次最多
 * {@code BATCH_SIZE} 条（50），拼成一条多行 INSERT —— 一次往返落一批。
 * ⚠️ 批很大时这条语句会很长，但 50 × 8 个占位符在 MySQL 的包上限与占位符上限面前都很小
 * （{@code max_allowed_packet} 默认 64MB、预处理语句占位符上限 65535）。
 */
@Repository
public class ObservedEventDao {

    /** 列宽上限：与 ⑲-1 的 DDL 逐列对齐。改 DDL 就要改这里（否则超长会在严格模式下报错）。 */
    private static final int KIND_MAX = 16;
    private static final int SEVERITY_MAX = 16;
    private static final int TITLE_MAX = 255;
    private static final int OBJECT_TYPE_MAX = 32;
    private static final int OBJECT_ID_MAX = 128;
    private static final int TRACE_ID_MAX = 64;
    private static final int BATCH_ID_MAX = 36;

    /** 每行的 8 个占位符（顺序即下面的 {@code addRow} —— 两处必须一起改）。 */
    private static final String ROW_PLACEHOLDERS = "(?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcClient jdbcClient;

    public ObservedEventDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 批量追加。返回**真写进去的行数**（{@code 0} 表示这一批是空的，不是失败）。
     *
     * <p>⚠️ <b>本方法<b>不吞异常</b></b>：写库失败必须让调用方（消费者）知道 —— 它要靠这个
     * 来决定「重试 / 转 DLQ」，而不是 ACK 掉一条没落库的证据。fail-soft 在**消费者**那一层
     * （见该类注释），不在本层：DAO 假装成功是 TD-20 那一族里最坏的一种。
     *
     * @param events 一批事件；空表直接返回 0（连 SQL 都不发）。顺序 = 写入顺序
     * @return 受影响行数
     */
    public int insertBatch(List<ObservedEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        StringBuilder values = new StringBuilder(events.size() * ROW_PLACEHOLDERS.length());
        List<Object> params = new ArrayList<>(events.size() * 8);
        for (ObservedEvent e : events) {
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(ROW_PLACEHOLDERS);
            params.add(clip(e.kind(), KIND_MAX));
            params.add(clip(e.severity(), SEVERITY_MAX));
            params.add(clip(e.title(), TITLE_MAX));
            params.add(clip(e.objectType(), OBJECT_TYPE_MAX));
            params.add(clip(e.objectId(), OBJECT_ID_MAX));
            params.add(clip(e.traceId(), TRACE_ID_MAX));
            params.add(clip(e.batchId(), BATCH_ID_MAX));
            // OccurredAt 是 datetime(3)：用 Timestamp 让它走 JDBC 的时间类型绑定，
            // 不用字符串（字符串会带上连接串的时区解释，是另一条更隐蔽的错路）。
            params.add(new Timestamp(e.epochMilli()));
        }
        return jdbcClient.sql("INSERT INTO mall_management_sql.observed_event "
                        + "(Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, OccurredAt) VALUES "
                        + values)
                .params(params)
                .update();
    }

    /** 按列宽截断；{@code null} 原样回去（这几列的 NULL 语义是「没有值」，不是空串）。 */
    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
