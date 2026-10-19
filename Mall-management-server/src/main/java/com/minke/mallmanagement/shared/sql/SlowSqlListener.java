package com.minke.mallmanagement.shared.sql;

import jakarta.servlet.http.HttpServletRequest;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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

    /**
     * 慢 SQL 事件出口（A5）：把「一条语句慢了」送进 {@code EventBroadcaster} 的合并环。
     *
     * <p>⚠️ 本类由 {@code SlowSqlConfig} **手工构造**（不是 Spring bean），故出口以构造参数
     * 传入；旧构造器不传 ⇒ 恒为 {@code null}（单测与只关心日志的调用方逐字节同行为）。
     * <p>⚠️ 出口调用必须 fail-soft：观测链路坏掉不能反过来把业务查询打断。
     */
    @FunctionalInterface
    public interface SlowSqlSink {
        void onSlowSql(long elapsedMs, long thresholdMs, String dataSource,
                       int queryCount, String sqlSummary, boolean injected);
    }

    /** 慢 SQL 告警阈值（ms）：{@code afterQuery} 在总耗时 ≥ 它时 WARN 一行。 */
    private final long thresholdMs;

    /** 慢 SQL 事件出口；{@code null} = 不发射（见 {@link SlowSqlSink} 的注释）。 */
    private final SlowSqlSink slowSqlSink;

    /**
     * 本条查询是否被 {@code dbSlow} 注入延迟过（ms）。{@code beforeQuery} 写、{@code afterQuery}
     * 读后清除 —— 一条查询在同一线程上成对调用，ThreadLocal 天然按查询隔离。
     * ⚠️ 不能用请求属性：一个请求会跑多条 SELECT，那样第一条注入后每条 afterQuery 都会误报。
     */
    private final ThreadLocal<Long> injectedDelayMs = new ThreadLocal<>();

    /**
     * D3 演练注入 {@code dbSlow} 的开关（读共享配置）。默认恒 false ⇒ 本类与 D3 之前
     * **逐字节同行为**（测试 / 关掉 slow-ms 的 profile 都走这条默认）。
     */
    private final BooleanSupplier dbSlowOn;

    /** 每条 SELECT 注入的延迟（ms）。 */
    private final long dbSlowMs;

    /** 每个请求的延迟总预算（ms）。需求裁定：**最多 300ms**。 */
    private final long dbSlowBudgetMs;

    /** 请求属性：本请求的延迟截止时刻（{@link System#nanoTime()} 基准）。 */
    private static final String BUDGET_ATTR = "mall.observe.injection.dbSlow.budgetDeadline";

    /** 请求属性：本请求是否已经记过一行注入日志（避免一次请求几十条 SELECT 刷屏）。 */
    private static final String LOGGED_ATTR = "mall.observe.injection.dbSlow.logged";

    /** 非请求线程（定时任务 / SSE 流线程）的 1 秒窗口预算：[窗口起点纳秒, 已用纳秒]。 */
    private static final ThreadLocal<long[]> THREAD_BUDGET =
            ThreadLocal.withInitial(() -> new long[]{System.nanoTime(), 0L});

    /** 旧构造器：不启用注入（测试与「只关心慢 SQL 告警」的调用方用）。 */
    public SlowSqlListener(long thresholdMs) {
        this(thresholdMs, () -> false, 0L, 0L);
    }

    public SlowSqlListener(long thresholdMs, BooleanSupplier dbSlowOn, long dbSlowMs, long dbSlowBudgetMs) {
        this(thresholdMs, dbSlowOn, dbSlowMs, dbSlowBudgetMs, null);
    }

    public SlowSqlListener(long thresholdMs, BooleanSupplier dbSlowOn, long dbSlowMs, long dbSlowBudgetMs,
                           SlowSqlSink slowSqlSink) {
        this.thresholdMs = thresholdMs;
        this.dbSlowOn = dbSlowOn == null ? () -> false : dbSlowOn;
        this.dbSlowMs = Math.max(0L, dbSlowMs);
        // 需求裁定逐字是「每请求最多延迟 300ms」⇒ 配置写大了也在这里夹住。
        this.dbSlowBudgetMs = Math.min(300L, Math.max(0L, dbSlowBudgetMs));
        this.slowSqlSink = slowSqlSink;
    }

    /**
     * D3：{@code dbSlow} 注入 —— **只对 SELECT** 生效，且有界。
     *
     * <p>三条边界，任一条不满足就**不睡**：
     * <ol>
     *   <li>只SELECT（{@code select} / {@code with} 开头）：写路径不被拖慢，避免一开注入就把
     *       下单 / 取消 / 回补也拖成 300ms；</li>
     *   <li>跳过攻击控制端点（{@code /admin/observe/attack/**}）：否则「打开注入」这个动作本身
     *       会被自己拖慢，演示台上点开关像卡死；</li>
     *   <li>每个请求 / 每个线程每秒有预算上限（{@code mall.observe.injection.db-slow-budget-ms}，
     *       硬上限 300）：一条请求发几十条 SELECT 时不会被叠加成几十秒。</li>
     * </ol>
     *
     * <p>⚠️ 本方法在**请求线程**上跑（datasource-proxy 的 beforeQuery），睡的是当前线程 ——
     * 这正是「后端真切感知到慢」的落点，不是伪造指标。
     */
    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // 每次查询开始先清掉上一条可能残留的标记（正常路径由 afterQuery 清除；
        // 这条是防「查询异常 / 监听器顺序异常」时把上一条的注入标记带进下一条）。
        injectedDelayMs.remove();
        if (dbSlowMs <= 0L || dbSlowBudgetMs <= 0L || !dbSlowOn.getAsBoolean()) {
            return;
        }
        if (!containsSelect(queryInfoList) || isAttackControlRequest()) {
            return;
        }
        long now = System.nanoTime();
        ServletRequestAttributes attrs = requestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();
        long sleepMs;
        if (request != null) {
            Object deadline = request.getAttribute(BUDGET_ATTR);
            long deadlineNanos;
            if (deadline instanceof Long value) {
                deadlineNanos = value;
            } else {
                deadlineNanos = now + TimeUnit.MILLISECONDS.toNanos(dbSlowBudgetMs);
                request.setAttribute(BUDGET_ATTR, deadlineNanos);
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - now);
            sleepMs = Math.min(dbSlowMs, remainingMs);
        } else {
            long[] window = THREAD_BUDGET.get();
            if (now - window[0] >= TimeUnit.SECONDS.toNanos(1L)) {
                window[0] = now;
                window[1] = 0L;
            }
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(dbSlowBudgetMs) - window[1];
            sleepMs = Math.min(dbSlowMs, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        }
        if (sleepMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // 标记「本条查询真的被注入延迟过」—— afterQuery 据此发射 slowSql 事件（哪怕总耗时
        // 没到阈值：注入本身的延迟就是需要被日志中心看见的设施故障）。
        injectedDelayMs.set(sleepMs);
        if (request == null) {
            THREAD_BUDGET.get()[1] += TimeUnit.MILLISECONDS.toNanos(sleepMs);
            log.debug("[DB-SLOW] 演练注入：非请求线程 SELECT 延迟 {} ms", sleepMs);
            return;
        }
        if (request.getAttribute(LOGGED_ATTR) == null) {
            request.setAttribute(LOGGED_ATTR, Boolean.TRUE);
            log.info("[DB-SLOW] 演练注入 dbSlow 生效：{} 的 SELECT 注入延迟 {} ms"
                            + "（本请求预算 {} ms；只延迟 SELECT，写路径不受影响）",
                    request.getRequestURI(), sleepMs, dbSlowBudgetMs);
        }
    }

    /** 本批语句里有没有（至少一条）SELECT / WITH。包级可见是为了可单测。 */
    static boolean containsSelect(List<QueryInfo> queryInfoList) {
        if (queryInfoList == null) {
            return false;
        }
        for (QueryInfo qi : queryInfoList) {
            String sql = qi == null ? null : qi.getQuery();
            if (sql == null) {
                continue;
            }
            String head = sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
            if (head.startsWith("select") || head.startsWith("with")) {
                return true;
            }
        }
        return false;
    }

    private static ServletRequestAttributes requestAttributes() {
        try {
            return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                    ? attrs : null;
        } catch (IllegalStateException e) {
            // 非 web 上下文（纯单测）
            return null;
        }
    }

    private static boolean isAttackControlRequest() {
        ServletRequestAttributes attrs = requestAttributes();
        if (attrs == null) {
            return false;
        }
        String uri = attrs.getRequest().getRequestURI();
        return uri != null && uri.startsWith("/admin/observe/attack");
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        long elapsed = execInfo.getElapsedTime();
        // dbSlow 注入过的查询：即使总耗时没到阈值，也是需要被日志中心捕获的设施故障。
        Long injected = injectedDelayMs.get();
        injectedDelayMs.remove();
        if (elapsed < thresholdMs && injected == null) {
            return;
        }
        int count = queryInfoList == null ? 0 : queryInfoList.size();
        String summary = summarize(queryInfoList);
        log.warn("慢 SQL（SLOW-SQL）：{} ms ≥ 阈值 {} ms · 数据源 {} · 条数 {} · 语句 {}{}",
                elapsed, thresholdMs, execInfo.getDataSourceName(), count, summary,
                injected == null ? "" : " · dbSlow 注入延迟 " + injected + " ms");
        if (slowSqlSink != null) {
            try {
                slowSqlSink.onSlowSql(elapsed, thresholdMs, execInfo.getDataSourceName(),
                        count, summary, injected != null);
            } catch (RuntimeException e) {
                // 观测出口坏掉不能反过来打断业务查询（与 EventPublisher 的 fail-soft 同口径）。
                log.debug("[SLOW-SQL] 事件出口失败（已忽略，不影响查询）：{}", e.toString());
            }
        }
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
