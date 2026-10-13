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
import static org.mockito.Mockito.mock;
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
 * 会话按 {@code interval-ms = 0} 建（{@link FramePacer} 窗口 0 ⇒ 每次调用都真算，
 * 不会被「半拍复用」搅乱断言）。
 */
class OrderStreamServiceTest {

    private final OrderDao orderDao = mock(OrderDao.class);
    private final MallOrderStateMetrics orderMetrics = new MallOrderStateMetrics(new SimpleMeterRegistry());
    private final OrderStreamService service = new OrderStreamService(orderDao, orderMetrics, 0);

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

        Map<String, Object> frame = service.frame(0);

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
                MallOrderStateMetrics.Result.APPLIED, "O-1");
        orderMetrics.illegal("010", "O-2");
        orderMetrics.birthRejected("non_initial", "O-3");
        orderMetrics.timelineWrite(false, "O-4");

        Map<String, Object> frame = service.frame(0);

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
                MallOrderStateMetrics.Result.APPLIED, "O-1");

        assertThat(movesOf(service.frame(0))).as("第一拍看见它动了").hasSize(1);
        assertThat(movesOf(service.frame(1))).as("第二拍同一批事件不再重复报").isEmpty();
    }

    @Test
    @DisplayName("⚠️ 取消**不进** moves（超时取消不经过状态机）—— 它的发生只能从 cancelled 计数看出来")
    void cancelledOrdersNeverAppearInMoves() {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("cancelled", 1L);
        when(orderDao.countOrderStates()).thenReturn(after);
        when(orderDao.selectRecentOrders(anyInt())).thenReturn(List.of(row("O-9", 0, 0, 0, "2026-10-13 11:00:00")));

        Map<String, Object> frame = service.frame(0);

        assertThat(movesOf(frame)).as("取消不经状态机 ⇒ 不发流转事件，本页也就没有它的 move").isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) frame.get("counts");
        assertThat(counts.get("cancelled")).as("但计数里看得见").isEqualTo(1L);
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
