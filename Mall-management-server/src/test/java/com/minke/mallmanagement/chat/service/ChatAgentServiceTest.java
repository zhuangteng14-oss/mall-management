package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.model.ResolvedIdentity;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatAgentService} 的回复组装判据 —— 锁住评审 P0-1 / P0-2 的**回退路径**。
 *
 * <p>DAO 用替身（SQL 的正确性归 `OrderDao` 的既有测试与手验）；本文件只验「拿到数据之后怎么答」。
 */
class ChatAgentServiceTest {

    private OrderDao orderDao;
    private OrderRefundService refundService;
    private IdentityResolver identityResolver;
    private SessionMemoryService memoryService;
    private LlmIntentClassifier llmClassifier;
    private ChatAgentService service;

    @BeforeEach
    void setUp() {
        orderDao = mock(OrderDao.class);
        refundService = mock(OrderRefundService.class);
        identityResolver = mock(IdentityResolver.class);
        memoryService = mock(SessionMemoryService.class);
        llmClassifier = mock(LlmIntentClassifier.class);
        // 默认：LLM 未启用（Mockito 布尔默认 false）⇒ 既有用例全部走规则引擎，行为与阶段一之前一致。
        // 默认：普通客户、无入驻申请；未登录走匿名。个别用例再覆盖。
        when(identityResolver.resolve(anyString(), anyInt()))
                .thenReturn(ResolvedIdentity.noApplication("demo_customer"));
        when(identityResolver.resolve(isNull(), isNull()))
                .thenReturn(ResolvedIdentity.anonymous());
        service = new ChatAgentService(orderDao, refundService, new OrderStateMachine(),
                identityResolver, memoryService, llmClassifier);
    }

    /** 打开 LLM 路由；具体分类结果由用例再 stub {@code classify}。 */
    private void llmEnabled() {
        when(llmClassifier.isEnabled()).thenReturn(true);
    }

    private void identity(ResolvedIdentity resolved) {
        when(identityResolver.resolve(anyString(), anyInt())).thenReturn(resolved);
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

    // ==================== N2：六态话术 ====================

    @Test
    @DisplayName("N2 无申请：第一句固定「系统显示您当前是普通客户…」")
    void noApplicationTalk() {
        identity(ResolvedIdentity.noApplication("demo_customer"));

        Map<String, Object> resp = service.ask("怎么开店", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .startsWith("系统显示您当前是普通客户，还没有提交过入驻申请。");
        assertThat(actionOf(resp)).containsEntry("routeKey", "apply");
    }

    @Test
    @DisplayName("N2 审核中：报店铺名与提交时间，且不给漫游动作")
    void pendingTalk() {
        identity(ResolvedIdentity.pending("demo_customer", "米可数码店", "2026-10-20 10:00:00"));

        Map<String, Object> resp = service.ask("怎么开店", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是「入驻审核中」", "店铺「米可数码店」", "于 2026-10-20 10:00:00",
                        "正在等平台审核");
        assertThat(resp).doesNotContainKey("action");
    }

    @Test
    @DisplayName("N2 被拒：报驳回原因；空原因回「未填写」")
    void rejectedTalk() {
        identity(ResolvedIdentity.rejected("demo_customer", "资料不全"));

        Map<String, Object> resp = service.ask("怎么开店", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是「入驻未通过」", "原因：资料不全");
        assertThat(actionOf(resp)).containsEntry("label", "重新申请");

        identity(ResolvedIdentity.rejected("demo_customer", ""));
        assertThat(String.valueOf(service.ask("怎么开店", "c1", 1).get("reply")))
                .contains("原因：未填写");
    }

    @Test
    @DisplayName("N2 商家：报商家号")
    void merchantTalk() {
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("怎么开店", "m1", 2);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是商家。", "商家号：m1。");
        assertThat(actionOf(resp)).containsEntry("routeKey", "apply");
    }

    @Test
    @DisplayName("N2 品牌商家：报品牌名与有效期")
    void brandTalk() {
        identity(ResolvedIdentity.brand("m1", "米可", "2099-01-01 00:00:00"));

        Map<String, Object> resp = service.ask("怎么开店", "m1", 2);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是品牌商家。", "品牌：米可", "有效期至 2099-01-01 00:00:00");
    }

    @Test
    @DisplayName("N2 匿名：先报未登录，再给开店引导")
    void anonymousTalk() {
        identity(ResolvedIdentity.anonymous());

        Map<String, Object> resp = service.ask("怎么开店", null, null);

        assertThat(String.valueOf(resp.get("reply"))).startsWith("您当前还未登录。");
        assertThat(actionOf(resp)).containsEntry("routeKey", "apply");
    }

    @Test
    @DisplayName("Q3 边界：role=3 回管理员话术，不参与入驻")
    void adminBoundary() {
        identity(ResolvedIdentity.admin());

        Map<String, Object> resp = service.ask("怎么开店", "admin", 3);

        assertThat(String.valueOf(resp.get("reply")))
                .startsWith("您当前是管理员账号。");
        assertThat(resp).doesNotContainKey("action");
    }

    @Test
    @DisplayName("Q3 边界：role=1 但 settle 已 approved ⇒ 已降级/冻结商家")
    void degradedBoundary() {
        identity(ResolvedIdentity.degradedMerchant("demo_customer", "m1"));

        Map<String, Object> resp = service.ask("怎么开店", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .startsWith("您当前是已降级/冻结商家。");
        assertThat(actionOf(resp)).containsEntry("label", "重新申请");
    }

    @Test
    @DisplayName("增量-A 规则 2：自称商家但系统查出无申请 ⇒ 两个胶囊（查进度 / 去入驻）")
    void claimMerchantButNoApplication() {
        identity(ResolvedIdentity.noApplication("demo_customer"));

        Map<String, Object> resp = service.ask("我是商家", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("IDENTITY_CLAIM");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是普通客户", "可以查一下入驻进度");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("applyStatus", "apply");
    }

    @Test
    @DisplayName("自称商家且系统也判定商家 ⇒ 不出现「去入驻」胶囊")
    void claimMerchantMatches() {
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我是商家", "m1", 2);

        assertThat(String.valueOf(resp.get("reply"))).contains("系统显示您当前是商家。");
        assertThat(objectSuggestions(resp)).isEmpty();
    }

    // ==================== N3：ADD_TO_SECKILL 六态分支 ====================

    @Test
    @DisplayName("N3 商家：只给秒杀报名导航胶囊")
    void seckillMerchant() {
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我要报名秒杀", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ADD_TO_SECKILL");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是商家。", "带你去秒杀报名页");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("seckill");
    }

    @Test
    @DisplayName("N3 品牌商家：同样给秒杀报名导航")
    void seckillBrand() {
        identity(ResolvedIdentity.brand("m1", "米可", null));

        Map<String, Object> resp = service.ask("限时抢购怎么参加", "m1", 2);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是品牌商家。", "带你去秒杀报名页");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("seckill");
    }

    @Test
    @DisplayName("N3 无申请：复用开店漫游，不给秒杀导航")
    void seckillNoApplication() {
        identity(ResolvedIdentity.noApplication("demo_customer"));

        Map<String, Object> resp = service.ask("报名活动", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是普通客户", "需要先完成商家入驻");
        assertThat(actionOf(resp)).containsEntry("routeKey", "apply");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .doesNotContain("seckill");
    }

    @Test
    @DisplayName("N3 审核中：只答不导航")
    void seckillPending() {
        identity(ResolvedIdentity.pending("demo_customer", "米可数码店", "2026-10-20 10:00:00"));

        Map<String, Object> resp = service.ask("我要参加秒杀", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("「入驻审核中」", "审核通过后就可以报名秒杀");
        assertThat(resp).doesNotContainKey("action");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .contains("applyStatus")
                .doesNotContain("seckill");
    }

    @Test
    @DisplayName("N3 被拒：给「重新申请」胶囊")
    void seckillRejected() {
        identity(ResolvedIdentity.rejected("demo_customer", "资料不全"));

        Map<String, Object> resp = service.ask("加入秒杀活动", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("「入驻未通过」", "原因：资料不全", "重新申请");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("apply");
    }

    @Test
    @DisplayName("N3 匿名：给「登录后去报名」，由前端 pendingTour 续接")
    void seckillAnonymous() {
        identity(ResolvedIdentity.anonymous());

        Map<String, Object> resp = service.ask("我要报名秒杀", null, null);

        assertThat(String.valueOf(resp.get("reply")))
                .startsWith("您当前还未登录。");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("seckill");
    }

    @Test
    @DisplayName("N3 边界：降级商家先重新入驻，管理员不参与")
    void seckillBoundaries() {
        identity(ResolvedIdentity.degradedMerchant("demo_customer", "m1"));
        Map<String, Object> degraded = service.ask("我要报名秒杀", "c1", 1);
        assertThat(String.valueOf(degraded.get("reply")))
                .contains("已降级/冻结商家", "重新入驻通过后");
        assertThat(objectSuggestions(degraded))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("apply");

        identity(ResolvedIdentity.admin());
        Map<String, Object> admin = service.ask("我要报名秒杀", "admin", 3);
        assertThat(String.valueOf(admin.get("reply")))
                .contains("管理员账号", "不参与秒杀报名");
    }

    // ==================== 增量：FAQ 兜底 ====================

    @Test
    @DisplayName("FAQ：政策问题先于售后意图（「拆封了能退货吗」不会被当成查售后）")
    void faqPolicyBeatsRefund() {
        Map<String, Object> resp = service.ask("拆封了能退货吗？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("FAQ");
        assertThat(String.valueOf(resp.get("reply")))
                .isEqualTo("支持7天无理由，但需保证不影响二次销售哦。");
        verify(refundService, org.mockito.Mockito.never())
                .myRefunds(anyString(), anyInt());
    }

    @Test
    @DisplayName("FAQ：UNKNOWN 命中运费问题 ⇒ 标准答案")
    void faqUnknownShipping() {
        Map<String, Object> resp = service.ask("运费怎么算", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("FAQ");
        assertThat(String.valueOf(resp.get("reply"))).isEqualTo("本店满99包邮，偏远地区除外。");
    }

    @Test
    @DisplayName("FAQ 未命中：仍走原 UNKNOWN 兜底")
    void faqMissFallsBack() {
        Map<String, Object> resp = service.ask("今天天气不错", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("UNKNOWN");
        assertThat(String.valueOf(resp.get("reply"))).contains("我暂时听不懂这句话");
    }

    // ==================== 增量：短期记忆 ====================

    @Test
    @DisplayName("记忆：上一轮查订单，这一轮「到哪了」复用上一轮单号")
    void followUpReusesPreviousOrder() {
        when(memoryService.lastContext("c1"))
                .thenReturn(new SessionMemoryService.Context(ChatIntent.ORDER_QUERY, "r2vfwK6z3E"));
        when(orderDao.countByCustomer("c1")).thenReturn(1L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));
        when(orderDao.selectByOrderIdAndCustomer("c1", "r2vfwK6z3E"))
                .thenReturn(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0));

        Map<String, Object> resp = service.ask("到哪了？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(String.valueOf(resp.get("reply"))).contains("无线蓝牙耳机", "已发货（待收货）");
        verify(memoryService).remember(eq("c1"), eq("到哪了？"), eq(ChatIntent.ORDER_QUERY),
                eq("r2vfwK6z3E"), anyString());
    }

    @Test
    @DisplayName("记忆：没有上下文时「到哪了」仍是 UNKNOWN，不乱猜")
    void followUpWithoutContextStaysUnknown() {
        when(memoryService.lastContext("c1")).thenReturn(null);

        Map<String, Object> resp = service.ask("到哪了？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("UNKNOWN");
    }

    // ==================== 阶段一：LLM 路由 + 槽位 ====================

    @Test
    @DisplayName("阶段一：LLM 判 ADD_TO_SECKILL + need_publish=true ⇒ 两个胶囊（去发布商品 / 去秒杀报名）")
    void llmNeedPublishGivesTwoChips() {
        llmEnabled();
        when(llmClassifier.classify("我的新商品要上架秒杀页，还没上架呢"))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ADD_TO_SECKILL, null, true));
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我的新商品要上架秒杀页，还没上架呢", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ADD_TO_SECKILL");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("系统显示您当前是商家。", "商品还没上架", "上架商品", "秒杀报名页");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("publish", "seckill");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("label"))
                .containsExactly("去发布商品", "去秒杀报名");
    }

    @Test
    @DisplayName("阶段一：LLM 抽到 orderId 槽位 ⇒ 直接按单号查，不再问「哪一笔」")
    void llmOrderIdSlotIsUsed() {
        llmEnabled();
        when(llmClassifier.classify("dRD3gqtbFr 这个订单到哪了"))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, "dRD3gqtbFr", false));
        when(orderDao.countByCustomer("c1")).thenReturn(9L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));
        // LLM 槽位按用户原文回传，不走规则引擎的小写归一 ⇒ 按原样大小写查库。
        when(orderDao.selectByOrderIdAndCustomer("c1", "dRD3gqtbFr"))
                .thenReturn(order("dRD3gqtbFr", "Java 实战课", 1, 0, 0));

        Map<String, Object> resp = service.ask("dRD3gqtbFr 这个订单到哪了", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(String.valueOf(resp.get("reply"))).contains("订单 dRD3gqtbFr", "Java 实战课");
    }

    @Test
    @DisplayName("阶段一：LLM 超时 / 异常 ⇒ 降级规则引擎，仍然答得出来（不抛给 Controller）")
    void llmFailureFallsBackToRules() {
        llmEnabled();
        when(llmClassifier.classify(anyString()))
                .thenThrow(new LlmIntentClassifier.LlmException("请求超时（>3s）"));
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我要报名秒杀", "m1", 2);

        assertThat(resp.get("success")).isEqualTo(true);
        assertThat(resp.get("intent")).isEqualTo("ADD_TO_SECKILL");
        assertThat(String.valueOf(resp.get("reply"))).contains("带你去秒杀报名页");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("seckill");
    }

    @Test
    @DisplayName("阶段一：模型把「拆封了能退货吗」判成 REFUND，本地政策 FAQ 仍然优先")
    void llmRefundStillLosesToPolicyFaq() {
        llmEnabled();
        when(llmClassifier.classify("拆封了能退货吗？"))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.REFUND_QUERY, null, false));

        Map<String, Object> resp = service.ask("拆封了能退货吗？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("FAQ");
        assertThat(String.valueOf(resp.get("reply")))
                .isEqualTo("支持7天无理由，但需保证不影响二次销售哦。");
        verify(refundService, org.mockito.Mockito.never()).myRefunds(anyString(), anyInt());
    }

    @Test
    @DisplayName("阶段一：LLM 返回 UNKNOWN 时再走一次规则引擎（问候 / 帮助不在 Prompt 白名单里）")
    void llmUnknownDelegatesToRules() {
        llmEnabled();
        when(llmClassifier.classify(anyString()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.UNKNOWN, null, false));

        assertThat(service.ask("你好", "c1", 1).get("intent")).isEqualTo("GREETING");
        assertThat(service.ask("你能做什么", "c1", 1).get("intent")).isEqualTo("HELP");
    }

    @Test
    @DisplayName("阶段一：LLM 判 ORDER_QUERY 但没抽到单号，短追问「到哪了」用上一轮记忆补")
    void llmFollowUpBackfilledFromMemory() {
        llmEnabled();
        when(llmClassifier.classify("到哪了？"))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, null, false));
        when(memoryService.lastContext("c1"))
                .thenReturn(new SessionMemoryService.Context(ChatIntent.ORDER_QUERY, "r2vfwK6z3E"));
        when(orderDao.countByCustomer("c1")).thenReturn(1L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));
        when(orderDao.selectByOrderIdAndCustomer("c1", "r2vfwK6z3E"))
                .thenReturn(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0));

        Map<String, Object> resp = service.ask("到哪了？", "c1", 1);

        assertThat(String.valueOf(resp.get("reply"))).contains("订单 r2vfwK6z3E", "已发货（待收货）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> actionOf(Map<String, Object> resp) {
        Object action = resp.get("action");
        return action instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectSuggestions(Map<String, Object> resp) {
        Object raw = resp.get("suggestions");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }
}
