package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;
import com.minke.mallmanagement.observe.service.EventBroadcaster.Severity;
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

/**
 * 事件广播器（{@link EventBroadcaster}）的语义实测 —— 八环合并、游标、severity 规则表、
 * 覆盖边界。**不起 Spring、不起 MySQL**（同 {@code MallLockMetricsTest} 的姿势）。
 *
 * <p>⚠️ 本文件测的是**广播器这一层**（归一化 + 游标 + 合并环）。八个源环各自的语义
 * （哪个情形记哪个值）由各自的 {@code *MetricsTest} 负责，这里不重复。
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
    private final EventBroadcaster broadcaster = new EventBroadcaster(
            cache, lock, order, stock, audit, notify, sensitive, identity);

    // ================= 合并与游标 =================

    @Test
    @DisplayName("八环各发一条 ⇒ poll() 报 8 条、类序 = Kind 声明序；再 poll 报 0（幂等）")
    void pollMergesAllEightRingsExactlyOnce() {
        emitOneOfEach();

        assertThat(broadcaster.poll()).as("八条一次并入").isEqualTo(8);
        List<ObservedEvent> all = broadcaster.since(-1);
        assertThat(all).extracting(ObservedEvent::kind)
                .as("顺序 = Kind 声明序（页面图例顺序），与入参的八环顺序一致")
                .containsExactly("cache", "lock", "order", "stock", "audit", "notify", "sensitive", "identity");
        assertThat(all).extracting(ObservedEvent::seq)
                .as("合并环自己的 seq 连续从 0 起").containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);

        assertThat(broadcaster.poll()).as("第二次 poll：没有新事件 ⇒ 0（游标推进到哪就说哪）").isZero();
        assertThat(broadcaster.since(7L)).as("已读到最新 ⇒ 没有增量").isEmpty();
    }

    @Test
    @DisplayName("since(afterSeq) 只给**之后**的：读走的不再重复给")
    void sinceReturnsOnlyNewerThanTheCursor() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        broadcaster.poll();

        assertThat(broadcaster.since(-1)).as("首次读（-1）= 要一屏全量").hasSize(3);
        assertThat(broadcaster.since(0L)).as("读到 0 之后：还剩 2 条").hasSize(2);
        assertThat(broadcaster.since(2L)).as("读到最新：0 条").isEmpty();

        stock.deduct(SKU, 1, StockMetrics.Deduct.INSUFFICIENT);
        stock.deduct(SKU, 1, StockMetrics.Deduct.ERROR);
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
    @DisplayName("severity 规则表：error ← result=error；warn ← 被挡/被拒/超时/降级/不足；其余 info")
    void severityFollowsTheOneRuleTable() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.INSUFFICIENT);      // warn
        stock.release(SKU, 1, StockMetrics.Release.ERROR);           // error
        stock.release(SKU, 1, StockMetrics.Release.MISSING);         // info（「什么也没发生」）
        audit.written("C-1", "goods.add", "goods", "M-1:P-1");       // info
        audit.failed("C-1", "goods.add", "db down", "goods", "M-1:P-1");  // warn
        notify.sent("email", "order.paid", "C-1");                   // info
        notify.failed("email", "order.paid", "C-1", "smtp 550");     // warn
        sensitive.masked("comment", 1, 20, "傻逼");                   // info
        sensitive.rejected("comment", 9, 10, "滚");                   // warn
        identity.passed("C-1", "/goods/goodsUp");                    // info
        identity.rejected("C-1", "令牌过期", "/goods/goodsUp");        // warn
        lock.acquire("order:create", "O-9", MallLockMetrics.Outcome.TIMEOUT, 3_000_000L);  // warn（fail-open）
        cache.l1Request("goods", "k1", false);                       // info（miss 刻意不染黄）

        broadcaster.poll();

        // ⚠️ 下面的顺序 = **环序**（Kind 声明序），不是**事件发生的先后**：
        //    poll() 是一环一环地并的（cache → lock → order → stock → audit → notify → sensitive
        //    → identity），故同一拍内跨环的事件在合并环里是按环分组的。上面的发事件顺序
        //    （库存 → 审计 → …… → 锁 → 缓存）与断言里的顺序对不上，是**设计如此**，不是巧合。
        //    页面按 seq 排卡片 ⇒ 它看到的同拍顺序就是这个环序（全局时间序只有墙钟 ts 能还原）。
        assertThat(titlesOf(Severity.ERROR)).as("只有 result=error 才红").containsExactly("库存释放失败");
        assertThat(titlesOf(Severity.WARN)).as("被挡 / 被拒 / 超时 / 不足 —— 页面配色靠这一档")
                .containsExactly("取锁超时（fail-open 直行）", "库存不足（未扣，防超卖命中）",
                        "审计写入失败", "通知发送失败", "敏感词拒收", "身份拒绝");
        assertThat(titlesOf(Severity.INFO)).as("成功路径 + miss/missing（不看这一档数「拦了多少」）")
                .containsExactly("L1 未命中", "释放未命中（无该 SKU 行）", "审计写入",
                        "通知发送", "敏感词打码", "身份放行");
    }

    @Test
    @DisplayName("订单流转：applied → info、rejected → warn、error → error")
    void orderTransitionSeverity() {
        order.transition(OrderAction.PAY, "waitPay", "waitShip", MallOrderStateMetrics.Result.APPLIED, "O-1");
        order.transition(OrderAction.PAY, "waitPay", null, MallOrderStateMetrics.Result.REJECTED, "O-1");
        order.transition(OrderAction.PAY, "waitPay", null, MallOrderStateMetrics.Result.ERROR, "O-1");
        broadcaster.poll();

        assertThat(severityOfAll()).containsExactly("info", "warn", "error");
        assertThat(titlesOf(Severity.INFO).get(0)).as("标题带上「从哪到哪」").isEqualTo("状态流转 waitPay → waitShip");
        assertThat(titlesOf(Severity.WARN).get(0)).as("rejected 时 to 为 null，标题照实写").isEqualTo("状态流转 waitPay → null");
    }

    // ================= 字段透传 =================

    @Test
    @DisplayName("业务键透传 + detail 尾部带 src=环名#源seq（排查时能定位回原环）")
    void keyIsPassedThroughAndDetailCarriesTheSourceRing() {
        stock.deduct(SKU, 3, StockMetrics.Deduct.OK);
        stock.deduct(SKU, 4, StockMetrics.Deduct.INSUFFICIENT);

        broadcaster.poll();
        List<ObservedEvent> events = broadcaster.since(-1);

        assertThat(events).extracting(ObservedEvent::key)
                .as("业务键（SKU）原样带出，但**不进 tag**（基数铁律在源环那侧已保证）")
                .containsExactly(SKU, SKU);
        assertThat(events.get(0).detail())
                .as("detail 里既有业务字段，也有「这条来自哪个环的第几号」").contains("sku=" + SKU)
                .contains("qty=3").contains("result=ok").endsWith("src=stock#0");
        assertThat(events.get(1).detail()).contains("qty=4").endsWith("src=stock#1");
        assertThat(events).extracting(ObservedEvent::epochMilli).as("墙钟时刻原样透传（源环记的就是墙钟）")
                .allSatisfy(ts -> assertThat(ts).isPositive());
    }

    @Test
    @DisplayName("对象字段逐环补全（块 7 · commit 8）：五环取各自的业务键，审计取 TargetType/TargetId、"
            + "敏感词取命中的词、身份取请求路径")
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
                        tuple("identity", "identity", "/order/orderReceive"));  // ← 补的就是这一列
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
                .as("八条都带上了记录那一刻的 traceId").containsOnly("trace0001");
    }

    // ================= 覆盖边界 =================

    @Test
    @DisplayName("⚠️ 一拍内超出容量：源环自己只留最近 512 条，合并环照抄该语义（丢的是最早的）")
    void mergesWhateverTheSourceRingStillHolds() {
        int total = 600;
        for (int i = 0; i < total; i++) {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        }

        assertThat(broadcaster.poll()).as("并入的是**源环还剩的** 512 条，不是 600").isEqualTo(512);
        List<ObservedEvent> merged = broadcaster.since(-1);
        assertThat(merged).hasSize(512);
        assertThat(merged.get(0).detail()).as("最早的那 88 条已被源环覆盖（这是源环的语义，不另设口径）")
                .endsWith("src=stock#88");
        assertThat(merged.get(511).detail()).endsWith("src=stock#599");
    }

    @Test
    @DisplayName("合并环自身写满后覆盖最旧：since(-1) 仍只有 512 条 —— 不报错、也不补历史")
    void mergeRingIsBoundedAt512() {
        for (int i = 0; i < 512 + 88; i++) {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
            broadcaster.poll();          // 逐条推进，确保每条都真进了合并环
        }

        List<ObservedEvent> merged = broadcaster.since(-1);
        assertThat(merged).as("容量有界：只剩最近 512 条").hasSize(512);
        assertThat(merged.get(0).seq()).as("最旧的那条是第 88 个（合并环的 seq 坐标系）").isEqualTo(88L);
        assertThat(merged.get(511).seq()).isEqualTo(599L);
        assertThat(broadcaster.since(87L)).as("被覆盖掉的区间：不报错也不补（覆盖即淘汰）").hasSize(512);
    }

    // ================= 工具 =================

    /** 八个环各发一条（值都取「成功路径」，故本用例只关心**类序**）。 */
    private void emitOneOfEach() {
        cache.l1Request("goods", "k1", true);
        lock.acquire("order:create", "O-1", MallLockMetrics.Outcome.ACQUIRED, 1_000_000L);
        order.transition(OrderAction.CREATE, "none", "waitPay", MallOrderStateMetrics.Result.APPLIED, "O-1");
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        audit.written("C-1", "order.add", "order", "O-1");
        notify.sent("email", "order.created", "C-1");
        sensitive.masked("comment", 0, 20, null);
        identity.passed("C-1", "/order/orderReceive");
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
