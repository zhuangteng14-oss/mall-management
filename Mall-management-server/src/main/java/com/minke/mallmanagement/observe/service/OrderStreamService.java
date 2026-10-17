package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 状态流转页（{@code POST /admin/observe/orders/stream}）一拍一帧的产地 —— 五态计数 + 最近订单
 * + **本拍真的动了的那几单**。
 *
 * <h2>【三条数据各有各的产地，不能混】</h2>
 * <ol>
 *   <li><b>五态计数</b>：一次聚合查询（{@link OrderDao#countOrderStates()}）——「此刻库里各有多少单」。
 *       ⚠️ **不用事件流累加**：事件流是有界缓冲、且只覆盖**本进程起来之后**发生的流转，
 *       拿它当计数会在重启后归零。这是规范第 6 条的另一面：**权威口径要说清**。</li>
 *   <li><b>最近订单列表</b>：一次只读查询（{@link OrderDao#selectRecentOrders(int)}）——
 *       每单当前态由三位 {@code PayIf/DeliveryIf/ReceiveIf} + {@code CancelledAt} 算出来，
 *       与计数用的是**同一条判据链**（见 {@link #stateOf}）。</li>
 *   <li><b>本拍流转（{@code moves}）</b>：来自订单状态环
 *       （{@code MallOrderStateMetrics.recentEvents()}）里 {@code kind=transition} 的事件 ——
 *       这是**唯一**能回答「刚刚哪一单从哪跳到哪」的通道（库里查不出来：状态位是覆盖写的，
 *       过去态没有留痕）。</li>
 * </ol>
 *
 * <h2>【⚠️ 一处已知边界：取消**不进** {@code moves}】</h2>
 * 超时取消（{@code OrderTimeoutCancelJob}）写的是 {@code CancelledAt}，
 * <b>不经过状态机</b>（取消的单三位仍是 {@code (0,0,0)}，见该类注释），故它**不发流转事件**
 * ⇒ 它的发生只能从「五态计数里 cancelled +1」看出来。这是**如实反映**而不是漏记：
 * {@code moves} 的定义就是「状态机的流转」，把非状态机的写库也算进来才是编造。
 * 页面若要「取消也闪一下」，判据得换成「b 帧的 cancelled 数 − a 帧的」，本块不做（页面保持简单）。
 *
 * <h2>【{@code stateOf} 与 SQL 的判据链必须一致 —— 两处都写在这里，改一处要改两处】</h2>
 * <pre>
 *   取消（CancelledAt 非空）    → cancelled   （**必须先判**：取消的单三位与「待付款」完全相同）
 *   ReceiveIf = 1              → completed
 *   DeliveryIf = 1             → waitReceive
 *   PayIf = 1                  → waitShip
 *   其余                        → waitPay
 * </pre>
 * SQL 那一侧（{@link OrderDao#countOrderStates()}）用 CASE 表达同一条链，顺序逐条对齐。
 *
 * <h2>【每连接一份】</h2>
 * 本类是单例，但**帧源不是**：{@link #newFrameSource} 每条连接现建一份 {@link Session}，
 * 环游标（{@code lastEventSeq}）与差分状态**都装在会话里**。理由：读差分量是**有副作用**的
 * （把环游标往前推），两条连接共用一份状态时先算的那条会把后算的那条的数据吃掉 ——
 * 页面看起来像死机。改前用一个半拍窗口把产帧压成「每半拍至多一次」来绕这个坑（代价是两条流
 * 相位差小于半拍时内容会重复），commit 10 换成每连接一份游标后那个窗口**连同它的类一起删了**。
 *
 * <p>⇒ 会话里的 {@code frame} **不需要** {@code synchronized}：它只被自己那条连接的流线程调用，
 * 状态不共享（{@link OrderDao} / 环本身各自并发安全，见它们自己的注释）。
 */
@Component
public class OrderStreamService {

    /** 最近订单列表一次取多少条（页面是「实时列表」，不是「订单管理」⇒ 一屏够）。 */
    private static final int RECENT_LIMIT = 20;

    private final OrderDao orderDao;
    private final MallOrderStateMetrics orderMetrics;

    public OrderStreamService(OrderDao orderDao, MallOrderStateMetrics orderMetrics) {
        this.orderDao = orderDao;
        this.orderMetrics = orderMetrics;
    }

    /**
     * 现建一份「这条连接自己的」帧源。
     *
     * @param batch 本次演练的批次号（每次产帧现问一次；空白 = 没有在跑的演练）。
     *              ⚠️ 现问、不当成构造期的固定值：演示时常常**先开着页面、再点开始**，
     *              构造期取一次会让这一整条连接永远停在「没有批次」。
     */
    public SseStreamWriter.FrameSource newFrameSource(Supplier<String> batch) {
        return newFrameSource(batch, false);
    }

    /**
     * 同上，并指定**计数/最近订单是否走「健康业务分布」口径**
     * （排除演练数据 + 已取消单，谓词见 {@code OrderDataScope.HEALTHY_SCOPE_SQL}）。
     *
     * <p>⚠️ 只过滤 {@code counts} 与 {@code orders}，**不过滤 {@code moves}**：页面上的
     * 「剔除已取消 / 仅看真实数据」开关针对的是聚合数字与表格；实时流轨仍要看见演练单的流转
     * （把流也藏掉，轨道就真的「死」了 —— 那正是本笔要治的病）。
     */
    public SseStreamWriter.FrameSource newFrameSource(Supplier<String> batch, boolean healthyOnly) {
        return new Session(batch, healthyOnly);
    }

    /** 一条连接的全部可变状态（见类注释「每连接一份」）。 */
    private final class Session implements SseStreamWriter.FrameSource {

        /** 本批次号（空白 = 无）。 */
        private final Supplier<String> batch;

        /** true ⇒ 计数与最近订单走健康口径（排除演练 + 已取消）；{@code moves} 不受影响。 */
        private final boolean healthyOnly;

        /**
         * 订单状态环「已读到哪」的游标。
         *
         * <p>⚠️ <b>初值 = 本会话建立那一刻环里的最大 seq</b>（没有事件则 -1）。
         * 旧实现固定从 -1 起，于是页面第一次连上时会把环里**最近 2048 条历史流转**一次全吐给前端 ——
         * 刷新/切页后表现就是「数字与流轨瞬间爆一屏」，正是用户报的「造假感 / 瞬间突变」。
         * 「本会话捕获」只应包含连上之后真正发生的事件；历史属于分析面（insight），不属于实时流。
         */
        private long lastEventSeq;

        private Session(Supplier<String> batch, boolean healthyOnly) {
            this.batch = batch;
            this.healthyOnly = healthyOnly;
            long last = -1L;
            for (OrderStateEvent e : orderMetrics.recentEvents()) {
                if (e.seq() > last) {
                    last = e.seq();
                }
            }
            this.lastEventSeq = last;
        }

        /**
         * 产一拍（**在流线程上被调用**，每条连接自己的那条）。
         *
         * @param tick 第几拍（从 0 起），原样回填
         */
        @Override
        public Map<String, Object> frame(long tick) {
            // ⚠️ false 时走**老的无参重载**（而不是 `(false)`）：老重载是既有调用方与单测的替身
            //    唯一会打桩的方法，换成 `(false)` 会让它们静默拿到空 Map（实测：两条旧用例当场红）。
            Map<String, Object> counts = healthyOnly
                    ? orderDao.countOrderStates(true) : orderDao.countOrderStates();
            List<Map<String, Object>> orders = new ArrayList<>();
            List<Map<String, Object>> recent = healthyOnly
                    ? orderDao.selectRecentOrders(RECENT_LIMIT, true)
                    : orderDao.selectRecentOrders(RECENT_LIMIT);
            for (Map<String, Object> raw : recent) {
                orders.add(orderRow(raw));
            }
            List<Map<String, Object>> moves = drainMoves();

            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("seq", tick);
            frame.put("ts", System.currentTimeMillis());
            frame.put("counts", counts);
            frame.put("orders", orders);
            frame.put("moves", moves);
            frame.put("moveCount", moves.size());
            frame.put("batchCounts", batchCounts());
            return frame;
        }

        /**
         * **本批次**的五态计数（块 7 · commit 10 · Q3 路线 i）—— 与 {@code counts}（全表）**并列**，
         * 口径互不覆盖：框内角标说全库，这一份说「这次演练落下的那批单现在各在哪个态」。
         *
         * <p>⚠️ <b>没有批次号 ⇒ 回 {@code null}，不回 0</b>：页面的口径是「没有批次画 {@code —}」，
         * 而 {@code 0} 会被读成「本批次一单都没有」。两者在演示里长得完全不一样。
         *
         * <p>⚠️ 代价如实记：这是**每拍一条短查询**（{@code WHERE BatchId = ?} 走 {@code BatchId} 索引）。
         * 连接数是「几个页面」而不是「几个请求」⇒ 这条查询的频次 = 打开的页面数 / 秒，可控。
         */
        private Map<String, Object> batchCounts() {
            String id = batch == null ? null : batch.get();
            if (id == null || id.isBlank()) {
                return null;
            }
            return orderDao.countOrderStatesByBatch(id);
        }

        /** 本拍新发生的流转（{@code kind=transition}；illegal / async 属日志页，不进本页）。 */
        private List<Map<String, Object>> drainMoves() {
            List<Map<String, Object>> moves = new ArrayList<>();
            for (OrderStateEvent e : orderMetrics.recentEvents()) {
                if (e.seq() <= lastEventSeq) {
                    continue;
                }
                lastEventSeq = e.seq();
                if (!"transition".equals(e.kind())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", e.key());
                row.put("action", e.action());
                row.put("from", e.from());
                row.put("to", e.to());
                row.put("result", e.result());
                row.put("ts", e.epochMilli());
                row.put("batchId", e.batchId());
                moves.add(row);
            }
            return moves;
        }
    }

    /**
     * 一行订单 → 页面要的形状（状态 + 进度步数 + 停留时长 / 超时判据）。
     *
     * <p>⚠️ 2026-10-17 起**同时给状态流转页与洞察端点**用（原先是私有实例方法）：两处若各写一份
     * 「状态 + 停留时长」的映射，判据链迟早走岔 —— 而这条链正是本页最容易写错的一处。
     * 做成 {@code static} 后没有实例状态，调用方不必再新建一个 service。
     */
    static Map<String, Object> orderRow(Map<String, Object> raw) {
        LocalDateTime created = timeOf(raw.get("CreateTime"));
        LocalDateTime paid = timeOf(raw.get("PaidAt"));
        LocalDateTime shipped = timeOf(raw.get("ShippedAt"));
        LocalDateTime received = timeOf(raw.get("ReceivedAt"));
        LocalDateTime cancelled = timeOf(raw.get("CancelledAt"));
        String state = stateOf(raw.get("CancelledAt"), raw.get("PayIf"),
                raw.get("DeliveryIf"), raw.get("ReceiveIf"));
        LocalDateTime since = stateSince(state, created, paid, shipped, received, cancelled);
        Long dwellSeconds = since == null ? null
                : Math.max(0L, Duration.between(since, LocalDateTime.now(DB_ZONE)).getSeconds());
        Integer thresholdHours = timeoutHoursOf(state);
        boolean timeout = dwellSeconds != null && thresholdHours != null
                && dwellSeconds >= thresholdHours * 3600L;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", raw.get("OrderId"));
        row.put("customerId", raw.get("CustomerId"));
        row.put("merchantId", raw.get("MerchantId"));
        row.put("amount", raw.get("Price"));
        row.put("quantity", raw.get("Quantity"));
        row.put("product", raw.get("ProductInformation"));
        row.put("createdAt", raw.get("CreateTime"));
        row.put("state", state);
        row.put("step", stepOf(state));
        row.put("cancelled", "cancelled".equals(state));
        // 时间轴 / 超时判据的原料 —— 与 state 同源（同一行原始时间戳），不另算一套。
        row.put("paidAt", raw.get("PaidAt"));
        row.put("shippedAt", raw.get("ShippedAt"));
        row.put("receivedAt", raw.get("ReceivedAt"));
        row.put("cancelledAt", raw.get("CancelledAt"));
        row.put("stateSince", since == null ? null : TIME_FMT.format(since));
        row.put("dwellSeconds", dwellSeconds);
        row.put("timeout", timeout);
        row.put("timeoutThresholdHours", thresholdHours);
        return row;
    }

    /** 待发货 / 待收货的超时阈值（小时）—— 页面上两条预警带与洞察端点共用这一份。 */
    static final int WAIT_SHIP_TIMEOUT_HOURS = 24;
    static final int WAIT_RECEIVE_TIMEOUT_HOURS = 48;

    /** 库内时间文本是 **UTC**（`@@system_time_zone=UTC`，同 `OrderDao`/`ShippingTrackDao` 的口径）
     *  ⇒ 「现在」必须也取 UTC 再相减；展示层（前端 `hms`/`dt`）再转本机时区。 */
    private static final ZoneOffset DB_ZONE = ZoneOffset.UTC;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 当前态是从哪个时刻开始的（停留时长的起点）。 */
    static LocalDateTime stateSince(String state, LocalDateTime created, LocalDateTime paid,
                                    LocalDateTime shipped, LocalDateTime received,
                                    LocalDateTime cancelled) {
        switch (state) {
            case "waitPay":
                return created;
            case "waitShip":
                return paid != null ? paid : created;
            case "waitReceive":
                return shipped != null ? shipped : (paid != null ? paid : created);
            case "completed":
                return received != null ? received : (shipped != null ? shipped : created);
            case "cancelled":
                return cancelled != null ? cancelled : created;
            default:
                return created;
        }
    }

    /** 该状态的超时阈值；{@code null} = 本页不把这一态算进超时预警。 */
    static Integer timeoutHoursOf(String state) {
        if ("waitShip".equals(state)) {
            return WAIT_SHIP_TIMEOUT_HOURS;
        }
        if ("waitReceive".equals(state)) {
            return WAIT_RECEIVE_TIMEOUT_HOURS;
        }
        return null;
    }

    /** DB 里时间列的两种形状都吃：{@code Timestamp} 与 ISO 式字符串（可能带毫秒）。 */
    static LocalDateTime timeOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        if (s.length() > 19) {
            s = s.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(s.replace('T', ' '), TIME_FMT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 三位 + 取消时刻 → 页面用的五态之一（判据链见类注释，**与 SQL 那份逐条对齐**）。
     * 包的可见性：单测直接测这条链（它是本页最容易写错的一处）。
     */
    static String stateOf(Object cancelledAt, Object payIf, Object deliveryIf, Object receiveIf) {
        if (cancelledAt != null) {
            return "cancelled";
        }
        if (isOne(receiveIf)) {
            return "completed";
        }
        if (isOne(deliveryIf)) {
            return "waitReceive";
        }
        if (isOne(payIf)) {
            return "waitShip";
        }
        return "waitPay";
    }

    /** 进度条点亮的节点数（0 = 已取消，不点亮任何节点）。 */
    static int stepOf(String state) {
        switch (state) {
            case "waitPay":
                return 1;
            case "waitShip":
                return 2;
            case "waitReceive":
                return 3;
            case "completed":
                return 4;
            default:
                return 0;
        }
    }

    /** 三位口径：{@code 1} 才算「是」，{@code 0} / {@code null} 都是「否」（与 SQL 里 {@code = 1} 同）。 */
    private static boolean isOne(Object v) {
        return v instanceof Number && ((Number) v).intValue() == 1;
    }
}
