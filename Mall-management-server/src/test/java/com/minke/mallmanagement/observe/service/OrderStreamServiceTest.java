package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.metrics.MallOrderStateMetrics;
import com.minke.mallmanagement.order.model.OrderAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态流转页产帧（{@link OrderStreamService}）的实测。
 *
 * <h2>【本文件钉的两件最重要的事】</h2>
 * <ol>
 *   <li><b>五态判据链</b>（{@link OrderStreamService#stateOf}）—— ⚠️ <b>取消除外必须排在最前</b>：
 *       取消单的三位仍是 {@code (0,0,0)}，与「待付款」逐位相同，靠三位根本分不开。
 *       下面 {@link #cancelledIsCheckedBeforeTheThreeBits()} 就是为此写的；</li>
 *   <li><b>{@code moves} 只取状态机流转</b>（{@code kind=transition}）——
 *       {@code illegal} / {@code async} 属日志页，混进来会让「刚刚哪一单动了」变成噪声。</li>
 * </ol>
 *
 * <p>{@code OrderDao} 用替身（{@code mock}）：本文件不碰 MySQL，也不验证 SQL 文本 ——
 * SQL 那侧的 CASE 链与这里的判据链**必须一起改**，这条约束写在
 * {@link OrderDao#countOrderStates()} 的注释里（两处互为「改一处要改两处」的提醒）。
 *
 * <p>⚠️ 块 7 · commit 10 起 {@link OrderStreamService} 是**工厂**：帧源（会话）才持有环游标，
 * 由 {@link OrderStreamService#newFrameSource} 每连接现建 ⇒ 本文件所有断言打在**同一份会话**上
 * （一条测试 = 一条连接），另有用例专门验「两条连接各读各的」。
 */
class OrderStreamServiceTest {

    private final OrderDao orderDao = mock(OrderDao.class);
    private final MallOrderStateMetrics orderMetrics = new MallOrderStateMetrics(new SimpleMeterRegistry());
    private final OrderStreamService service = new OrderStreamService(orderDao, orderMetrics);

    /** 本用例的那一条连接（块 7 · commit 10 起帧源由工厂**每连接现建**，见类注释）。 */
    private final SseStreamWriter.FrameSource frames = service.newFrameSource(() -> "");

    // ================= 五态判据链 =================

    @Test
    @DisplayName("⚠️ 取消**最先判**：三位全是 0 的单，只有 CancelledAt 能把它与「待付款」分开")
    void cancelledIsCheckedBeforeTheThreeBits() {
        assertThat(OrderStreamService.stateOf(null, 0, 0, 0)).as("三位全 0 = 待付款").isEqualTo("waitPay");
        assertThat(OrderStreamService.stateOf("2026-10-13 10:00:00", 0, 0, 0))
                .as("**同三位 + CancelledAt 非空 = 已取消**（判据链顺序反了就永远判不出来）").isEqualTo("cancelled");
        assertThat(OrderStreamService.stateOf("2026-10-13 10:00:00", 1, 1, 1))
                .as("已完成的单后来被取消：仍是 cancelled（取消压过三位）").isEqualTo("cancelled");
    }

    @Test
    @DisplayName("未取消时按 Receive → Delivery → Pay 逐位回退；null 一律当「否」")
    void stateFollowsTheBitCascade() {
        assertThat(OrderStreamService.stateOf(null, 1, 1, 1)).isEqualTo("completed");
        assertThat(OrderStreamService.stateOf(null, 1, 1, 0)).isEqualTo("waitReceive");
        assertThat(OrderStreamService.stateOf(null, 1, 0, 0)).isEqualTo("waitShip");
        assertThat(OrderStreamService.stateOf(null, 0, 0, 0)).isEqualTo("waitPay");
        assertThat(OrderStreamService.stateOf(null, null, null, null))
                .as("列缺失（null）与 0 同义：SQL 里写的是 `= 1`，Java 这边就得同口径").isEqualTo("waitPay");
        assertThat(OrderStreamService.stateOf(null, 1, null, null)).as("只有 Pay 亮：待发货").isEqualTo("waitShip");
    }

    @Test
    @DisplayName("进度步数：取消不点亮任何节点（0），其余 1..4 递增")
    void stepMapping() {
        assertThat(OrderStreamService.stepOf("waitPay")).isEqualTo(1);
        assertThat(OrderStreamService.stepOf("waitShip")).isEqualTo(2);
        assertThat(OrderStreamService.stepOf("waitReceive")).isEqualTo(3);
        assertThat(OrderStreamService.stepOf("completed")).isEqualTo(4);
        assertThat(OrderStreamService.stepOf("cancelled")).as("取消单不点亮节点").isZero();
    }

    // ================= 一帧的形状 =================

    @Test
    @DisplayName("一帧 = 五态计数（库查的，原样透传）+ 最近订单（每单算当前态）+ moves")
    void frameCarriesCountsOrdersAndMoves() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("cancelled", 1L);
        counts.put("completed", 2L);
        counts.put("waitReceive", 3L);
        counts.put("waitShip", 4L);
        counts.put("waitPay", 5L);
        counts.put("total", 15L);
        when(orderDao.countOrderStates()).thenReturn(counts);
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of(
                row("O-1", 1, 1, 1, null),
                row("O-2", 0, 0, 0, "2026-10-13 09:00:00")));

        Map<String, Object> frame = frames.frame(0);

        assertThat(frame).containsEntry("seq", 0L).containsEntry("moveCount", 0);
        assertThat(frame.get("counts")).as("计数是库里的权威口径，原样透传（不在服务端重算）")
                .isSameAs(counts);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) frame.get("orders");
        assertThat(orders).extracting(o -> o.get("orderId")).containsExactly("O-1", "O-2");
        assertThat(orders).extracting(o -> o.get("state")).containsExactly("completed", "cancelled");
        assertThat(orders).extracting(o -> o.get("step")).containsExactly(4, 0);
        assertThat(orders).extracting(o -> o.get("cancelled")).containsExactly(false, true);
        assertThat(orders.get(0)).as("页面要的字段名与库列名解耦（OrderId → orderId）")
                .containsEntry("amount", 99).containsEntry("product", "苹果");
        assertThat(frame.get("moves")).as("这一拍还没发生流转").isEqualTo(List.of());
    }

    // ================= moves =================

    @Test
    @DisplayName("moves 只取 kind=transition；illegal / async 是日志页的（混进来会变噪声）")
    void movesOnlyCarryStateMachineTransitions() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());

        orderMetrics.transition(OrderAction.PAY, "waitPay", "waitShip",
                MallOrderStateMetrics.Result.APPLIED, "O-1", 1_500L, null);
        orderMetrics.illegal("010", "O-2", 1_500L);
        orderMetrics.birthRejected("non_initial", "O-3", 1_500L);
        orderMetrics.timelineWrite(false, "O-4", null, "时间线写入失败：IllegalStateException：db down");

        Map<String, Object> frame = frames.frame(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moves = (List<Map<String, Object>>) frame.get("moves");
        assertThat(moves).as("四个事件里只有一个是状态机流转").hasSize(1);
        assertThat(moves.get(0)).containsEntry("orderId", "O-1").containsEntry("action", "pay")
                .containsEntry("from", "waitPay").containsEntry("to", "waitShip")
                .containsEntry("result", "applied");
        assertThat(frame.get("moveCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("游标：同一批流转只报一次（下一拍 moves 为空，不重复刷同一单）")
    void movesAreDrainedOnce() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());
        orderMetrics.transition(OrderAction.PAY, "waitPay", "waitShip",
                MallOrderStateMetrics.Result.APPLIED, "O-1", 1_500L, null);

        assertThat(movesOf(frames.frame(0))).as("第一拍看见它动了").hasSize(1);
        assertThat(movesOf(frames.frame(1))).as("第二拍同一批事件不再重复报").isEmpty();
    }

    @Test
    @DisplayName("⚠️ **没有汇总事件**的取消不进 moves（取消不经过状态机）—— 2026-10-17 起由 cancelBatch 补")
    void cancelledWithoutSummaryNeverAppearInMoves() {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("cancelled", 1L);
        when(orderDao.countOrderStates()).thenReturn(after);
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of(row("O-9", 0, 0, 0, "2026-10-13 11:00:00")));

        Map<String, Object> frame = frames.frame(0);

        assertThat(movesOf(frame)).as("取消不经状态机 ⇒ 不发流转事件，本页也就没有它的 move").isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) frame.get("counts");
        assertThat(counts.get("cancelled")).as("但计数里看得见").isEqualTo(1L);
    }

    @Test
    @DisplayName("超时取消汇总 ⇒ **一条** move：count=N、orderId=null（前端必须按 count 加权，不能数条数）")
    void timeoutCancelSummaryBecomesOneWeightedMove() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());

        orderMetrics.cancelBatch(1200, "CREATED");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moves = (List<Map<String, Object>>) frames.frame(0).get("moves");
        assertThat(moves).as("1200 单也只推一条，不挤爆 SSE").hasSize(1);
        Map<String, Object> move = moves.get(0);
        assertThat(move).containsEntry("action", "cancel")
                .containsEntry("from", "CREATED").containsEntry("to", "CANCELLED")
                .containsEntry("result", "applied").containsEntry("count", 1200);
        assertThat(move.get("orderId")).as("跨多单、无单一业务键 ⇒ 必须是 null，不能伪造一个").isNull();
    }

    @Test
    @DisplayName("丙（块 7 · commit 10）：两条连接各持一份环游标 —— 同一批流转两条流**都**看得见")
    void twoSessionsEachSeeTheSameMoves() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());
        SseStreamWriter.FrameSource other = service.newFrameSource(() -> "");
        orderMetrics.transition(OrderAction.PAY, "waitPay", "waitShip",
                MallOrderStateMetrics.Result.APPLIED, "O-1", 1_500L, null);

        assertThat(movesOf(frames.frame(0))).as("先开的那条看见它动了").hasSize(1);
        assertThat(movesOf(other.frame(0))).as("后开的那条**也**看得见。改前共用一份游标时，"
                + "先算的那条会把这条 move 读走，后开的那条页面看着像死机").hasSize(1);
    }

    @Test
    @DisplayName("⚠️ 晚连上的会话**不重放**环里的历史：只看见连上之后的流转（「瞬间爆一屏」的根因）")
    void aLateSessionDoesNotReplayHistoricalMoves() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());

        // 会话建立**之前**环里已经有的那条（旧实现会把这条也吐给页面）。
        orderMetrics.transition(OrderAction.PAY, "waitPay", "waitShip",
                MallOrderStateMetrics.Result.APPLIED, "O-OLD", 1_500L, null);
        SseStreamWriter.FrameSource late = service.newFrameSource(() -> "");
        // 连上之后真的发生的那条。
        orderMetrics.transition(OrderAction.DELIVER, "waitShip", "waitReceive",
                MallOrderStateMetrics.Result.APPLIED, "O-NEW", 2_500L, null);

        assertThat(movesOf(late.frame(0)))
                .as("游标初值必须 = 本会话建立那一刻环里的最大 seq；固定从 -1 起会把最近 2048 条全灌进来")
                .extracting(m -> m.get("orderId"))
                .containsExactly("O-NEW");
    }

    @Test
    @DisplayName("「仅看真实数据」：healthy 只换计数/最近订单的口径，**moves 仍全量**（流轨不能一起被藏掉）")
    void healthyOnlyFiltersCountsAndOrdersButNotMoves() {
        Map<String, Object> filtered = new LinkedHashMap<>();
        filtered.put("waitPay", 2L);
        filtered.put("total", 2L);
        when(orderDao.countOrderStates(true)).thenReturn(filtered);
        when(orderDao.selectRecentOrders(anyInt(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(List.of(row("O-REAL", 0, 0, 0, null)));
        SseStreamWriter.FrameSource filteredSession = service.newFrameSource(() -> "", true);
        orderMetrics.transition(OrderAction.PAY, "waitPay", "waitShip",
                MallOrderStateMetrics.Result.APPLIED, "SIM-X", 1_500L, null);

        Map<String, Object> frame = filteredSession.frame(0);

        assertThat(frame.get("counts")).as("计数走过滤版").isSameAs(filtered);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) frame.get("orders");
        assertThat(rows).extracting(o -> o.get("orderId")).containsExactly("O-REAL");
        assertThat(movesOf(frame)).as("演练单的流转仍要在流轨里看得见")
                .extracting(m -> m.get("orderId")).containsExactly("SIM-X");
    }

    // ================= 全局明细服务端分页（2026-10-17 · 用户 P0）=================

    @Test
    @DisplayName("detail：total 与行集走**同一个 status 谓词**；limit 夹到 200、offset 负数归 0")
    void detailPagesOnTheServerWithClampedParams() {
        when(orderDao.countOrderPage(false, "waitShip", false)).thenReturn(2271);
        when(orderDao.selectOrderPage(false, "waitShip", false, 40, 200))
                .thenReturn(List.of(row("O-1", 1, 0, 0, null)));

        Map<String, Object> out = service.detail(false, "waitShip", false, 40, 9999);

        assertThat(out).containsEntry("status", "waitShip").containsEntry("offset", 40)
                .containsEntry("limit", 200).containsEntry("total", 2271);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("orderId", "O-1").containsEntry("state", "waitShip");
        // 关键：total 来自 COUNT，不是 rows.size() —— 否则「Tab 2271、表 20 条」又会被读成只有 20 单。
        assertThat(rows).as("这一页只有一条，但 total 仍是 2271").hasSizeLessThan(2271);
    }

    @Test
    @DisplayName("detail：offset 负数归 0、limit 0 抬到 1（手改 URL 不能把整表拉进内存）")
    void detailClampsNegativeOffsetAndZeroLimit() {
        when(orderDao.countOrderPage(false, "", false)).thenReturn(1);
        when(orderDao.selectOrderPage(false, "", false, 0, 1))
                .thenReturn(List.of(row("O-1", 1, 1, 1, null)));

        Map<String, Object> out = service.detail(false, "", false, -5, 0);

        assertThat(out).containsEntry("offset", 0).containsEntry("limit", 1);
        verify(orderDao).selectOrderPage(false, "", false, 0, 1);
    }

    // ================= 本批次五态计数（块 7 · commit 10 · Q3 路线 i）=================

    @Test
    @DisplayName("没有批次号 ⇒ batchCounts 是 null，**不是**一份全 0（「没批次」与「本批次一单没有」是两件事）")
    void batchCountsIsNullWithoutABatch() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());

        assertThat(frames.frame(0)).containsEntry("batchCounts", null);
        verify(orderDao, never()).countOrderStatesByBatch(anyString());
    }

    @Test
    @DisplayName("有批次号 ⇒ batchCounts 取库里的本批次聚合；且批次号是**每拍现问**（先开页面、后点开始也对）")
    void batchCountsFollowsTheLiveBatchId() {
        when(orderDao.countOrderStates()).thenReturn(Map.of());
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of());
        Map<String, Object> byBatch = new LinkedHashMap<>();
        byBatch.put("waitPay", 7L);
        when(orderDao.countOrderStatesByBatch("batch-live")).thenReturn(byBatch);

        // ⚠️ 故意用一个**可变的**供应者：批次号在会话建好**之后**才出现（演示的真实次序 ——
        //    页面先开着，演练是后来才点的开始）。构造期取一次值的写法在这里会永远读到空串。
        String[] live = {""};
        SseStreamWriter.FrameSource session = service.newFrameSource(() -> live[0]);

        assertThat(session.frame(0)).as("还没点开始 ⇒ 没有本批次").containsEntry("batchCounts", null);

        live[0] = "batch-live";
        assertThat(session.frame(1)).as("点了开始 ⇒ 同一份会话下一拍就拿到本批次口径")
                .containsEntry("batchCounts", byBatch);
    }

    // ================= 工具 =================

    /** 一行订单的库侧形状（列名 = SQL 别名，故意保持「大写开头」以贴近真实结果集）。 */
    private static Map<String, Object> row(String orderId, int payIf, int deliveryIf, int receiveIf, Object cancelledAt) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("OrderId", orderId);
        raw.put("CustomerId", "C-1");
        raw.put("MerchantId", "M-1");
        raw.put("Price", 99);
        raw.put("Quantity", 2);
        raw.put("ProductInformation", "苹果");
        raw.put("PayIf", payIf);
        raw.put("DeliveryIf", deliveryIf);
        raw.put("ReceiveIf", receiveIf);
        raw.put("CancelledAt", cancelledAt);
        raw.put("CreateTime", "2026-10-13 08:00:00");
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> movesOf(Map<String, Object> frame) {
        return new ArrayList<>((List<Map<String, Object>>) frame.get("moves"));
    }
}
