package com.minke.mallmanagement.order.model;

import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 订单状态流转时间线（B3 新增表 {@code order_state_transition}）的唯一写入点。
 *
 * <h2>【为什么单独一张表，而不是给 {@code order} 加列】</h2>
 * 时间线是 **1 单对 N 条**（一单可以付款→发货→收货），加列只能存最后一次。同理也不能靠
 * {@code order.SettlementDate}：那一列被三次转移**覆写**（BD-01 的遗留，见
 * {@code docs/agent/business-decisions.md}），任何时刻只留得下最后一个时间点，且它同时被
 * 「历史订单」页当展示字段用 —— 借它当时间线会同时毁掉两件事。故 B3 记为**新增表 D1**。
 *
 * <h2>【它跑在异步线程上，故「失败」的边界与请求路径不同】</h2>
 * 本方法**不参与请求的成败**：状态位已经在 {@code order} 表里改完了，这里只是**补记一笔过程**。
 * 所以它**永不抛**，一律 fail-soft —— 抛出去也只会被线程池吞掉，反而丢掉「记一笔」的机会。
 * 代价照实说：{@code result=error} 就是**这一条时间线永久丢了**（本笔不做重投，见类注释末段）。
 * 允许丢，不许静默：计数 + 事件流都记。
 *
 * <h2>【WARN 只在真实翻转（正常 → 失败）时打一行】</h2>
 * 同 {@code DistributedLockService.markDegraded}：库连不上时每个任务都会失败，
 * 逐次 WARN 刷屏且不带来新信息 —— 「多少」由 {@code mall.order.state.timeline.writes{result=error}}
 * 回答、「哪一单」由事件流回答，日志只在**状态发生翻转**时补一句「从此刻起时间线开始缺」。
 *
 * <h2>【为什么不做重投 / 本地落盘兜底】</h2>
 * 三条理由，都不是省事：
 * <ol>
 *   <li>重投需要队列，而队列打满时的处置本笔已经裁决过（D3 丢最旧）—— 给一条**已经允许丢**的
 *       记录再套一层可能有界队列，只是把「丢在哪一层」变成说不清的事。</li>
 *   <li>落盘兜底要求写入路径能容忍磁盘满/权限错，而那时**没有更好的地方可去**，
 *       只会把「丢一条时间线」升级成「请求路径上有一次可能抛异常的文件 I/O」。</li>
 *   <li>{@code order} 表里的状态位是**真值**，时间线是**过程记录**。重建时间线的能力已经在数据里
 *       （每次转移的结果可由状态位推出），缺的只是顺序与时刻。</li>
 * </ol>
 * 真要补，正确的位置是 C3（时间线视图）发现缺口时的**一次性对齐**，不是写路径上的重投。
 *
 * <h2>【与 {@code order} 表的关系】</h2>
 * ⚠️ 本表**不设外键**：{@code order} 是 mysqldump 全量重建的（{@code mall_management_sql.sql} 里有
 * {@code DROP TABLE IF EXISTS `order`}），外键会让「重建 order」在依赖本表时失败。约束由写入点保证。
 * ⚠️ {@code order.OrderId} 上**没有索引**（既有 Schema），本表自己在 {@code (OrderId, Id)} 上建了
 * 一个 —— C3 的「查某一单的时间线」正是这个形状。
 */
@Service
public class OrderTimelineWriter {

    private static final Logger log = LoggerFactory.getLogger(OrderTimelineWriter.class);

    /**
     * 表名。⚠️ 时间线表的唯一定义处（建表 SQL 见 {@code mall_management_sql.sql} 末尾的
     * Migration 块，登记行见 {@code docs/agent/db-migration.md}）。
     */
    static final String TABLE = "mall_management_sql.`order_state_transition`";

    /**
     * 落库语句。列序与参数序**必须**一一对应，由 {@code OrderTimelineWriterTest} 钉住。
     *
     * <p>{@code OccurredAt} 由调用方传入而**不是** {@code NOW()}：它要记的是**转移发生**的时刻
     * （在订单锁的临界区内取的），不是**这一行落库**的时刻。两者之差就是 B3 引入的异步延迟，
     * 也是本列存在的全部理由 —— 让异步的代价可被测量，而不是只能被相信。
     *
     * <p>⚠️ <b>2026-10-10 补充：传入的是**毫秒瞬时点**，但落库的必须是 {@link #UTC_TEXT} 格式化后的
     * <em>无时区文本</em></b> —— 见 {@link #toUtcText}。语句本身（占位符个数与形状）一个字没改。
     */
    static final String INSERT_SQL = "INSERT INTO " + TABLE
            + " (OrderId, Action, FromState, ToState, OccurredAt) VALUES (?, ?, ?, ?, ?)";

    /**
     * 库时钟口径的文本格式 —— {@code OccurredAt} 按它格式化后落库。
     *
     * <p>⚠️ <b>这一步不能省（2026-10-10，用户报「轨迹 22:27:51 vs 订单列表 14:29:16」）。</b>
     * 库时钟是 <b>UTC</b>（VM / a3 实测 {@code @@system_time_zone=UTC}、{@code NOW()} 与机器 UTC 同步），
     * 而 JDBC URL 带着 {@code serverTimezone=Asia/Shanghai}；{@code java.sql.Timestamp} 是**瞬时点**，
     * 驱动必须按连接时区把它折成墙钟再写 ⇒ 同一时刻写进去的 {@code OccurredAt} 整整 <b>+8 小时</b>
     * （实测：{@code order_state_transition.OccurredAt} 全线比 {@code order.CreateTime} 多 8 小时）。
     * 换成**无时区的文本**后，串里根本没有时区信息，驱动**无从折算** —— 与
     * {@code ShippingTrackDao} / {@code MessageDao}（{@code NOW()}）同一条口径：
     * <b>时刻只以库时钟为准，Java 侧不构造带时区的值</b>。
     */
    static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 参数个数。与 {@link #INSERT_SQL} 的占位符数必须相等 —— 由测试钉住（少一个会运行时才炸）。 */
    static final int PARAM_COUNT = 5;

    private final JdbcTemplate jdbcTemplate;
    private final MallOrderStateMetrics metrics;

    /**
     * 是否正处于「时间线落库持续失败」的状态。只影响 WARN 的频率，**不影响计数与事件**
     * —— 后两者一次不漏（正是这个字段存在的意义：让日志闭嘴，而不是让观测闭嘴）。
     */
    private final AtomicBoolean failing = new AtomicBoolean();

    public OrderTimelineWriter(JdbcTemplate jdbcTemplate, MallOrderStateMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    /**
     * 记一条转移。**永不抛**（fail-soft，理由见类注释）。
     *
     * <p>⚠️ 本方法由 {@link OrderStateExecutor} 在异步线程上调用，**不在**请求线程、
     * **不在**订单锁内。不要在它里面加任何「和请求时序有关」的逻辑。
     *
     * @param orderId          订单号（业务键，只进事件流）
     * @param action           动作（{@link OrderAction}，其 tag 落入 {@code Action} 列）
     * @param fromState        转移前状态名；由 {@code OrderStateMachine.previousStateName}
     *                         从**转移后**三位清本位反推（精确反推，见该类注释）
     * @param toState          转移后状态名（从 {@code order} 表读回三位后映射）
     * @param occurredAtMillis 转移发生时刻（{@code System.currentTimeMillis()}，临界区内取）
     */
    public void write(String orderId, OrderAction action, String fromState, String toState,
                      long occurredAtMillis) {
        // 块 7 · 阶段 3a.3：耗时紧包那一行 INSERT（方案 §2 的 order·timelineWrite 口径）。
        // ⚠️ 声明在 try 外：catch 里也要用（编译期作用域）。⚠️ 已知局限：timelineWrite 的耗时
        // **只在失败事件上看得见**（ok 那一路只计数、刻意不发事件，见 MallOrderStateMetrics）
        long startedNanos = System.nanoTime();
        try {
            jdbcTemplate.update(INSERT_SQL, orderId, action.tag(), fromState, toState,
                    toUtcText(occurredAtMillis));
            metrics.timelineWrite(true, orderId, System.nanoTime() - startedNanos, null);
            markOk();
        } catch (Exception e) {
            // ⚠️ catch Exception 而不是 DataAccessException：时间线**必须 total catch**
            // （同 DistributedLockService 对锁异常的处理）—— 任何落库失败都不该让异步线程带着
            // 异常退出，那会同时丢掉计数、丢掉事件、丢掉 WARN。
            metrics.timelineWrite(false, orderId, System.nanoTime() - startedNanos,
                    com.minke.mallmanagement.shared.text.EventReason.of("时间线写入失败", e));
            markFailed(e, orderId);
        }
    }

    /**
     * 瞬时毫秒 → <b>库时钟（UTC）文本</b>。
     *
     * <p>⚠️ <b>别改回 {@code new Timestamp(ms)}</b> —— 那是本仓今日最长寿的一处「静默 +8 小时」，
     * 理由与实测见 {@link #UTC_TEXT}。{@code ZoneOffset.UTC} 是**写死的**，不是
     * {@code ZoneId.systemDefault()}：格式化的目的正是把「机器/连接时区」这一层剥掉，
     * 用系统时区等于把刚剥掉的那层又贴回来（同一处坑，前身是 {@code ShippingTrackAdvanceJob.toMillis}）。
     */
    private static String toUtcText(long epochMillis) {
        return UTC_TEXT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
    }

    /**
     * 恢复：只在真实翻转（失败 → 成功）时记一行 INFO。热路径上只读一个 {@code AtomicBoolean}。
     */
    private void markOk() {
        if (failing.compareAndSet(true, false)) {
            log.info("订单状态时间线已恢复（B3）：落库正常");
        }
    }

    /**
     * 转入失败（或维持失败）。WARN 只在翻转时打一行，理由见类注释。
     * 并发下两三个线程可能同时判到翻转而各打一行 —— 与本仓其它监控出口一致，不为一行日志加锁。
     */
    private void markFailed(Exception e, String orderId) {
        if (failing.compareAndSet(false, true)) {
            log.warn("订单状态时间线落库失败（B3）：自此刻起时间线开始缺条（状态位本身不受影响，"
                            + "order 表已提交）。累计缺条数见 mall.order.state.timeline.writes{result=error}，"
                            + "具体订单见事件流。本次订单 {}，原因：{}", orderId, e.toString());
        }
    }
}
