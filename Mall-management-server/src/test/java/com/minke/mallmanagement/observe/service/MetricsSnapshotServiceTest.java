package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标面板产帧（{@link MetricsSnapshotService}）的差分语义实测。
 *
 * <h2>【本文件钉三件容易被「看起来对」骗过去的事】</h2>
 * <ol>
 *   <li><b>首帧一律 0</b>：Micrometer 的计数器是**自进程启动以来**的累计，直接当本拍用会画出
 *       一根假尖峰（进程跑了三天 ⇒ 首屏 QPS「几十万」）；</li>
 *   <li><b>中途新出现的计数器 = 它现在的值就是本拍增量**：Micrometer 的计数器从 0 起，
 *       第一见它就说明这几下确实是本拍发生的；</li>
 *   <li><b>占比的分母是**本拍**的</b>：没有请求时返回 {@code null}（「没有请求」与「0% 出错」
 *       是两件事，页面要分开显示）。</li>
 * </ol>
 *
 * <p>{@code GoodsDao} 用替身（本文件不碰 MySQL）。⚠️ 块 7 · commit 10 起
 * {@link MetricsSnapshotService} 是**工厂**：差分状态（上一拍读数）在**会话**里，
 * 由 {@link MetricsSnapshotService#newFrameSource()} 每连接现建 ⇒ 本文件所有断言打在
 * **同一份会话**上（一条测试 = 一条连接），另有一例专验「两条连接各差分各的」。
 *
 * <p>⚠️ 本文件**不**验健康度阈值的数值（那是「演示口径、待裁」，见类注释）——
 * 只验「断流 / 不断流」与「留空 / 不留空」这两类**结构**差异。
 */
class MetricsSnapshotServiceTest {

    private static final String L1 = "mall.cache.l1.requests";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GoodsDao goodsDao = mock(GoodsDao.class);
    private final MetricsSnapshotService service = new MetricsSnapshotService(registry, goodsDao);

    /** 本用例的那一条连接（差分状态就装在它里面）。 */
    private final SseStreamWriter.FrameSource frames = service.newFrameSource();

    @Test
    @DisplayName("首帧所有差分量记 0：累计值不当本拍用；命中率因分母为 0 而**留空**（不是 0%）")
    void firstFrameIsAllZeros() {
        registry.counter(L1, "result", "hit").increment(5);
        registry.counter(L1, "result", "miss").increment(2);
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());

        Map<String, Object> frame = frames.frame(0);

        assertThat(cacheOf(frame)).containsEntry("hit", 0L).containsEntry("miss", 0L);
        assertThat(cacheOf(frame).get("hitRate")).as("没有请求 ⇒ null（不是 0.0 —— 两者页面显示不同）").isNull();
        assertThat(requestsOf(frame)).containsEntry("count", 0L).containsEntry("qps", 0.0);
        assertThat(requestsOf(frame).get("errorRate")).as("没请求 ⇒ 出错比例无从谈起").isNull();
        assertThat(requestsOf(frame).get("meanMs")).as("没请求 ⇒ 均值无从谈起").isNull();
        assertThat(requestsOf(frame).get("p95Ms")).as("没开分位 / 没有计时器 ⇒ null").isNull();
        assertThat(frame).containsEntry("seq", 0L);
    }

    @Test
    @DisplayName("第二帧起报**增量**：计数器只增不减，面板要的是「这一拍发生了什么」")
    void secondFrameReportsTheDelta() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());
        registry.counter(L1, "result", "hit").increment(5);
        registry.counter(L1, "result", "miss").increment(2);
        frames.frame(0);

        registry.counter(L1, "result", "hit").increment(3);
        Map<String, Object> frame = frames.frame(1);

        assertThat(cacheOf(frame)).as("本拍只命中 3 次（不是累计 8 次）")
                .containsEntry("hit", 3L).containsEntry("miss", 0L).containsEntry("hitRate", 1.0);
    }

    @Test
    @DisplayName("中途新出现的计数器：**它现在的值就是本拍增量**（不当作「没有上一拍」而丢掉）")
    void newlyAppearingCounterCountsAsItsCurrentValue() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());
        frames.frame(0);                                  // 先拍一次，进 primed 状态

        registry.counter("mall.audit.writes", "result", "failed").increment(4);
        Map<String, Object> frame = frames.frame(1);

        assertThat(protectionOf(frame, "auditFailed")).as("新计数器第一见 ≠ 没有增量").isEqualTo(4L);
        assertThat(protectionOf(frame, "cacheHit")).as("同类里没新值的照旧 0").isZero();
    }

    @Test
    @DisplayName("防护 8 项：固定 8 行、顺序与 tag 固定（页面柱子顺序不许随数据变）")
    void protectionsAreAlwaysTheSameEightRows() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());

        Map<String, Object> frame = frames.frame(0);

        assertThat(rowsOf(frame, "protections")).extracting(r -> r.get("tag")).containsExactly(
                "cacheHit", "lockTimeout", "orderRejected", "stockInsufficient",
                "auditFailed", "notifyFailed", "sensitiveHit", "identityRejected");
        assertThat(rowsOf(frame, "protections")).extracting(r -> r.get("label"))
                .allSatisfy(label -> assertThat(label).as("标签是给页面直接显示的中文").isInstanceOf(String.class));
    }

    @Test
    @DisplayName("库存水位：占比由 Stock / BaselineStock 现算；基线缺失 ⇒ 占比**留空**（不当 0%）")
    void stockRowsCarryRatioOrNull() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of(
                stockRow("SKU-1", "苹果", "500g", 4, 20),
                stockRow("SKU-2", "香蕉", "1kg", 3, null)));

        Map<String, Object> frame = frames.frame(0);

        assertThat(stockOf(frame)).containsEntry("available", true);
        List<Map<String, Object>> rows = rowsOf(stockOf(frame), "rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("sku", "SKU-1").containsEntry("goodName", "苹果")
                .containsEntry("stock", 4L).containsEntry("baseline", 20L).containsEntry("ratio", 0.2);
        assertThat(rows.get(1)).containsEntry("sku", "SKU-2").containsEntry("stock", 3L);
        assertThat(rows.get(1).get("baseline")).as("基线没准备 ⇒ 留空（不是 0）").isNull();
        assertThat(rows.get(1).get("ratio")).as("分母缺失 ⇒ 占比留空（不是 0%）").isNull();
    }

    @Test
    @DisplayName("⚠️ 库存读失败**不断流**：显式 available=false + 健康度降为「注意」——半死数据比断流更坏")
    void stockFailureIsExplicitNotSilent() {
        when(goodsDao.selectStockLevels(anyInt())).thenThrow(new IllegalStateException("db down"));

        Map<String, Object> frame = frames.frame(0);

        assertThat(stockOf(frame)).as("显式标注不可用，而不是把上一拍的值当新的").containsEntry("available", false);
        assertThat(rowsOf(stockOf(frame), "rows")).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) frame.get("health");
        assertThat(health).containsEntry("level", "warn");
        assertThat((List<?>) health.get("reasons")).as("页面要能说出为什么黄").isNotEmpty();
    }

    // ================= SSE 长连接不参与延迟（commit 8b2） =================

    @Test
    @DisplayName("⚠️ 只开了 SSE 长连接：count/qps 照记，但 P99 / 均值 / max 一律**留空**（不是 0，也不是连接存活时长）")
    void sseOnlyTrafficLeavesLatencyFieldsEmpty() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());
        Timer stream = Timer.builder("http.server.requests")
                .tags("uri", "/admin/observe/metrics/stream", "status", "200")
                .register(registry);
        frames.frame(0);                                  // 先拍一次，进 primed 状态

        stream.record(26_843, TimeUnit.MILLISECONDS);      // 客户端挂了 26.8 秒才断开
        Map<String, Object> frame = frames.frame(1);

        assertThat(requestsOf(frame)).as("SSE 也是真请求 ⇒ 计数那一路照记").containsEntry("count", 1L);
        assertThat((Double) requestsOf(frame).get("qps"))
                .as("qps ≠ 0 —— 正是截图那一拍（所以「QPS=0 才留空」单用抓不到）").isGreaterThan(0.0);
        assertThat(requestsOf(frame).get("p95Ms")).as("0 个「能当延迟看的」样本 ⇒ 留空（页面显示 —）").isNull();
        assertThat(requestsOf(frame).get("p99Ms")).as("26,843 ms 是连接存活时长，不是延迟").isNull();
        assertThat(requestsOf(frame).get("meanMs")).as("均值同样无从谈起（此前它会把 18 秒的均摊报上来）").isNull();
        assertThat(requestsOf(frame).get("maxMs")).isNull();
        assertThat(healthOf(frame)).as("这一拍不该红").containsEntry("level", "ok");
    }

    @Test
    @DisplayName("⚠️ 真业务与 SSE 并存：P99 只由业务请求算 —— 长连接的 26.8 秒不进分位，也不点亮 P99 红")
    void sseLongDurationDoesNotPolluteLatency() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());
        Timer business = Timer.builder("http.server.requests")
                .tags("uri", "/order/orderAdd", "status", "200")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Timer stream = Timer.builder("http.server.requests")
                .tags("uri", "/admin/observe/events/stream", "status", "200")
                .register(registry);
        frames.frame(0);

        business.record(40, TimeUnit.MILLISECONDS);
        business.record(60, TimeUnit.MILLISECONDS);
        stream.record(26_843, TimeUnit.MILLISECONDS);
        Map<String, Object> frame = frames.frame(1);

        assertThat(requestsOf(frame)).as("两条都算进计数（SSE 的请求数是真的）").containsEntry("count", 3L);
        Double p99 = (Double) requestsOf(frame).get("p99Ms");
        assertThat(p99).as("有业务样本 ⇒ 报真值（不是 null）").isNotNull();
        assertThat(p99).as("只由业务请求算：~60 ms，不是长连接的 26.8 秒").isLessThan(1000.0);
        assertThat(healthOf(frame)).as("P99 未越过 1000 ms 阈值 ⇒ 不该有那个红").containsEntry("level", "ok");
    }

    @Test
    @DisplayName("⚠️ 反向对照：真慢的**业务**请求仍然点亮 P99 红（改动不是把红线改死，是让它认对对象）")
    void genuinelySlowBusinessRequestStillTurnsP99Red() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());
        Timer business = Timer.builder("http.server.requests")
                .tags("uri", "/order/orderAdd", "status", "200")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        frames.frame(0);

        business.record(1_500, TimeUnit.MILLISECONDS);     // 一条真·慢请求（> 1000 ms 阈值）
        Map<String, Object> frame = frames.frame(1);

        assertThat(healthOf(frame)).as("业务请求真的慢了 ⇒ 该红").containsEntry("level", "error");
        assertThat((List<?>) healthOf(frame).get("reasons")).as("理由要说得出来是 P99")
                .anySatisfy(r -> assertThat(String.valueOf(r)).contains("P99"));
    }

    // ================= 每连接一份（块 7 · commit 10 的「丙」）=================

    @Test
    @DisplayName("丙：两条连接各差分各的 —— 后开的那条不会拿到一屏 0（先算的那条把读数吃走了）")
    void twoSessionsEachDiffAgainstTheirOwnPrevious() {
        when(goodsDao.selectStockLevels(anyInt())).thenReturn(List.of());
        SseStreamWriter.FrameSource other = service.newFrameSource();
        registry.counter(L1, "result", "hit").increment(4);
        frames.frame(0);                 // 先开的那条：首帧记 0（没有上一拍）
        other.frame(0);                  // 后开的：它自己也是首帧 ⇒ 同样记 0

        registry.counter(L1, "result", "hit").increment(6);
        assertThat(cacheOf(frames.frame(1))).as("先开的那条：本拍 +6").containsEntry("hit", 6L);
        assertThat(cacheOf(other.frame(1))).as("后开的那条：**也是 +6**。共用一份差分状态时，"
                + "这里会是 0（读数已被前一条读走）—— 页面看起来像铁死了").containsEntry("hit", 6L);
    }

    // ================= 工具 =================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cacheOf(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("cache");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> healthOf(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("health");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestsOf(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("requests");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stockOf(Map<String, Object> frame) {
        return (Map<String, Object>) frame.get("stock");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> container, String key) {
        return (List<Map<String, Object>>) container.get(key);
    }

    /** 某项防护的本拍值（页面上那根柱子的高度）。 */
    private static Long protectionOf(Map<String, Object> frame, String tag) {
        return rowsOf(frame, "protections").stream()
                .filter(r -> tag.equals(r.get("tag")))
                .map(r -> (Long) r.get("value"))
                .findFirst().orElse(null);
    }

    private static Map<String, Object> stockRow(String sku, String name, String axis, Object stock, Object baseline) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("SkuCode", sku);
        row.put("GoodName", name);
        row.put("AxisName", axis);
        row.put("Stock", stock);
        row.put("BaselineStock", baseline);
        return row;
    }
}
