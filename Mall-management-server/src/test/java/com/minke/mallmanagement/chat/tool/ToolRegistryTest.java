package com.minke.mallmanagement.chat.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolRegistry} 的扫描 / Schema / 角色过滤 / 反射调用判据。
 *
 * <p>用真实的 {@link AnnotationConfigApplicationContext}（不是 mock）：本类的价值就在
 * 「Bean 里加了注解，容器扫出来什么」，用替身验不出这一点。
 */
class ToolRegistryTest {

    // ==================== 测试用工具 Bean ====================

    enum Color { RED, BLUE }

    static class Tools {

        @AgentTool(name = "buyer_only", description = "买家独有的工具", targetRole = {1},
                intent = ChatIntent.ORDER_QUERY)
        public ToolResult buyer(@AgentParam(name = "orderId", description = "单号", required = false)
                                String orderId, ToolContext ctx) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderId", orderId);
            data.put("role", ctx.role());
            data.put("user", ctx.userId());
            return ToolResult.of("buyer-ok", data);
        }

        @AgentTool(name = "merchant_only", description = "商家独有的工具", targetRole = {2},
                intent = ChatIntent.ADD_TO_SECKILL)
        public ToolResult merchant(@AgentParam(name = "count", description = "数量") int count,
                                   ToolContext ctx) {
            return ToolResult.of("merchant-ok:" + count, null);
        }

        @AgentTool(name = "anyone", description = "所有角色都能用")
        public String anyone(@AgentParam(name = "tags", description = "标签") List<String> tags,
                             @AgentParam(name = "color", description = "颜色", required = false) Color color) {
            return "anyone:" + tags + ":" + color;
        }
    }

    /** 与 {@link Tools#buyer} 重名：保留先注册的那个。 */
    static class Duplicate {

        @AgentTool(name = "buyer_only", description = "重名的后来者")
        public String dup() {
            return "dup";
        }
    }

    /** 第一次扫描之后才注册的 Bean —— 证明「新增方法 → 重新扫描即生效」。 */
    static class LateTool {

        @AgentTool(name = "late_tool", description = "后注册的工具", targetRole = {2})
        public ToolResult late(ToolContext ctx) {
            return ToolResult.of("late-ok", Map.of("role", ctx.role()));
        }
    }

    // ==================== 工具方法 ====================

    private static AnnotationConfigApplicationContext context(Class<?>... beans) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(ObjectMapper.class);
        for (Class<?> bean : beans) {
            ctx.register(bean);
        }
        ctx.refresh();
        return ctx;
    }

    private static ToolRegistry registry(AnnotationConfigApplicationContext ctx) {
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.setApplicationContext(ctx);
        registry.scan();
        return registry;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("扫描：只收 @AgentTool 方法，参数反射成 JSON Schema（ToolContext 不进 Schema）")
    void scansAnnotatedMethodsAndBuildsSchema() {
        ToolRegistry registry = registry(context(Tools.class));

        assertThat(registry.names()).containsExactlyInAnyOrder("buyer_only", "merchant_only", "anyone");

        ToolDescriptor buyer = registry.find("buyer_only");
        assertThat(buyer.intent()).isEqualTo(ChatIntent.ORDER_QUERY);
        assertThat(buyer.targetRole()).containsExactly(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) buyer.schema().get("properties");
        assertThat(properties).containsOnlyKeys("orderId");
        assertThat(properties.get("orderId")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "string")
                .containsEntry("description", "单号");
        // ToolContext 是注入位，不能出现在 Schema 里。
        assertThat(properties).doesNotContainKey("ctx");
        assertThat(buyer.schema().get("required")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> merchantProps = (Map<String, Object>) registry.find("merchant_only")
                .schema().get("properties");
        assertThat(merchantProps.get("count")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "integer");
        assertThat(registry.find("merchant_only").schema().get("required")).isEqualTo(List.of("count"));

        @SuppressWarnings("unchecked")
        Map<String, Object> anyoneProps = (Map<String, Object>) registry.find("anyone")
                .schema().get("properties");
        assertThat(anyoneProps.get("tags")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "array");
        @SuppressWarnings("unchecked")
        Map<String, Object> color = (Map<String, Object>) anyoneProps.get("color");
        assertThat(color).containsEntry("type", "string").containsEntry("enum", List.of("RED", "BLUE"));
        assertThat(registry.find("anyone").schema().get("required")).isEqualTo(List.of("tags"));
    }

    @Test
    @DisplayName("角色过滤：买家看不到商家工具、商家看不到买家工具；空 targetRole = 所有角色")
    void filtersByRole() {
        ToolRegistry registry = registry(context(Tools.class));

        assertThat(registry.forRole(1)).extracting(ToolDescriptor::name)
                .contains("buyer_only", "anyone").doesNotContain("merchant_only");
        assertThat(registry.forRole(2)).extracting(ToolDescriptor::name)
                .contains("merchant_only", "anyone").doesNotContain("buyer_only");
        assertThat(registry.forRole(3)).extracting(ToolDescriptor::name)
                .containsExactly("anyone");
        // 未登录按买家过滤（匿名也只能看到买家工具，绝不泄露商家工具）。
        assertThat(registry.forRole(null)).extracting(ToolDescriptor::name)
                .contains("buyer_only", "anyone").doesNotContain("merchant_only");
    }

    @Test
    @DisplayName("Prompt 目录按角色隔离：买家 Prompt 里没有商家工具名，反之亦然")
    void promptCatalogIsRoleScoped() {
        ToolRegistry registry = registry(context(Tools.class));

        String buyer = registry.promptCatalog(1);
        String merchant = registry.promptCatalog(2);

        assertThat(buyer).contains("buyer_only").doesNotContain("merchant_only")
                .contains("\"type\":\"object\"").contains("\"orderId\"");
        assertThat(merchant).contains("merchant_only").doesNotContain("buyer_only")
                .contains("\"count\"");
        assertThat(registry.promptCatalog(3)).doesNotContain("buyer_only", "merchant_only");
    }

    @Test
    @DisplayName("调用：ToolContext 自动注入 + 字符串参数按类型转换；角色不匹配直接拒")
    void executesWithContextInjectionAndRoleGuard() {
        ToolRegistry registry = registry(context(Tools.class));

        Object ok = registry.execute("merchant_only",
                new ToolContext("m1", 2, "我要报名秒杀"), Map.of("count", "7"));
        assertThat(ok).isInstanceOf(ToolResult.class);
        assertThat(((ToolResult) ok).reply()).isEqualTo("merchant-ok:7");

        Object withCtx = registry.execute("buyer_only",
                new ToolContext("c1", 1, "查订单"), Map.of("orderId", "ORD-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = ((ToolResult) withCtx).data();
        assertThat(data).containsEntry("orderId", "ORD-1")
                .containsEntry("role", 1)
                .containsEntry("user", "c1");

        assertThatThrownBy(() -> registry.execute("merchant_only",
                new ToolContext("c1", 1, "越权"), Map.of("count", 1)))
                .isInstanceOf(ToolRegistry.ToolException.class)
                .hasMessageContaining("不允许角色");
        assertThatThrownBy(() -> registry.execute("nope", new ToolContext("c1", 1, ""), Map.of()))
                .isInstanceOf(ToolRegistry.ToolException.class)
                .hasMessageContaining("未注册");
    }

    @Test
    @DisplayName("按意图找当前角色的工具；没有就返回 null（调用方回退 switch）")
    void findsToolByIntent() {
        ToolRegistry registry = registry(context(Tools.class));

        assertThat(registry.forIntent(ChatIntent.ORDER_QUERY, 1).name()).isEqualTo("buyer_only");
        assertThat(registry.forIntent(ChatIntent.ORDER_QUERY, 2)).isNull();
        assertThat(registry.forIntent(ChatIntent.ADD_TO_SECKILL, 2).name()).isEqualTo("merchant_only");
        assertThat(registry.forIntent(ChatIntent.NAVIGATE, 1)).isNull();
        // intent 留空（UNKNOWN）的工具只能按名字显式调用：否则「没听懂」会命中它
        // —— 2026-10-22 线上实测把「查商品」答成了自检工具的「pong」。
        assertThat(registry.forIntent(ChatIntent.UNKNOWN, 1)).isNull();
        assertThat(registry.forIntent(ChatIntent.UNKNOWN, 3)).isNull();
    }

    @Test
    @DisplayName("重名：保留先注册的，后来的被忽略（不把服务拖崩）")
    void duplicateNameKeepsFirst() {
        ToolRegistry registry = registry(context(Tools.class, Duplicate.class));

        assertThat(registry.find("buyer_only").description()).isEqualTo("买家独有的工具");
        assertThat(registry.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("自动生效：扫描之后新注册一个带注解的 Bean，再扫一次就出现在目录与调用里")
    void lateRegisteredBeanIsPickedUpOnRescan() {
        AnnotationConfigApplicationContext ctx = context(Tools.class);
        ToolRegistry registry = registry(ctx);
        assertThat(registry.find("late_tool")).isNull();

        // GenericApplicationContext 不允许二次 refresh ⇒ 直接往 BeanFactory 里补一条定义，
        // 模拟「开发期新增了一个带注解的 Bean，重启后扫描到」。
        DefaultListableBeanFactory factory = (DefaultListableBeanFactory) ctx.getBeanFactory();
        factory.registerBeanDefinition("lateTool", new RootBeanDefinition(LateTool.class));
        registry.scan();

        assertThat(registry.find("late_tool")).isNotNull();
        assertThat(registry.forRole(2)).extracting(ToolDescriptor::name).contains("late_tool");
        assertThat(registry.execute("late_tool", new ToolContext("m1", 2, ""), Map.of()))
                .isInstanceOf(ToolResult.class);
    }
}
