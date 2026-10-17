package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics.Result;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import com.minke.mallmanagement.observe.service.EventBroadcaster.Severity;
import com.minke.mallmanagement.observe.mq.EventPublisher;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.model.OrderAction;
import com.minke.mallmanagement.shared.cache.MallCacheMetrics;
import com.minke.mallmanagement.shared.lock.MallLockMetrics;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

/**
 * 事件广播器（{@link EventBroadcaster}）的语义实测 —— 九环合并、游标、severity 规则表、
 * 覆盖边界。**不起 Spring、不起 MySQL**（同 {@code MallLockMetricsTest} 的姿势）。
 *
 * <p>⚠️ 本文件测的是**广播器这一层**（归一化 + 游标 + 合并环）。九个源环各自的语义
 * （哪个情形记哪个值）由各自的 {@code *MetricsTest} 负责，这里不重复 —— 第八环「防护」
 * 的源语义在 {@code ProtectionMetricsTest}，本文件只测它**并入后的翻译与配色**。
 */
class EventBroadcasterTest {

    private static final String SKU = "SKU-0001";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MallCacheMetrics cache = new MallCacheMetrics(registry);
    private final MallLockMetrics lock = new MallLockMetrics(registry);
    private final MallOrderStateMetrics order = new MallOrderStateMetrics(registry);
    private final StockMetrics stock = new StockMetrics(registry);
    private final AuditMetrics audit = new AuditMetrics(registry);
    private final NotifyMetrics notify = new NotifyMetrics(registry);
    private final SensitiveMetrics sensitive = new SensitiveMetrics(registry);
    private final IdentityMetrics identity = new IdentityMetrics(registry);
    /** 第 9 类源环（D 段 · commit 2）。 */
    private final ProtectionMetrics protection = new ProtectionMetrics(registry);
    /** 台账出口的替身（块 7 · commit 8c）：本文件测的是**广播器这一层**，不测 MQ。 */
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final EventBroadcaster broadcaster = new EventBroadcaster(
            cache, lock, order, stock, audit, notify, sensitive, identity, protection, publisher);

    // ================= 合并与游标 =================

    @Test
    @DisplayName("九环各发一条 ⇒ poll() 报 9 条、类序 = Kind 声明序；再 poll 报 0（幂等）")
    void pollMergesAllRingsExactlyOnce() {
        emitOneOfEach();

        assertThat(broadcaster.poll()).as("九条一次并入").isEqualTo(9);
        List<ObservedEvent> all = broadcaster.since(-1);
        assertThat(all).extracting(ObservedEvent::kind)
                .as("顺序 = Kind 声明序（页面图例顺序），与入参的九环顺序一致")
                .containsExactly("cache", "lock", "order", "stock", "audit", "notify", "sensitive",
                        "identity", "protection");
        assertThat(all).extracting(ObservedEvent::seq)
                .as("合并环自己的 seq 连续从 0 起").containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);

        assertThat(broadcaster.poll()).as("第二次 poll：没有新事件 ⇒ 0（游标推进到哪就说哪）").isZero();
        assertThat(broadcaster.since(8L)).as("已读到最新 ⇒ 没有增量").isEmpty();
    }

    @Test
    @DisplayName("since(afterSeq) 只给**之后**的：读走的不再重复给")
    void sinceReturnsOnlyNewerThanTheCursor() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null, null);
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null, null);
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null, null);
        broadcaster.poll();

        assertThat(broadcaster.since(-1)).as("首次读（-1）= 要一屏全量").hasSize(3);
        assertThat(broadcaster.since(0L)).as("读到 0 之后：还剩 2 条").hasSize(2);
        assertThat(broadcaster.since(2L)).as("读到最新：0 条").isEmpty();

        stock.deduct(SKU, 1, StockMetrics.Deduct.INSUFFICIENT, 1_500L, null,
                "防超卖拦截：扣减未命中（库存不足或 goods_sku 无此 SKU，需 1 件）");
        stock.deduct(SKU, 1, StockMetrics.Deduct.ERROR, 1_500L, null,
                "库存扣减失败：IllegalStateException：db down");
        broadcaster.poll();

        assertThat(broadcaster.since(2L)).extracting(ObservedEvent::seq)
                .as("新增的两条接在旧 seq 之后（seq 不与源环的混用）").containsExactly(3L, 4L);
        assertThat(broadcaster.latestSeq()).isEqualTo(4L);
    }

    @Test
    @DisplayName("空前：latestSeq() = -1、since(-1) 空 —— 与 since 的入参同一坐标系")
    void emptyBroadcaster() {
        assertThat(broadcaster.latestSeq()).isEqualTo(-1L);
        assertThat(broadcaster.since(-1)).isEmpty();
        assertThat(broadcaster.poll()).isZero();
    }

    // ================= severity 规则表 =================

    @Test
    @DisplayName("severity 规则表：error ← result=error；protect ← 身份拒绝/敏感词/重复提交；warn ← 业务被拒/超时/降级/不足；其余 info")
    void severityFollowsTheOneRuleTable() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.INSUFFICIENT, 1_500L, null,
                "防超卖拦截：扣减未命中（库存不足或 goods_sku 无此 SKU，需 1 件）");      // warn
        stock.release(SKU, 1, StockMetrics.Release.ERROR, 1_500L,
                "库存释放失败：IllegalStateException：db down");           // error
        stock.release(SKU, 1, StockMetrics.Release.MISSING, 1_500L,
                "释放未命中：goods_sku 中不存在该 SKU");         // info（「什么也没发生」）
        audit.written("C-1", "goods.add", "goods", "M-1:P-1", 1_500L);       // info
        audit.failed("C-1", "goods.add", "db down", "goods", "M-1:P-1", 1_500L);  // warn
        notify.sent("email", "order.paid", "C-1", 1_500L);                   // info
        notify.failed("email", "order.paid", "C-1", "smtp 550", 1_500L);     // warn
        // 2026-10-17 用户最终口径：敏感词拒收 / 打码都是**防护层成功防御** ⇒ PROTECT（紫），
        //   业务没有被执行、系统也没崩，绝不能算进「业务失败率」。
        sensitive.masked("comment", 1, 20, "傻逼", 1_500L);                   // protect
        sensitive.rejected("comment", 9, 10, "滚", 1_500L,
                "敏感词拒收：命中比例 90% ≥ 40%（命中 9/10）");                   // protect
        identity.passed("C-1", "/goods/goodsUp", 1_500L);                    // info
        identity.rejected("C-1", "令牌过期", "/goods/goodsUp", 1_500L);        // protect
        protection.blocked("duplicateSubmit", "order", "orderId=O-9 已存在", null,
                "幂等查重命中：同一 OrderId 重复提交");                             // protect
        lock.acquire("order:create", "O-9", MallLockMetrics.Outcome.TIMEOUT, 3_000_000L,
                "取锁超时：等待 3000 ms 未获得");  // warn（fail-open）
        cache.l1Request("goods", "k1", false, 1_500L);                       // info（miss 刻意不染黄）

        broadcaster.poll();

        // ⚠️ 下面的顺序 = **环序**（Kind 声明序），不是**事件发生的先后**：
        //    poll() 是一环一环地并的（cache → lock → order → stock → audit → notify → sensitive
        //    → identity），故同一拍内跨环的事件在合并环里是按环分组的。上面的发事件顺序
        //    （库存 → 审计 → …… → 锁 → 缓存）与断言里的顺序对不上，是**设计如此**，不是巧合。
        //    页面按 seq 排卡片 ⇒ 它看到的同拍顺序就是这个环序（全局时间序只有墙钟 ts 能还原）。
        assertThat(titlesOf(Severity.ERROR)).as("只有 result=error 才红").containsExactly("库存释放失败");
        assertThat(titlesOf(Severity.PROTECT)).as("防护层成功防御（紫）：敏感词 / 身份 / 重复提交，均不算业务失败")
                .containsExactly("敏感词打码", "敏感词拒收", "身份拒绝", "重复提交拦截（未执行）");
        assertThat(titlesOf(Severity.WARN)).as("真正的业务失败 / 降级 / 超时 —— 页面配色靠这一档")
                .containsExactly("取锁超时（fail-open 直行）", "库存不足（未扣，防超卖命中）",
                        "审计写入失败", "通知发送失败");
        assertThat(titlesOf(Severity.INFO)).as("成功路径 + miss/missing（不看这一档数「拦了多少」）")
                .containsExactly("L1 未命中", "释放未命中（无该 SKU 行）", "审计写入",
                        "通知发送", "身份放行");
    }

    @Test
    @DisplayName("订单流转：applied → info、rejected → warn、error → error")
    void orderTransitionSeverity() {
        order.transition(OrderAction.PAY, "waitPay", "waitShip", MallOrderStateMetrics.Result.APPLIED, "O-1", 1_500L, null);
        order.transition(OrderAction.PAY, "waitPay", null, MallOrderStateMetrics.Result.REJECTED, "O-1", 1_500L,
                "订单不存在或已付款");
        order.transition(OrderAction.PAY, "waitPay", null, MallOrderStateMetrics.Result.ERROR, "O-1", 1_500L,
                "数据库错误：IllegalStateException：db down");
        broadcaster.poll();

        assertThat(severityOfAll()).containsExactly("info", "warn", "error");
        assertThat(titlesOf(Severity.INFO).get(0)).as("标题带上「从哪到哪」").isEqualTo("状态流转 waitPay → waitShip");
        assertThat(titlesOf(Severity.WARN).get(0)).as("rejected 时 to 为 null，标题照实写").isEqualTo("状态流转 waitPay → null");
    }

    // ================= 字段透传 =================

    @Test
    @DisplayName("业务键透传 + detail 尾部带 src=环名#源seq（排查时能定位回原环）")
    void keyIsPassedThroughAndDetailCarriesTheSourceRing() {
        stock.deduct(SKU, 3, StockMetrics.Deduct.OK, 1_500L, 97, null);
        stock.deduct(SKU, 4, StockMetrics.Deduct.INSUFFICIENT, 1_500L, 1,
                "防超卖拦截：库存不足（需 4 件，剩余 1 件）");

        broadcaster.poll();
        List<ObservedEvent> events = broadcaster.since(-1);

        assertThat(events).extracting(ObservedEvent::key)
                .as("业务键（SKU）原样带出，但**不进 tag**（基数铁律在源环那侧已保证）")
                .containsExactly(SKU, SKU);
        assertThat(events.get(0).detail())
                .as("detail 里既有业务字段，也有「这条来自哪个环的第几号」").contains("sku=" + SKU)
                .contains("qty=3").contains("result=ok").endsWith("src=stock#0");
        assertThat(events.get(1).detail()).contains("qty=4").endsWith("src=stock#1");
        // 块 7 · 阶段 3a.3：结构化明细 fields 与 detail **同源**（在同一个 Emit 里一起构造）。
        //   pin 住「detail 里 result= 后面那段」与 fields 里的 result 是同一个值 ——
        //   两处分别构造必然漂移，那正是本仓反复吃的亏（见 EventBroadcaster.Emit 的 javadoc）。
        assertThat(events.get(0).fields()).as("stock 的 deduct 构造了结构化明细（不是 null）").isNotNull();
        String detail0 = events.get(0).detail();
        String resultAfterEq = detail0.substring(detail0.indexOf("result=") + "result=".length()).split(" ")[0];
        assertThat(events.get(0).fields().get("result")).as("fields.result 与 detail 里 result= 那段同源")
                .isEqualTo(resultAfterEq);
        assertThat(events).extracting(ObservedEvent::epochMilli).as("墙钟时刻原样透传（源环记的就是墙钟）")
                .allSatisfy(ts -> assertThat(ts).isPositive());
        // 块 7 · 阶段 3a.3 第二步：**当时剩余**从源环一路带到合并环（StockEvent.remaining → ObservedEvent.remaining）。
        //   ⚠️ 这条断言是**正向**的（传了值、值真的过来了）—— 只钉「release 时为 null」那一半的写法
        //   零判别力：把 add(...) 里那个形参写死成 null，只有这一句会红（见 TD 里「负向一半不构成判据」）。
        assertThat(events).extracting(ObservedEvent::remaining)
                .as("扣档 97 / 不足档 1 原样带出（其余八环恒 null，见 ObservedEvent#remaining 的裁定）")
                .containsExactly(97, 1);
        // 块 7 · 阶段 3a.3 后续：原因只进 fields.reason，成功档不写空键
        assertThat(events.get(0).fields()).as("成功档不写 fields.reason（不是写了 null）")
                .doesNotContainKey("reason");
        assertThat(events.get(1).fields()).as("不足档的原因进了 fields（不进顶层、不进 tag）")
                .containsEntry("reason", "防超卖拦截：库存不足（需 4 件，剩余 1 件）");
        assertThat(events.get(1)).extracting(ObservedEvent::fields).as("reason 不新增顶层帧键")
                .isSameAs(events.get(1).fields());
    }

    @Test
    @DisplayName("对象字段逐环补全（块 7 · commit 8）：五环取各自的业务键，审计取 TargetType/TargetId、"
            + "敏感词取命中的词、身份取请求路径、防护取防护开关 key")
    void objectFieldsAreFilledPerRing() {
        emitOneOfEach();
        broadcaster.poll();

        assertThat(broadcaster.since(-1))
                .extracting(ObservedEvent::kind, ObservedEvent::objectType, ObservedEvent::objectId)
                .as("顺序 = 环序（同 poll 的分组口径）")
                .containsExactly(
                        tuple("cache", "cache", "k1"),
                        tuple("lock", "lock", "O-1"),
                        tuple("order", "order", "O-1"),
                        tuple("stock", "sku", SKU),
                        tuple("audit", "order", "O-1"),        // ← 补的就是这两列
                        tuple("notify", "notify", "C-1"),
                        tuple("sensitive", "sensitive", null), // ← 一条也没命中 ⇒ 没有「命中的词」
                        tuple("identity", "identity", "/order/orderReceive"),  // ← 补的就是这一列
                        // 防护环（D 段）：objectId 取的是**防护开关 key**，不是业务键 ——
                        // 防护事件没有业务键（理由写在 EventBroadcaster.ingestProtection 的注释里）。
                        tuple("protection", "protection", "circuitBreak"));
    }

    @Test
    @DisplayName("traceId 在**记录那一刻**取（MDC）—— 不是在 poll() 那一刻取（流线程上 MDC 是空的）")
    void traceIdIsCapturedAtRecordTime() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace0001");
        try {
            emitOneOfEach();
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
        // 到这里 MDC 已经清空 —— 若取值点落在 poll() / since() 上，下面每一条都会是 null。
        broadcaster.poll();

        assertThat(broadcaster.since(-1)).extracting(ObservedEvent::traceId)
                .as("九条都带上了记录那一刻的 traceId").containsOnly("trace0001");
    }

    // ================= 第 9 类：防护（D 段 · commit 2） =================

    @Test
    @DisplayName("防护环翻译：blocked 按 key 分标题（限流 / 熔断）、四档 protect、closed 落 info")
    void protectionRingIsTranslatedPerGate() {
        // 两条 blocked 的 key 不同 —— 标题必须跟着分岔：两者都是 protect，但排障方向相反
        // （限流查流量、熔断查下游）。合并成一个标题就等于把两条线索糊成一条。
        protection.blocked("rateLimit", "inbound", "path=/goods/goodsSelectHome 每 1s 放 200 条，这一拍没有余量", null,
                "入站限流：未取得令牌（limitForPeriod=200）");
        protection.blocked("circuitBreak", "mysql", "circuitBreaker=mallMysqlWrite 已打开：本次调用未执行", null,
                "熔断开路：熔断器 mallMysqlWrite 已打开，本次调用未执行");
        protection.state(Result.OPENED, "circuitBreak", "mysql", "circuitBreaker=mallMysqlWrite CLOSED → OPEN 失败率=62.5%",
                "熔断打开：失败率 62.5%，慢调用率 0%");
        protection.state(Result.HALF_OPEN, "circuitBreak", "mysql", "circuitBreaker=mallMysqlWrite OPEN → HALF_OPEN",
                "熔断半开：等待期结束，放行试探请求");
        protection.state(Result.CLOSED, "circuitBreak", "mysql", "circuitBreaker=mallMysqlWrite HALF_OPEN → CLOSED", null);
        protection.shed("poolIsolation", "pool", "pool=observe-stream active=32 ⇒ 拒绝（不排队）",
                "线程池已满：任务未排队、当场拒绝（active=32，poolSize=32，queue=64）");
        broadcaster.poll();

        assertThat(severityOfAll())
                .as("四档 protect，第五档 closed（恢复）刻意落 info —— 见类注释第 2 件")
                .containsExactly("protect", "protect", "protect", "protect", "info", "protect");
        assertThat(titlesOf(Severity.PROTECT)).containsExactly(
                "入站限流拦下", "熔断开路拒绝（未执行）", "熔断打开", "熔断半开（放行试探）", "线程池挤出（未排队）");
        assertThat(titlesOf(Severity.INFO)).containsExactly("熔断恢复关闭");

        assertThat(broadcaster.since(-1)).extracting(ObservedEvent::kind).containsOnly("protection");
        assertThat(broadcaster.since(-1)).extracting(ObservedEvent::objectType).containsOnly("protection");
        assertThat(broadcaster.since(-1)).extracting(ObservedEvent::objectId)
                .as("objectId = 防护开关 key（rateLimit / circuitBreak / poolIsolation）")
                .containsExactly("rateLimit", "circuitBreak", "circuitBreak", "circuitBreak", "circuitBreak", "poolIsolation");
        assertThat(broadcaster.since(-1).get(0).detail())
                .as("源环的 scope 与自由文本明细都进了 detail，尾部照旧带 src=环名#源seq")
                .contains("protection=rateLimit").contains("scope=inbound").endsWith("src=protection#0");
    }

    @Test
    @DisplayName("防护环的第一条 blocked 的 key 认不出时：**不硬套**某个闸名，照实写 key")
    void protectionBlockedWithUnknownGateDoesNotGuess() {
        protection.blocked("degrade", "somewhere", "一个本段不该出现的 key", null,
                "熔断打开：熔断器 degrade 已打开，本次调用未执行");
        broadcaster.poll();

        assertThat(titlesOf(Severity.PROTECT)).as("不写「入站限流拦下」也不写「熔断开路拒绝」—— 两者都是猜")
                .containsExactly("防护拦下：degrade");
        assertThat(severityOfAll()).containsExactly("protect");
    }

    // ================= 失败原因（块 7 · 阶段 3a.3 后续） =================

    @Test
    @DisplayName("六环失败档的 reason 都落进 fields；成功档不写这个键；order 异步仍以 shape 为原因")
    void reasonIsCarriedInFieldsAcrossRings() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.INSUFFICIENT, 1_500L, 0,
                "防超卖拦截：库存不足（需 1 件，剩余 0 件）");
        lock.acquire("order:create", "O-1", MallLockMetrics.Outcome.TIMEOUT, 1_000_000L,
                "取锁超时：等待 1000 ms 未获得");
        lock.acquire("order:create", "O-2", MallLockMetrics.Outcome.ACQUIRED, 1_000_000L, null);
        cache.l2Request("goods", "k1", MallCacheMetrics.L2Result.ERROR, 1_500L,
                "L2 读取失败：IllegalStateException：db down");
        order.timelineWrite(false, "O-1", null, "时间线写入失败：IllegalStateException：db down");
        order.asyncRejected(MallOrderStateMetrics.RejectReason.QUEUE_FULL, "O-2");
        sensitive.rejected("comment", 9, 10, "滚", 1_500L,
                "敏感词拒收：命中比例 90% ≥ 40%（命中 9/10）");
        protection.blocked("rateLimit", "inbound", "d", null, "入站限流：未取得令牌（limitForPeriod=200）");
        broadcaster.poll();

        List<ObservedEvent> events = broadcaster.since(-1);
        assertThat(events).extracting(ObservedEvent::kind)
                .as("环序：cache 1 + lock 2 + order 2 + stock 1 + sensitive 1 + protection 1")
                .containsExactly("cache", "lock", "lock", "order", "order", "stock", "sensitive", "protection");

        assertThat(events.get(0).fields()).as("cache 的 L2 读失败原因")
                .containsEntry("reason", "L2 读取失败：IllegalStateException：db down");
        assertThat(events.get(1).fields()).as("lock timeout 原因")
                .containsEntry("reason", "取锁超时：等待 1000 ms 未获得");
        assertThat(events.get(2).fields()).as("lock acquired（成功档）不写 reason 键")
                .doesNotContainKey("reason");
        assertThat(events.get(3).fields()).as("order 时间线写失败原因（来自新加的 reason）")
                .containsEntry("reason", "时间线写入失败：IllegalStateException：db down");
        assertThat(events.get(4).fields()).as("order 异步被拒仍以 shape 为准（queue_full），不混进空原因")
                .containsEntry("reason", "queue_full");
        assertThat(events.get(5).fields()).as("stock 不足原因")
                .containsEntry("reason", "防超卖拦截：库存不足（需 1 件，剩余 0 件）");
        assertThat(events.get(6).fields()).as("sensitive 拒收原因")
                .containsEntry("reason", "敏感词拒收：命中比例 90% ≥ 40%（命中 9/10）");
        assertThat(events.get(7).fields()).as("protection 限流原因")
                .containsEntry("reason", "入站限流：未取得令牌（limitForPeriod=200）");

        // 原因绝不进 tag（基数铁律由源环那侧保证，这里钉住出口没多出一条时间序列）
        assertThat(registry.find("mall.stock.deduct")
                .tag("reason", "防超卖拦截：库存不足（需 1 件，剩余 0 件）").counters())
                .as("reason 不进 tag：按 reason 查时间序列必须查不到")
                .isEmpty();
    }

    // ================= 覆盖边界 =================

    @Test
    @DisplayName("⚠️ 一拍内超出容量：源环自己只留最近 2048 条，合并环照抄该语义（丢的是最早的）")
    void mergesWhateverTheSourceRingStillHolds() {
        // ⚠️ 数值与各环的 EVENT_CAPACITY 同值（块 7 · commit 10 裁决 2：512 → 2048）。
        //    改容量时这条会红在「并入的不是 2048」上 —— 那正是我们要的那声提醒。
        int capacity = 2048;
        int extra = 88;
        for (int i = 0; i < capacity + extra; i++) {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null, null);
        }

        assertThat(broadcaster.poll()).as("并入的是**源环还剩的** %d 条，不是全部", capacity).isEqualTo(capacity);
        List<ObservedEvent> merged = broadcaster.since(-1);
        assertThat(merged).hasSize(capacity);
        assertThat(merged.get(0).detail()).as("最早的那 %d 条已被源环覆盖（这是源环的语义，不另设口径）", extra)
                .endsWith("src=stock#" + extra);
        assertThat(merged.get(capacity - 1).detail()).endsWith("src=stock#" + (capacity + extra - 1));
    }

    @Test
    @DisplayName("合并环自身写满后覆盖最旧：since(-1) 仍只有 2048 条 —— 不报错、也不补历史")
    void mergeRingIsBoundedAtCapacity() {
        int capacity = 2048;
        int extra = 88;
        for (int i = 0; i < capacity + extra; i++) {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null, null);
            broadcaster.poll();          // 逐条推进，确保每条都真进了合并环
        }

        List<ObservedEvent> merged = broadcaster.since(-1);
        assertThat(merged).as("容量有界：只剩最近 %d 条", capacity).hasSize(capacity);
        assertThat(merged.get(0).seq()).as("最旧的那条是第 %d 个（合并环的 seq 坐标系）", extra).isEqualTo(extra);
        assertThat(merged.get(capacity - 1).seq()).isEqualTo(capacity + extra - 1L);
        assertThat(broadcaster.since(extra - 1L)).as("被覆盖掉的区间：不报错也不补（覆盖即淘汰）").hasSize(capacity);
    }

    // ================= 工具 =================

    /** 九个环各发一条（值都取「成功路径」，故本用例只关心**类序**）。 */
    private void emitOneOfEach() {
        cache.l1Request("goods", "k1", true, 1_500L);
        lock.acquire("order:create", "O-1", MallLockMetrics.Outcome.ACQUIRED, 1_000_000L, null);
        order.transition(OrderAction.CREATE, "none", "waitPay", MallOrderStateMetrics.Result.APPLIED, "O-1", 1_500L, null);
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null, null);
        audit.written("C-1", "order.add", "order", "O-1", 1_500L);
        notify.sent("email", "order.created", "C-1", 1_500L);
        sensitive.masked("comment", 0, 20, null, 1_500L);
        identity.passed("C-1", "/order/orderReceive", 1_500L);
        // 第 9 环取 closed（熔断恢复关闭）而不是 blocked：它是防护环里**落 info 的那一档**
        // （⚠️ 阶段 2b 起落 info 的有两档，另一档是 retry_success —— 这里取 closed 是因为它
        //   属于熔断三态那一族、不引入新标题分组），于是这条共用夹具不会把 severity 规则表
        //   那几条断言搅乱（那几条按档位分组断言标题）。
        protection.state(Result.CLOSED, "circuitBreak", "mysql", "circuitBreaker=mallMysqlWrite HALF_OPEN → CLOSED", null);
    }

    private List<String> titlesOf(Severity severity) {
        return broadcaster.since(-1).stream()
                .filter(e -> severity.tag.equals(e.severity()))
                .map(ObservedEvent::title)
                .toList();
    }

    private List<String> severityOfAll() {
        return broadcaster.since(-1).stream().map(ObservedEvent::severity).toList();
    }
}
