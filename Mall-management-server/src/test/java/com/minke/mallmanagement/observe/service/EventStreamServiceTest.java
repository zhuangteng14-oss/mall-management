package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.observe.mq.EventPublisher;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.shared.cache.MallCacheMetrics;
import com.minke.mallmanagement.shared.lock.MallLockMetrics;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import com.minke.mallmanagement.shared.web.TraceLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实时日志页产帧（{@link EventStreamService}）的实测 —— 增量、三个汇总、首帧语义。
 *
 * <p>八个源环真建（共用一个 {@code SimpleMeterRegistry}），事件从各环的公开方法发出
 * ⇒ 本文件测的是**「环 → 广播器 → 一帧」这条链**的**形状与增量语义**，
 * 不是数值正确性（那是各 {@code *MetricsTest} 的活）。
 *
 * <p>⚠️ 块 7 · commit 10 起 {@link EventStreamService} 不再直接产帧：它是**工厂**
 * （{@link EventStreamService#newFrameSource()}），帧源（会话）才是产帧的那一个。
 * 本文件因此持有一份会话，所有断言都打在**同一份会话**上（一条测试 = 一条连接）。
 */
class EventStreamServiceTest {

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
    /** 第 9 类源环（D 段 · commit 2）：本文件只要求它**在构造函数里占位**（帧的形状与它无关）。 */
    private final ProtectionMetrics protection = new ProtectionMetrics(registry);

    /** 台账出口的替身（块 7 · commit 8c）。⚠️ 默认 {@code connected()=false} —— 本文件另有一条
     *  用例把它设成 true 来验帧里的 `mq` 字段；其余用例只关心**帧的形状**，不关心 MQ 通不通。 */
    private final EventPublisher publisher = mock(EventPublisher.class);

    /** ⚠️ 广播器认的必须是**上面那几个**环实例（各环的缓冲是实例字段，不是 registry 里的东西）。 */
    private final EventBroadcaster broadcaster = new EventBroadcaster(
            cache, lock, order, stock, audit, notify, sensitive, identity, protection, publisher);
    private final EventStreamService service = new EventStreamService(broadcaster, publisher);

    /** 本用例的那**一条连接**（JUnit 每个用例一个新实例 ⇒ 这条会话不会被别的用例读过）。 */
    private final SseStreamWriter.FrameSource frames = service.newFrameSource();

    @Test
    @DisplayName("首帧：把八环已攒的都送出来，三个汇总都是**本拍**的；第一拍速率报 0（不报无穷）")
    void firstFrameCarriesEverythingAlreadyInTheRings() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, 99);
        stock.deduct(SKU, 2, StockMetrics.Deduct.INSUFFICIENT, 1_500L, 1);
        audit.failed("C-1", "goods.add", "db down", "goods", "M-1:P-1", 1_500L);

        Map<String, Object> frame = frames.frame(0);

        assertThat(frame).containsEntry("seq", 0L).containsEntry("count", 3).containsEntry("cursor", 2L);
        assertThat(frame.get("rate")).as("第一拍没有「上一拍」可比 ⇒ 0.0（不是无穷大）").isEqualTo(0.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> byKind = (Map<String, Object>) frame.get("counts");
        assertThat(byKind).as("9 类键一个不少（顺序 = Kind 声明序；第 9 类 protection 是 D 段新增）").containsOnlyKeys(
                "cache", "lock", "order", "stock", "audit", "notify", "sensitive", "identity", "protection");
        assertThat(byKind).containsEntry("stock", 2).containsEntry("audit", 1).containsEntry("cache", 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> bySeverity = (Map<String, Object>) frame.get("severity");
        assertThat(bySeverity).containsOnlyKeys("info", "warn", "error");
        assertThat(bySeverity).as("ok 一条 = info；insufficient 与 failed 各一条 = warn；无 error")
                .containsEntry("info", 1).containsEntry("warn", 2).containsEntry("error", 0);
    }

    @Test
    @DisplayName("增量：送过的不再送（第二拍 0 条；速率仍是数字，页面画折线要一个数）")
    void secondFrameOnlyCarriesWhatIsNew() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);
        frames.frame(0);

        Map<String, Object> quiet = frames.frame(1);
        assertThat(quiet).containsEntry("count", 0).containsEntry("cursor", 0L);
        assertThat((List<?>) quiet.get("events")).isEmpty();
        assertThat(quiet.get("rate")).as("没有新事件 ⇒ 0.0，但字段仍在").isInstanceOf(Double.class);

        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);
        Map<String, Object> next = frames.frame(2);
        assertThat(next).containsEntry("count", 1).containsEntry("cursor", 1L);
        assertThat((List<?>) next.get("events")).as("只送第 1 号（第 0 号上一拍已送过）").hasSize(1);
    }

    @Test
    @DisplayName("每张卡片带的字段：seq / ts / kind / severity / title / detail / key，一个不少")
    void eventsCarryEverythingTheCardNeeds() {
        stock.deduct("SKU-9", 5, StockMetrics.Deduct.OK, 1_500L, 12);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) frames.frame(0).get("events");

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsOnlyKeys("seq", "ts", "kind", "severity", "title", "detail", "key",
                "objectType", "objectId", "traceId", "batchId",
                // 块 7 · 阶段 3a.3 新增三键（帧 11 → 14）：actor（对象或 null）/ durationUs / fields
                "actor", "durationUs", "fields",
                // 块 7 · 阶段 3a.3 第二步再增一键（帧 14 → 15）：remaining（当时剩余）
                "remaining");
        assertThat(events.get(0)).containsEntry("kind", "stock").containsEntry("severity", "info")
                .containsEntry("title", "库存扣减").containsEntry("key", "SKU-9");
        assertThat(events.get(0)).containsEntry("objectType", "sku").containsEntry("objectId", "SKU-9");
        // 块 7 · 阶段 3a.3 第二步：**当时剩余**进了帧（这一步的正向判据）。
        //   ⚠️ 值取 12 而不是 0/null：0 会让「忘了带」与「当时正好为 0」长得一样。
        assertThat(events.get(0)).as("帧里带出「扣完那一刻还剩 12 件」—— 值刻意非 0，免得与「没带」混淆")
                .containsEntry("remaining", 12);
        // 块 7 · 阶段 3a.3 裁定 ①：没有身份时 actor **整个是 null**（不是 {id:null, role:null} 的对象）——
        //   两者在页面上是两句话（「系统」vs「有一个身份但字段都空」），故钉住这条事件没有身份。
        assertThat(events.get(0).get("actor")).as("没有身份 ⇒ actor 整个为 null，不是一个两个字段都空的对象")
                .isNull();
        assertThat(events.get(0).get("detail").toString()).as("卡片第二行：业务字段 + 溯源").contains("src=stock#0");
        assertThat((Long) events.get(0).get("ts")).as("墙钟，不是 nanoTime").isPositive();
    }

    @Test
    @DisplayName("MDC 里装好身份 ⇒ 帧里 actor 是 {id, role} 对象（裁定 ② 的**正向**那一半）")
    void actorTravelsFromMdcIntoTheFrameAsAnObject() {
        // ⚠️ 上一条用例（eventsCarryEverythingTheCardNeeds）只钉住了「没身份 ⇒ 整个 null」这一半；
        //    「装好身份 ⇒ 出对象」在这一条之前**无任何用例覆盖** —— 而它才是裁定 ② 的形状本身
        //    （对象 vs 平铺两键的取舍就在这一半上）。台账侧的对应两列走 ㉑ 的落库证据，不在此处。
        TraceIdFilter.bind("trace-actor", "batch-actor", "C-42", 2);
        try {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                    (List<Map<String, Object>>) frames.frame(0).get("events");

            assertThat(events).hasSize(1);
            assertThat(events.get(0).get("actor"))
                    .as("裁定 ②：出 {id, role} 对象，不是平铺的 actorId / actorRole 两键")
                    .isEqualTo(Map.of("id", "C-42", "role", 2));
            assertThat((Long) events.get(0).get("durationUs")).as("纳秒入参 → 微秒进帧：1500ns → 1µs")
                    .isEqualTo(1L);
        } finally {
            // MDC 是**线程级静态** —— 不清掉会漏给同一个 JVM 里的后续用例。值为 null 的键会被
            // bind 移除（见 TraceIdFilter.bind 的 javadoc），与「本来就没有上下文」同态。
            TraceIdFilter.bind(null, null, null, null);
        }
    }

    @Test
    @DisplayName("帧里另有一条 traces 数组（块 7 · commit 8）—— 与 events 同为**增量**（送过的不再送）")
    void frameCarriesTraceLogIncrementally() {
        // ⚠️ 先空跑一拍：把存量（同 JVM 里其它用例经 TraceIdFilter 记下的）送掉 —— TraceLog 是**静态**台账，
        //    本用例只能断言自己的增量，不能假定它一开始就是空的。
        assertThat(frames.frame(0)).containsKey("traces");

        TraceLog.record("trace0001", "POST", "/goods/goodsUp", 200, 1_000L, 1_042L);
        List<Map<String, Object>> traces = tracesOf(frames.frame(1));
        assertThat(traces).as("本拍只含本用例新记的那一条").hasSize(1);
        assertThat(traces.get(0)).containsEntry("traceId", "trace0001").containsEntry("path", "/goods/goodsUp")
                .containsEntry("status", 200).containsEntry("ms", 42L);

        assertThat(tracesOf(frames.frame(2))).as("送过的不再送（与 events 同一增量口径）").isEmpty();
    }

    @Test
    @DisplayName("块 7 · commit 8c：帧里的 `mq` = 发布方**实测**的连接状态（页面那个黄点读它）")
    void frameCarriesMqConnectivity() {
        when(publisher.connected()).thenReturn(false);
        assertThat(frames.frame(0)).as("连不上 ⇒ 帧里是 false（页面画黄点 + 那句「不落库」）")
                .containsEntry("mq", false);

        when(publisher.connected()).thenReturn(true);
        assertThat(frames.frame(1)).as("连上了 ⇒ true。⚠️ 只有这一条用例会把替身设成 true")
                .containsEntry("mq", true);
    }

    /**
     * 块 7 · commit 8c 的判据：「演练批次要从**记录事件的那条线程**一路带到帧里」。
     * 这里走的是**源环 → 广播器 → 帧**全程 —— 各 metrics 类在记事件那一刻现读 MDC
     * （{@code TraceIdFilter.currentBatch()}），所以测试只要在同一个线程上把 MDC 设好。
     */
    @Test
    @DisplayName("块 7 · commit 8c：MDC 里的演练批次一路进到卡片（日常流量为 null）")
    void eventCarriesDrillBatchFromMdc() {
        MDC.put(TraceIdFilter.MDC_BATCH_KEY, "batch-8c");
        try {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);
        } finally {
            // ⚠️ 必须清：MDC 是 ThreadLocal，JUnit 默认**同一线程**跑下一个用例
            //    （不共享 MDC 的干净线程只在别处出现）⇒ 漏清会让别的用例读到这个批次。
            MDC.remove(TraceIdFilter.MDC_BATCH_KEY);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) frames.frame(0).get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("batchId", "batch-8c");
    }

    @Test
    @DisplayName("丙（块 7 · commit 10）：两条连接各读各的 —— 后开的那条不会被先开的那条「吃空」")
    void twoSessionsEachSeeTheSameDelta() {
        SseStreamWriter.FrameSource other = service.newFrameSource();
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);
        stock.deduct(SKU, 2, StockMetrics.Deduct.OK, 1_500L, null);

        assertThat(frames.frame(0)).as("先开的那条：两条都在").containsEntry("count", 2);
        assertThat(other.frame(0)).as("后开的那条：**也是**两条。共用一份帧源时这里会是 0 "
                + "（先算的那条把差分量读走了，后开的那条看着像死机）").containsEntry("count", 2);
    }

    @Test
    @DisplayName("裁决 2（块 7 · commit 10）：环满丢了多少**如实上屏** —— 首帧一屏不算丢；跟丢的一拍记 dropped / droppedTotal")
    void ringFullLossIsReportedNotHidden() {
        // ⚠️ 容量与 EventBroadcaster.EVENT_CAPACITY 同值（那个常量是私有的；改容量时这条会红，
        //    红在「count 不再是 2048」上 —— 这正是我们要的那声提醒）。
        int capacity = 2048;

        // 页 B 只负责**推着合并环往前走**；页 A 故意停在原地不读（模拟：标签页被浏览器降频）。
        SseStreamWriter.FrameSource pageB = service.newFrameSource();
        for (int i = 0; i < capacity; i++) {
            stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);
        }
        Map<String, Object> first = frames.frame(0);
        assertThat(first).as("首帧一屏 = 本类的语义，**不是**跟丢").containsEntry("count", capacity);
        assertThat(first).containsEntry("dropped", 0L).containsEntry("droppedTotal", 0L);

        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < capacity + 10; i++) {
                stock.deduct(SKU, 1, StockMetrics.Deduct.OK, 1_500L, null);
            }
            pageB.frame(round);
        }

        Map<String, Object> behind = frames.frame(1);
        assertThat((Long) behind.get("dropped")).as("A 停在原地两圈 ⇒ 丢掉的那一截要报出来").isPositive();
        assertThat(behind.get("droppedTotal")).as("累计 = 本拍这一次（A 只读了两拍）").isEqualTo(behind.get("dropped"));
        assertThat((Long) behind.get("droppedIngest")).as("源环 → 合并环 那一路的**全局**计数，源环也被写爆过 ⇒ 正数")
                .isPositive();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tracesOf(Map<String, Object> frame) {
        return (List<Map<String, Object>>) frame.get("traces");
    }
}
