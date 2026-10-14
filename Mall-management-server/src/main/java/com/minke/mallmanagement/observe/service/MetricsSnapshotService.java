package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.goods.dao.GoodsDao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 指标面板（{@code POST /admin/observe/metrics/stream}）一拍一帧的产地 —— 把 Micrometer 里的
 * 累计计数**差分**成「这一拍发生了什么」，再补上库存水位。
 *
 * <h2>【为什么必须差分：Micrometer 的计数器只增不减，而面板要的是「此刻的速率」】</h2>
 * {@code http.server.requests} 是自进程启动以来的累计；QPS、错误率、命中率都只能由
 * **相邻两拍之差**得到。本类因此持有上一拍的读数（{@link #lastCounts}）与上一拍的时刻。
 * <ul>
 *   <li><b>首帧一律记 0</b>（{@code primed}）：那时没有「上一拍」可比 —— 把累计值当本拍
 *       会画出一根假的尖峰（进程跑了三天 ⇒ 首屏 QPS「几十万」）。</li>
 *   <li><b>中途新出现的计数器按「它现在的值就是本拍增量」算</b>：Micrometer 的计数器从 0 起，
 *       第一见它时它的值确实是本拍新增的（本类不把它当「没有上一拍」）。</li>
 *   <li>QPS / 速率的分母用 {@code System.nanoTime} 的**实差**（不是配置里的 1000ms）：
 *       节拍会抖，用配置值算出来的速率会跟着假抖。</li>
 * </ul>
 *
 * <h2>【⚠️ 指标名在这里是字面量 —— 这是一处**已知的重复口径**】</h2>
 * 各埋点类里的指标名常量（{@code MallCacheMetrics.L1_REQUESTS} 等）是**包私有**的，
 * 本类读不到；而块 7 的边界是「既有三环一个字不动」⇒ 不能为了本类把它们改成 public。
 * 故这里写死字面量，并在下面每一条后面标了**权威出处**。改名时必须同时改两处 ——
 * 这条约束写在这里，就是为了让改名的人至少有一次机会看见它。
 *
 * <h2>【8 类防护命中：全部来自计数器，**不**从事件流里数】</h2>
 * 事件流（{@link EventBroadcaster}）是**有界且会被覆盖**的，拿它数「拦了多少」在事件量大时
 * 会系统性偏小。计数器是准的 ⇒ 柱状图读计数器（规范第 6 条的另一面：同一事实不两处埋点，
 * 那就意味着**哪一处才是权威**要说清：**「多少」= 计数器，「怎么发生的」= 事件流**）。
 *
 * <h2>【阈值是「拍的」，与业务无关】</h2>
 * {@link #ERROR_RATE_ERROR} 等三个常量没有业务依据（本仓没有 SLA 配置），它们是**演示口径**：
 * 写在常量里、标成待裁，而不是埋进 if 表达式里当既有事实（同 {@code AdminOrderDao.selectMonitor}
 * 对 24h/48h 两个阈值的处置）。
 *
 * <h2>【代价】</h2>
 * 每拍一次只读查询（库存水位，{@code LIMIT 20}）+ 若干次 registry 查表。**不缓存**库存读数：
 * 缓存会让「水位不动」看起来像「面板坏了」，而水位实时变正是这块面板要看的东西。
 * 查询失败**不吞**：payload 里显式 {@code stock.available=false} + WARN 日志（页面把库存卡片
 * 变灰、健康度降级），流本身继续 —— 一次 DB 抖动不该让整页断流。
 */
@Component
public class MetricsSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(MetricsSnapshotService.class);

    // ===== 指标名（权威出处逐条标注；见类注释那条「已知的重复口径」）=====

    /** Spring Boot / actuator 自动埋的 HTTP 计时器（**不是**本仓代码写的名字）。 */
    private static final String HTTP = "http.server.requests";
    /** {@code shared/cache/MallCacheMetrics.java:102} */
    private static final String CACHE_L1 = "mall.cache.l1.requests";
    /** {@code shared/cache/MallCacheMetrics.java:103} */
    private static final String CACHE_L2 = "mall.cache.l2.requests";
    /** {@code shared/cache/MallCacheMetrics.java:109}（gauge，无 tag） */
    private static final String CACHE_L2_UP = "mall.cache.l2.up";
    /** {@code shared/lock/MallLockMetrics.java:71}（tag：lock / result） */
    private static final String LOCK_ACQUIRES = "mall.lock.acquires";
    /** {@code shared/lock/MallLockMetrics.java:72}（tag：lock） */
    private static final String LOCK_WAIT = "mall.lock.wait.duration";
    /** {@code shared/lock/MallLockMetrics.java:74}（gauge，无 tag） */
    private static final String LOCK_UP = "mall.lock.up";
    /** {@code order/metrics/MallOrderStateMetrics.java:104}（tag：action / result） */
    private static final String ORDER_TRANSITIONS = "mall.order.state.transitions";
    /** {@code observe/metrics/StockMetrics.java:75} */
    private static final String STOCK_DEDUCT = "mall.stock.deduct";
    /** {@code observe/metrics/AuditMetrics.java} */
    private static final String AUDIT_WRITES = "mall.audit.writes";
    /** {@code observe/metrics/NotifyMetrics.java} */
    private static final String NOTIFY_SENDS = "mall.notify.sends";
    /** {@code observe/metrics/SensitiveMetrics.java} */
    private static final String SENSITIVE_CHECKS = "mall.sensitive.checks";
    /** {@code observe/metrics/IdentityMetrics.java} */
    private static final String IDENTITY_CHECKS = "mall.identity.checks";

    /** 5xx 占比达到它 = 健康度红（**演示阈值，待业务裁**）。 */
    private static final double ERROR_RATE_ERROR = 0.05;
    /** 5xx 占比超过它 = 健康度黄（同上）。 */
    private static final double ERROR_RATE_WARN = 0.01;
    /**
     * P99 超过它（ms）= 健康度红（同上；1s 是本仓最慢那条链路的实测上限，见 {@code http.js} 的超时注释）。
     * ⚠️ 参评的 P99 **已剔除 SSE 长连接**（见 {@link #isLatencyTimer}）—— 不剔的话这里会被
     * 三条观测流的「连接存活时长」天天点亮（2026-10-14 实测：P99 26,843 ms 的红就是这么来的）。
     */
    private static final double P99_MS_ERROR = 1000.0;

    /**
     * 「耗时不算延迟」的 uri 后缀 —— SSE 长连接（块 7 的三条观测流）。
     * ⚠️ 与 {@code shared/web/TraceLog.SKIP_SUFFIX} **同一个字面量、同一个理由**：那条请求要等客户端
     * 断开才结束，记下来的「耗时」是**连接存活时长**而非一次请求的处理时长。
     * 排除的实测依据见 {@link #isLatencyTimer}。
     */
    private static final String SSE_URI_SUFFIX = "/stream";

    /** 库存水位一次最多列多少条（面板一屏够看；不做分页 —— 这是「水位条」不是「库存管理页」）。 */
    private static final int STOCK_LIMIT = 20;

    private final MeterRegistry registry;
    private final GoodsDao goodsDao;

    /** 上一拍各计数器的读数；键 = 指标名 + tags（见 {@link #key}）。 */
    private final Map<String, Double> lastCounts = new HashMap<>();
    private long lastNanos;
    /** 是否已经有过完整的一拍：首帧所有差分量记 0（理由见类注释）。 */
    private boolean primed;

    /** 一拍只算一次（理由见 {@link #frame} 与 {@link FramePacer}）。 */
    private final FramePacer pacer;

    public MetricsSnapshotService(MeterRegistry registry, GoodsDao goodsDao,
                                  @Value("${mall.observe.sse.interval-ms:1000}") long intervalMs) {
        this.registry = registry;
        this.goodsDao = goodsDao;
        this.pacer = new FramePacer(intervalMs);
    }

    /**
     * 产一拍。**在流线程上被调用**（{@code SseStreamWriter.FrameSource}），故整体 {@code synchronized}：
     * 同一实例可能被多条流共用（页面开两次），差分状态不能交错。
     *
     * <p>⚠️ <b>同一拍只算一次</b>（{@link FramePacer}）：若距上一次产帧不足**半个节拍**，
     * 直接把上一拍的内容换个 {@code seq} / {@code ts} 交回去。不这么做的话，「两个页面同时开」
     * 会让**后调用的那条流**看到全 0 的差分量（计数器已被前一条流「读走」了）——
     * 那是页面看起来像坏了、而其实在工作的假故障。
     *
     * @param tick 第几拍（从 0 起）；原样回填到帧里，页面据此丢弃过期帧
     */
    public synchronized Map<String, Object> frame(long tick) {
        long nowNanos = System.nanoTime();
        if (pacer.reusable(nowNanos)) {
            return pacer.reuse(tick);
        }
        double seconds = lastNanos == 0L ? 0.0 : (nowNanos - lastNanos) / 1_000_000_000.0;

        Map<String, Object> requests = requests(seconds);
        Map<String, Object> cache = cache();
        Map<String, Object> gauges = gauges();
        List<Map<String, Object>> locks = locks();
        List<Map<String, Object>> protections = protections(cache, locks);
        Map<String, Object> stock = stock();

        primed = true;
        lastNanos = nowNanos;

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("seq", tick);
        frame.put("ts", System.currentTimeMillis());
        frame.put("intervalMs", Math.round(seconds * 1000.0));
        frame.put("health", health(requests, cache, gauges, stock));
        frame.put("requests", requests);
        frame.put("cache", cache);
        frame.put("gauges", gauges);
        frame.put("locks", locks);
        frame.put("protections", protections);
        frame.put("stock", stock);
        pacer.remember(frame, nowNanos);
        return frame;
    }

    // ==================== 各段 ====================

    /** HTTP 段：本拍请求数 / 速率 / 错误率 / 延迟（均值 + 分位）。 */
    private Map<String, Object> requests(double seconds) {
        double count = 0.0;
        double errors5xx = 0.0;
        double errors4xx = 0.0;
        // 延迟那一路**只收**能当「一次请求的处理时长」看的序列（见 isLatencyTimer）；
        // 计数那一路是**全量** —— SSE 也是真请求，只是它的耗时不是延迟。
        double latencyCount = 0.0;
        double latencyMs = 0.0;
        for (Timer t : registry.find(HTTP).timers()) {
            double c = t.count();
            count += c;
            String status = t.getId().getTag("status");
            if (status != null && status.length() >= 1) {
                if (status.startsWith("5")) {
                    errors5xx += c;
                } else if (status.startsWith("4")) {
                    errors4xx += c;
                }
            }
            if (isLatencyTimer(t)) {
                latencyCount += c;
                latencyMs += t.totalTime(TimeUnit.MILLISECONDS);
            }
        }

        double dCount = delta(key(HTTP, "count"), count);
        double dLatencyCount = delta(key(HTTP, "latencyCount"), latencyCount);
        double dLatencyMs = delta(key(HTTP, "latencyMs"), latencyMs);
        double d5xx = delta(key(HTTP, "5xx"), errors5xx);
        double d4xx = delta(key(HTTP, "4xx"), errors4xx);
        // ⚠️ 本拍**有没有**「能当延迟看的」样本：没有就一律留空（null）。
        //    两条理由：① 均值 / 分位在 0 样本上无从谈起 —— 报 0 会被页面画成「快到 0 ms」的假好；
        //    ② 只在**这一拍真的发生过这类请求**时，Micrometer 那个衰减窗口才代表当下，
        //       否则它报的是上一个窗口里的陈迹（本笔要修的正是这个 —— 见 isLatencyTimer）。
        boolean hasLatency = dLatencyCount > 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", (long) dCount);
        out.put("qps", seconds <= 0 ? 0.0 : round(dCount / seconds));
        out.put("errors5xx", (long) d5xx);
        out.put("errors4xx", (long) d4xx);
        out.put("errorRate", ratio(d5xx, dCount));
        out.put("clientErrorRate", ratio(d4xx, dCount));
        out.put("meanMs", hasLatency ? round(dLatencyMs / dLatencyCount) : null);
        out.put("p95Ms", hasLatency ? percentile(0.95) : null);
        out.put("p99Ms", hasLatency ? percentile(0.99) : null);
        // max 是 Micrometer 的衰减窗口最大值（默认 2 分钟），**不是**本拍最大 —— 字段名照实写明。
        out.put("maxMs", hasLatency ? maxMs() : null);
        return out;
    }

    /**
     * 这条时间序列的耗时**能不能当延迟看**。
     *
     * <p>⚠️ <b>排除 SSE 长连接（uri 以 {@code /stream} 结尾）</b>：那三条（metrics / orders / events
     * 观测流）的「耗时」是**连接存活时长** —— 客户端挂着多久就记多久，不是一次请求的处理时长。
     * 实测（2026-10-14，dev 8088，actuator {@code http.server.requests} 按 uri 拆）：
     * <pre>
     *   /admin/observe/metrics/stream   6 次 / 109.07 s（均摊 18.2 s）
     *   /admin/observe/events/stream    4 次 /  51.62 s（均摊 12.9 s）
     *   /admin/observe/orders/stream    3 次 /  20.33 s（均摊  6.8 s）
     *   /order/orderAdd（真业务）    1654 次 /  73.13 s（均摊  44 ms）
     * </pre>
     * ⇒ 13 条长连接贡献约 181 s，而 1654 条真业务请求拢共才 73 s。它们把衰减窗口里的分位抬到
     * 26,843 ms，面板照实画成「P99 26.8 秒」并点亮健康度红 —— 那个红是**误报**。
     *
     * <p>它们**仍计入** {@code count} / {@code qps} / {@code errors4xx} / {@code errors5xx}：
     * 「发生过几次请求、几成出错」与「请求快不快」是两件事，SSE 对前者的贡献是真的。
     *
     * <p>⚠️ 后缀判据不是本处新发明的口径 —— {@code shared/web/TraceLog} 早就有
     * {@code SKIP_SUFFIX = "/stream"}`，两处**同一个字面量、同一个理由**（别各写各的）。
     */
    private static boolean isLatencyTimer(Timer t) {
        String uri = t.getId().getTag("uri");
        return uri == null || !uri.endsWith(SSE_URI_SUFFIX);
    }

    /** 缓存段：本拍 L1 命中 / 未命中 / 命中率 + L2 降级跳过数。 */
    private Map<String, Object> cache() {
        double hit = delta(key(CACHE_L1, "hit"), sum(CACHE_L1, "result", "hit"));
        double miss = delta(key(CACHE_L1, "miss"), sum(CACHE_L1, "result", "miss"));
        double degraded = delta(key(CACHE_L2, "degraded"), sum(CACHE_L2, "result", "degraded"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hit", (long) hit);
        out.put("miss", (long) miss);
        out.put("hitRate", ratio(hit, hit + miss));
        out.put("l2Degraded", (long) degraded);
        return out;
    }

    /** 两个 0/1 状态通道（可能为 null：gauge 还没被绑定时 Micrometer 里根本没有这个 meter）。 */
    private Map<String, Object> gauges() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("l2Up", gauge(CACHE_L2_UP));
        out.put("lockUp", gauge(LOCK_UP));
        return out;
    }

    /** 锁段：每个锁名一拍一行（页面拿它画「锁名 × 时间」的竞争热力图）。 */
    private List<Map<String, Object>> locks() {
        Map<String, Map<String, Object>> byLock = new LinkedHashMap<>();
        for (Counter c : registry.find(LOCK_ACQUIRES).counters()) {
            String lock = c.getId().getTag("lock");
            String result = c.getId().getTag("result");
            if (lock == null || result == null) {
                continue;
            }
            Map<String, Object> row = byLock.computeIfAbsent(lock, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("lock", k);
                r.put("acquires", 0L);
                r.put("acquired", 0L);
                r.put("timeouts", 0L);
                r.put("degraded", 0L);
                r.put("errors", 0L);
                r.put("waitMeanMs", null);
                return r;
            });
            double d = delta(key(LOCK_ACQUIRES, lock + "|" + result), c.count());
            bump(row, "acquires", d);
            switch (result) {
                case "acquired":
                    bump(row, "acquired", d);
                    break;
                case "timeout":
                    bump(row, "timeouts", d);
                    break;
                case "degraded":
                    bump(row, "degraded", d);
                    break;
                default:
                    bump(row, "errors", d);
                    break;
            }
        }
        for (Timer t : registry.find(LOCK_WAIT).timers()) {
            String lock = t.getId().getTag("lock");
            if (lock == null) {
                continue;
            }
            double dCount = delta(key(LOCK_WAIT, lock + "|count"), t.count());
            double dMs = delta(key(LOCK_WAIT, lock + "|totalMs"), t.totalTime(TimeUnit.MILLISECONDS));
            Map<String, Object> row = byLock.computeIfAbsent(lock, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("lock", k);
                r.put("acquires", 0L);
                r.put("acquired", 0L);
                r.put("timeouts", 0L);
                r.put("degraded", 0L);
                r.put("errors", 0L);
                r.put("waitMeanMs", null);
                return r;
            });
            row.put("waitMeanMs", dCount <= 0 ? null : round(dMs / dCount));
        }
        return new ArrayList<>(byLock.values());
    }

    /** 8 类防护命中（本拍增量）。前 4 类来自既有三块，后 4 类来自本块四环。 */
    private List<Map<String, Object>> protections(Map<String, Object> cache,
                                                  List<Map<String, Object>> locks) {
        long lockTimeout = 0L;
        for (Map<String, Object> row : locks) {
            lockTimeout += (Long) row.get("timeouts");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(protection("cacheHit", "缓存命中", (Long) cache.get("hit")));
        out.add(protection("lockTimeout", "锁等待超时", lockTimeout));
        out.add(protection("orderRejected", "订单流转被拒",
                delta(key(ORDER_TRANSITIONS, "rejected"), sum(ORDER_TRANSITIONS, "result", "rejected"))));
        out.add(protection("stockInsufficient", "库存不足（防超卖）",
                delta(key(STOCK_DEDUCT, "insufficient"), sum(STOCK_DEDUCT, "result", "insufficient"))));
        out.add(protection("auditFailed", "审计写入失败",
                delta(key(AUDIT_WRITES, "failed"), sum(AUDIT_WRITES, "result", "failed"))));
        out.add(protection("notifyFailed", "通知发送失败",
                delta(key(NOTIFY_SENDS, "failed"), sum(NOTIFY_SENDS, "result", "failed"))));
        out.add(protection("sensitiveHit", "敏感词拦下",
                delta(key(SENSITIVE_CHECKS, "all"), sumAll(SENSITIVE_CHECKS))));
        out.add(protection("identityRejected", "身份拒绝",
                delta(key(IDENTITY_CHECKS, "rejected"), sum(IDENTITY_CHECKS, "result", "rejected"))));
        return out;
    }

    /** 库存水位段（唯一一次真查询；失败也不让流断，理由见类注释「代价」一节）。 */
    private Map<String, Object> stock() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (Map<String, Object> raw : goodsDao.selectStockLevels(STOCK_LIMIT)) {
                Map<String, Object> row = new LinkedHashMap<>();
                Object stock = raw.get("Stock");
                Object baseline = raw.get("BaselineStock");
                row.put("sku", raw.get("SkuCode"));
                row.put("goodName", raw.get("GoodName"));
                row.put("axisName", raw.get("AxisName"));
                row.put("stock", stock == null ? null : ((Number) stock).longValue());
                row.put("baseline", baseline == null ? null : ((Number) baseline).longValue());
                row.put("ratio", ratioOf(stock, baseline));
                rows.add(row);
            }
            out.put("available", true);
        } catch (Exception e) {
            // ⚠️ 不吞：WARN 留痕 + payload 里显式带 available=false（页面把卡片变灰、健康度降级）。
            //    流**继续**推 —— 一次 DB 抖动不该让整页断流；这与「宁可断流也不给半死数据」不冲突：
            //    这里是**显式标注了不可用**，不是把旧值当新值。
            log.warn("[observe] 库存水位读取失败（本拍该段标记为不可用，流继续）：{}", e.toString());
            out.put("available", false);
        }
        out.put("rows", rows);
        return out;
    }

    /** 健康度：绿 / 黄 / 红 + 中文理由（阈值是演示口径，见类注释）。 */
    private Map<String, Object> health(Map<String, Object> requests, Map<String, Object> cache,
                                       Map<String, Object> gauges, Map<String, Object> stock) {
        List<String> reasons = new ArrayList<>();
        List<String> warnReasons = new ArrayList<>();

        Double errorRate = (Double) requests.get("errorRate");
        Double p99 = (Double) requests.get("p99Ms");
        Double l2Up = (Double) gauges.get("l2Up");
        Double lockUp = (Double) gauges.get("lockUp");
        Double cacheHitRate = (Double) cache.get("hitRate");
        if (errorRate != null && errorRate >= ERROR_RATE_ERROR) {
            reasons.add("5xx 占比 " + pct(errorRate) + "（≥ " + pct(ERROR_RATE_ERROR) + "）");
        }
        if (p99 != null && p99 >= P99_MS_ERROR) {
            reasons.add("P99 " + p99 + "ms（≥ " + (long) P99_MS_ERROR + "ms）");
        }
        if (errorRate != null && errorRate > ERROR_RATE_WARN) {
            warnReasons.add("5xx 占比 " + pct(errorRate));
        }
        if (lockUp != null && lockUp == 0.0) {
            warnReasons.add("锁处于降级窗口（互斥未生效，fail-open 直行）");
        }
        if (l2Up != null && l2Up == 0.0) {
            warnReasons.add("L2 处于降级窗口（读回落数据库）");
        }
        if (Boolean.FALSE.equals(stock.get("available"))) {
            warnReasons.add("库存水位本轮读不到");
        }

        String level;
        List<String> all;
        if (!reasons.isEmpty()) {
            level = "error";
            all = reasons;
        } else if (!warnReasons.isEmpty()) {
            level = "warn";
            all = warnReasons;
        } else {
            level = "ok";
            all = new ArrayList<>();
            if (cacheHitRate != null) {
                all.add("L1 命中率 " + pct(cacheHitRate));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("label", "error".equals(level) ? "异常" : ("warn".equals(level) ? "注意" : "健康"));
        out.put("reasons", all);
        return out;
    }

    // ==================== 小工具 ====================

    /**
     * 差分：把累计值换成「本拍增量」。
     *
     * @param k       稳定键（同名指标的不同 tag 组合必须分开记，否则会互相抵消）
     * @param current 本拍读到的累计值
     */
    private double delta(String k, double current) {
        Double prev = lastCounts.put(k, current);
        if (!primed) {
            return 0.0;
        }
        if (prev == null) {
            return current;
        }
        // 计数器只会增；出现负数只能是进程内 meter 被重建（本仓不会），兜到 0 而不是画一根倒吸的线。
        return Math.max(0.0, current - prev);
    }

    private static String key(String name, String suffix) {
        return name + "@" + suffix;
    }

    /** 某指标下、按 tag 过滤的计数器**当前累计值之和**；没有匹配就是 0。 */
    private double sum(String name, String tagKey, String tagValue) {
        double total = 0.0;
        for (Counter c : registry.find(name).tag(tagKey, tagValue).counters()) {
            total += c.count();
        }
        return total;
    }

    private double sumAll(String name) {
        double total = 0.0;
        for (Counter c : registry.find(name).counters()) {
            total += c.count();
        }
        return total;
    }

    private Double gauge(String name) {
        Gauge g = registry.find(name).gauge();
        if (g == null || Double.isNaN(g.value())) {
            return null;
        }
        return g.value();
    }

    /**
     * P95 / P99。
     *
     * <p>⚠️ **口径写清楚**：取各条时间序列（method × uri × status × outcome）分位数的**最大值** ——
     * 它是全站分位数的**上界近似**。真·全局分位要求跨时间序列做直方图合并（Prometheus 的
     * {@code histogram_quantile} 就是干这个的），本仓没有那一层 ⇒ 这个字段**别当成精确值**用。
     * 分位数未开启时返回 {@code null}（{@code application.properties} 里没配
     * {@code management.metrics.distribution.percentiles.*}，由本块
     * {@code observe/ObserveMetricsConfig.java} 直接给这一个指标开了 0.5/0.95/0.99）。
     *
     * <p>⚠️ <b>只算非 SSE 的时间序列</b>（见 {@link #isLatencyTimer}）：长连接的存活时长混进来，
     * 这个字段就变成「客户端挂了多久」而不是「接口多慢」。
     */
    private Double percentile(double p) {
        Double best = null;
        for (Timer t : registry.find(HTTP).timers()) {
            if (!isLatencyTimer(t)) {
                continue;
            }
            for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
                if (Math.abs(v.percentile() - p) < 1.0e-9) {
                    double ms = v.value(TimeUnit.MILLISECONDS);
                    best = best == null ? ms : Math.max(best, ms);
                }
            }
        }
        return best == null ? null : round(best);
    }

    private Double maxMs() {
        Double best = null;
        for (Timer t : registry.find(HTTP).timers()) {
            if (!isLatencyTimer(t)) {
                continue;
            }
            double ms = t.max(TimeUnit.MILLISECONDS);
            if (!Double.isNaN(ms)) {
                best = best == null ? ms : Math.max(best, ms);
            }
        }
        return best == null ? null : round(best);
    }

    /** 柱状图一行。{@code value} 收 {@link Number}：调用方既有现成的 {@code Long}（缓存命中），又有 {@code delta()} 给的 {@code double}。 */
    private static Map<String, Object> protection(String tag, String label, Number value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", tag);
        row.put("label", label);
        row.put("value", value.longValue());
        return row;
    }

    private static void bump(Map<String, Object> row, String key, double d) {
        row.put(key, (Long) row.get(key) + (long) d);
    }

    /** 占比：分母 ≤ 0 时返回 {@code null}（「没有请求」与「0% 出错」是两件事，页面要分开显示）。 */
    private static Double ratio(double numerator, double denominator) {
        if (denominator <= 0) {
            return null;
        }
        return round(numerator / denominator);
    }

    /** 水位占比：两个数都可能没有（{@code BaselineStock} 未准备 / 行里没这一列）。 */
    private static Double ratioOf(Object stock, Object baseline) {
        if (!(stock instanceof Number) || !(baseline instanceof Number)) {
            return null;
        }
        double b = ((Number) baseline).doubleValue();
        if (b <= 0) {
            return null;
        }
        return round(((Number) stock).doubleValue() / b);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100.0);
    }
}
