package com.minke.mallmanagement.observe.dao;

import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 死信（DLQ）的**落库副本**（D3-2）—— 写出口一个、只读出口一个。
 *
 * <h2>【它是什么，不是什么】</h2>
 * broker 的 {@code observed_event.dlq} 队列是**最终兜底**：消息真的被 NACK 后躺在那里，
 * 只有能登录 broker 的人看得见。本表是同一批死信的**同库副本**，让管理端页面能直接读
 * 「最近有哪些事件没进台账、卡在哪一步、报了什么错」。
 * <ul>
 *   <li><b>不是「已处理」标记</b>：本表有行 ≠ 原消息已经被重新入队；原消息仍在 broker 的 DLQ 里
 *       （见 {@code RabbitObserveConfig.DLQ}）。</li>
 *   <li><b>是 best-effort</b>：写入失败**只 WARN**，绝不影响消费者的 NACK ——
 *       调用方 {@code ObservedEventConsumer#reportDeadLetters} 用 try/catch 包住本类的调用。</li>
 *   <li><b>「库挂了 ⇒ 本表也写不进去」要如实承认</b>：落库失败最常见的原因就是数据库不可用，
 *       而那正是本表写不进去的时候。于是「指标帧里的 {@code dlq.persisted}」在这种场景下
 *       只能返回 {@code null}（页面显示 {@code —}），**不能显示 0** ——
 *       「读不到」与「一条都没有」是两件事。</li>
 * </ul>
 *
 * <h2>【列与 {@code observed_event} 的关系】</h2>
 * 前半段与台账逐列同宽同义（{@code Seq / Kind / … / FieldsJson / OccurredAt}），另加三列
 * {@code FailedStage / ErrorClass / ErrorText} 记「为什么死」与 {@code RawBody} 记
 * 「连 JSON 都解不开时那一串原始字节」。⚠️ 同 {@link ObservedEventDao#insertBatch}：
 * 有界标量列按列宽截断，{@code FieldsJson} **绝不截断**（截断=非法 JSON=写不进去）。
 *
 * <h2>【幂等】</h2>
 * {@code UNIQUE(TraceId, Seq)} + {@code ON DUPLICATE KEY UPDATE Id = Id}：同一条消息若被重复上报
 * （例如消费者在同一个失败批上被再次触发），不会多出一行。{@code TraceId}/{@code Seq} 为 NULL 的
 * 原始字节行不受唯一键约束（MySQL 的 UNIQUE 不把多个 NULL 视为相等）。
 */
@Repository
public class ObservedEventDlqDao {

    /** 与 ㉔ 的 DDL 逐列对齐。改 DDL 就要改这里，否则严格模式下超长会让**整批**死信写不进去。 */
    private static final int KIND_MAX = 16;
    private static final int SEVERITY_MAX = 16;
    private static final int TITLE_MAX = 255;
    private static final int OBJECT_TYPE_MAX = 32;
    private static final int OBJECT_ID_MAX = 128;
    private static final int TRACE_ID_MAX = 64;
    private static final int BATCH_ID_MAX = 36;
    private static final int ACTOR_ID_MAX = 50;
    private static final int STAGE_MAX = 128;
    private static final int ERROR_CLASS_MAX = 255;
    private static final int ERROR_TEXT_MAX = 1024;
    /** 原始字节最多存 64 KiB：它已经确定不是合法 JSON，再大也只会把一次「解释失败」变成一次「写入爆炸」。 */
    private static final int RAW_BODY_MAX = 64 * 1024;

    /**
     * 只为 {@code FieldsJson} 存在的序列化器（理由同 {@link ObservedEventDao}）：
     * 默认配置、取值面全是标量、不引第二个 Spring bean。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 每行 **17** 个占位符（顺序即 {@link #insertBatch} 里的 {@code params.add}）。 */
    private static final String ROW_PLACEHOLDERS =
            "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** 列表端点的列（发生时刻与落库时刻都转成无时区文本，前端不必猜 JDBC 的类型映射）。 */
    private static final String COLUMNS =
            "Id, Seq, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                    + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, "
                    + "FailedStage, ErrorClass, ErrorText, RawBody, "
                    + "CAST(OccurredAt AS CHAR) AS OccurredAt, CAST(created_at AS CHAR) AS CreatedAt ";

    private final JdbcClient jdbcClient;

    public ObservedEventDlqDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 把一批**解析成功的**死信事件写成行（best-effort 由调用方负责：本方法**不吞异常**）。
     *
     * @param events     失败的那批事件
     * @param stage      失败阶段的人话（「落库连续 3 次失败」）
     * @param errorClass 最后一次异常的类名（可空）
     * @param errorText  最后一次异常的根因 message（可空）
     * @return 受影响行数；空表 ⇒ 0（连 SQL 都不发）
     */
    public int insertBatch(List<ObservedEvent> events, String stage, String errorClass, String errorText) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        StringBuilder values = new StringBuilder(events.size() * ROW_PLACEHOLDERS.length());
        List<Object> params = new ArrayList<>(events.size() * 17);
        for (ObservedEvent e : events) {
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(ROW_PLACEHOLDERS);
            params.add(e.seq());
            params.add(clip(e.kind(), KIND_MAX));
            params.add(clip(e.severity(), SEVERITY_MAX));
            params.add(clip(e.title(), TITLE_MAX));
            params.add(clip(e.objectType(), OBJECT_TYPE_MAX));
            params.add(clip(e.objectId(), OBJECT_ID_MAX));
            params.add(clip(e.traceId(), TRACE_ID_MAX));
            params.add(clip(e.batchId(), BATCH_ID_MAX));
            params.add(clip(e.actorId(), ACTOR_ID_MAX));
            params.add(e.actorRole());
            params.add(e.durationUs());
            params.add(e.remaining());
            // ⚠️ 同 ObservedEventDao：这里绝不截断（截断=非法 JSON=整行被拒）。
            params.add(fieldsJson(e.fields()));
            params.add(new Timestamp(e.epochMilli()));
            params.add(clip(stage, STAGE_MAX));
            params.add(clip(errorClass, ERROR_CLASS_MAX));
            params.add(clip(errorText, ERROR_TEXT_MAX));
        }
        return jdbcClient.sql("INSERT INTO mall_management_sql.observed_event_dlq "
                        + "(Seq, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                        + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, OccurredAt, "
                        + "FailedStage, ErrorClass, ErrorText) VALUES "
                        + values
                        + " ON DUPLICATE KEY UPDATE Id = Id")
                .params(params)
                .update();
    }

    /**
     * 把一条**连 JSON 都解不开**的原始消息写成行。
     *
     * <p>它的 {@code Seq / TraceId} 全为 {@code NULL}（解析不出），唯一键对它不生效；
     * 这也意味着同一串字节被重复上报时会多行 —— 那是可以接受的：这种消息本来就无法身份化。
     *
     * @param rawBody    原始字节（按 {@value #RAW_BODY_MAX} 字节截断）
     * @param stage      失败阶段
     * @param errorClass 解析异常的类名
     * @param errorText  解析异常的根因 message
     * @return 受影响行数；{@code rawBody} 为空白 ⇒ 0
     */
    public int insertRaw(String rawBody, String stage, String errorClass, String errorText) {
        if (rawBody == null || rawBody.isBlank()) {
            return 0;
        }
        return jdbcClient.sql("INSERT INTO mall_management_sql.observed_event_dlq "
                        + "(RawBody, FailedStage, ErrorClass, ErrorText) VALUES (?, ?, ?, ?)")
                .params(clip(rawBody, RAW_BODY_MAX), clip(stage, STAGE_MAX),
                        clip(errorClass, ERROR_CLASS_MAX), clip(errorText, ERROR_TEXT_MAX))
                .update();
    }

    /** 表里现存多少条死信（指标帧的 {@code persisted}）。读不到由调用方转成 {@code null}。 */
    public long count() {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.observed_event_dlq")
                .query(Long.class).single();
    }

    /**
     * 最近的死信，按**落库时刻**倒序（同一毫秒内按 {@code Id} 倒序）。
     *
     * @param limit  最多几行（调用方已夹进 {@code [1, 100]}）
     * @param offset 跳过几行（≥ 0）
     */
    public List<Map<String, Object>> select(int limit, int offset) {
        return jdbcClient.sql("SELECT " + COLUMNS + "FROM mall_management_sql.observed_event_dlq "
                        + "ORDER BY created_at DESC, Id DESC LIMIT ? OFFSET ?")
                .params(limit, offset)
                .query().listOfRows();
    }

    /** 按列宽截断；{@code null} 原样回去。 */
    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** 与 {@link ObservedEventDao} 同一条口径：{@code null}/空表 ⇒ NULL；序列化失败**抛**（不静默落 NULL）。 */
    private static String fieldsJson(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new IllegalStateException("死信字段（fields）序列化失败：本列不静默落 NULL", e);
        }
    }
}
