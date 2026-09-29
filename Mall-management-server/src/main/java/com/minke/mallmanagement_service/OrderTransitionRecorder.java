package com.minke.mallmanagement_service;

import com.minke.mallmanagement_server.MallOrderStateMetrics;
import com.minke.mallmanagement_server.MallOrderStateMetrics.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 一次订单状态变化的**观测编排**：读回三位、推出前后态、分类、投递时间线。
 *
 * <h2>【为什么单独一个类，而不是留在 Controller 里】</h2>
 * 这段编排有四条真实分支（读回失败 / 命中且合法 / 命中且是伪造态 / 出生），而它**跑在订单锁的
 * 临界区内、与 UPDATE 同一处** —— 是最不该靠「读代码觉得对」的那类代码。留在
 * {@code MallManagementGoodsController} 里，它只能被 HTTP + mock 触达，而 mock 掉指标之后
 * 分支本身就没法断言了。抽出来之后：Controller 只负责分支与文案，本类由
 * {@code OrderTransitionRecorderTest} 直接驱动（真状态机 + 真指标 + 假 JdbcTemplate）。
 *
 * <h2>【与业务事实同点，是刻意的】</h2>
 * 本类的每个方法都在 UPDATE 返回之后**立刻**被调用，就在临界区里。两条理由：
 * <ul>
 *   <li>观测与业务事实同点，指标/事件的口径才不漂移（搬出去就会记到别人的转移上）；</li>
 *   <li>读回三位**必须在锁内**：搬到锁外，并发的另一次转移可能挤进来，
 *       时间线就会把 {@code from} 记成别人造成的那个状态。被拒绝的路径**一次都不读**
 *       （A3 的重复冲击下被拒是多数），故锁内只多一次毫秒级往返，相对
 *       {@code app.lock.lease=10s} 可忽略。</li>
 * </ul>
 *
 * <h2>【全程 fail-soft】</h2>
 * 本类的任何失败都**不影响响应体、不上抛**：状态位已经在 {@code order} 表里改完了，那是真值；
 * 指标与时间线是过程记录。丢一条时间线要被计数（{@code mall.order.state.timeline.writes}），
 * 但不许把一次成功的转移变成一次 5xx。
 *
 * <h2>【前态不需要额外读库】</h2>
 * 守卫必含「本动作那一位 = 0」，而本动作只置这一位 ⇒ {@code from ≡ to 把本位清零}，
 * 这是**精确反推**而非近似。前提由 {@code OrderStateMachineTest
 * .everyGuardRequiresItsOwnBitToBeZero} 钉死，反推本身由
 * {@code OrderStateMachine.previousStateName} 提供。
 */
@Service
public class OrderTransitionRecorder {

    private static final Logger log = LoggerFactory.getLogger(OrderTransitionRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final OrderStateMachine stateMachine;
    private final MallOrderStateMetrics metrics;
    private final OrderStateExecutor executor;
    private final OrderTimelineWriter timelineWriter;

    /**
     * 时间线总开关。关闭时**不投递**落库任务，但 {@code transitions{result=applied}} 照记 ——
     * 于是「投递数 ≡ transitions{result=applied}」这个等式（{@code MallOrderStateMetrics} 类注释
     * 用它论证「不埋 async.submitted」）**只在开启时**成立。故本值必须打印出来，
     * 否则那条等式可不可用就成了说不清的事。启动期打印见构造器。
     */
    private final boolean timelineEnabled;

    public OrderTransitionRecorder(JdbcTemplate jdbcTemplate,
                                   OrderStateMachine stateMachine,
                                   MallOrderStateMetrics metrics,
                                   OrderStateExecutor executor,
                                   OrderTimelineWriter timelineWriter,
                                   @Value("${app.order.state.timeline.enabled:true}") boolean timelineEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.stateMachine = stateMachine;
        this.metrics = metrics;
        this.executor = executor;
        this.timelineWriter = timelineWriter;
        this.timelineEnabled = timelineEnabled;

        // 把生效值打进启动日志（同 CacheConfig 的「两级缓存已装配」、DistributedLockService 的
        // 「分布式锁已装配」）：属性名拼错时 @Value 会静默退化成默认值。ASCII 片段 "B3" 供 GBK 检索。
        log.info("订单状态观测出口已装配（B3）：timeline.enabled={} — {}",
                timelineEnabled,
                timelineEnabled
                        ? "异步投递数 ≡ mall.order.state.transitions{result=applied}"
                        : "时间线不投递；该等式不成立，transitions 照记（含非法形状检测）");
    }

    /**
     * 一次**命中**的转移（{@code rows > 0}）：读回三位 → 推出前后态 → 记指标与事件 → 投递时间线。
     *
     * <p>读回失败时：仍记 {@code applied}（转移确实成功了），但**没有** {@code from}/{@code to}
     * 可记，时间线这一条补不回来 —— 按已有的「允许丢，不许静默」口径记一笔
     * {@code timeline.writes{result=error}}，不新造指标。
     */
    public void applied(OrderAction action, String orderId) {
        long occurredAt = System.currentTimeMillis();
        int payIf;
        int deliveryIf;
        int receiveIf;
        try {
            Map<String, Object> bits = jdbcTemplate.queryForMap(stateMachine.selectBitsSql(), orderId);
            payIf = asInt(bits.get("PayIf"));
            deliveryIf = asInt(bits.get("DeliveryIf"));
            receiveIf = asInt(bits.get("ReceiveIf"));
        } catch (Exception e) {
            log.warn("转移已生效但三位读不回，这条时间线补不回来（B3）：订单 {} 动作 {}，原因：{}",
                    orderId, action.tag(), e.toString());
            metrics.transition(action, null, null, Result.APPLIED, orderId);
            metrics.timelineWrite(false, orderId);
            return;
        }

        String toState = stateMachine.stateName(payIf, deliveryIf, receiveIf);
        String fromState = stateMachine.previousStateName(action, payIf, deliveryIf, receiveIf);
        metrics.transition(action, fromState, toState, Result.APPLIED, orderId);

        // 读回的三位若是伪造/越界的，这里会分类出形状 —— 与下单口那次检测同一个口径，
        // 只是这次发生在**转移之后**：PAY 能把伪造态洗成合法态，所以两处都得看。
        recordIllegalIfAny(orderId, payIf, deliveryIf, receiveIf);

        submitTimeline(orderId, action, fromState, toState, occurredAt);
    }

    /**
     * 守卫没命中（{@code rows == 0}）：重复流转 / 单不存在。
     *
     * <p>都是**既有行为** —— 本笔不新增任何拒绝文案，只把结果记下来。
     * 这是 {@code A-S3-2}（20 并发支付里恰好 19 个返回逐字「订单不存在或已付款」）能保持绿的前提。
     */
    public void rejected(OrderAction action, String orderId) {
        metrics.transition(action, null, null, Result.REJECTED, orderId);
    }

    /** UPDATE 抛异常（库连不上等）。响应体的文案由 Controller 决定，本类只记结果。 */
    public void errored(OrderAction action, String orderId) {
        metrics.transition(action, null, null, Result.ERROR, orderId);
    }

    /**
     * 一次「下单」—— TD-17 **唯一**的入口：三个状态位由客户端直传（{@code orderAdd} 的入参），
     * 伪造态只能从这里造出来。故出生这一笔既做非法形状检测、也记进时间线。
     *
     * <p>⚠️ {@link OrderAction#CREATE} 不是转移（没有守卫、没有前态），却**必须记**：
     * 只下过单、还没付款的订单否则在时间线里一行都没有，而它的初始三位在别处**不可恢复**
     * （{@code order} 表只存当前值）。前态用 {@link OrderStateMachine#NO_PREVIOUS_STATE} 占位。
     *
     * <p>⚠️ **只观测、不拦截**（D2）：伪造出 {@code (0,1,0)} 照样下单成功。拦截会让 A3 的 S4a
     * 全部 8 条断言失去前置构造，属另一笔、需业务裁决。
     */
    public void created(String orderId, int payIf, int deliveryIf, int receiveIf) {
        long occurredAt = System.currentTimeMillis();
        String toState = stateMachine.stateName(payIf, deliveryIf, receiveIf);
        metrics.transition(OrderAction.CREATE, null, toState, Result.APPLIED, orderId);
        recordIllegalIfAny(orderId, payIf, deliveryIf, receiveIf);

        submitTimeline(orderId, OrderAction.CREATE, OrderStateMachine.NO_PREVIOUS_STATE, toState, occurredAt);
    }

    /**
     * 投递时间线落库。**只入队、不做 I/O**，且仍在锁的临界区内 —— 入队是纳秒级动作，
     * 而「观测与业务事实同点」比省那几个纳秒重要。
     *
     * <p>总开关关闭时**不投递**：此时「投递数 ≡ transitions{result=applied}」不成立，
     * 生效值由构造器打印（{@link MallOrderStateMetrics} 的命名规范第 6 条靠它才可判定）。
     */
    private void submitTimeline(String orderId, OrderAction action, String fromState, String toState,
                                long occurredAt) {
        if (!timelineEnabled) {
            return;
        }
        executor.submit(orderId, () -> timelineWriter.write(orderId, action, fromState, toState, occurredAt));
    }

    private void recordIllegalIfAny(String orderId, int payIf, int deliveryIf, int receiveIf) {
        String shape = stateMachine.illegalShape(payIf, deliveryIf, receiveIf);
        if (shape != null) {
            metrics.illegal(shape, orderId);
        }
    }

    /**
     * 三位是 {@code int} 列，JDBC 给的是 {@code Integer}。取不到（列名变了、读回的是空 Map）
     * 时返回 {@link Integer#MIN_VALUE} —— **不是** 0：0 是一个合法的位值，会把它悄悄洗成
     * 「未付款」，而越界值会让 {@code stateName} 判 {@link OrderStateMachine#UNCLASSIFIED}，
     * 这才是「不知道」该有的表现。
     */
    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
    }
}
