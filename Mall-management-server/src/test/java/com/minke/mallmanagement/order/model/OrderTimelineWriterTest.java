package com.minke.mallmanagement.order.model;

import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.minke.mallmanagement.order.model.OrderAction.DELIVER;
import static com.minke.mallmanagement.order.model.OrderAction.PAY;
import static com.minke.mallmanagement.order.model.OrderState.COMPLETED;
import static com.minke.mallmanagement.order.model.OrderState.PAID;
import static com.minke.mallmanagement.order.model.OrderState.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link OrderTimelineWriter} 的实测。**没有 golden copy** —— 这是张新表，没有「改动前」可比对，
 * 故这里守的是另外三件事：
 *
 * <ol>
 *   <li><b>fail-soft 是真的</b>（{@link #failedWriteNeverThrowsAndIsCountedOnBothChannels}）——
 *       本类跑在异步线程上，抛出去只会被线程池吞掉，反而丢掉「记一笔」的机会。这是本类
 *       最值钱的性质：它决定了一条时间线丢失时**是否留得下证据**。</li>
 *   <li><b>参数顺序对</b>（{@link #writeForwardsAllFiveParametersInColumnOrder}）——
 *       五列五个 {@code ?}，错位是运行时才炸、且数据看起来「有值」的那类 bug。</li>
 *   <li><b>日志闭嘴 ≠ 观测闭嘴</b>（{@link #logSuppressionNeverSuppressesCounting}）——
 *       WARN 只在翻转时打一行（否则库一挂就刷屏），但**每一次失败都要照样计数**。
 *       这两件事很容易在「顺手加个 if 包住整段」时一起丢掉，故分开钉。</li>
 * </ol>
 *
 * <p>⚠️ 指标名用**字面量**：{@code MallOrderStateMetrics} 的名字常量是包私有的（既有约定），
 * 而本类在 {@code …service} 包。名字的唯一出处与有界性由 {@code MallOrderStateMetricsTest} 守。
 */
class OrderTimelineWriterTest {

    private static final String TIMELINE_WRITES = "mall.order.state.timeline.writes";
    private static final long OCCURRED_AT = 1_756_000_000_000L;   // 固定值，避免断言依赖时钟

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallOrderStateMetrics metrics = new MallOrderStateMetrics(registry);
    private final RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

    private final OrderTimelineWriter writer = new OrderTimelineWriter(jdbc, metrics);

    // ===================== 一、语句形状 =====================

    @Test
    @DisplayName("INSERT 的列与占位符一一对应（错位是「有值但值不对」的那类 bug，静态钉住）")
    void insertSqlPinsColumnsAndPlaceholders() {
        assertThat(OrderTimelineWriter.TABLE).isEqualTo("mall_management_sql.`order_state_transition`");
        assertThat(OrderTimelineWriter.INSERT_SQL).isEqualTo(
                "INSERT INTO mall_management_sql.`order_state_transition` "
                        + "(OrderId, Action, FromState, ToState, OccurredAt) VALUES (?, ?, ?, ?, ?)");
        assertThat(OrderTimelineWriter.INSERT_SQL.chars().filter(c -> c == '?').count())
                .as("占位符数必须等于声明的参数个数，否则运行时才炸")
                .isEqualTo(OrderTimelineWriter.PARAM_COUNT);
    }

    @Test
    @DisplayName("五个参数按列序原样传给 JDBC（ACTION 落的是 OrderAction.tag()，不是枚举名）")
    void writeForwardsAllFiveParametersInColumnOrder() {
        writer.write("ORDER-9", PAY, "CREATED", "PAID", OCCURRED_AT);

        assertThat(jdbc.calls).hasSize(1);
        Object[] args = jdbc.calls.get(0);
        assertThat(args).hasSize(OrderTimelineWriter.PARAM_COUNT);
        assertThat(args[0]).isEqualTo("ORDER-9");
        assertThat(args[1])
                .as("落 tag（pay）而不是枚举名（PAY）—— 与 MallOrderStateMetrics 的 tag 口径一致")
                .isEqualTo("pay");
        assertThat(args[2]).isEqualTo("CREATED");
        assertThat(args[3]).isEqualTo("PAID");
        assertThat(args[4])
                .as("落的是**库时钟（UTC）文本**，不是 Timestamp —— 后者是瞬时点，会被 driver 按 "
                        + "serverTimezone=Asia/Shanghai 折成墙钟，落库整整 +8 小时（2026-10-10 实测）")
                .isEqualTo("2025-08-24 01:46:40");   // = OCCURRED_AT 的 UTC 墙钟写法（date -u -d @1756000000）
    }

    @Test
    @DisplayName("动作 tag 逐一对上：deliver → SHIPPED → COMPLETED 这条也走同一条语句")
    void everyActionUsesTheSameStatement() {
        writer.write("ORDER-1", PAY, "CREATED", PAID.name(), OCCURRED_AT);
        writer.write("ORDER-2", DELIVER, PAID.name(), SHIPPED.name(), OCCURRED_AT);
        writer.write("ORDER-3", OrderAction.RECEIVE, SHIPPED.name(), COMPLETED.name(), OCCURRED_AT);

        assertThat(jdbc.sqls).containsOnly(OrderTimelineWriter.INSERT_SQL);
        assertThat(jdbc.calls).extracting(a -> a[1]).containsExactly("pay", "deliver", "receive");
        assertThat(count("result", "ok")).isEqualTo(3.0);
    }

    // ===================== 二、fail-soft（本类最值钱的性质）=====================

    @Test
    @DisplayName("落库失败：**永不抛**、计数 + 事件两条通道都记，且事件带上订单号")
    void failedWriteNeverThrowsAndIsCountedOnBothChannels() {
        jdbc.toThrow = new IllegalStateException("模拟库连不上");

        assertThatCode(() -> writer.write("ORDER-BOOM", PAY, "CREATED", "PAID", OCCURRED_AT))
                .as("抛出去只会被线程池吞掉，反而丢掉记一笔的机会 —— 必须自己吞")
                .doesNotThrowAnyException();

        assertThat(count("result", "error")).isEqualTo(1.0);
        List<OrderStateEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo("async");
        assertThat(events.get(0).result()).isEqualTo("error");
        assertThat(events.get(0).key())
                .as("「丢了哪一单」是失败事件存在的全部意义 —— 成功的落库反而不发事件")
                .isEqualTo("ORDER-BOOM");
    }

    @Test
    @DisplayName("成功落库：只计数、**不发事件**（事务表自带时刻，成功的顺序没有增量信息）")
    void successfulWriteCountsButEmitsNoEvent() {
        writer.write("ORDER-1", PAY, "CREATED", "PAID", OCCURRED_AT);

        assertThat(count("result", "ok")).isEqualTo(1.0);
        assertThat(metrics.recentEvents())
                .as("事件环容量有限（2048 槽），成功落库不占位")
                .isEmpty();
    }

    @Test
    @DisplayName("日志闭嘴 ≠ 观测闭嘴：WARN 只打一次，但**每一次**失败都照样计数")
    void logSuppressionNeverSuppressesCounting() {
        jdbc.toThrow = new IllegalStateException("模拟库连不上");

        for (int i = 0; i < 5; i++) {
            writer.write("ORDER-" + i, PAY, "CREATED", "PAID", OCCURRED_AT);
        }

        assertThat(count("result", "error"))
                .as("这是「顺手用 if 把整段包起来」时最容易一起丢掉的东西 —— 单独钉一条")
                .isEqualTo(5.0);
        assertThat(metrics.recentEvents().stream().filter(e -> "error".equals(e.result())))
                .as("五次失败五条事件，一次不漏（WARN 才是被抑制的那个）")
                .hasSize(5);
    }

    @Test
    @DisplayName("恢复后再失败：翻转标记被重置，两次失败都记（不会因为「已经报过」而永久闭嘴）")
    void failureFlagResetsOnSuccess() {
        jdbc.toThrow = new IllegalStateException("第一次故障");
        writer.write("ORDER-1", PAY, "CREATED", "PAID", OCCURRED_AT);

        jdbc.toThrow = null;
        writer.write("ORDER-2", PAY, "CREATED", "PAID", OCCURRED_AT);

        jdbc.toThrow = new IllegalStateException("第二次故障");
        writer.write("ORDER-3", PAY, "CREATED", "PAID", OCCURRED_AT);

        assertThat(count("result", "error")).as("两次故障都记了").isEqualTo(2.0);
        assertThat(count("result", "ok")).isEqualTo(1.0);
        assertThat(metrics.recentEvents()).extracting(OrderStateEvent::key)
                .containsExactly("ORDER-1", "ORDER-3");
    }

    @Test
    @DisplayName("越界状态名照写不误：观测路径不该因为伪造数据（TD-17）而落不了库")
    void unclassifiedStatesAreWrittenAsIs() {
        writer.write("ORDER-FORGED", PAY, OrderStateMachine.UNCLASSIFIED,
                OrderState.ILLEGAL_DELIVERED_RECEIVED_UNPAID.name(), OCCURRED_AT);

        assertThat(count("result", "ok")).isEqualTo(1.0);
        Object[] args = jdbc.calls.get(0);
        assertThat(args[2]).isEqualTo(OrderStateMachine.UNCLASSIFIED);
        assertThat(args[3]).isEqualTo("ILLEGAL_DELIVERED_RECEIVED_UNPAID");
    }

    @Test
    @DisplayName("最长状态名塞得进 varchar(40)（与建表语句同步，改枚举名会先在 OrderStateMachineTest 红）")
    void longestStateNameFitsTheColumn() {
        for (OrderState s : OrderState.values()) {
            writer.write("ORDER-" + s.name(), PAY, s.name(), s.name(), OCCURRED_AT);
        }
        assertThat(jdbc.calls).allSatisfy(args -> {
            assertThat(((String) args[2]).length())
                    .isLessThanOrEqualTo(OrderStateMachine.STATE_NAME_MAX_LEN);
            assertThat(((String) args[3]).length())
                    .isLessThanOrEqualTo(OrderStateMachine.STATE_NAME_MAX_LEN);
        });
    }

    // ===================== 工具 =====================

    private double count(String... tags) {
        var counter = registry.find(TIMELINE_WRITES).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 只记账不落库的 JdbcTemplate 替身。**这是本文件不需要数据库的全部原因** ——
     * 本类的失败路径（fail-soft）恰恰是生产里最难遇到、又最需要被证明的一条。
     */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        final List<Object[]> calls = new CopyOnWriteArrayList<>();
        final List<String> sqls = new CopyOnWriteArrayList<>();
        volatile RuntimeException toThrow;

        @Override
        public int update(String sql, Object... args) {
            sqls.add(sql);
            calls.add(args);
            RuntimeException e = toThrow;
            if (e != null) {
                throw e;
            }
            return 1;
        }
    }
}
