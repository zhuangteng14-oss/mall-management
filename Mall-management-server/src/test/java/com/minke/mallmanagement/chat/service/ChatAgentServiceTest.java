package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChatAgentService} 的回复组装判据 —— 锁住评审 P0-1 / P0-2 的**回退路径**。
 *
 * <p>DAO 用替身（SQL 的正确性归 `OrderDao` 的既有测试与手验）；本文件只验「拿到数据之后怎么答」。
 */
class ChatAgentServiceTest {

    private OrderDao orderDao;
    private OrderRefundService refundService;
    private ChatAgentService service;

    @BeforeEach
    void setUp() {
        orderDao = mock(OrderDao.class);
        refundService = mock(OrderRefundService.class);
        service = new ChatAgentService(orderDao, refundService, new OrderStateMachine());
    }

    private static Map<String, Object> order(String id, String product, int pay, int delivery, int receive) {
        Map<String, Object> row = new HashMap<>();
        row.put("OrderId", id);
        row.put("CustomerId", "c1");
        row.put("MerchantId", "m1");
        row.put("Price", 299.0);
        row.put("ProductInformation", product + ":GD-101-9552");
        row.put("PayIf", pay);
        row.put("DeliveryIf", delivery);
        row.put("ReceiveIf", receive);
        row.put("CreateTime", "2026-10-18 14:22:10");
        row.put("Quantity", 1);
        return row;
    }

    @Test
    @DisplayName("P0-2：回复带「最近有 N 笔」，data 里给最近 3 笔，建议带跳转型「查看全部订单」")
    void multiOrderSummary() {
        when(orderDao.countByCustomer("c1")).thenReturn(7L);
        List<Map<String, Object>> recent = new ArrayList<>();
        recent.add(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0));
        recent.add(order("dRD3gqtbFr", "Java 实战课", 1, 0, 0));
        recent.add(order("OKWapbkaxx", "纯棉 T 恤", 1, 1, 1));
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt())).thenReturn(recent);

        Map<String, Object> resp = service.ask("查我的订单", "c1", 1);

        assertThat(resp.get("success")).isEqualTo(true);
        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(String.valueOf(resp.get("reply"))).contains("你最近有 7 笔订单", "最新一笔是", "已发货（待收货）");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("total")).isEqualTo(7L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) data.get("orders");
        assertThat(orders).hasSize(3);
        assertThat(orders.get(0)).containsEntry("orderId", "r2vfwK6z3E")
                .containsEntry("stateLabel", "已发货（待收货）");

        @SuppressWarnings("unchecked")
        List<Object> suggestions = (List<Object>) resp.get("suggestions");
        assertThat(suggestions).anySatisfy((s) -> {
            assertThat(s).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) s).get("routeKey")).isEqualTo("orders");
        });
    }

    @Test
    @DisplayName("P0-1：按单号查不到 ⇒ 仍回答「你最近的订单是…」")
    void orderIdMissFallsBackToLatest() {
        when(orderDao.countByCustomer("c1")).thenReturn(2L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));
        when(orderDao.selectByOrderIdAndCustomer(eq("c1"), anyString())).thenReturn(null);

        Map<String, Object> resp = service.ask("单号 zz9k8j7h6g5f", "c1", 1);

        String reply = String.valueOf(resp.get("reply"));
        assertThat(reply).contains("没找到单号 zz9k8j7h6g5f", "你最近有 2 笔订单", "无线蓝牙耳机");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) data.get("orders");
        assertThat(orders).hasSize(1);
    }

    @Test
    @DisplayName("按单号命中 ⇒ 只讲这一笔，total 不再写全量（否则前端多出答非所问的脚注）")
    void orderIdHitShowsSingleOrder() {
        when(orderDao.countByCustomer("c1")).thenReturn(9L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));
        when(orderDao.selectByOrderIdAndCustomer("c1", "drd3gqtbfr"))
                .thenReturn(order("dRD3gqtbFr", "Java 实战课", 1, 0, 0));

        Map<String, Object> resp = service.ask("单号 dRD3gqtbFr", "c1", 1);

        assertThat(String.valueOf(resp.get("reply"))).contains("订单 drd3gqtbfr", "Java 实战课", "待发货");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("total")).isEqualTo(1L);
    }

    @Test
    @DisplayName("未登录：查订单回「登录后才能查」，不是报错")
    void anonymousGetsLoginPrompt() {
        Map<String, Object> resp = service.ask("查我的订单", null, null);

        assertThat(resp.get("success")).isEqualTo(true);
        assertThat(String.valueOf(resp.get("reply"))).contains("登录后才能查询");
        assertThat(resp).doesNotContainKey("data");
    }

    @Test
    @DisplayName("怎么开店：带 tour action，路由键为 apply")
    void openStoreReturnsTourAction() {
        Map<String, Object> resp = service.ask("怎么开店", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("OPEN_STORE");
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) resp.get("action");
        assertThat(action).containsEntry("type", "tour")
                .containsEntry("tourId", "open-store")
                .containsEntry("routeKey", "apply");
    }

    @Test
    @DisplayName("售后：状态 1 → 待商家审核，并带退款金额")
    void refundReply() {
        Map<String, Object> row = new HashMap<>();
        row.put("OrderId", "r2vfwK6z3E");
        row.put("RefundStatus", 1);
        row.put("RefundAmount", 199.0);
        row.put("RefundReason", "不想要了");
        row.put("RefundApplyAt", "2026-10-20 10:00:00");
        row.put("RefundRetryCount", 0);
        when(refundService.myRefunds("c1", 1)).thenReturn(Map.of("success", true, "list", List.of(row)));

        Map<String, Object> resp = service.ask("我的退款到哪了", "c1", 1);

        assertThat(String.valueOf(resp.get("reply"))).contains("待商家审核", "¥199.00", "不想要了");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data).containsEntry("refundStatus", 1).containsEntry("refundStatusLabel", "待商家审核");
    }

    @Test
    @DisplayName("空消息 / 超长消息：success=false + msg")
    void badInput() {
        assertThat(service.ask("   ", "c1", 1)).containsEntry("success", false);
        assertThat(service.ask("x".repeat(201), "c1", 1)).containsEntry("success", false);
    }
}
