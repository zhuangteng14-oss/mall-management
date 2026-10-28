package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.model.ResolvedIdentity;
import com.minke.mallmanagement.chat.tool.AgentParam;
import com.minke.mallmanagement.chat.tool.AgentTool;
import com.minke.mallmanagement.chat.tool.ToolContext;
import com.minke.mallmanagement.chat.tool.ToolExecutor;
import com.minke.mallmanagement.chat.tool.ToolRegistry;
import com.minke.mallmanagement.chat.tool.ToolResult;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private CommentDao commentDao;
    private LlmReplyComposer replyComposer;
    private GoodsDao goodsDao;
    private SemanticRouter semanticRouter;
    private ChatAgentService service;

    @BeforeEach
    void setUp() {
        orderDao = mock(OrderDao.class);
        refundService = mock(OrderRefundService.class);
        identityResolver = mock(IdentityResolver.class);
        memoryService = mock(SessionMemoryService.class);
        llmClassifier = mock(LlmIntentClassifier.class);
        commentDao = mock(CommentDao.class);
        replyComposer = mock(LlmReplyComposer.class);
        goodsDao = mock(GoodsDao.class);
        semanticRouter = mock(SemanticRouter.class);
        // 默认：LLM 未启用（Mockito 布尔默认 false）⇒ 既有用例全部走规则引擎，行为与阶段一之前一致。
        // 默认：话术生成也不启用 ⇒ 走确定性模板，断言可以逐字写。
        // 默认：普通客户、无入驻申请；未登录走匿名。个别用例再覆盖。
        when(identityResolver.resolve(anyString(), anyInt()))
                .thenReturn(ResolvedIdentity.noApplication("demo_customer"));
        when(identityResolver.resolve(isNull(), isNull()))
                .thenReturn(ResolvedIdentity.anonymous());
        service = new ChatAgentService(orderDao, refundService, new OrderStateMachine(),
                identityResolver, memoryService, llmClassifier, commentDao, replyComposer,
                null, null, goodsDao);
        // 默认不命中语义层（Mockito 返回 null）⇒ 既有用例仍走 LLM / 规则引擎。
        service.setSemanticRouter(semanticRouter);
    }

    /** 打开 LLM 路由；具体分类结果由用例再 stub {@code classify}。 */
    private void llmEnabled() {
        when(llmClassifier.isEnabled()).thenReturn(true);
    }

    private void identity(ResolvedIdentity resolved) {
        when(identityResolver.resolve(anyString(), anyInt())).thenReturn(resolved);
    }

    // ==================== 插件化（2026-10-22）：工具由注册表分发 ====================

    /** 测试用插件工具：不在 {@code ChatToolSet} 里，也没有任何 switch 分支认识它。 */
    static class EchoTool {
        static final AtomicReference<String> LAST_CALL = new AtomicReference<>();

        @AgentTool(name = "echo_tool", description = "回显一条消息", intent = ChatIntent.UNKNOWN)
        public ToolResult echo(@AgentParam(name = "echo", description = "回显文本", required = false)
                               String echo, ToolContext ctx) {
            LAST_CALL.set(echo + "|" + ctx.role() + "|" + ctx.userId());
            return ToolResult.of("工具回显：" + echo, null);
        }
    }

    private ToolRegistry registryWith(Class<?>... beanClasses) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(ObjectMapper.class);
        for (Class<?> bean : beanClasses) {
            ctx.register(bean);
        }
        ctx.refresh();
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.setApplicationContext(ctx);
        registry.scan();
        return registry;
    }

    /** 装好注册表的编排层（其它依赖仍是替身）。 */
    private ChatAgentService serviceWithTools(ToolRegistry registry) {
        return new ChatAgentService(orderDao, refundService, new OrderStateMachine(),
                identityResolver, memoryService, llmClassifier, commentDao, replyComposer,
                registry, new ToolExecutor(registry), goodsDao);
    }

    @Test
    @DisplayName("插件化：模型选了注册表里的工具 ⇒ 直接执行它（身份由 ToolContext 注入），不经 switch")
    void executesToolSelectedByModel() {
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.UNKNOWN, null, false, null,
                        "echo_tool", Map.of("echo", "你好")));
        EchoTool.LAST_CALL.set(null);
        ChatAgentService service = serviceWithTools(registryWith(EchoTool.class));

        Map<String, Object> resp = service.ask("随便说一句", "c1", 1);

        assertThat(resp.get("success")).isEqualTo(true);
        assertThat(resp.get("reply")).isEqualTo("工具回显：你好");
        // 身份只从会话参数注入，不从 slots 读。
        assertThat(EchoTool.LAST_CALL.get()).isEqualTo("你好|1|c1");
    }

    @Test
    @DisplayName("插件化：注册表里没有对应工具（问候）⇒ 回退既有 switch，行为不变")
    void fallsBackToSwitchWhenNoToolMatches() {
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.GREETING, null, false, null));
        ChatAgentService service = serviceWithTools(registryWith(EchoTool.class));

        Map<String, Object> resp = service.ask("你好", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GREETING");
        assertThat(String.valueOf(resp.get("reply"))).contains("你好呀");
    }

    /** 永远抛错的插件工具：验证编排层吞掉工具异常、回退内置分支。 */
    static class BrokenTool {
        @AgentTool(name = "broken_tool", description = "永远抛错", intent = ChatIntent.UNKNOWN)
        public ToolResult boom() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    @DisplayName("插件化：工具执行抛错 ⇒ 回退既有 switch（用户不会看到 500）")
    void toolFailureFallsBackToSwitch() {
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, null, false, null,
                        "broken_tool", Map.of()));
        ChatAgentService service = serviceWithTools(registryWith(EchoTool.class, BrokenTool.class));

        Map<String, Object> resp = service.ask("查我的订单", "c1", 1);

        // 工具抛错 → ToolException → 回退 switch 的 ORDER_QUERY 分支（DAO 替身答「没有下单记录」）。
        assertThat(resp.get("success")).isEqualTo(true);
        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(String.valueOf(resp.get("reply"))).contains("下单记录");
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
    @DisplayName("怎么开店：默认给【开始引导】（不直接跳申请页）")
    void openStoreReturnsTourAction() {
        Map<String, Object> resp = service.ask("怎么开店", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("OPEN_STORE");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "开始引导");
        assertThat(String.valueOf(resp.get("reply"))).contains("一步步教您");
    }

    @Test
    @DisplayName("怎么申请品牌：默认【开始引导】，落点是入驻申请页")
    void brandApplyDefaultsToGuide() {
        Map<String, Object> resp = service.ask("怎么申请品牌", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("OPEN_STORE");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "开始引导");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("好的，我来一步步教您。点击下方按钮开始。");
    }

    @Test
    @DisplayName("直接带我去申请品牌：明说直达 ⇒ 【一键直达】，不触发漫游")
    void brandApplyWithDirectPhraseReturnsJump() {
        Map<String, Object> resp = service.ask("直接带我去申请品牌", "c1", 1);

        assertThat(actionOf(resp)).containsEntry("type", "jump")
                .containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "一键直达");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("已为您准备好直达入口，点击即可前往。");
    }

    @Test
    @DisplayName("我要去商家后台：直达口令 ⇒ 导航动作是 jump")
    void directPhraseOpensJumpAction() {
        Map<String, Object> resp = service.ask("我要去商家后台", "m1", 2);

        assertThat(actionOf(resp)).containsEntry("type", "jump")
                .containsEntry("label", "一键直达");
        assertThat(String.valueOf(resp.get("reply"))).contains("已为您准备好直达入口");
    }

    @Test
    @DisplayName("直达判据：只有明确口令才算直达，问「怎么去」不算")
    void directPhraseDetection() {
        assertThat(ChatAgentService.isDirectNavigation("直接带我去申请品牌")).isTrue();
        assertThat(ChatAgentService.isDirectNavigation("我要去商家后台")).isTrue();
        assertThat(ChatAgentService.isDirectNavigation("马上打开秒杀报名")).isTrue();
        assertThat(ChatAgentService.isDirectNavigation("怎么申请品牌")).isFalse();
        assertThat(ChatAgentService.isDirectNavigation("怎么去商家后台")).isFalse();
        assertThat(ChatAgentService.isDirectNavigation("如何上架商品")).isFalse();
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
        assertThat(actionOf(resp)).containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "开始引导");
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
        assertThat(actionOf(resp)).containsEntry("label", "开始引导")
                .containsEntry("targetPath", "/customer/user/apply");

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
        assertThat(actionOf(resp)).containsEntry("targetPath", "/merchant/user/apply")
                .containsEntry("label", "开始引导");
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
        assertThat(actionOf(resp)).containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "开始引导");
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
        assertThat(actionOf(resp)).containsEntry("label", "开始引导")
                .containsEntry("targetPath", "/customer/user/apply");
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
        assertThat(actionOf(resp)).containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "开始引导");
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
                .isEqualTo("已拆封的商品，只要不影响二次销售（吊牌、包装完整），7 天内可以无理由退货。");
        verify(refundService, org.mockito.Mockito.never())
                .myRefunds(anyString(), anyInt());
    }

    @Test
    @DisplayName("FAQ：UNKNOWN 命中运费问题 ⇒ 标准答案")
    void faqUnknownShipping() {
        Map<String, Object> resp = service.ask("运费怎么算", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("FAQ");
        assertThat(String.valueOf(resp.get("reply")))
                .isEqualTo("运费按订单金额算：满99元包邮；不满99元邮费由买家承担，偏远地区除外。");
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
        when(llmClassifier.classify(eq("我的新商品要上架秒杀页，还没上架呢"), any(), isNull()))
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
        when(llmClassifier.classify(eq("dRD3gqtbFr 这个订单到哪了"), any(), isNull()))
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
        when(llmClassifier.classify(anyString(), any(), isNull()))
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
        when(llmClassifier.classify(eq("拆封了能退货吗？"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.REFUND_QUERY, null, false));

        Map<String, Object> resp = service.ask("拆封了能退货吗？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("FAQ");
        assertThat(String.valueOf(resp.get("reply")))
                .isEqualTo("已拆封的商品，只要不影响二次销售（吊牌、包装完整），7 天内可以无理由退货。");
        verify(refundService, org.mockito.Mockito.never()).myRefunds(anyString(), anyInt());
    }

    @Test
    @DisplayName("阶段一：LLM 返回 UNKNOWN 时再走一次规则引擎（问候 / 帮助不在 Prompt 白名单里）")
    void llmUnknownDelegatesToRules() {
        llmEnabled();
        when(llmClassifier.classify(anyString(), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.UNKNOWN, null, false));

        assertThat(service.ask("你好", "c1", 1).get("intent")).isEqualTo("GREETING");
        assertThat(service.ask("你能做什么", "c1", 1).get("intent")).isEqualTo("HELP");
    }

    @Test
    @DisplayName("阶段一：LLM 判 ORDER_QUERY 但没抽到单号，短追问「到哪了」用上一轮记忆补")
    void llmFollowUpBackfilledFromMemory() {
        llmEnabled();
        when(llmClassifier.classify(eq("到哪了？"), any(), isNull()))
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

    // ==================== NavGraph：target_path → navigate action ====================

    @Test
    @DisplayName("NavGraph：LLM 判 NAVIGATE ⇒ 只回 target_path，默认给「开始引导」")
    void llmNavigateReturnsAction() {
        llmEnabled();
        when(llmClassifier.classify(eq("怎么查看店铺数据"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.NAVIGATE, null, false, "/merchant/stats"));

        Map<String, Object> resp = service.ask("怎么查看店铺数据", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp))
                .containsEntry("type", "tour")
                .containsEntry("targetPath", "/merchant/stats")
                .containsEntry("targetLabel", "经营数据")
                .containsEntry("label", "开始引导");
        // 后端不返回步骤数组：怎么走是前端 NavGraph 的事。
        assertThat(resp).doesNotContainKey("steps");
        // 「幽灵前缀」修复：回复不再拼页面描述（否则页面选错时会先冒出「收藏的商品。」）。
        assertThat(String.valueOf(resp.get("reply"))).isEqualTo("好的，我带您去「经营数据」，点下面按钮开始。");
    }

    @Test
    @DisplayName("NavGraph：target_path 归到入驻申请 ⇒ 复用既有开店六态（仍是 tour action）")
    void llmNavigateApplyKeepsOpenStoreBehaviour() {
        llmEnabled();
        when(llmClassifier.classify(eq("怎么入驻开店"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.NAVIGATE, null, false, "/customer/user/apply"));

        Map<String, Object> resp = service.ask("怎么入驻开店", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("OPEN_STORE");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/customer/user/apply")
                .containsEntry("label", "开始引导");
    }

    @Test
    @DisplayName("模型漏抽 need_publish，但用户明说「还没上架」⇒ 规则侧补上，仍给两枚胶囊")
    void needPublishAlsoComesFromMessage() {
        llmEnabled();
        when(llmClassifier.classify(eq("我的新商品要上架秒杀页，还没上架呢"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ADD_TO_SECKILL, null, false));
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我的新商品要上架秒杀页，还没上架呢", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ADD_TO_SECKILL");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("publish", "seckill");
    }

    @Test
    @DisplayName("模型超时降级：规则引擎也认「还没上架」这个前置条件（不新增意图正则）")
    void ruleFallbackKeepsNeedPublish() {
        // 默认 llmClassifier 未启用 ⇒ 直接走 byRules；SECKILL 正则命中，前置条件由消息补。
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我的新商品要上架秒杀页，还没上架呢", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ADD_TO_SECKILL");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("publish", "seckill");
    }

    @Test
    @DisplayName("NavGraph：target_path 归到秒杀报名时，need_publish 槽位一并带过去（仍是两枚胶囊）")
    void navigateToSeckillKeepsNeedPublish() {
        llmEnabled();
        when(llmClassifier.classify(eq("我的新商品要上架秒杀页，还没上架呢"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.NAVIGATE, null, true, "/merchant/seckill"));
        identity(ResolvedIdentity.merchant("m1"));

        Map<String, Object> resp = service.ask("我的新商品要上架秒杀页，还没上架呢", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ADD_TO_SECKILL");
        assertThat(objectSuggestions(resp))
                .extracting((m) -> m.get("routeKey"))
                .containsExactly("publish", "seckill");
    }

    @Test
    @DisplayName("NavGraph：模型把「怎么上架商品」判成开店 ⇒ 字典指向发布页，以字典为准")
    void catalogOverridesWrongOpenStoreIntent() {
        llmEnabled();
        when(llmClassifier.classify(eq("怎么上架商品"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.OPEN_STORE, null, false));

        Map<String, Object> resp = service.ask("怎么上架商品", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp))
                .containsEntry("type", "tour")
                .containsEntry("targetPath", "/merchant/user/add")
                .containsEntry("label", "开始引导");
    }

    @Test
    @DisplayName("模型超时降级：「怎么处理退款」在规则侧也走字典导航；问状态仍留数据查询")
    void ruleFallbackNavigatesRefundHowTo() {
        // 默认 llmClassifier 未启用 ⇒ byRules；规则正则先命中「退款」，再由字典纠偏成导航。
        Map<String, Object> resp = service.ask("怎么处理退款", "m1", 2);
        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp)).containsEntry("targetPath", "/merchant/after-sales");

        when(refundService.myRefunds("c1", 1)).thenReturn(Map.of("list", List.of()));
        Map<String, Object> status = service.ask("我的退款到哪了", "c1", 1);
        assertThat(status.get("intent")).isEqualTo("REFUND_QUERY");
        assertThat(actionOf(status)).doesNotContainEntry("type", "navigate");
    }

    @Test
    @DisplayName("LLM 不可用：规则降级仍能从路由字典命中页面（不新增意图正则）")
    void ruleFallbackNavigatesFromCatalog() {
        // 默认 llmClassifier.isEnabled()=false ⇒ 直接走 byRules。
        Map<String, Object> resp = service.ask("我想改一下那个 299 元耳机的价格", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp))
                .containsEntry("type", "tour")
                .containsEntry("targetPath", "/merchant/user/list");
    }

    @Test
    @DisplayName("LLM 超时降级：「怎么上架商品」的「上架」不算开店，字典指向发布页")
    void ruleFallbackPublishFromCatalog() {
        Map<String, Object> resp = service.ask("怎么上架商品", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp))
                .containsEntry("type", "tour")
                .containsEntry("targetPath", "/merchant/user/add")
                .containsEntry("label", "开始引导");
    }

    @Test
    @DisplayName("NavGraph：模型把小问题判成 ORDER_QUERY，字典里有明确页面说法 ⇒ 改判导航")
    void catalogOverridesWrongDataIntent() {
        llmEnabled();
        when(llmClassifier.classify(eq("怎么处理退款"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, null, false));

        Map<String, Object> resp = service.ask("怎么处理退款", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp)).containsEntry("targetPath", "/customer/orders");
    }

    @Test
    @DisplayName("NavGraph：售后句模型选了别的页面（会员中心）⇒ 按字典纠正到我的订单")
    void catalogOverridesWrongNavigateTarget() {
        llmEnabled();
        when(llmClassifier.classify(eq("怎么处理退款"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(
                        ChatIntent.NAVIGATE, null, false, "/customer/user/member"));

        Map<String, Object> resp = service.ask("怎么处理退款", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp)).containsEntry("targetPath", "/customer/orders");
    }

    @Test
    @DisplayName("NavGraph：用户直接说页面名（「查我的订单」）⇒ 仍回答数据，落点不随模型判断漂移")
    void declaredPageNameKeepsDataQuery() {
        llmEnabled();
        when(llmClassifier.classify(eq("查我的订单"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, null, false));
        when(orderDao.countByMerchant("m1")).thenReturn(1L);
        when(orderDao.selectRecentByMerchant(eq("m1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));

        Map<String, Object> resp = service.ask("查我的订单", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(actionOf(resp)).doesNotContainEntry("type", "navigate");
        assertThat(String.valueOf(resp.get("reply"))).contains("单号 r2vfwK6z3E");
    }

    @Test
    @DisplayName("NavGraph：问「我那一笔到哪了」仍然是数据查询，不被带去订单页")
    void statusQuestionStaysOrderQuery() {
        llmEnabled();
        when(llmClassifier.classify(eq("我的订单到哪了"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, null, false));
        when(orderDao.countByCustomer("c1")).thenReturn(0L);

        Map<String, Object> resp = service.ask("我的订单到哪了", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(actionOf(resp)).doesNotContainEntry("type", "navigate");
    }

    @Test
    @DisplayName("NavGraph：模型给了当前角色没有的路径 ⇒ 降级规则引擎，不返回 navigate")
    void llmNavigateRejectsForeignPath() {
        llmEnabled();
        // 分类器自己也会抛；这里模拟「已经过了分类器但路径仍不可归位」的兜底。
        when(llmClassifier.classify(eq("我要看商家报表"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.NAVIGATE, null, false, "/customer/stats"));

        Map<String, Object> resp = service.ask("我要看商家报表", "c1", 1);

        assertThat(actionOf(resp)).doesNotContainEntry("type", "navigate");
    }

    // ==================== 业务工具挂载（2026-10-22）：评论数据 + 话术回喂 ====================

    @Test
    @DisplayName("工具挂载：商家「我要看评论」⇒ COMMENT_QUERY 真查 DAO，播报条数与未回复数，不再导航")
    void commentQueryReadsRealMerchantComments() {
        when(commentDao.countByMerchant("m1")).thenReturn(5L);
        when(commentDao.countUnansweredByMerchant("m1")).thenReturn(2L);
        when(commentDao.selectRecentByMerchant(eq("m1"), anyInt())).thenReturn(List.of(
                commentRow(101, "review", 5, "很好用，续航给力", "无线蓝牙耳机", 1),
                commentRow(102, "question", 0, "支持降噪吗？", "机械键盘", 0)));

        Map<String, Object> resp = service.ask("我要看评论", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("COMMENT_QUERY");
        assertThat(actionOf(resp)).doesNotContainEntry("type", "navigate");
        String reply = String.valueOf(resp.get("reply"));
        assertThat(reply).contains("5 条").contains("2 条还没有商家回复")
                .contains("无线蓝牙耳机").contains("商家").doesNotContain("带你去");
        Map<String, Object> data = dataOf(resp);
        assertThat(data).containsEntry("scope", "merchant").containsEntry("total", 5L)
                .containsEntry("unanswered", 2L);
        List<Map<String, Object>> comments = commentsOf(data);
        assertThat(comments).hasSize(2);
        assertThat(comments.get(0)).containsEntry("kindLabel", "评价").containsEntry("rating", 5)
                .containsEntry("merchantReplied", true);
        assertThat(comments.get(1)).containsEntry("kindLabel", "提问").containsEntry("merchantReplied", false);
        // 商家分支不该去换算登录名（那是客户分支的事）。
        verify(commentDao, never()).selectUsernameByUserId(anyString());
    }

    @Test
    @DisplayName("工具挂载：客户「看评论」按登录名查自己的评论，并说明商家是否回复")
    void commentQueryReadsOwnComments() {
        when(commentDao.selectUsernameByUserId("c1")).thenReturn("1111");
        when(commentDao.countByAuthor("1111")).thenReturn(1L);
        when(commentDao.selectRecentByAuthor(eq("1111"), anyInt())).thenReturn(List.of(
                commentRow(201, "review", 4, "做工扎实，充电快", "Type-C 快充数据线", 1)));

        Map<String, Object> resp = service.ask("我发过的评论", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("COMMENT_QUERY");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("1 条").contains("Type-C 快充数据线").contains("商家已经回复");
        assertThat(dataOf(resp)).containsEntry("scope", "customer").containsEntry("total", 1L)
                .doesNotContainKey("unanswered");
        verify(commentDao, never()).countByMerchant(anyString());
    }

    @Test
    @DisplayName("工具挂载：模型把「我要看评论」判成 NAVIGATE / ORDER_QUERY，一律纠偏到 COMMENT_QUERY")
    void commentQueryOverridesWrongModelIntent() {
        llmEnabled();
        when(llmClassifier.classify(eq("我要看评论"), any(), isNull()))
                .thenReturn(new LlmIntentClassifier.Result(
                        ChatIntent.NAVIGATE, null, false, "/customer/orders"));
        when(commentDao.selectUsernameByUserId("c1")).thenReturn("1111");
        when(commentDao.countByAuthor("1111")).thenReturn(0L);

        Map<String, Object> resp = service.ask("我要看评论", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("COMMENT_QUERY");
        assertThat(actionOf(resp)).doesNotContainEntry("type", "navigate");
        assertThat(String.valueOf(resp.get("reply"))).contains("还没有发表过评论");
    }

    @Test
    @DisplayName("工具挂载：真实数据回喂模型生成话术；模型话术优先于模板，事实里带真数字")
    void composerReceivesRealCommentFacts() {
        when(replyComposer.isEnabled()).thenReturn(true);
        when(replyComposer.compose(anyString(), eq("COMMENT_QUERY"), anyString()))
                .thenReturn("您店里最近有买家留言，还有 2 条没回，抽空看看吧～");
        when(commentDao.countByMerchant("m1")).thenReturn(5L);
        when(commentDao.countUnansweredByMerchant("m1")).thenReturn(2L);
        when(commentDao.selectRecentByMerchant(eq("m1"), anyInt())).thenReturn(List.of(
                commentRow(101, "review", 5, "很好用，续航给力", "无线蓝牙耳机", 0)));

        Map<String, Object> resp = service.ask("有没有人评论我的商品", "m1", 2);

        assertThat(resp.get("reply")).isEqualTo("您店里最近有买家留言，还有 2 条没回，抽空看看吧～");
        ArgumentCaptor<String> facts = ArgumentCaptor.forClass(String.class);
        verify(replyComposer).compose(anyString(), eq("COMMENT_QUERY"), facts.capture());
        assertThat(facts.getValue()).contains("总数：5").contains("还没有商家回复：2")
                .contains("无线蓝牙耳机").contains("很好用，续航给力");
    }

    @Test
    @DisplayName("工具挂载：订单同样走「查库 → 事实 → 模型话术」，但 data/胶囊保持原契约")
    void composerReceivesRealOrderFacts() {
        when(replyComposer.isEnabled()).thenReturn(true);
        when(replyComposer.compose(anyString(), eq("ORDER_QUERY"), anyString()))
                .thenReturn("您最近有一笔耳机订单，正在等商家发货。");
        when(orderDao.countByCustomer("c1")).thenReturn(1L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt()))
                .thenReturn(List.of(order("r2vfwK6z3E", "无线蓝牙耳机", 1, 1, 0)));

        Map<String, Object> resp = service.ask("查我的订单", "c1", 1);

        assertThat(resp.get("reply")).isEqualTo("您最近有一笔耳机订单，正在等商家发货。");
        assertThat(dataOf(resp)).containsEntry("total", 1L);
        assertThat(objectSuggestions(resp)).anySatisfy(s -> assertThat(s).containsEntry("routeKey", "orders"));
        ArgumentCaptor<String> facts = ArgumentCaptor.forClass(String.class);
        verify(replyComposer).compose(anyString(), eq("ORDER_QUERY"), facts.capture());
        assertThat(facts.getValue()).contains("单号 r2vfwK6z3E").contains("已发货（待收货）");
    }

    // ==================== 商品查询（2026-10-22 修复） ====================

    @Test
    @DisplayName("商品查询：LLM 判 UNKNOWN 时不再命中 intent 留空的插件工具，而是查商品并明确说未找到")
    void goodsQueryWithPrefixedCodeIsNotFound() {
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.UNKNOWN, null, false, null));
        when(goodsDao.selectForChat(eq("222:ij-555"), anyInt())).thenReturn(List.of());

        Map<String, Object> resp = service.ask("怎么看 222:ij-555 商品的详细数据", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GOODS_QUERY");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("未找到商品").contains("222:ij-555");
        verify(goodsDao).selectForChat(eq("222:ij-555"), anyInt());
        // 商品编号不能被当成订单号，也不该触发「你最近有 N 笔订单」。
        verify(orderDao, never()).selectRecentByCustomer(anyString(), anyInt());
    }

    @Test
    @DisplayName("商品查询：只回买家该看的字段（名称/价格/库存/分类/状态），内部 Id 与店铺号不外泄")
    void goodsQueryExistingProductReturnsSanitizedData() {
        Map<String, Object> row = new HashMap<>();
        row.put("Id", 121);
        row.put("MerchantId", "5N1k5K2guh");
        row.put("GoodName", "oppo12");
        row.put("ProductCode", "OP2");
        row.put("GoodType", "电子用品");
        row.put("Price", 1888.0);
        row.put("OriginalPrice", 1999.0);
        row.put("ShelfState", 1);
        row.put("Status", 2);
        row.put("SkuCount", 4);
        row.put("StockTotal", 400);
        when(goodsDao.selectForChat(eq("oppo12"), anyInt())).thenReturn(List.of(row));

        Map<String, Object> resp = service.ask("oppo12 多少钱", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GOODS_QUERY");
        String reply = String.valueOf(resp.get("reply"));
        assertThat(reply)
                .contains("为您查到商品「oppo12」").contains("1888.00")
                .contains("库存充足").contains("分类 电子用品").contains("正在热卖中")
                .contains("直接带您去详情页");
        // 脱敏：内部商家号 / 内部编号绝不能出现在回复里。
        assertThat(reply).doesNotContain("5N1k5K2guh").doesNotContain("OP2")
                .doesNotContain("MerchantId");

        Map<String, Object> data = dataOf(resp);
        assertThat(data).containsEntry("found", true).containsEntry("total", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> goods = (List<Map<String, Object>>) data.get("goods");
        assertThat(goods).hasSize(1);
        assertThat(goods.get(0))
                .containsEntry("name", "oppo12").containsEntry("price", 1888.0)
                .containsEntry("stock", 400).containsEntry("stockLabel", "库存充足")
                .containsEntry("category", "电子用品").containsEntry("state", "在售")
                .doesNotContainKeys("Id", "MerchantId", "ProductCode");
        assertThat(objectSuggestions(resp))
                .anySatisfy(s -> assertThat(s).containsEntry("routeKey", "goods")
                        .containsEntry("keyword", "oppo12")
                        .containsEntry("label", "带我去商品详情"));
        verify(goodsDao).selectForChat(eq("oppo12"), anyInt());
    }

    @Test
    @DisplayName("商品查询：没有 SKU 行 ⇒ 库存如实说「以详情页为准」，不编数字")
    void goodsQueryWithoutSkuDoesNotInventStock() {
        Map<String, Object> row = new HashMap<>();
        row.put("GoodName", "oppo12");
        row.put("GoodType", "电子用品");
        row.put("Price", 1886.0);
        row.put("ShelfState", 1);
        row.put("Status", 2);
        row.put("SkuCount", 0);
        when(goodsDao.selectForChat(eq("oppo12"), anyInt())).thenReturn(List.of(row));

        Map<String, Object> resp = service.ask("oppo12 多少钱", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("库存以详情页为准").doesNotContain("库存充足");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> goods =
                (List<Map<String, Object>>) dataOf(resp).get("goods");
        assertThat(goods.get(0)).containsEntry("stock", null);
    }

    @Test
    @DisplayName("商品查询：已下架的商品不喊「热卖中」，也不给「带我去详情」的胶囊")
    void offShelfGoodsHasNoDetailCapsule() {
        Map<String, Object> row = new HashMap<>();
        row.put("GoodName", "oppo12");
        row.put("Price", 1886.0);
        row.put("ShelfState", 0);
        row.put("Status", 2);
        row.put("SkuCount", 4);
        row.put("StockTotal", 400);
        when(goodsDao.selectForChat(eq("oppo12"), anyInt())).thenReturn(List.of(row));

        Map<String, Object> resp = service.ask("oppo12 多少钱", "c1", 1);

        assertThat(String.valueOf(resp.get("reply")))
                .contains("已下架").doesNotContain("正在热卖中");
        assertThat(objectSuggestions(resp))
                .noneSatisfy(s -> assertThat(s).containsEntry("routeKey", "goods"));
    }

    @Test
    @DisplayName("商品查询纠偏：模型判成 ORDER_QUERY 也不行，带商品标记的句子一律走商品查询")
    void goodsQueryOverridesOrderMisroute() {
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.ORDER_QUERY, "222:ij-555", false, null));
        when(goodsDao.selectForChat(eq("222:ij-555"), anyInt())).thenReturn(List.of());

        Map<String, Object> resp = service.ask("怎么看 222:ij-555 商品的详细数据", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GOODS_QUERY");
        assertThat(String.valueOf(resp.get("reply"))).contains("未找到商品");
        verify(orderDao, never()).selectRecentByCustomer(anyString(), anyInt());
    }

    @Test
    @DisplayName("回归：intent=UNKNOWN 的插件工具不能被「按意图匹配」自动执行（pong 事故）")
    void unknownIntentNeverAutoExecutesPluginTool() {
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.UNKNOWN, null, false, null));
        EchoTool.LAST_CALL.set(null);
        ChatAgentService service = serviceWithTools(registryWith(EchoTool.class));

        Map<String, Object> resp = service.ask("今天天气不错", "c1", 1);

        assertThat(EchoTool.LAST_CALL.get()).isNull();
        assertThat(String.valueOf(resp.get("reply"))).doesNotContain("工具回显");
        assertThat(resp.get("intent")).isEqualTo("UNKNOWN");
    }

    private static Map<String, Object> commentRow(long id, String kind, int rating, String content,
                                                  String goods, int merchantReplies) {
        Map<String, Object> row = new HashMap<>();
        row.put("Id", id);
        row.put("Kind", kind);
        row.put("Rating", rating);
        row.put("Content", content);
        row.put("GoodName", goods);
        row.put("CreatedAt", "2026-10-20 10:00:00");
        row.put("ReplyCount", merchantReplies);
        row.put("MerchantReplies", merchantReplies);
        row.put("TargetId", String.valueOf(id + 1000));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> resp) {
        Object data = resp.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> commentsOf(Map<String, Object> data) {
        Object raw = data.get("comments");
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

    @SuppressWarnings("unchecked")
    // ==================== 语义路由（2026-10-22 企业级改造） ====================

    @Test
    @DisplayName("语义路由：怎么看我上架的商品 ⇒ GOODS_MANAGE，回复不再拼「收藏的商品」幽灵前缀")
    void semanticGoodsManageHasNoGhostPrefix() {
        when(semanticRouter.route(eq("怎么看我上架的商品"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.GOODS_MANAGE,
                        "/merchant/user/list", 0.917, "怎么看我的商品", 0.830, true));

        Map<String, Object> resp = service.ask("怎么看我上架的商品", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GOODS_MANAGE");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/merchant/user/list")
                .containsEntry("label", "开始引导");
        assertThat(resp.get("reply")).isEqualTo("好的，我带您去「查看商品」，点下面按钮开始。");
        assertThat(String.valueOf(resp.get("reply"))).doesNotContain("收藏");
        verify(llmClassifier, never()).classify(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("语义路由：怎么前往商家工作台 ⇒ MERCHANT_DASHBOARD，不再答「听不懂」")
    void semanticMerchantDashboard() {
        when(semanticRouter.route(eq("怎么前往商家工作台"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.MERCHANT_DASHBOARD,
                        "/merchant/user", 0.964, "怎么去商家工作台", 0.690, true));

        Map<String, Object> resp = service.ask("怎么前往商家工作台", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("MERCHANT_DASHBOARD");
        assertThat(actionOf(resp)).containsEntry("targetPath", "/merchant/user")
                .containsEntry("type", "tour");
        assertThat(resp.get("reply")).isEqualTo("好的，我带您去「个人中心」，点下面按钮开始。");
    }

    @Test
    @DisplayName("语义路由：我花出去的钱去哪了 ⇒ ASSET_QUERY，落会员中心（商家壳归位）")
    void semanticAssetQuery() {
        when(semanticRouter.route(eq("我花出去的钱去哪了"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.ASSET_QUERY,
                        "/customer/user/member", 1.0, "我花出去的钱去哪了", 0.709, true));

        Map<String, Object> resp = service.ask("我花出去的钱去哪了", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("ASSET_QUERY");
        assertThat(actionOf(resp)).containsEntry("targetPath", "/merchant/user/member")
                .containsEntry("type", "tour");
    }

    @Test
    @DisplayName("语义护栏：相似度 < 0.75 ⇒ LLM 二次判定也接不住时才 CLARIFY")
    void semanticLowScoreClarifies() {
        when(semanticRouter.route(eq("今天天气不错"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.ORDER_QUERY,
                        null, 0.57, "查我的订单", 0.54, false));
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any()))
                .thenReturn(new LlmIntentClassifier.Result(ChatIntent.UNKNOWN, null, false, null));

        Map<String, Object> resp = service.ask("今天天气不错", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("CLARIFY");
        assertThat(resp.get("reply"))
                .isEqualTo("没太听懂您的意思。您可以问我：搜商品、查订单、管理商品，或者去工作台。");
        assertThat(resp).doesNotContainKey("action");
        // 语义低分不再短路 LLM：必须再判一次。
        verify(llmClassifier).classify(eq("今天天气不错"), eq(2), isNull());
    }

    @Test
    @DisplayName("语义低分 + LLM 兜底：模型给出业务意图就照它走，不再澄清")
    void semanticLowScoreFallsBackToLlm() {
        when(semanticRouter.route(eq("我那个东西咋样了"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.CLARIFY,
                        null, 0.58, "查我的订单", 0.55, false));
        llmEnabled();
        when(llmClassifier.classify(any(), any(), any())).thenReturn(
                new LlmIntentClassifier.Result(ChatIntent.GOODS_MANAGE, null, false,
                        "/merchant/user/list"));

        Map<String, Object> resp = service.ask("我那个东西咋样了", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GOODS_MANAGE");
        verify(llmClassifier).classify(eq("我那个东西咋样了"), eq(2), isNull());
    }

    @Test
    @DisplayName("跨域购物：商家问「护膝在哪买」⇒ GOODS_QUERY + /merchant/search?kw=护膝")
    void merchantBuyingGetsSearchAction() {
        when(semanticRouter.route(eq("护膝在哪买"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.GOODS_MANAGE,
                        "/merchant/user/list", 0.66, "我要管理商品", 0.61, false));

        Map<String, Object> resp = service.ask("护膝在哪买", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GOODS_QUERY");
        assertThat(resp.get("reply").toString()).doesNotContain("管理商品");
        assertThat(actionOf(resp)).containsEntry("type", "jump")
                .containsEntry("targetPath", "/merchant/search?kw=%E6%8A%A4%E8%86%9D")
                .containsEntry("label", "去商城搜索");
        assertThat(objectSuggestions(resp))
                .anySatisfy(s -> assertThat(s).containsEntry("routeKey", "search")
                        .containsEntry("keyword", "护膝"));
    }

    @Test
    @DisplayName("跨域购物：买家问「oppo12这商品在哪购买」⇒ /customer/search?kw=oppo12")
    void customerBuyingGetsSearchAction() {
        when(semanticRouter.route(eq("oppo12这商品在哪购买？"), eq(1)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.GOODS_QUERY,
                        null, 0.55, "这个商品多少钱", 0.52, false));
        when(goodsDao.selectForChat(eq("oppo12"), anyInt())).thenReturn(List.of());

        Map<String, Object> resp = service.ask("oppo12这商品在哪购买？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GOODS_QUERY");
        assertThat(actionOf(resp)).containsEntry("type", "jump")
                .containsEntry("targetPath", "/customer/search?kw=oppo12");
    }

    @Test
    @DisplayName("澄清胶囊按角色：买家不出现「管理商品 / 工作台」")
    void buyerClarifyHasNoMerchantChips() {
        when(semanticRouter.route(eq("那个东西呢"), eq(1)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.CLARIFY,
                        null, 0.50, "查我的订单", 0.48, false));

        Map<String, Object> resp = service.ask("那个东西呢", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("CLARIFY");
        String chips = String.valueOf(resp.get("suggestions"));
        assertThat(chips).contains("去商城搜索", "查我的订单", "怎么去个人中心")
                .doesNotContain("管理商品", "工作台");
    }

    @Test
    @DisplayName("澄清胶囊按角色：商家才有「管理商品 / 前往商家工作台」")
    void merchantClarifyHasMerchantChips() {
        when(semanticRouter.route(eq("那个东西呢"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.CLARIFY,
                        null, 0.50, "查我的订单", 0.48, false));

        Map<String, Object> resp = service.ask("那个东西呢", "m1", 2);

        String chips = String.valueOf(resp.get("suggestions"));
        assertThat(chips).contains("去商城搜索", "查我的订单", "管理商品", "前往商家工作台");
    }

    @Test
    @DisplayName("复读机修复：上一轮也是 CLARIFY ⇒ 换话术且不再给角色选项")
    void secondClarifyChangesWording() {
        when(memoryService.lastContext("m1"))
                .thenReturn(new SessionMemoryService.Context(ChatIntent.CLARIFY, null));
        when(semanticRouter.route(eq("今天天气不错"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.CLARIFY,
                        null, 0.50, "查我的订单", 0.48, false));

        Map<String, Object> resp = service.ask("今天天气不错", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("CLARIFY");
        assertThat(resp.get("reply").toString())
                .isEqualTo("抱歉，我还是没太听懂。您可以直接输入商品名称去搜索，或者点下方按钮联系人工客服。");
        String chips = String.valueOf(resp.get("suggestions"));
        assertThat(chips).doesNotContain("管理商品", "工作台", "查我的订单");
    }

    @Test
    @DisplayName("语义路由 + 直接口令：直接带我去管理商品 ⇒ GOODS_MANAGE 但动作是 jump")
    void semanticGoodsManageHonoursDirectWording() {
        when(semanticRouter.route(eq("直接带我去管理商品"), eq(2)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.GOODS_MANAGE,
                        "/merchant/user/list", 0.88, "我要管理商品", 0.70, true));

        Map<String, Object> resp = service.ask("直接带我去管理商品", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GOODS_MANAGE");
        assertThat(actionOf(resp)).containsEntry("type", "jump")
                .containsEntry("targetPath", "/merchant/user/list")
                .containsEntry("label", "一键直达");
    }

    @Test
    @DisplayName("语义低分但用户直接贴商品编号 ⇒ 仍走 GOODS_QUERY，不澄清")
    void semanticLowScoreButExplicitGoodsCodeStillQueries() {
        when(semanticRouter.route(eq("oppo12 多少钱"), eq(1)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.ORDER_QUERY,
                        null, 0.67, "买了什么", 0.65, false));

        Map<String, Object> resp = service.ask("oppo12 多少钱", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GOODS_QUERY");
    }

    // ==================== 对抗性压测的确定性闸（2026-10-24） ====================

    @Test
    @DisplayName("对抗性：问「这个月花了多少钱」⇒ 资产查询 + 会员中心，不当成搜商品")
    void spendingGoesToMemberCenter() {
        Map<String, Object> resp = service.ask("帮我查一下这个月我花了多少钱买衣服？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("ASSET_QUERY");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/customer/user/member");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("钱包余额、优惠券和收支流水", "会员中心");
        // 确定性闸在语义 / LLM 之前，不能让模型把它吸到商品搜索。
        verify(llmClassifier, never()).classify(anyString(), any(), any());
    }

    @Test
    @DisplayName("对抗性：商家问「这个月赚了多少钱」⇒ 经营数据，不当成搜商品")
    void merchantRevenueGoesToStats() {
        Map<String, Object> resp = service.ask("我这个月赚了多少钱？", "m1", 2);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/merchant/stats");
        assertThat(String.valueOf(resp.get("reply"))).contains("经营数据");
    }

    @Test
    @DisplayName("对抗性：问优惠券 / 能不能便宜 ⇒ 会员中心，而不是泛泛的兜底话术")
    void couponGoesToMemberCenter() {
        Map<String, Object> resp = service.ask("有优惠券吗？能便宜点不？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("GUIDE_NAVIGATION");
        assertThat(actionOf(resp)).containsEntry("type", "tour")
                .containsEntry("targetPath", "/customer/user/member");
        assertThat(String.valueOf(resp.get("reply"))).contains("会员中心");
    }

    @Test
    @DisplayName("对抗性：问「你是谁 / 是不是 GPT」⇒ 如实说明是商城客服，不冒充通用模型")
    void botIdentityAnswersHonestly() {
        Map<String, Object> resp = service.ask("你是谁？你是GPT吗？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("BOT_IDENTITY");
        assertThat(resp.get("reply")).isEqualTo(
                "我是米可小助手，商城的智能客服，不是 GPT 之类的通用模型。"
                        + "我可以帮你查订单、售后，或带你去搜索商品。");
        assertThat(String.valueOf(resp.get("reply"))).doesNotContain("没太听懂");
    }

    @Test
    @DisplayName("对抗性：讲笑话 / 写代码 ⇒ 礼貌拒答并拉回电商，不暴露「不知道你在说什么」")
    void outOfScopeRefusesPolitely() {
        Map<String, Object> joke = service.ask("讲个笑话听听。", "c1", 1);
        Map<String, Object> code = service.ask("给我写一段Python代码。", "c1", 1);

        assertThat(joke.get("intent")).isEqualTo("OUT_OF_SCOPE");
        assertThat(code.get("intent")).isEqualTo("OUT_OF_SCOPE");
        assertThat(String.valueOf(joke.get("reply")))
                .contains("商城的智能客服").doesNotContain("没太听懂");
        assertThat(String.valueOf(code.get("reply")))
                .contains("商城的智能客服").doesNotContain("没太听懂");
        // 越界请求要覆盖上一轮上下文，否则下一句乱码会被当成「第二次澄清」而复读。
        verify(memoryService).remember(eq("c1"), eq("讲个笑话听听。"),
                eq(ChatIntent.OUT_OF_SCOPE), isNull(), anyString());
    }

    @Test
    @DisplayName("对抗性：越界请求之后的第一句乱码，仍用首次澄清话术（不复读）")
    void outOfScopeDoesNotPoisonNextClarify() {
        when(memoryService.lastContext("c1"))
                .thenReturn(new SessionMemoryService.Context(ChatIntent.OUT_OF_SCOPE, null));
        // 真实环境里乱码会先过语义层：低分 ⇒ CLARIFY。单测把这条路径显式造出来。
        when(semanticRouter.route(eq("asdfghjkl"), eq(1)))
                .thenReturn(new SemanticRouter.Result(ChatIntent.ORDER_QUERY,
                        null, 0.42, "查我的订单", 0.38, false));

        Map<String, Object> resp = service.ask("asdfghjkl", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("CLARIFY");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("没太听懂您的意思")
                .doesNotContain("抱歉，我还是没太听懂");
    }

    @Test
    @DisplayName("对抗性：带情绪的退款催问 ⇒ 先致歉再报退款状态，并给「联系人工客服」")
    void upsetRefundStartsWithApology() {
        Map<String, Object> row = new HashMap<>();
        row.put("OrderId", "r2vfwK6z3E");
        row.put("RefundStatus", 1);
        row.put("RefundAmount", 199.0);
        row.put("RefundReason", "不想要了");
        row.put("RefundApplyAt", "2026-10-20 10:00:00");
        row.put("RefundRetryCount", 0);
        when(refundService.myRefunds("c1", 1))
                .thenReturn(Map.of("success", true, "list", List.of(row)));

        Map<String, Object> resp = service.ask("人呢？客服死哪去了，我的退款到底什么时候退！", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("REFUND_QUERY");
        assertThat(String.valueOf(resp.get("reply")))
                .startsWith("非常抱歉让您久等了。").contains("待商家审核", "¥199.00");
        assertThat(String.valueOf(resp.get("suggestions"))).contains("联系人工客服");
    }

    @Test
    @DisplayName("对抗性：话术模型已先道歉时不再前缀「非常抱歉」，避免连续两句抱歉")
    void empathyIsNotDoubledWhenComposerAlreadyApologises() {
        Map<String, Object> row = new HashMap<>();
        row.put("OrderId", "r2vfwK6z3E");
        row.put("RefundStatus", 1);
        row.put("RefundAmount", 199.0);
        row.put("RefundRetryCount", 0);
        when(refundService.myRefunds("c1", 1))
                .thenReturn(Map.of("success", true, "list", List.of(row)));
        when(replyComposer.isEnabled()).thenReturn(true);
        when(replyComposer.compose(anyString(), eq("REFUND_QUERY"), anyString()))
                .thenReturn("实在抱歉让您久等了，非常理解您着急的心情。您这笔售后正在待商家审核。");

        Map<String, Object> resp = service.ask("人呢？我的退款到底什么时候退！", "c1", 1);

        assertThat(resp.get("reply")).isEqualTo(
                "实在抱歉让您久等了，非常理解您着急的心情。您这笔售后正在待商家审核。");
    }

    @Test
    @DisplayName("对抗性：最新一笔已取消却问「发货没 / 快递卡哪」⇒ 明说没有物流，请补单号")
    void cancelledOrderShippingQuestionIsClarified() {
        Map<String, Object> cancelled = order("cancelled01", "跑步护膝", 0, 0, 0);
        cancelled.put("CancelledAt", "2026-10-19 09:00:00");
        when(orderDao.countByCustomer("c1")).thenReturn(1L);
        when(orderDao.selectRecentByCustomer(eq("c1"), anyInt())).thenReturn(List.of(cancelled));

        Map<String, Object> resp = service.ask("那个快递怎么还没到，卡在哪儿了？", "c1", 1);

        assertThat(resp.get("intent")).isEqualTo("ORDER_QUERY");
        assertThat(String.valueOf(resp.get("reply")))
                .contains("已取消", "没有发货和物流记录", "把单号发我");
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
