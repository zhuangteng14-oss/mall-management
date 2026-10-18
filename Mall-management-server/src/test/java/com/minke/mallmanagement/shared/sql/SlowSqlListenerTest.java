package com.minke.mallmanagement.shared.sql;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SlowSqlListener} 的**纯单测**：直接 new，把 {@link ExecutionInfo} 当数据喂进去，
 * 用 logback 的 {@link ListAppender} 把日志截下来断言。
 *
 * <p>⚠️ 为什么不用 {@code @SpringBootTest} 真连一次库：那要一条跑得慢的真 SQL 才能造出阈值以上
 * 的耗时，而「跑得慢」取决于机器 —— 一条在 CI 上 300ms 的语句在本机可能 20ms。把耗时当**入参**
 * 喂进去（{@code ExecutionInfo.setElapsedTime}），判据就是确定的、与机器无关的。
 */
class SlowSqlListenerTest {

    private static final long THRESHOLD = 200L;

    private final Logger logger = (Logger) LoggerFactory.getLogger(SlowSqlListener.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static ExecutionInfo exec(long elapsedMs) {
        ExecutionInfo info = new ExecutionInfo();
        info.setElapsedTime(elapsedMs);
        info.setDataSourceName("mall");
        return info;
    }

    private static List<QueryInfo> sql(String... statements) {
        List<QueryInfo> out = new ArrayList<>();
        for (String s : statements) {
            out.add(new QueryInfo(s));
        }
        return out;
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private String onlyMessage() {
        assertThat(events()).hasSize(1);
        return events().get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("低于阈值 1 ms（199）→ 一行都不打")
    void silentBelowThreshold() {
        new SlowSqlListener(THRESHOLD).afterQuery(exec(199L), sql("select 1"));

        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("恰好等于阈值（200）→ 打一条 WARN（判据是 ≥，不是 >）")
    void firesExactlyAtThreshold() {
        new SlowSqlListener(THRESHOLD).afterQuery(exec(THRESHOLD), sql("select 1"));

        assertThat(events()).hasSize(1);
        assertThat(events().get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("超阈值（450）→ 一条 WARN，行内有耗时、阈值、数据源与 ASCII 检索片段 SLOW-SQL")
    void messageCarriesTheEssentials() {
        new SlowSqlListener(THRESHOLD).afterQuery(exec(450L), sql("select count(*) from mall_management_sql.`order`"));

        String msg = onlyMessage();
        assertThat(msg).contains("SLOW-SQL");
        assertThat(msg).contains("450 ms");
        assertThat(msg).contains("阈值 200 ms");
        assertThat(msg).contains("mall");
        assertThat(msg).contains("select count(*)");
    }

    @Test
    @DisplayName("多行 SQL 被压成单空格 —— 否则一行 WARN 会「断成好几行」，按行检索的人会以为那是好几条记录")
    void collapsesWhitespace() {
        String messy = "select\n  a,\r\n\tb\nfrom t\n where id = 1";

        String flat = SlowSqlListener.summarize(sql(messy));

        assertThat(flat).isEqualTo("select a, b from t where id = 1");
        assertThat(flat).doesNotContain("\n");
    }

    @Test
    @DisplayName("超长 SQL 截断到 500 字符 + 「…(截断)」—— 不让一条批量 IN 把终端刷满")
    void truncatesLongSql() {
        String huge = "select * from t where id in (" + "1,".repeat(1000) + "0)";

        String out = SlowSqlListener.summarize(sql(huge));

        assertThat(out).endsWith("…(截断)");
        assertThat(out).hasSize(500 + "…(截断)".length());
    }

    @Test
    @DisplayName("空语句列表 → 打「(无语句)」而不是抛 NPE")
    void handlesEmptyQueryList() {
        assertThat(SlowSqlListener.summarize(List.of())).isEqualTo("(无语句)");
        assertThat(SlowSqlListener.summarize(null)).isEqualTo("(无语句)");

        new SlowSqlListener(THRESHOLD).afterQuery(exec(999L), List.of());
        assertThat(onlyMessage()).contains("(无语句)");
    }

    @Test
    @DisplayName("参数值**不上屏** —— 参数里过手机号/地址，落盘就是一份不该存在的副本（断言含值反例）")
    void neverLogsParameterValues() throws Exception {
        Method setString = PreparedStatement.class.getMethod("setString", int.class, String.class);
        QueryInfo qi = new QueryInfo("update mall_management_sql.`user` set phone = ? where id = ?");
        List<ParameterSetOperation> ops = new ArrayList<>();
        ops.add(new ParameterSetOperation(setString, new Object[]{1, "13800138000"}));
        ops.add(new ParameterSetOperation(setString, new Object[]{2, "13900139000"}));
        qi.setParametersList(List.of(ops));

        new SlowSqlListener(THRESHOLD).afterQuery(exec(700L), List.of(qi));

        String msg = onlyMessage();
        assertThat(msg).contains("set phone = ?");          // 模板在
        assertThat(msg).doesNotContain("13800138000");      // 值不在
        assertThat(msg).doesNotContain("13900139000");
    }

    // ==================== D3：dbSlow 注入 ====================

    @Test
    @DisplayName("dbSlow：只延迟 SELECT / WITH；写路径不睡（一条 30ms 也量得出来）")
    void dbSlowSleepsForSelectOnly() {
        SlowSqlListener listener = new SlowSqlListener(THRESHOLD, () -> true, 40, 300);

        long updateStart = System.nanoTime();
        listener.beforeQuery(null, sql("update mall_management_sql.`user` set phone = ? where id = ?"));
        long updateMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - updateStart);

        long selectStart = System.nanoTime();
        listener.beforeQuery(null, sql("  SeLeCt 1"));
        long selectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - selectStart);

        assertThat(updateMs).as("写路径不延迟（不是没测到：下面那条真的睡了）").isLessThan(30);
        assertThat(selectMs).as("SELECT 真的延迟了").isGreaterThanOrEqualTo(30);
        assertThat(SlowSqlListener.containsSelect(sql("with t as (select 1) select * from t"))).isTrue();
        assertThat(SlowSqlListener.containsSelect(sql("insert into t values (1)"))).isFalse();
    }

    @Test
    @DisplayName("dbSlow：预算硬上限 300ms —— 连续 SELECT 不会被叠加成 N×delay")
    void dbSlowStopsAtTheBudget() {
        SlowSqlListener listener = new SlowSqlListener(THRESHOLD, () -> true, 50, 300);
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            listener.beforeQuery(null, sql("select " + i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("10 × 50ms 若不受预算约束会是 500ms").isLessThan(500);
        assertThat(elapsedMs).as("预算不是 0（前几拍确实延迟了）").isGreaterThanOrEqualTo(250);
    }

    // ==================== A5：设施事件出口 ====================

    @Test
    @DisplayName("A5：超阈值 ⇒ 出口收到 injected=false；dbSlow 注入过 ⇒ 未到阈值也发 injected=true")
    void emitsFacilityEvents() {
        List<String> calls = new ArrayList<>();
        SlowSqlListener listener = new SlowSqlListener(THRESHOLD, () -> true, 40, 300,
                (elapsed, threshold, dataSource, count, sql, injected) ->
                        calls.add(elapsed + "|" + injected + "|" + sql));

        listener.beforeQuery(null, sql("select injected"));
        listener.afterQuery(exec(40L), sql("select injected"));   // 注入过：40 < 200 也要发
        listener.afterQuery(exec(THRESHOLD), sql("select slow")); // 无注入：恰好阈值
        listener.afterQuery(exec(199L), sql("select quiet"));     // 无注入且未到阈值：不发

        assertThat(calls).containsExactly(
                "40|true|select injected",
                THRESHOLD + "|false|select slow");
    }

    @Test
    @DisplayName("模拟库自带实现的反例：本实现**不排**任何定时任务（afterQuery 立即同步返回）")
    void doesNotScheduleAnything() throws Exception {
        SlowSqlListener listener = new SlowSqlListener(THRESHOLD);
        // beforeQuery 是 no-op：若它像库自带的 SlowQueryListener 那样往 ScheduledExecutorService 排任务，
        // 这里的第二次 afterQuery 之前就会有别的线程动过状态。用「连调两次、条数恰好翻倍」把这条钉住。
        listener.beforeQuery(exec(0L), sql("select 1"));
        listener.afterQuery(exec(300L), sql("select 1"));
        listener.beforeQuery(exec(0L), sql("select 2"));
        listener.afterQuery(exec(300L), sql("select 2"));

        assertThat(events()).hasSize(2);
    }
}
