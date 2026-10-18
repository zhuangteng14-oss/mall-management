package com.minke.mallmanagement.observe.dao;

import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ObservedEventDlqDao} 的纯替身单测（D3-2 新增）。
 *
 * <h2>【它钉的仍然是「四处同改」】</h2>
 * 本 DAO 与 {@link ObservedEventDao} 同形：{@code ROW_PLACEHOLDERS} 的 {@code ?} 个数、
 * INSERT 列清单、{@code params.add} 顺序、以及读侧 {@code COLUMNS}。这些错**都不会被编译器
 * 或 MySQL 挡下**（例如 DurationUs 与 RemainingStock 都是整数，写反了只会安静地记错数）。
 * 桩法照抄 {@link ObservedEventDaoTest}（那篇类注释解释了 {@code queryForList} 与
 * {@code update(PreparedStatementCreator)} 两条重载，别改桩法）。
 *
 * <h2>【本文件验不到的】</h2>
 * 真 MySQL 收不收得下（列宽 / {@code json} 列 / UNIQUE 冲突）—— 那要真库；
 * 本文件只钉 Java 侧拼出来的语句形状与绑定顺序。
 */
class ObservedEventDlqDaoTest {

    private static final long OCCURRED_AT = 1_700_000_000_123L;
    private static final String STAGE = "落库连续 3 次失败";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObservedEventDlqDao dao = new ObservedEventDlqDao(jdbcTemplate);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    private final ParameterMetaData parameterMetaData = mock(ParameterMetaData.class);

    private final List<String> preparedSql = new ArrayList<>();
    private final List<List<String>> preparedBinds = new ArrayList<>();

    @BeforeEach
    void wireConnection() throws Exception {
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            preparedSql.add(sql);
            List<String> bound = new ArrayList<>();
            preparedBinds.add(bound);
            return mock(PreparedStatement.class, invocation -> {
                Object[] args = invocation.getArguments();
                String name = invocation.getMethod().getName();
                if ("setNull".equals(name) && args.length == 2 && args[0] instanceof Integer idx) {
                    bound.add(idx + "=null");
                } else if (name.startsWith("set") && args.length == 2 && args[0] instanceof Integer idx) {
                    bound.add(idx + "=" + args[1]);
                }
                if ("getConnection".equals(name)) {
                    return connection;
                }
                if ("getParameterMetaData".equals(name)) {
                    return parameterMetaData;
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
        });
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDriverName()).thenReturn("MySQL Connector/J");
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(parameterMetaData.getParameterType(anyInt())).thenReturn(Types.NULL);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(inv -> {
            ((PreparedStatementCreator) inv.getArgument(0)).createPreparedStatement(connection);
            return 1;
        });
    }

    @Test
    @DisplayName("17 个占位符 / 列清单 / params 顺序逐位对齐；错误三列排在事件列之后")
    void batchColumnsAndParamsAreAligned() {
        ObservedEvent e = new ObservedEvent(
                7L, OCCURRED_AT, "stock", "info", "库存扣减", "detail src=stock#7", "SKU-1",
                "sku", "SKU-1", "trace-1", "batch-1",
                "C-42", 2, 171L, 98, fields("qty", 2, "result", "ok"));

        assertThat(dao.insertBatch(List.of(e), STAGE,
                "java.sql.SQLException", "connection refused")).isEqualTo(1);

        assertThat(preparedSql).as("一批 = 一条多行 INSERT").hasSize(1);
        assertThat(preparedSql.get(0))
                .contains("(Seq, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                        + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, OccurredAt, "
                        + "FailedStage, ErrorClass, ErrorText)")
                .contains("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .as("唯一键冲突只 no-op，不吞其它错误（同 observed_event 的底线）")
                .contains("ON DUPLICATE KEY UPDATE Id = Id");

        assertThat(preparedBinds.get(0)).as("17 个绑定值，顺序 = 列清单")
                .containsExactly("1=7", "2=stock", "3=info", "4=库存扣减", "5=sku", "6=SKU-1",
                        "7=trace-1", "8=batch-1", "9=C-42", "10=2", "11=171", "12=98",
                        "13={\"qty\":2,\"result\":\"ok\"}", "14=" + new Timestamp(OCCURRED_AT),
                        "15=" + STAGE, "16=java.sql.SQLException", "17=connection refused");
    }

    @Test
    @DisplayName("空批：不发 SQL、返回 0（不是失败）；null 同样")
    void emptyBatchIsANoOp() {
        assertThat(dao.insertBatch(List.of(), STAGE, null, null)).isZero();
        assertThat(dao.insertBatch(null, STAGE, null, null)).isZero();
        assertThat(preparedSql).isEmpty();
    }

    @Test
    @DisplayName("非法 JSON 的原始字节：只写 RawBody + 三列错误信息")
    void rawBodyRowCarriesOnlyWhatIsKnown() {
        dao.insertRaw("{not json", "消息不是合法的事件 JSON",
                "com.fasterxml.jackson.core.JsonParseException", "Unexpected character '{'");

        assertThat(preparedSql).hasSize(1);
        assertThat(preparedSql.get(0))
                .contains("(RawBody, FailedStage, ErrorClass, ErrorText) VALUES (?, ?, ?, ?)");
        assertThat(preparedBinds.get(0)).containsExactly(
                "1={not json", "2=消息不是合法的事件 JSON",
                "3=com.fasterxml.jackson.core.JsonParseException", "4=Unexpected character '{'");
    }

    @Test
    @DisplayName("RawBody 截到 64 KiB：它已经确定不是 JSON，不该把一次「解释失败」变成「写入爆炸」")
    void rawBodyIsClipped() {
        String huge = "x".repeat(70_000);

        dao.insertRaw(huge, STAGE, "E", "msg");

        assertThat(preparedBinds.get(0).get(0)).as("前缀 `1=` + 64×1024 个字符")
                .hasSize(2 + 64 * 1024);
    }

    @Test
    @DisplayName("读侧：列表按落库时刻倒序，LIMIT/OFFSET 交给 SQL")
    void listSelectUsesCreatedAtDescendingAndPaging() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            preparedSql.add(inv.getArgument(0));
            return new ArrayList<Map<String, Object>>();
        });

        assertThat(dao.select(100, 20)).isEmpty();

        assertThat(preparedSql.get(0))
                .contains("ORDER BY created_at DESC, Id DESC LIMIT ? OFFSET ?")
                .as("发生时刻与落库时刻都转成无时区文本，前端不必猜 JDBC 类型")
                .contains("CAST(OccurredAt AS CHAR) AS OccurredAt")
                .contains("CAST(created_at AS CHAR) AS CreatedAt");
    }

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
