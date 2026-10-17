package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.order.dao.OrderDao;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态流转页的**分析面**（2026-10-17）—— 24 小时积压趋势、耗时直方图、账龄分桶、
 * 健康度输入、最慢 Top N、以及给底部全局明细表用的订单行。
 *
 * <h2>【为什么单开一条端点，不塞进 SSE 帧】</h2>
 * 这些量是**聚合**（全表/全窗口），不是「每拍变了多少」。若塞进 1 秒 1 帧：
 * <ul>
 *   <li>每拍要跑 6–8 条扫描 + 在 Java 里重算 24 个时间点 —— 页面开着就一直在算；</li>
 *   <li>趋势图根本不需要 1 秒粒度（横轴是小时），重算出来的点还几乎一样。</li>
 * </ul>
 * ⇒ 本服务由 {@code POST /admin/observe/orders/insight} 一次性返回，页面挂载时读一次、
 * 之后按低频刷新。**实时**那一半仍由 SSE 的 {@code counts} / {@code moves} 承担。
 *
 * <h2>【口径：库里存 UTC，比较用 UTC，展示转 +8】</h2>
 * 库里的 {@code CreateTime/PaidAt/…} 是**无时区文本**，库时钟为 UTC
 * （{@code @@system_time_zone=UTC}，同 {@code OrderDao} / {@code ShippingTrackDao} 的已核实口径）。
 * 故本类一律用 {@link #DB_ZONE} 取「现在」参与相减（差值天然正确）；只有**给人看的**趋势横轴
 * 与 {@code from/to} 在返回前转成 Asia/Shanghai（{@link #localText}），前端不再折算。
 * ⚠️ 不直接用 SQL {@code NOW()}：一次请求里多个桶必须落在同一个瞬间，且这里只读一次表。
 *
 * <h2>【趋势的可重建边界】</h2>
 * 趋势按生命周期时间戳重建「每个整点各态多少单」。有 6 行单的三位标记已置 1、
 * 但对应时间戳为空（历史遗留）—— 这类行无法定位「什么时候进的这一态」，
 * 本服务不编造转换时刻，而是从 `stateSince()` 的回退起点起按**当前态**计
 * （缺的是时间，不是位置；详见 {@code Life#stateAt}）。当前值仍以
 * {@link OrderDao#countOrderStates()} 的权威聚合为准，两者不混。
 */
@Service
public class OrderInsightService {

    /** 一次读多少行做分析。演示库 256 行；上限留足余量，不把整表读成无限。 */
    private static final int ROW_LIMIT = 1000;
    /** 趋势窗口（小时）。 */
    private static final int TREND_HOURS = 24;
    /** 最慢 Top N（抽屉左栏）。 */
    private static final int SLOWEST_LIMIT = 10;

    /** 库时钟（UTC）—— 与时间戳相减用这个。 */
    private static final ZoneOffset DB_ZONE = ZoneOffset.UTC;
    /** 展示时区（东八区）—— 只用于趋势横轴与 from/to。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final OrderDao orderDao;

    public OrderInsightService(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    /** 一拍分析面（默认**全量**：含压测单与已取消单，与页面开关的默认态一致）。 */
    public Map<String, Object> insight() {
        return insight(false);
    }

    /**
     * 一拍分析面。
     *
     * @param healthyOnly {@code true} ⇒ 聚合前先排除演练数据（{@code SIM-%} 前缀 **OR**
     *                    {@code Source='attack'}）与已取消单 —— 与超时取消 job / 清场端点
     *                    共用 {@link OrderDao} 背后那一条 {@code OrderDataScope} 谓词。
     *                    ⚠️ 过滤必须发生在**这一层**：趋势 / 耗时 / 账龄 / 健康度 / 明细
     *                    全部由同一批行派生，前端拿到的是聚合结果、无法自己重算分母。
     *                    实时 SSE 流**不**受这个开关影响（流轨要看见演练单真的在动）。
     */
    public Map<String, Object> insight(boolean healthyOnly) {
        LocalDateTime now = LocalDateTime.now(DB_ZONE);
        List<Life> rows = new ArrayList<>();
        for (Map<String, Object> raw : orderDao.selectRecentOrders(ROW_LIMIT, healthyOnly)) {
            rows.add(new Life(raw));
        }
        Map<String, Object> counts = orderDao.countOrderStates(healthyOnly);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", System.currentTimeMillis());
        out.put("counts", counts);
        out.put("trend", trend(rows, now));
        out.put("durations", durations(rows));
        Map<String, Object> aging = aging(rows);
        out.put("aging", aging);
        out.put("health", health(counts, aging));
        out.put("slowest", slowest(rows));
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Life l : rows) {
            orders.add(OrderStreamService.orderRow(l.raw));
        }
        out.put("orders", orders);
        return out;
    }

    // ==================== 24 小时趋势 ====================

    /**
     * 逐整点的「各态积压」。
     *
     * <p>⚠️ 若**当前 24 小时内库内没有任何生命周期动作**（演示库的常态：最后一单在前一天），
     * 则把窗口锚到「最后一次动作所在的那一小时」—— 否则整张图是三条贴着 0 的直线，
     * 看不出任何流转。返回值里带 {@code anchoredToLatest=true}，页面必须把这个口径写出来。
     */
    private Map<String, Object> trend(List<Life> rows, LocalDateTime now) {
        LocalDateTime latest = null;
        for (Life l : rows) {
            for (LocalDateTime t : l.timestamps()) {
                if (t != null && (latest == null || t.isAfter(latest))) {
                    latest = t;
                }
            }
        }
        boolean anchored = latest != null && latest.isBefore(now.minusHours(TREND_HOURS));
        // ⚠️ 终点 = **当前整点**，不 `plusHours(1)`：加了会画出「未来一小时」的桶，
        //    横轴最后一格比本机时钟快 1 小时（2026-10-17 截图实测 20:05 显示 21:00）。
        LocalDateTime end = (anchored ? latest : now)
                .withMinute(0).withSecond(0).withNano(0);
        LocalDateTime start = end.minusHours(TREND_HOURS);

        List<String> categories = new ArrayList<>();
        List<Integer> waitPay = new ArrayList<>();
        List<Integer> waitShip = new ArrayList<>();
        List<Integer> waitReceive = new ArrayList<>();
        for (int i = 0; i <= TREND_HOURS; i++) {
            LocalDateTime t = start.plusHours(i);
            categories.add(localText(t));
            int[] n = new int[3];
            for (Life l : rows) {
                switch (l.stateAt(t)) {
                    case "waitPay" -> n[0]++;
                    case "waitShip" -> n[1]++;
                    case "waitReceive" -> n[2]++;
                    default -> { /* 已完成 / 已取消不画进「积压」 */ }
                }
            }
            waitPay.add(n[0]);
            waitShip.add(n[1]);
            waitReceive.add(n[2]);
        }

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("hours", TREND_HOURS);
        trend.put("from", localText(start));
        trend.put("to", localText(end));
        trend.put("anchoredToLatest", anchored);
        trend.put("anchorHint", anchored
                ? "当前 24 小时内无新动作，窗口锚到库内最后一次生命周期动作；压测开始后会自动回到「现在」"
                : "窗口终点 = 当前整点，随 SSE 刷新");
        trend.put("categories", categories);
        List<Map<String, Object>> series = new ArrayList<>();
        series.add(series("waitPay", "待付款", waitPay));
        series.add(series("waitShip", "待发货", waitShip));
        series.add(series("waitReceive", "待收货", waitReceive));
        trend.put("series", series);
        return trend;
    }

    /** 库内 UTC 文本 → 东八区展示文本（只转给人看的轴标签，不参与任何相减）。 */
    private static String localText(LocalDateTime utc) {
        return HOUR_FMT.format(utc.atZone(DB_ZONE).withZoneSameInstant(ZONE));
    }

    private Map<String, Object> series(String key, String name, List<Integer> data) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("name", name);
        s.put("data", data);
        return s;
    }

    // ==================== 耗时直方图 ====================

    /** 三段耗时（付款 / 发货 / 收货）的分桶 + 平均 + 样本数。 */
    private Map<String, Object> durations(List<Life> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pay", duration(rows, "pay"));
        out.put("ship", duration(rows, "ship"));
        out.put("receive", duration(rows, "receive"));
        return out;
    }

    private Map<String, Object> duration(List<Life> rows, String segment) {
        int[] edges;
        String[] labels;
        switch (segment) {
            case "pay" -> {
                edges = new int[]{0, 5, 15, 60, 360, 1440};
                labels = new String[]{"0–5分", "5–15分", "15–60分", "1–6小时", "6–24小时", "24小时+"};
            }
            case "ship" -> {
                edges = new int[]{0, 60, 360, 1440, 4320, 10080};
                labels = new String[]{"0–1小时", "1–6小时", "6–24小时", "1–3天", "3–7天", "7天+"};
            }
            default -> {
                edges = new int[]{0, 360, 1440, 4320, 10080};
                labels = new String[]{"0–6小时", "6–24小时", "1–3天", "3–7天", "7天+"};
            }
        }
        int[] counts = new int[labels.length];
        List<Long> samples = new ArrayList<>();
        for (Life l : rows) {
            Long minutes = l.segmentMinutes(segment);
            if (minutes == null) {
                continue;
            }
            samples.add(minutes);
            counts[indexOf(minutes, edges)]++;
        }
        double avg = samples.isEmpty() ? 0
                : samples.stream().mapToLong(Long::longValue).average().orElse(0);
        long max = samples.stream().mapToLong(Long::longValue).max().orElse(0);

        List<Map<String, Object>> bins = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> bin = new LinkedHashMap<>();
            bin.put("label", labels[i]);
            bin.put("count", counts[i]);
            bin.put("fromMinutes", edges[i]);
            bin.put("toMinutes", i + 1 < edges.length ? edges[i + 1]
                    : Math.max(max, edges[i] * 2L));
            bins.add(bin);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bins", bins);
        out.put("samples", samples.size());
        out.put("avgMinutes", round1(avg));
        out.put("maxMinutes", max);
        return out;
    }

    private int indexOf(long v, int[] edges) {
        for (int i = edges.length - 1; i >= 0; i--) {
            if (v >= edges[i]) {
                return i;
            }
        }
        return 0;
    }

    // ==================== 超时积压账龄 ====================

    /** 待发货 / 待收货的账龄分桶、超时数与最老样本。 */
    private Map<String, Object> aging(List<Life> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waitShip", agingOf(rows, "waitShip", OrderStreamService.WAIT_SHIP_TIMEOUT_HOURS));
        out.put("waitReceive", agingOf(rows, "waitReceive", OrderStreamService.WAIT_RECEIVE_TIMEOUT_HOURS));
        return out;
    }

    private Map<String, Object> agingOf(List<Life> rows, String state, int timeoutHours) {
        int[] edges = "waitShip".equals(state)
                ? new int[]{0, 24, 72, 168, 336, 720}
                : new int[]{0, 48, 168, 336, 720};
        String[] labels = "waitShip".equals(state)
                ? new String[]{"0–1天", "1–3天", "3–7天", "7–14天", "14–30天", "30天+"}
                : new String[]{"0–2天", "2–7天", "7–14天", "14–30天", "30天+"};
        int[] counts = new int[labels.length];
        int total = 0;
        int timeout = 0;
        long maxHours = 0;
        long sumHours = 0;
        for (Life l : rows) {
            if (!state.equals(l.state)) {
                continue;
            }
            LocalDateTime since = l.stateSince();
            if (since == null) {
                continue;
            }
            long hours = Math.max(0, Duration.between(since, LocalDateTime.now(DB_ZONE)).toHours());
            total++;
            sumHours += hours;
            maxHours = Math.max(maxHours, hours);
            if (hours >= timeoutHours) {
                timeout++;
            }
            counts[indexOf(hours, edges)]++;
        }
        List<Map<String, Object>> bins = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> bin = new LinkedHashMap<>();
            bin.put("label", labels[i]);
            bin.put("count", counts[i]);
            bins.add(bin);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        out.put("thresholdHours", timeoutHours);
        out.put("total", total);
        out.put("timeout", timeout);
        out.put("avgHours", total == 0 ? 0 : Math.round((double) sumHours / total));
        out.put("maxHours", maxHours);
        out.put("bins", bins);
        return out;
    }

    // ==================== 健康度 ====================

    /**
     * 流转健康度（0–100）：三块扣分 —— 超时率 60 / 积压占比 25 / 平均停留 15。
     * 权重是**页面口径**，不是后端业务规则；页面上同时把三个输入列出来，避免只给一个黑箱分数。
     */
    private Map<String, Object> health(Map<String, Object> counts, Map<String, Object> aging) {
        int waitPay = n(counts.get("waitPay"));
        int waitShip = n(counts.get("waitShip"));
        int waitReceive = n(counts.get("waitReceive"));
        int total = n(counts.get("total"));
        int active = waitPay + waitShip + waitReceive;
        int timeout = n(((Map<?, ?>) aging.get("waitShip")).get("timeout"))
                + n(((Map<?, ?>) aging.get("waitReceive")).get("timeout"));
        double timeoutRate = active == 0 ? 0 : (double) timeout / active;
        double backlogShare = total == 0 ? 0 : (double) active / total;
        double avgDwellHours = (n(((Map<?, ?>) aging.get("waitShip")).get("avgHours"))
                + n(((Map<?, ?>) aging.get("waitReceive")).get("avgHours"))) / 2.0;
        double score = 100
                - 60 * Math.min(1, timeoutRate / 0.5)
                - 25 * Math.min(1, backlogShare / 0.5)
                - 15 * Math.min(1, avgDwellHours / 72);
        int rounded = (int) Math.round(Math.max(0, Math.min(100, score)));
        String grade;
        if (rounded >= 85) {
            grade = "健康";
        } else if (rounded >= 60) {
            grade = "关注";
        } else if (rounded >= 35) {
            grade = "告警";
        } else {
            grade = "危险";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", rounded);
        out.put("grade", grade);
        out.put("timeoutRate", round1(timeoutRate * 100));
        out.put("timeoutCount", timeout);
        out.put("activeCount", active);
        out.put("backlogShare", round1(backlogShare * 100));
        out.put("avgDwellHours", round1(avgDwellHours));
        return out;
    }

    // ==================== 最慢 Top N ====================

    /** 待发货 / 待收货里停留最久的 N 单（抽屉左栏）。 */
    private List<Map<String, Object>> slowest(List<Life> rows) {
        List<Life> active = new ArrayList<>();
        for (Life l : rows) {
            if ("waitShip".equals(l.state) || "waitReceive".equals(l.state)) {
                active.add(l);
            }
        }
        active.sort(Comparator.comparingLong(Life::dwellSeconds).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(SLOWEST_LIMIT, active.size()); i++) {
            Map<String, Object> row = OrderStreamService.orderRow(active.get(i).raw);
            row.put("rank", i + 1);
            out.add(row);
        }
        return out;
    }

    // ==================== 行模型 ====================

    private static int n(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 一行订单 + 解析好的生命周期时刻。 */
    private static final class Life {
        private final Map<String, Object> raw;
        private final LocalDateTime created;
        private final LocalDateTime paid;
        private final LocalDateTime shipped;
        private final LocalDateTime received;
        private final LocalDateTime cancelled;
        private final String state;

        private Life(Map<String, Object> raw) {
            this.raw = raw;
            this.created = OrderStreamService.timeOf(raw.get("CreateTime"));
            this.paid = OrderStreamService.timeOf(raw.get("PaidAt"));
            this.shipped = OrderStreamService.timeOf(raw.get("ShippedAt"));
            this.received = OrderStreamService.timeOf(raw.get("ReceivedAt"));
            this.cancelled = OrderStreamService.timeOf(raw.get("CancelledAt"));
            this.state = OrderStreamService.stateOf(raw.get("CancelledAt"), raw.get("PayIf"),
                    raw.get("DeliveryIf"), raw.get("ReceiveIf"));
        }

        /**
         * 某个整点时的状态（只用于趋势；当前态仍以三位/取消标记为准）。
         *
         * <p>⚠️ 历史遗留的 6 行「标记已置 1、但对应时间戳为空」：只按时间戳推会把它们
         * **永远算成待付款**，于是趋势里多出一条 `waitPay=6` 的线，与上面 KPI 的 `0` 矛盾。
         * 故这里做一次**单调回退**：时间戳推出来的态比当前态早、且当前态的起点
         * （{@link #stateSince()} 已带逐级回退）已经到了，就取当前态 ——
         * 缺的是「什么时候转的」，不是「现在在哪」。
         */
        private String stateAt(LocalDateTime t) {
            if (created == null || created.isAfter(t)) {
                return "absent";
            }
            String byTs;
            if (cancelled != null && !cancelled.isAfter(t)) {
                byTs = "cancelled";
            } else if (received != null && !received.isAfter(t)) {
                byTs = "completed";
            } else if (shipped != null && !shipped.isAfter(t)) {
                byTs = "waitReceive";
            } else if (paid != null && !paid.isAfter(t)) {
                byTs = "waitShip";
            } else {
                byTs = "waitPay";
            }
            LocalDateTime since = stateSince();
            if (rank(state) > rank(byTs) && since != null && !since.isAfter(t)) {
                return state;
            }
            return byTs;
        }

        private static int rank(String s) {
            return switch (s) {
                case "waitPay" -> 0;
                case "waitShip" -> 1;
                case "waitReceive" -> 2;
                case "completed" -> 3;
                case "cancelled" -> 4;
                default -> -1;
            };
        }

        private LocalDateTime stateSince() {
            return OrderStreamService.stateSince(state, created, paid, shipped, received, cancelled);
        }

        private Long segmentMinutes(String segment) {
            LocalDateTime a;
            LocalDateTime b;
            switch (segment) {
                case "pay" -> {
                    a = created;
                    b = paid;
                }
                case "ship" -> {
                    a = paid;
                    b = shipped;
                }
                default -> {
                    a = shipped;
                    b = received;
                }
            }
            if (a == null || b == null || b.isBefore(a)) {
                return null;
            }
            return Duration.between(a, b).toMinutes();
        }

        private List<LocalDateTime> timestamps() {
            // ⚠️ 不能写 List.of：它会拒绝 null，而生命周期列大量为 null。
            return Arrays.asList(created, paid, shipped, received, cancelled);
        }

        private long dwellSeconds() {
            LocalDateTime since = stateSince();
            return since == null ? 0L : Math.max(0L, Duration.between(since, LocalDateTime.now(DB_ZONE)).getSeconds());
        }
    }
}
