package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics.OrderStateEvent;
import org.springframework.stereotype.Component;

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
        return new Session(batch);
    }

    /** 一条连接的全部可变状态（见类注释「每连接一份」）。 */
    private final class Session implements SseStreamWriter.FrameSource {

        /** 本批次号（空白 = 无）。 */
        private final Supplier<String> batch;

        /** 订单状态环「已读到哪」的游标；-1 = 还没读过（空环的 seq 从 0 起）。 */
        private long lastEventSeq = -1L;

        private Session(Supplier<String> batch) {
            this.batch = batch;
        }

        /**
         * 产一拍（**在流线程上被调用**，每条连接自己的那条）。
         *
         * @param tick 第几拍（从 0 起），原样回填
         */
        @Override
        public Map<String, Object> frame(long tick) {
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
