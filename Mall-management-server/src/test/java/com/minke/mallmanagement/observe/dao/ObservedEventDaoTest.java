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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ObservedEventDao} 的**纯替身单测**（块 7 · 阶段 3a.3 第二步新增）。
 *
 * <h2>【它为什么存在：本类是「四处同改」的现场，而此前一个用例都没有】</h2>
 * 本类写一行要同时改四处 —— {@code ROW_PLACEHOLDERS} 的 {@code ?} 个数、INSERT 的**列清单**、
 * {@code params.add} 的**顺序**、以及 {@code COLUMNS}（回放读侧）。这四处里有两类失效
 * **不会被编译器和 MySQL 挡下**：
 * <ul>
 *   <li><b>顺序写反、个数却对</b> —— {@code DurationUs bigint} 与 {@code RemainingStock int}
 *       都是整数列 ⇒ 把「耗时 171µs」记成「剩余 171 件」**完全合法**，两条语句都跑得过，
 *       只有人在页面上觉得数字怪。本步两列**同时**加，正是这条错最容易被造出来的时刻。</li>
 *   <li><b>读侧漏改</b> —— {@code COLUMNS} 少一列，回放照常返回、只是永远缺那个键。</li>
 * </ul>
 * 本文件把那四处的形状钉住。⚠️ <b>方法同 {@code ObservedEventCleanupDaoTest}</b>（那里是同类
 * DAO 的第一份「纯替身」单测，桩法说明写得更细 —— 两条 jdbc client 重载事实、以及
 * 「桩错重载 ⇒ 静默全绿」的教训都在那篇类注释里，本文不重抄）。
 *
 * <h2>【⚠️ jdbc client 重载事实 · 第三条（本文件实测补上的）】</h2>
 * 隔壁那篇记了两条（写 = {@code update(PreparedStatementCreator)}；读 {@code .query(T.class).list()}
 * = {@code query(String, RowMapper, Object...)}）。本类**读侧走的是另一条路** ——
 * {@code selectByBatch} 用的是 {@code .query().listOfRows()}（不指定映射类型，要的就是原始行），
 * 6.2.6 源码 {@code DefaultJdbcClient#IndexedParamResultQuerySpec#listOfRows} 落到
 * {@code JdbcTemplate.queryForList(String, Object...)}。
 * <p>把隔壁那份桩照抄过来 ⇒ {@code queryForList} 无人应答 ⇒ 返回空表、SQL 也记不下来，
 * 用例红在 {@code IndexOutOfBoundsException: Index 0 out of bounds for length 0} 上
 * —— <b>看起来像断言写错，实际是桩错了重载</b>。这是同一类坑的第三次现身，故单独记一行。
 *
 * <h2>【⚠️ 本文件验不到的】</h2>
 * ① MySQL 收不收得下（列类型 / COMMENT 长度 / {@code json} 列会不会拒绝）—— 那要真库，
 * 由本笔落 VM 库的只读复核证据兜；② 消费者那条「失败 ⇒ 重试 ⇒ DLQ」的路径。
 */
class ObservedEventDaoTest {

    private static final long OCCURRED_AT = 1_700_000_000_123L;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObservedEventDao dao = new ObservedEventDao(jdbcTemplate);
    private final Connection connection = mock(Connection.class);

    /**
     * 绑 {@code null} 时 Spring 要**逐层问驱动**（不给就 NPE 在 {@code StatementCreatorUtils} 里）——
     * 详见 {@link #wireConnection()} 的注释。这两个替身只有绑 null 的路径会用到。
     */
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    private final ParameterMetaData parameterMetaData = mock(ParameterMetaData.class);

    /** 每次下发的语句的 SQL 原文，按调用顺序（与 {@link #preparedBinds} 对齐）。 */
    private final List<String> preparedSql = new ArrayList<>();

    /** 每条语句绑定的值（形如 {@code "1=stock"}，按占位符序）。 */
    private final List<List<String>> preparedBinds = new ArrayList<>();

    /**
     * 让每次 {@code prepareStatement} 都返回一个**全新的**替身语句，并把它收到的 {@code setXxx}
     * 记进 {@link #preparedBinds}。
     *
     * <p>⚠️ 用自定义 {@code Answer} 而不是逐个 {@code when(ps).setString(...)}：本类绑定的值里
     * 有 {@code String} / {@code Integer} / {@code Long} / {@code Timestamp} / {@code null}
     * 五类，Spring 对不同类型的 {@code setXxx} 走的 setter 不同 ⇒ 写死某一个 setter
     * 会让另一类**静默不记录**（那正是本仓最怕的那种坏测试）。
     */
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
                // ⚠️ 绑 null 时 Spring 走的是 `setNull(idx, sqlType)`（第二实参是 sqlType 那个 int），
                //    照抄「idx=args[1]」会把它记成「9=0」—— 一条**看起来像真值**的假绑定。
                if ("setNull".equals(name) && args.length == 2 && args[0] instanceof Integer idx) {
                    bound.add(idx + "=null");
                } else if (name.startsWith("set") && args.length == 2 && args[0] instanceof Integer idx) {
                    bound.add(idx + "=" + args[1]);
                }
                // ⚠️ 绑 null 时 Spring 会**逐层问驱动**（6.2.6 `StatementCreatorUtils#setNull`，四条
                //    早退路径全都先问驱动名）：`ps.getConnection().getMetaData().getDriverName()`
                //    → 按驱动决定是否再问 `ps.getParameterMetaData().getParameterType(idx)`。
                //    替身对这些一律返 null ⇒ NPE 在 Spring 内部、栈顶指向 StatementCreatorUtils，
                //    看起来与本类毫无关系（本文件第一版就是死在这里的）。
                //    这里按**本仓实际那条支路**喂：驱动名既非 PostgreSQL 也非 Microsoft
                //    ⇒ 去问参数元数据 ⇒ 拿到 Types.NULL ⇒ 最终落到 `ps.setNull(idx, Types.NULL)`。
                //    ⚠️ 本桩只保证「走的是哪一路、最终调用了哪个 setter」，**不模拟真实列类型**
                //       （真 MySQL 上 getParameterType 会给 VARCHAR 之类；对本文件记录到的绑定值
                //       没有区别 —— 那一支记下来的都是 `idx=null`）。
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

    // ==================== ① 四处同改：列清单 / 占位符 / params 顺序 ====================

    @Test
    @DisplayName("13 个占位符、列清单、params 顺序**逐位对齐**（durationUs 与 remaining 都是整数，写反了不报错）")
    void columnListAndParamsAreAligned() {
        // ⚠️ 两个数刻意取不同的值：写反了在 SQL 层完全合法（都是整数列），只有这条断言会红。
        ObservedEvent e = new ObservedEvent(
                7L, OCCURRED_AT, "stock", "info", "库存扣减", "kind=deduct sku=SKU-1 src=stock#7", "SKU-1",
                "sku", "SKU-1", "trace-1", "batch-1",
                "C-42", 2, 171L, 98, fields("qty", 2, "result", "ok"));

        assertThat(dao.insertBatch(List.of(e))).as("返回真写进去的行数（桩给 1）").isEqualTo(1);

        assertThat(preparedSql).as("一批 = 一条多行 INSERT（一次往返）").hasSize(1);
        assertThat(preparedSql.get(0))
                .as("列清单：新两列接在 DurationUs 之后、OccurredAt 之前（与 Migration ㉒ 的 AFTER 链一致）")
                .contains("(Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                        + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, OccurredAt)")
                .as("一行的 13 个占位符（少一个/多一个由 MySQL 报错，但那时已经跑在运行期了）")
                .contains("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

        assertThat(preparedBinds.get(0))
                .as("13 个绑定值，顺序 = 上面那份列清单（第 10/11 位就是本步最容易写反的那一对）")
                .containsExactly("1=stock", "2=info", "3=库存扣减", "4=sku", "5=SKU-1", "6=trace-1",
                        "7=batch-1", "8=C-42", "9=2", "10=171", "11=98",
                        "12={\"qty\":2,\"result\":\"ok\"}", "13=" + new Timestamp(OCCURRED_AT));
    }

    @Test
    @DisplayName("整批拼一条多行 INSERT：两行 ⇒ 两条占位符组，绑定值按行顺序追平")
    void batchIsOneMultiRowInsert() {
        dao.insertBatch(List.of(event(1L, 10), event(2L, 20)));

        assertThat(preparedSql).hasSize(1);
        assertThat(preparedSql.get(0)).as("一个 VALUES 里两组占位符")
                .contains("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?), "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        // ⚠️ 记下来的标签是 **JDBC 的 1 基参数号**，且在整条语句里**连号**（不是每行从 1 重来）——
        //    第 2 行的第 11 个参数是整条语句的第 24 个（14 + 10）。第一版按「行内序号」写，
        //    断言红在 expected "11=20" but was "24=20"：数没错，是我把坐标系搞混了。
        assertThat(preparedBinds.get(0)).as("两行 × 13 个绑定值").hasSize(26);
        assertThat(preparedBinds.get(0).get(10)).as("第 1 行的 RemainingStock（参数号 = 下标 + 1）")
                .isEqualTo("11=10");
        assertThat(preparedBinds.get(0).get(23)).as("第 2 行的 RemainingStock：下标 23 ⇒ 参数号 24（13 + 11）")
                .isEqualTo("24=20");
    }

    @Test
    @DisplayName("空批：连 SQL 都不发，返回 0（不是失败）")
    void emptyBatchIsANoOp() {
        assertThat(dao.insertBatch(List.of())).isZero();
        assertThat(dao.insertBatch(null)).isZero();
        assertThat(preparedSql).as("一次往返都不花").isEmpty();
    }

    // ==================== ② FieldsJson ====================

    @Test
    @DisplayName("FieldsJson：有明细 ⇒ JSON 文本；没有（null / 空表）⇒ NULL，不是 {} 也不是空串")
    void fieldsJsonIsNullWhenThereIsNothingToStore() {
        dao.insertBatch(List.of(
                event(1L, 10),
                withFields(null),
                withFields(new LinkedHashMap<>())));

        // 三行 39 个绑定值；FieldsJson 在每行里是第 12 个 ⇒ 参数号 12 / 25 / 38（连号，见上一条用例）。
        List<String> bound = preparedBinds.get(0);
        assertThat(bound).as("三行 × 13").hasSize(39);
        assertThat(bound.get(11)).as("第 1 行有明细").isEqualTo("12={\"qty\":2,\"result\":\"ok\"}");
        assertThat(bound.get(24)).as("第 2 行 fields=null ⇒ 落 NULL（「没有明细」是事实，不是错误）")
                .isEqualTo("25=null");
        assertThat(bound.get(37)).as("第 3 行空表同理 —— 不写成 {}（那会被读成「有明细，只是空的」）")
                .isEqualTo("38=null");
    }

    // ==================== ③ 截断：本类的责任 ====================

    @Test
    @DisplayName("列宽截断：超长的标题/业务号截断；FieldsJson **不**截断（截断=非法 JSON=整批进死信）")
    void longValuesAreClippedExceptFieldsJson() {
        String longTitle = "T".repeat(300);
        String longActor = "A".repeat(80);
        String longExtra = "E".repeat(600);
        ObservedEvent e = new ObservedEvent(
                1L, OCCURRED_AT, "cache", "info", longTitle, "d", "k",
                "cache", "k", null, null,
                longActor, null, null, null, fields("extra", longExtra));

        dao.insertBatch(List.of(e));

        List<String> bound = preparedBinds.get(0);
        assertThat(bound.get(2)).as("Title varchar(255) ⇒ 截到 255").isEqualTo("3=" + "T".repeat(255));
        assertThat(bound.get(7)).as("ActorId varchar(50) ⇒ 截到 50").isEqualTo("8=" + "A".repeat(50));
        assertThat(bound.get(11)).as("FieldsJson 原样整段（截断会造出非法 JSON，json 列会整条拒绝）")
                .isEqualTo("12={\"extra\":\"" + longExtra + "\"}");
    }

    // ==================== ④ 读侧：回放的列清单 ====================

    @Test
    @DisplayName("回放读侧：COLUMNS 把新两列排在 ActorRole/DurationUs 之后、OccurredAt 之前")
    void replaySelectCarriesTheNewColumns() {
        // ⚠️ 桩的是 queryForList（理由见类注释第三条重载事实）—— 桩错重载的话这条用例会红在
        //    IndexOutOfBounds 上，看不出是桩的问题。
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
            preparedSql.add(inv.getArgument(0));
            return new ArrayList<Map<String, Object>>();
        });

        assertThat(dao.selectByBatch("b-1", 50)).isEmpty();

        assertThat(preparedSql.get(0))
                .as("新两列跟着回放一起出来（前端按需读；漏了它们，回放页就永远看不到剩余与明细）")
                .contains("Id, Kind, Severity, Title, ObjectType, ObjectId, TraceId, BatchId, "
                        + "ActorId, ActorRole, DurationUs, RemainingStock, FieldsJson, ")
                .as("排序判据一个字没动（同一毫秒内的先后只有 Id 能定）")
                .contains("ORDER BY OccurredAt ASC, Id ASC");
    }

    @Test
    @DisplayName("fields 序列化不了 ⇒ **抛**（不静默落 NULL）—— 代价是整批进 DLQ，理由见 fieldsJson 的 javadoc")
    void unserializableFieldsThrowInsteadOfWritingNull() {
        // 一个 Jackson 序列化不出来的值（裸 Object：默认 FAIL_ON_EMPTY_BEANS 下必然抛）。
        // 本类取值面上不该发生（各 fromXxx 放的都是标量），但真发生了必须**看得见** ——
        // 不能被降级成一个 NULL，那会让「回放少了一段当时的数据流程」重新变成静默损害。
        assertThatThrownBy(() -> dao.insertBatch(List.of(withFields(fields("x", new Object())))))
                .as("抛出来 ⇒ 消费者重试 ⇒ 整批转 DLQ（可见、可查），而不是悄悄写个 NULL")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fields");
    }

    // ==================== 造数据与桩 ====================

    /** 一条形状正常的事件（fields 固定、remaining 由调用方给）。 */
    private static ObservedEvent event(long seq, Integer remaining) {
        return new ObservedEvent(
                seq, OCCURRED_AT, "stock", "info", "库存扣减", "detail src=stock#0", "SKU-1",
                "sku", "SKU-1", "trace-1", "batch-1",
                "C-42", 2, 171L, remaining, fields("qty", 2, "result", "ok"));
    }

    /** 同上，但换掉 fields（其余字段与 {@link #event} 逐字相同，便于按位断言）。 */
    private static ObservedEvent withFields(Map<String, Object> fields) {
        return new ObservedEvent(
                1L, OCCURRED_AT, "stock", "info", "库存扣减", "detail src=stock#0", "SKU-1",
                "sku", "SKU-1", "trace-1", "batch-1",
                "C-42", 2, 171L, 10, fields);
    }

    /** {@code LinkedHashMap} —— 键序 = 插入序，断言 JSON 文本时才有确定的串可比。 */
    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
