package com.minke.mallmanagement.order.model;

import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.minke.mallmanagement.order.model.OrderAction.DELIVER;
import static com.minke.mallmanagement.order.model.OrderAction.PAY;
import static com.minke.mallmanagement.order.model.OrderAction.RECEIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link OrderTransitionRecorder} 的实测 —— 本笔里**分支最多、又最靠近锁**的一段代码。
 *
 * <h2>【为什么它必须有自己的单测】</h2>
 * 这段编排有五条真实分支（读回失败 / 命中 / 拒中 / 出生 / 出生闸拦截），前四条跑在**订单锁的
 * 临界区内、与 UPDATE 同一处**（C#6 的第五条例外：它在锁**外**）。留在 Controller 里时，
 * 它只能被 HTTP + mock 触达，而 mock 掉指标之后分支本身就没法断言了 —— 等于「读代码觉得对」。
 * 抽成本类之后：真状态机 + 真指标 + **假 JdbcTemplate**，五条分支全部可确定性驱动，
 * 不起 Spring、不连库。
 *
 * <h2>【一条被本文件证伪的直觉，别把它写回文档】</h2>
 * 我原本以为「转移之后再查一次非法形状」能捕获**伪造态**。实测下来它**不能**（见
 * {@link #afterAnySuccessfulTransitionTheStateIsAlwaysLegal}）：三条守卫分别要求
 * {@code PayIf=0}（只 PAY 有）或 {@code PayIf=1}，而 PAY 又必然把 {@code PayIf} 置 1
 * ⇒ **任何一次成功转移之后的状态，按 A-S4a-8 都必然合法**。所以转移后那次检测实际是
 * **数据损坏探测器**（越界取值 → {@code non_binary}），伪造态的探测**只有下单口那一处**
 * 有信号。两处都留着（口径一致、代价是一次纯内存判断），但**别再把后者说成防伪造**。
 *
 * <p>⚠️ 指标名用**字面量**：{@code MallOrderStateMetrics} 的常量是包私有的（既有约定），
 * 而本类在 {@code …service} 包。名字的唯一出处与有界性由 {@code MallOrderStateMetricsTest} 守。
 */
class OrderTransitionRecorderTest {

    private static final String TRANSITIONS = "mall.order.state.transitions";
    private static final String ILLEGAL = "mall.order.state.illegal";
    private static final String BIRTH_REJECTED = "mall.order.state.birth.rejected";
    private static final String TIMELINE_WRITES = "mall.order.state.timeline.writes";

    private static final String ORDER = "ORDER-REC-1";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallOrderStateMetrics metrics = new MallOrderStateMetrics(registry);
    private final BitsJdbcTemplate jdbc = new BitsJdbcTemplate();
    private final RecordingTimelineWriter writer = new RecordingTimelineWriter(jdbc, metrics);
    private final RecordingExecutor executor = new RecordingExecutor(metrics);

    private final OrderTransitionRecorder recorder = new OrderTransitionRecorder(
            jdbc, new OrderStateMachine(), metrics, executor, writer, true);

    // ===================== 一、命中（applied）=====================

    @Test
    @DisplayName("命中：从读回的三位精确推出前后态（from ≡ to 清本位），两条通道 + 时间线都已落")
    void appliedDerivesFromAndToFromTheReadBackBits() {
        jdbc.bits(1, 0, 0);          // PAY 之后
        recorder.applied(PAY, ORDER, 1_500L);

        assertThat(count(TRANSITIONS, "action", "pay", "result", "applied")).isEqualTo(1.0);

        List<OrderStateEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).from()).isEqualTo("CREATED");
        assertThat(events.get(0).to()).isEqualTo("PAID");
        assertThat(events.get(0).key()).isEqualTo(ORDER);

        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).from()).as("前态不需要额外读库，是算出来的").isEqualTo("CREATED");
        assertThat(writer.rows.get(0).to()).isEqualTo("PAID");
        assertThat(writer.rows.get(0).action()).isEqualTo(PAY);
        assertThat(writer.rows.get(0).occurredAt()).isPositive();
        assertThat(executor.submitted).containsExactly(ORDER);
    }

    @Test
    @DisplayName("接受货：从 (1,0,1) 反推回 PAID 而不是 SHIPPED（未发货即收货这条支路）")
    void appliedHandlesReceiveWithoutDelivery() {
        jdbc.bits(1, 0, 1);
        recorder.applied(RECEIVE, ORDER, 1_500L);

        assertThat(writer.rows.get(0).from())
                .as("后端确放行「未发货即收货」—— 不是 SHIPPED")
                .isEqualTo("PAID");
        assertThat(writer.rows.get(0).to()).isEqualTo("RECEIVED_NODELIVERY");
    }

    @Test
    @DisplayName("伪造行被 PAY 洗白：时间线如实记为「从非法态来」，不粉饰")
    void appliedRecordsTheForgedPredecessorAsIs() {
        jdbc.bits(1, 1, 0);          // 伪造 (0,1,0) 被 PAY 推成 (1,1,0)
        recorder.applied(PAY, ORDER, 1_500L);

        assertThat(writer.rows.get(0).from()).isEqualTo("ILLEGAL_DELIVERED_UNPAID");
        assertThat(writer.rows.get(0).to()).isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("**任何成功转移之后的状态按 A-S4a-8 必然合法** —— 转移后的检测只抓越界，不抓伪造")
    void afterAnySuccessfulTransitionTheStateIsAlwaysLegal() {
        // 穷举「守卫接纳的前态 × 动作」，看转移后能否落在非法集上。
        OrderStateMachine machine = new OrderStateMachine();
        for (OrderAction action : new OrderAction[]{PAY, DELIVER, RECEIVE}) {
            for (OrderState from : machine.guardAdmits(action)) {
                OrderState to = machine.apply(action, from);
                assertThat(to.legal())
                        .as("%s: %s → %s —— 若这里出现非法态，说明「转移后检测能防伪造」成立", action, from, to)
                        .isTrue();
                // 把结果喂给真实分类器，确认它不会报出 010/001/011 这类「伪造形状」
                assertThat(machine.illegalShape(to.payIf(), to.deliveryIf(), to.receiveIf())).isNull();
            }
        }
    }

    @Test
    @DisplayName("转移后的检测抓的是**越界取值**（数据损坏），记为 non_binary")
    void appliedFlagsOutOfRangeBitsAsNonBinary() {
        jdbc.bits(1, 2, 0);          // 越界的 DeliveryIf
        recorder.applied(DELIVER, ORDER, 1_500L);

        assertThat(count(ILLEGAL, "shape", "non_binary")).isEqualTo(1.0);
        assertThat(registry.find(ILLEGAL).counters())
                .as("越界值先归一，shape 的取值才不会随客户端传什么都变（tag 基数必须有上界）")
                .hasSize(1);
        assertThat(writer.rows.get(0).to())
                .as("状态名也不粉饰：越界 ⇒ UNCLASSIFIED，不是某个凑合出来的枚举名")
                .isEqualTo(OrderStateMachine.UNCLASSIFIED);
    }

    // ===================== 二、读回失败（fail-soft 的核心）=====================

    @Test
    @DisplayName("读回失败：仍记 applied，时间线记一笔**丢条**，且**不上抛**（响应体不受影响）")
    void appliedWhenReadBackFailsRecordsLostTimelineAndNeverThrows() {
        jdbc.toThrow = new IllegalStateException("模拟库连不上");

        assertThatCode(() -> recorder.applied(PAY, ORDER, 1_500L))
                .as("转移本身已经成功了，观测失败不该把一次成功的转移变成一次 5xx")
                .doesNotThrowAnyException();

        assertThat(count(TRANSITIONS, "action", "pay", "result", "applied"))
                .as("转移确实成功了，这一点必须照记").isEqualTo(1.0);
        assertThat(count(TIMELINE_WRITES, "result", "error"))
                .as("算不出 from/to ⇒ 这一条时间线补不回来，按既有的「允许丢，不许静默」口径记一笔")
                .isEqualTo(1.0);
        assertThat(writer.rows).as("不知道从哪来、到哪去，就不该写一条猜出来的行").isEmpty();

        // 两条事件：转移本身（无前后态） + 被记下的「丢条」。两者订单号相同，
        // 故事件流里能顺着同一个 key 看出「这一单转了，但它的时间线缺了一行」。
        List<OrderStateEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).kind()).as("先记转移").isEqualTo("transition");
        assertThat(events.get(0).result()).isEqualTo("applied");
        assertThat(events.get(0).from()).isNull();
        assertThat(events.get(0).to()).isNull();
        assertThat(events.get(1).kind()).as("再记丢条 —— 允许丢，不许静默").isEqualTo("async");
        assertThat(events.get(1).result()).isEqualTo("error");
        assertThat(events).extracting(OrderStateEvent::key).containsOnly(ORDER);
    }

    @Test
    @DisplayName("读回的 Map 里缺列 ⇒ 判**不知道**，不是判 0（0 是合法位值，会把它洗成「未付款」）")
    void missingColumnsAreTreatedAsUnknownNotAsZero() {
        jdbc.row = Map.of();          // 一个列都没有
        recorder.applied(PAY, ORDER, 1_500L);

        assertThat(writer.rows.get(0).to())
                .as("若把取不到的位当成 0，这里会算出 (1,0,0)=PAID —— 一个看起来挺合理的错答案")
                .isEqualTo(OrderStateMachine.UNCLASSIFIED);
        assertThat(writer.rows.get(0).from()).isEqualTo(OrderStateMachine.UNCLASSIFIED);
        assertThat(count(ILLEGAL, "shape", "non_binary")).isEqualTo(1.0);
    }

    // ===================== 三、未命中与异常（不碰时间线）=====================

    @Test
    @DisplayName("守卫没命中：只记 rejected，**不写时间线、不投递**（没有「转移后」可言）")
    void rejectedRecordsOnlyTheOutcome() {
        recorder.rejected(PAY, ORDER, 1_500L, "订单不存在或已付款");

        assertThat(count(TRANSITIONS, "action", "pay", "result", "rejected")).isEqualTo(1.0);
        assertThat(metrics.recentEvents()).hasSize(1);
        assertThat(metrics.recentEvents().get(0).to()).isNull();
        assertThat(metrics.recentEvents().get(0).reason())
                .as("复用既有 noRowsMsg —— 与日志里的那句话同源，不另编一份")
                .isEqualTo("订单不存在或已付款");
        assertThat(writer.rows).isEmpty();
        assertThat(executor.submitted).isEmpty();
        assertThat(jdbc.queries).as("被拒绝的路径一次都不用读回三位").hasValue(0);
    }

    @Test
    @DisplayName("UPDATE 抛异常：只记 error，同样不碰时间线")
    void erroredRecordsOnlyTheOutcome() {
        recorder.errored(DELIVER, ORDER, 1_500L,
                "数据库错误：IllegalStateException：db down");

        assertThat(count(TRANSITIONS, "action", "deliver", "result", "error")).isEqualTo(1.0);
        assertThat(metrics.recentEvents().get(0).reason())
                .as("error 档带异常摘要（类名 + message，不拼 SQL）")
                .isEqualTo("数据库错误：IllegalStateException：db down");
        assertThat(writer.rows).isEmpty();
        assertThat(executor.submitted).isEmpty();
    }

    // ===================== 四、出生（TD-17 唯一的门）=====================

    @Test
    @DisplayName("下单即付款（(1,0,0)）是合法出生：记 CREATE + 时间线，前态为占位名")
    void createdRecordsBirthWithTheNoPreviousStatePlaceholder() {
        recorder.created(ORDER, 1, 0, 0, 1_500L);

        assertThat(count(TRANSITIONS, "action", "create", "result", "applied")).isEqualTo(1.0);
        assertThat(metrics.recentEvents().get(0).kind()).isEqualTo("transition");
        assertThat(metrics.recentEvents().get(0).action()).isEqualTo("create");
        assertThat(metrics.recentEvents().get(0).from())
                .as("出生没有前态 —— 空着会让人以为是「没读到」，故用显式占位名").isNull();
        assertThat(metrics.recentEvents().get(0).to()).isEqualTo("PAID");

        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).from()).isEqualTo(OrderStateMachine.NO_PREVIOUS_STATE);
        assertThat(writer.rows.get(0).to()).isEqualTo("PAID");
        assertThat(count(ILLEGAL, "shape", "010")).isZero();
    }

    /**
     * ⚠️ <b>C#6（2026-10-04 {@code fix/order-state-guard}）之后，本条测的支路在 API 路径上不可达</b>
     * —— 出生闸在 {@code OrderService.orderAdd} 的 <b>锁外</b>就把它挡掉
     * （{@code OrderState.isInitial}），请求根本走不到 INSERT。所以这是**直接驱动方法**的
     * 单元测试（旁路了闸），它证明的是「这个方法本身仍按原样工作」，<b>不是</b>
     * 「伪造态还能从 API 进来」。两件事必须分开读：
     * <ul>
     *   <li>API 层「伪造态进不来」由 {@code OrderAddStateGuardSmokeTest} 钉死（跑在阻塞 job 里，
     *       不靠 {@code A3_ENABLED} 门控）；</li>
     *   <li>API 层的观测由
     *       {@link #rejectedAtBirthRecordsOnBothChannelsAndNeverTouchesTheDatabase}
     *       与 {@code mall.order.state.birth.rejected} 承担。</li>
     * </ul>
     *
     * <p>⚠️ <b>顺带订正一处流传的说法</b>：本条原先的 {@code as(...)} 写的是「拦截会让 A3 的 S4a
     * 八条断言失去前置构造」。2026-10-04 实测，受影响的是 S4a <b>3 条</b>（前置态是 PD / PR / PDR
     * 的那三条 —— 它的 5 个前置态按 A-S4a-8 全是合法态，与伪造无关），以及 A3 的 <b>C 组</b>
     * （{@code C_BITS} 八组合要求全部出生）。两处已在同笔一并改造。
     */
    @Test
    @DisplayName("⚠️ 伪造出生（(0,1,0)）落库后仍被记形状 —— 但该支路已不在 API 路径上（见 javadoc）")
    void createdWithForgedBitsRecordsTheShape() {
        recorder.created(ORDER, 0, 1, 0, 1_500L);

        assertThat(count(TRANSITIONS, "action", "create", "result", "applied"))
                .as("本方法**自己没有变** —— 它照旧只观测、不拦截")
                .isEqualTo(1.0);
        assertThat(count(ILLEGAL, "shape", "010")).isEqualTo(1.0);
        assertThat(writer.rows.get(0).to()).isEqualTo("ILLEGAL_DELIVERED_UNPAID");
        assertThat(writer.rows.get(0).from()).isEqualTo(OrderStateMachine.NO_PREVIOUS_STATE);
    }

    @Test
    @DisplayName("越界出生（orderAdd 收 int，连 2 都收）：形状归一为 non_binary，状态名 UNCLASSIFIED")
    void createdWithOutOfRangeBitsRecordsNonBinary() {
        recorder.created(ORDER, 2, 0, 0, 1_500L);

        assertThat(count(ILLEGAL, "shape", "non_binary")).isEqualTo(1.0);
        assertThat(registry.find(ILLEGAL).counters()).hasSize(1);
        assertThat(writer.rows.get(0).to()).isEqualTo(OrderStateMachine.UNCLASSIFIED);
    }

    @Test
    @DisplayName("非法形状的取值表封闭：全部 8 种三位组合 + 越界，只产生 3 个 shape 取值")
    void illegalShapesAreBoundedAcrossEveryBitCombination() {
        for (int p = 0; p <= 1; p++) {
            for (int d = 0; d <= 1; d++) {
                for (int r = 0; r <= 1; r++) {
                    recorder.created("ORDER-" + p + d + r, p, d, r, 1_500L);
                }
            }
        }
        recorder.created("ORDER-OOR", 5, 5, 5, 1_500L);

        assertThat(registry.find(ILLEGAL).counters())
                .as("3 个非法组合 + non_binary —— 基数铁律的可执行形式（否则 tag 会随客户端传什么都涨）")
                .hasSize(4);
        assertThat(count(ILLEGAL, "shape", "010")).isEqualTo(1.0);
        assertThat(count(ILLEGAL, "shape", "001")).isEqualTo(1.0);
        assertThat(count(ILLEGAL, "shape", "011")).isEqualTo(1.0);
        assertThat(count(ILLEGAL, "shape", "non_binary")).isEqualTo(1.0);
    }

    // ===================== 五、总开关 =====================

    @Test
    @DisplayName("timeline.enabled=false：不投递、不落库，但 transitions 与非法形状检测**照记**")
    void disabledTimelineStopsSubmittingButKeepsCounting() {
        OrderTransitionRecorder off = new OrderTransitionRecorder(
                jdbc, new OrderStateMachine(), metrics, executor, writer, false);
        jdbc.bits(1, 0, 0);

        off.applied(PAY, ORDER, 1_500L);
        off.created(ORDER, 0, 1, 0, 1_500L);

        assertThat(executor.submitted)
                .as("关掉开关就一条都不投 —— 「投递数 ≡ transitions{applied}」正是靠它才只在开启时成立")
                .isEmpty();
        assertThat(writer.rows).isEmpty();
        assertThat(count(TRANSITIONS, "action", "pay", "result", "applied")).isEqualTo(1.0);
        assertThat(count(TRANSITIONS, "action", "create", "result", "applied")).isEqualTo(1.0);
        assertThat(count(ILLEGAL, "shape", "010"))
                .as("观测不该跟着时间线一起被关掉 —— 伪造态的探测与落库无关")
                .isEqualTo(1.0);
    }

    // ===================== 六、出生闸的观测出口（C#6）=====================

    @Test
    @DisplayName("出生闸拦下：两条通道都发，且**一次都不碰库**（闸在锁外、在 INSERT 之前）")
    void rejectedAtBirthRecordsOnBothChannelsAndNeverTouchesTheDatabase() {
        recorder.rejectedAtBirth(0, 1, 0, ORDER);

        assertThat(count(BIRTH_REJECTED, "shape", "010")).isEqualTo(1.0);
        assertThat(registry.find(BIRTH_REJECTED).counters()).hasSize(1);

        List<OrderStateEvent> events = metrics.recentEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo("illegal");
        assertThat(events.get(0).result())
                .as("与「落库后的非法态观测」共用 kind，靠 result 区分（见 MallOrderStateMetrics 的事件语法）")
                .isEqualTo("rejected");
        assertThat(events.get(0).shape()).isEqualTo("010");
        assertThat(events.get(0).key()).isEqualTo(ORDER);

        assertThat(jdbc.queries).as("闸在锁外、在 INSERT 之前：一次都不读回三位").hasValue(0);
        assertThat(writer.rows).as("没有出生，也就没有时间线").isEmpty();
        assertThat(executor.submitted).isEmpty();
        assertThat(count(TRANSITIONS, "action", "create", "result", "applied"))
                .as("被拒不是一次「出生」，不许记成 create/applied ⇒ 两个指标永不相加")
                .isZero();
        assertThat(count(ILLEGAL, "shape", "010"))
                .as("也**不是** illegal（那是「已经落库的」）—— 两条序列各记各的")
                .isZero();
    }

    @Test
    @DisplayName("⚠️ 7 次拦截只落进 5 个标号；其中 3 个「合法但非初始」是 illegalShape **答不出来**的")
    void rejectedAtBirthIsBoundedAndCoversLegalButNonInitialStates() {
        recorder.rejectedAtBirth(0, 1, 0, "O-010");
        recorder.rejectedAtBirth(0, 0, 1, "O-001");
        recorder.rejectedAtBirth(0, 1, 1, "O-011");
        recorder.rejectedAtBirth(1, 1, 0, "O-110");
        recorder.rejectedAtBirth(1, 0, 1, "O-101");
        recorder.rejectedAtBirth(1, 1, 1, "O-111");
        recorder.rejectedAtBirth(2, 0, 0, "O-OOR");

        assertThat(registry.find(BIRTH_REJECTED).counters())
                .as("7 次拦截 → 5 条时间序列。若把三位原值当 tag 就会是 7 条，且上界随客户端传什么都涨")
                .hasSize(5);
        assertThat(count(BIRTH_REJECTED, "shape", "010")).isEqualTo(1.0);
        assertThat(count(BIRTH_REJECTED, "shape", "001")).isEqualTo(1.0);
        assertThat(count(BIRTH_REJECTED, "shape", "011")).isEqualTo(1.0);
        assertThat(count(BIRTH_REJECTED, "shape", "non_binary")).isEqualTo(1.0);
        assertThat(count(BIRTH_REJECTED, "shape", OrderStateMachine.NON_INITIAL))
                .as("⚠️ 少了这条支路，C3 的「今日拦截 N 次」会**漏记六分之三**且不报错")
                .isEqualTo(3.0);
        assertThat(metrics.recentEvents()).as("每次拦截都发一条事件，无一静默").hasSize(7);
    }

    /**
     * ⚠️ <b>契约违约分支：本方法被要求记一笔「闸放行了」的输入。</b>
     *
     * <p>契约上不可达（闸与观测判据同源），但**必须挡**：{@code shape == null} 会一路走到
     * {@code registry.counter(name, "shape", null)}，而 Micrometer 的 {@code ImmutableTag}
     * 对 tag 值 {@code requireNonNull} ⇒ NPE ⇒ 本方法在请求路径上（锁外）⇒ 一次下单 5xx。
     * 见 {@code OrderTransitionRecorder.rejectedAtBirth} 的 javadoc。
     */
    @Test
    @DisplayName("契约违约（放行组合被送进来）：WARN 后**不计数、不抛** —— 抛了就是一次下单 500，兜默认值就是说谎")
    void rejectedAtBirthRefusesToCountAContractViolation() {
        assertThatCode(() -> recorder.rejectedAtBirth(0, 0, 0, ORDER))
                .as("白名单内的组合不该出现在这条路径上，但不许因此把下单打成 5xx")
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.rejectedAtBirth(1, 0, 0, ORDER)).doesNotThrowAnyException();

        assertThat(registry.find(BIRTH_REJECTED).counters())
                .as("不兜一个默认 shape：那会把「代码坏了」静默折进一条正常的时间序列 —— 本仓反复吃的亏")
                .isEmpty();
        assertThat(metrics.recentEvents())
                .as("也不发事件：事件流里不该出现一条含义不明的「拦截」")
                .isEmpty();
    }

    // ===================== 工具 =====================

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 可配置三位的 JdbcTemplate 替身；只实现读回那一句。 */
    private static final class BitsJdbcTemplate extends JdbcTemplate {
        /** 记录调用次数，用来证明「被拒绝的路径一次都不读」。 */
        final AtomicInteger queries = new AtomicInteger();
        volatile Map<String, Object> row = Map.of("PayIf", 1, "DeliveryIf", 0, "ReceiveIf", 0);
        volatile RuntimeException toThrow;

        void bits(int payIf, int deliveryIf, int receiveIf) {
            row = Map.of("PayIf", payIf, "DeliveryIf", deliveryIf, "ReceiveIf", receiveIf);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            queries.incrementAndGet();
            RuntimeException e = toThrow;
            if (e != null) {
                throw e;
            }
            return row;
        }
    }

    /** 记下投递了什么，并**同步跑掉**：本测试断言的是编排，异步性由 {@code OrderStateExecutorTest} 覆盖。 */
    private static final class RecordingExecutor extends OrderStateExecutor {
        final List<String> submitted = new CopyOnWriteArrayList<>();

        RecordingExecutor(MallOrderStateMetrics metrics) {
            super(metrics, 1, 1, 8, Duration.ofSeconds(1));
        }

        @Override
        public void submit(String orderId, Runnable task) {
            submitted.add(orderId);
            task.run();
        }
    }

    /** 记下落库参数；不真的写库（落库本身由 {@code OrderTimelineWriterTest} 覆盖）。 */
    private static final class RecordingTimelineWriter extends OrderTimelineWriter {
        record Row(String orderId, OrderAction action, String from, String to, long occurredAt) {
        }

        final List<Row> rows = new CopyOnWriteArrayList<>();

        RecordingTimelineWriter(JdbcTemplate jdbc, MallOrderStateMetrics metrics) {
            super(jdbc, metrics);
        }

        @Override
        public void write(String orderId, OrderAction action, String fromState, String toState,
                          long occurredAtMillis) {
            rows.add(new Row(orderId, action, fromState, toState, occurredAtMillis));
        }
    }
}
