package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.observe.metrics.AuditMetrics;
import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.observe.metrics.NotifyMetrics;
import com.minke.mallmanagement.observe.metrics.SensitiveMetrics;
import com.minke.mallmanagement.observe.metrics.StockMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.shared.cache.MallCacheMetrics;
import com.minke.mallmanagement.shared.lock.MallLockMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时日志页产帧（{@link EventStreamService}）的实测 —— 增量、三个汇总、首帧语义。
 *
 * <p>八个源环真建（共用一个 {@code SimpleMeterRegistry}），事件从各环的公开方法发出
 * ⇒ 本文件测的是**「环 → 广播器 → 一帧」这条链**的**形状与增量语义**，
 * 不是数值正确性（那是各 {@code *MetricsTest} 的活）。
 *
 * <p>{@code interval-ms = 0} ⇒ {@link FramePacer} 窗口 0 ⇒ 每次 {@code frame()} 都真算。
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

    /** ⚠️ 广播器认的必须是**上面那几个**环实例（各环的缓冲是实例字段，不是 registry 里的东西）。 */
    private final EventBroadcaster broadcaster = new EventBroadcaster(
            cache, lock, order, stock, audit, notify, sensitive, identity);
    private final EventStreamService service = new EventStreamService(broadcaster, 0);

    @Test
    @DisplayName("首帧：把八环已攒的都送出来，三个汇总都是**本拍**的；第一拍速率报 0（不报无穷）")
    void firstFrameCarriesEverythingAlreadyInTheRings() {
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        stock.deduct(SKU, 2, StockMetrics.Deduct.INSUFFICIENT);
        audit.failed("C-1", "goods.add", "db down");

        Map<String, Object> frame = service.frame(0);

        assertThat(frame).containsEntry("seq", 0L).containsEntry("count", 3).containsEntry("cursor", 2L);
        assertThat(frame.get("rate")).as("第一拍没有「上一拍」可比 ⇒ 0.0（不是无穷大）").isEqualTo(0.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> byKind = (Map<String, Object>) frame.get("counts");
        assertThat(byKind).as("8 类键一个不少（顺序 = Kind 声明序）").containsOnlyKeys(
                "cache", "lock", "order", "stock", "audit", "notify", "sensitive", "identity");
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
        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        service.frame(0);

        Map<String, Object> quiet = service.frame(1);
        assertThat(quiet).containsEntry("count", 0).containsEntry("cursor", 0L);
        assertThat((List<?>) quiet.get("events")).isEmpty();
        assertThat(quiet.get("rate")).as("没有新事件 ⇒ 0.0，但字段仍在").isInstanceOf(Double.class);

        stock.deduct(SKU, 1, StockMetrics.Deduct.OK);
        Map<String, Object> next = service.frame(2);
        assertThat(next).containsEntry("count", 1).containsEntry("cursor", 1L);
        assertThat((List<?>) next.get("events")).as("只送第 1 号（第 0 号上一拍已送过）").hasSize(1);
    }

    @Test
    @DisplayName("每张卡片带的字段：seq / ts / kind / severity / title / detail / key，一个不少")
    void eventsCarryEverythingTheCardNeeds() {
        stock.deduct("SKU-9", 5, StockMetrics.Deduct.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) service.frame(0).get("events");

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsOnlyKeys("seq", "ts", "kind", "severity", "title", "detail", "key");
        assertThat(events.get(0)).containsEntry("kind", "stock").containsEntry("severity", "info")
                .containsEntry("title", "库存扣减").containsEntry("key", "SKU-9");
        assertThat(events.get(0).get("detail").toString()).as("卡片第二行：业务字段 + 溯源").contains("src=stock#0");
        assertThat((Long) events.get(0).get("ts")).as("墙钟，不是 nanoTime").isPositive();
    }
}
