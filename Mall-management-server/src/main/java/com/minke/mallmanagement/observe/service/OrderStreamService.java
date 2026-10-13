package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <h2>【一拍只算一次】</h2>
 * 同 {@link MetricsSnapshotService}：两个页面（或刷新时的旧连接）同时读时，**同一拍共用一份结果**
 * （在 {@link #frame} 里按半拍窗口复用），否则后调用的那条会看到「本拍什么都没发生」。
 * 代价：相位差小于半拍的两次读会拿到内容相同、只有 {@code seq}/{@code ts} 不同的两帧
 * （页面按 {@code seq} 幂等处理）。
 */
@Component
public class OrderStreamService {

    /** 最近订单列表一次取多少条（页面是「实时列表」，不是「订单管理」⇒ 一屏够）。 */
    private static final int RECENT_LIMIT = 20;

    private final OrderDao orderDao;
    private final MallOrderStateMetrics orderMetrics;

    /** 订单状态环「已读到哪」的游标；-1 = 还没读过（空环的 seq 从 0 起）。 */
    private long lastEventSeq = -1L;

    /** 一拍只算一次（理由见类注释最后一节与 {@link FramePacer}）。 */
    private final FramePacer pacer;

    public OrderStreamService(OrderDao orderDao,
                              MallOrderStateMetrics orderMetrics,
                              @Value("${mall.observe.sse.interval-ms:1000}") long intervalMs) {
        this.orderDao = orderDao;
        this.orderMetrics = orderMetrics;
        this.pacer = new FramePacer(intervalMs);
    }

    /**
     * 产一拍（**在流线程上被调用**）。
     *
     * @param tick 第几拍（从 0 起），原样回填
     */
    public synchronized Map<String, Object> frame(long tick) {
        long now = System.nanoTime();
        if (pacer.reusable(now)) {
            return pacer.reuse(tick);
        }

        Map<String, Object> counts = orderDao.countOrderStates();
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> raw : orderDao.selectRecentOrders(RECENT_LIMIT)) {
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
        pacer.remember(frame, now);
        return frame;
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
            moves.add(row);
        }
        return moves;
    }

    /** 一行订单 → 页面要的形状（状态 + 进度步数）。 */
    private Map<String, Object> orderRow(Map<String, Object> raw) {
        String state = stateOf(raw.get("CancelledAt"), raw.get("PayIf"),
                raw.get("DeliveryIf"), raw.get("ReceiveIf"));
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
        return row;
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
