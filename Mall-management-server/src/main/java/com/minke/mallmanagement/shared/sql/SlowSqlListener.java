package com.minke.mallmanagement.shared.sql;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 慢 SQL 告警（C3 块 7 · commit 6b · 批文 Q4 的「丁」）：一条语句跑够 {@code thresholdMs} 就 WARN 一行。
 *
 * <h2>【为什么自己写 listener，而不是用库自带的 {@code logSlowQueryBySlf4j}】</h2>
 * 这是本笔**最不显眼、但最要紧**的一个决定，理由是实测出来的（读过 1.11.0 的源码，不是猜）：
 * 库自带的 {@code SlowQueryListener.beforeQuery} 会为**每一条**查询
 * {@code executor.schedule(check, threshold, unit)} —— 往一个<b>单线程 ScheduledExecutorService</b>
 * 里排一个定时任务，并且 {@code onSlowQuery} 回调是在<b>那个调度线程</b>上跑的。两个后果：
 * <ol>
 *   <li><b>traceId 会丢</b>：回调不在请求线程上，而 MDC 是 ThreadLocal ⇒ 库自带的那条 WARN
 *       恰好<b>没有</b> traceId。而「慢 SQL 属于哪个请求」正是这条告警的全部价值 —— 丢了它，
 *       就只剩一句「有条语句慢了」，还得靠时间去猜。</li>
 *   <li><b>给压测添一座调度器</b>：每分钟几万条查询就是几万个排队任务，单线程消费。
 *       A3 的并发门禁（P0–P7）跑的就是高并发真 SQL，这条开销会直接落在门禁数字上。</li>
 * </ol>
 * 自实现的 {@link #afterQuery} 在**请求线程**上跑（MDC 天然在），且不做任何调度 ——
 * 只比一个现成的 {@code long}（{@code elapsedTime} 由 datasource-proxy 在调用前后用 Stopwatch 测好，
 * {@code StatementProxyLogic} 在成功与异常<b>两条路径</b>上都先 {@code setElapsedTime} 再回调，
 * 故此处读到的是<b>最终总耗时</b>）。语义顺带更贴常用定义：总耗时 ≥ 阈值 —— 与 MySQL
 * 自己的 {@code slow_query_log} 同款（库自带的那个是「执行**到**阈值就报」）。
 *
 * <h2>【为什么不打参数值】</h2>
 * 慢查询排查时参数当然有用，但它会**把用户数据写进日志**：本仓的参数里过手机号、地址、
 * 支付单号，明文落盘后日志就成了一份不该存在的副本。折中：打语句模板 + 参数<b>个数</b>，
 * 不打值。真要取值，用一次可复现的查询在库里复现，而不是让它长期躺在日志里。
 */
public class SlowSqlListener implements QueryExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlListener.class);

    /**
     * 语句模板的截断长度。⚠️ 为什么要有：一条批量的 {@code IN (?)×200} 或带长注释的动态 SQL
     * 能轻松上万字符，而告警只需要「是哪条语句」—— 不截断的话，一行 WARN 会把终端刷满，
     * 反而看不见后面的告警。500 足够容纳本仓最长的语句（含 {@code mall_management_sql.} 前缀）。
     */
    private static final int MAX_SQL_CHARS = 500;

    private final long thresholdMs;

    public SlowSqlListener(long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    /** 刻意留空：本类只关心「跑完了、跑了多久」，不关心「要跑了」。 */
    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // no-op
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        long elapsed = execInfo.getElapsedTime();
        if (elapsed < thresholdMs) {
            return;
        }
        log.warn("慢 SQL（SLOW-SQL）：{} ms ≥ 阈值 {} ms · 数据源 {} · 条数 {} · 语句 {}",
                elapsed, thresholdMs, execInfo.getDataSourceName(),
                queryInfoList == null ? 0 : queryInfoList.size(), summarize(queryInfoList));
    }

    /** 把这一批语句拼成一行可读文本（截断见 {@link #MAX_SQL_CHARS}）。包级可见是为了可单测。 */
    static String summarize(List<QueryInfo> queryInfoList) {
        if (queryInfoList == null || queryInfoList.isEmpty()) {
            return "(无语句)";
        }
        StringBuilder sb = new StringBuilder();
        for (QueryInfo qi : queryInfoList) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            String sql = qi == null ? null : qi.getQuery();
            // 折行与连续空白压成单空格：一条多行 SQL 会让 println 出去的那一行「断成好几行」，
            // 使得按行检索日志的人以为那是好几条独立记录。
            sb.append(sql == null ? "(null)" : sql.replaceAll("\\s+", " ").trim());
            if (sb.length() > MAX_SQL_CHARS) {
                break;
            }
        }
        return sb.length() <= MAX_SQL_CHARS ? sb.toString() : sb.substring(0, MAX_SQL_CHARS) + "…(截断)";
    }
}
