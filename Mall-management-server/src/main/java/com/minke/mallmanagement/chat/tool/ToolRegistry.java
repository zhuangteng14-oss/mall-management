package com.minke.mallmanagement.chat.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务工具注册表：启动时扫描 {@link AgentTool}，反射生成 JSON Schema，按角色提供目录。
 *
 * <h2>【扫描时机】</h2>
 * 实现 {@link SmartInitializingSingleton}：等所有单例（含 {@code ChatToolSet}）都实例化完
 * 再扫，避免拿到半成品 Bean。扫描用 {@link ApplicationContext#getBeanDefinitionNames()}，
 * 对每个 Bean 的**用户类**（剥掉 AOP 代理）用 {@link ReflectionUtils#getAllDeclaredMethods}
 * 找方法注解；本仓 Bean 数量在百级，启动一次可接受。
 *
 * <h2>【Schema】</h2>
 * {@link ToolContext} 参数被识别为「注入身份」，**不**进 Schema；其余参数用
 * {@link AgentParam} 的显式名字与说明，类型映射到 JSON Schema 的
 * string / integer / number / boolean / array / object。生成结果缓存在
 * {@code Map<String, ToolDescriptor>} 里，运行期不再反射参数（只按缓存调用）。
 *
 * <h2>【角色过滤】</h2>
 * {@code targetRole} 为空 = 所有角色；未登录（role=null）按买家 1 处理 —— 匿名用户仍可
 * 看到买家工具（会得到「请先登录」的确定性回答），但绝不会看到商家工具。
 *
 * <h2>【失败面】</h2>
 * 单个 Bean / 方法解析失败只记 WARN，不影响启动；重名工具保留先注册的那个并记 ERROR
 * （重名是开发期配置错误，但不该把整台服务拖崩）。
 */
@Component
public class ToolRegistry implements ApplicationContextAware, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 未登录时的「有效角色」：按买家过滤。 */
    public static final int ANONYMOUS_ROLE = 1;

    private final ObjectMapper objectMapper;
    private ApplicationContext applicationContext;
    private final Map<String, ToolDescriptor> tools = Collections.synchronizedMap(new LinkedHashMap<>());

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        scan();
    }

    /** 扫描全部 Bean，幂等（重复调用会先清空再建）。测试可直接调。 */
    public synchronized void scan() {
        tools.clear();
        if (applicationContext == null) {
            log.warn("ToolRegistry 没有 ApplicationContext，跳过扫描");
            return;
        }
        int beans = 0;
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            if (beanName.startsWith("org.springframework") || beanName.startsWith("scopedTarget.")) {
                continue;
            }
            Class<?> type;
            try {
                type = applicationContext.getType(beanName);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            List<Method> annotated = new ArrayList<>();
            ReflectionUtils.doWithMethods(type, method -> {
                if (AnnotationUtils.findAnnotation(method, AgentTool.class) != null) {
                    annotated.add(method);
                }
            }, method -> !method.isBridge() && !method.isSynthetic());
            if (annotated.isEmpty()) {
                continue;
            }
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (RuntimeException e) {
                log.warn("工具 Bean {} 实例化失败，跳过：{}", beanName, e.getMessage());
                continue;
            }
            for (Method method : annotated) {
                register(beanName, bean, method);
                beans++;
            }
        }
        log.info("AgentTool 扫描完成：{} 个方法 → {} 个工具 {}", beans, tools.size(), tools.keySet());
        tools.values().forEach(t -> log.info("  · {}（roles={}，intent={}）— {}",
                t.name(), t.targetRole().isEmpty() ? "ALL" : t.targetRole(), t.intent(), t.description()));
    }

    private void register(String beanName, Object bean, Method method) {
        AgentTool annotation = AnnotationUtils.findAnnotation(method, AgentTool.class);
        if (annotation == null) {
            return;
        }
        String name = annotation.name() == null ? "" : annotation.name().trim();
        if (name.isEmpty()) {
            log.error("工具方法 {}#{} 的 name 为空，跳过", beanName, method.getName());
            return;
        }
        if (tools.containsKey(name)) {
            log.error("工具名重复：{}（{}#{} 被忽略，保留先注册的 {}）",
                    name, beanName, method.getName(), tools.get(name).method().toGenericString());
            return;
        }
        Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(invocable);

        List<ToolParam> params = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Parameter[] raw = method.getParameters();
        for (int i = 0; i < raw.length; i++) {
            Parameter p = raw[i];
            boolean context = ToolContext.class.isAssignableFrom(p.getType());
            AgentParam meta = p.getAnnotation(AgentParam.class);
            String paramName = meta != null && !meta.name().isBlank()
                    ? meta.name().trim()
                    : p.getName();
            boolean isRequired = !context && (meta == null || meta.required());
            String description = meta == null ? "" : meta.description();
            params.add(new ToolParam(i, paramName, p.getType(), p.getParameterizedType(),
                    context, isRequired, description));
            if (context) {
                continue;
            }
            Map<String, Object> prop = typeSchema(p.getType(), p.getParameterizedType());
            if (!description.isBlank()) {
                prop.put("description", description);
            }
            properties.put(paramName, prop);
            if (isRequired) {
                required.add(paramName);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);

        Set<Integer> roles = new LinkedHashSet<>();
        for (int r : annotation.targetRole()) {
            roles.add(r);
        }
        ToolDescriptor descriptor = new ToolDescriptor(name, annotation.description(), roles,
                annotation.intent(), schema, bean, invocable, params);
        tools.put(name, descriptor);
    }

    // ==================== 查询 ====================

    public int size() {
        return tools.size();
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public ToolDescriptor find(String name) {
        return name == null ? null : tools.get(name);
    }

    public boolean allows(ToolDescriptor tool, Integer role) {
        if (tool == null) {
            return false;
        }
        if (tool.targetRole().isEmpty()) {
            return true;
        }
        return tool.targetRole().contains(effectiveRole(role));
    }

    public boolean allows(String name, Integer role) {
        return allows(find(name), role);
    }

    private static int effectiveRole(Integer role) {
        return role == null ? ANONYMOUS_ROLE : role;
    }

    /** 当前角色可用的工具（按名字排序，Prompt 稳定可复现）。 */
    public List<ToolDescriptor> forRole(Integer role) {
        List<ToolDescriptor> list = new ArrayList<>();
        for (ToolDescriptor t : tools.values()) {
            if (allows(t, role)) {
                list.add(t);
            }
        }
        list.sort((a, b) -> a.name().compareTo(b.name()));
        return list;
    }

    /**
     * 按意图找当前角色的工具（内置工具一个意图一个名字）。
     *
     * <p>⚠️ {@link ChatIntent#UNKNOWN} **永不参与**意图匹配：它是「没听懂」的兜底值，
     * 若某个工具把 intent 留空（默认 UNKNOWN），按意图查找就会把随便一句听不懂的话
     * 路由到它 —— 2026-10-22 线上实测过：查商品被识别成 UNKNOWN 后，命中了自检工具
     * {@code dev_ping}，回复成「pong」。这类工具只能由模型**按名字**显式选择。
     */
    public ToolDescriptor forIntent(ChatIntent intent, Integer role) {
        if (intent == null || intent == ChatIntent.UNKNOWN) {
            return null;
        }
        for (ToolDescriptor t : forRole(role)) {
            if (t.intent() == intent) {
                return t;
            }
        }
        return null;
    }

    // ==================== Prompt ====================

    /**
     * 动态拼「工具可用列表」：只列当前角色能用的工具 + 其 JSON Schema。
     *
     * <p>没有注册表（单测 / 极端早调用）时返回空串，Prompt 里就不出现工具段。
     */
    public String promptCatalog(Integer role) {
        List<ToolDescriptor> list = forRole(role);
        StringBuilder sb = new StringBuilder();
        sb.append("你可以「调用」的业务工具（已按当前登录角色过滤；由后端真正执行，"
                + "你不需要自己查，也不要编造结果）：\n");
        if (list.isEmpty()) {
            sb.append("（当前角色没有可用工具）\n");
            return sb.toString();
        }
        for (ToolDescriptor t : list) {
            sb.append("- ").append(t.name()).append(" — ").append(t.description()).append('\n');
            sb.append("  参数 schema：").append(toJson(t.schema())).append('\n');
        }
        sb.append("调用规则：\n");
        sb.append("1. 需要查业务数据 / 办业务 ⇒ 把 tool 填成上面某个工具名，参数按 schema 填进 slots。\n");
        sb.append("2. 只做页面导航 ⇒ tool 填 navigate，target_path 从「可选页面」里逐字取。\n");
        sb.append("3. 闲聊 / 问候 ⇒ tool 填 null。\n");
        return sb.toString();
    }

    /** 给诊断端点 / 测试用的结构化视图。 */
    public Map<String, Object> describeForRole(Integer role) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolDescriptor t : forRole(role)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", t.name());
            item.put("description", t.description());
            item.put("targetRole", t.targetRole());
            item.put("intent", t.intent().name());
            item.put("schema", t.schema());
            list.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role);
        out.put("count", list.size());
        out.put("tools", list);
        return out;
    }

    // ==================== 调用 ====================

    /**
     * 按名字反射调用工具。
     *
     * @throws ToolException 未注册 / 角色不允许 / 方法执行异常
     */
    public Object execute(String name, ToolContext context, Map<String, Object> args) {
        ToolDescriptor tool = find(name);
        if (tool == null) {
            throw new ToolException("未注册的工具：" + name);
        }
        if (!allows(tool, context == null ? null : context.role())) {
            throw new ToolException("工具 " + name + " 不允许角色 "
                    + (context == null ? null : context.role()) + " 调用");
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Object[] values = new Object[tool.params().size()];
        for (ToolParam p : tool.params()) {
            if (p.context()) {
                values[p.index()] = context;
                continue;
            }
            Object raw = safeArgs.get(p.name());
            values[p.index()] = convert(raw, p.type(), p.name());
        }
        try {
            Object result = tool.method().invoke(tool.bean(), values);
            log.debug("工具 {} 调用成功（role={}）", name, context == null ? null : context.role());
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ToolException("工具执行失败：" + name + " → "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new ToolException("工具调用反射失败：" + name, e);
        }
    }

    /** 按意图执行当前角色的工具；没有对应工具返回 null（调用方回退 switch）。 */
    public Object executeForIntent(ChatIntent intent, Integer role, ToolContext context,
                                   Map<String, Object> args) {
        ToolDescriptor tool = forIntent(intent, role);
        if (tool == null) {
            return null;
        }
        return execute(tool.name(), context, args);
    }

    /**
     * 字符串 / 数字 → 目标类型。
     *
     * <p>模型的 slots 是 JSON，Jackson 已把数字解析成 Integer/Double、布尔成 Boolean；
     * 这里只做「类型不完全一致」的收口，不引入隐式魔法。无法转换时抛
     * {@link ToolException}（宁可让本次调用失败并降级，也不要把错值传进业务）。
     */
    static Object convert(Object raw, Class<?> type, String name) {
        if (raw == null) {
            if (type.isPrimitive()) {
                if (type == boolean.class) return false;
                if (type == char.class) return '\0';
                if (type == double.class || type == float.class) return 0d;
                return 0;
            }
            return null;
        }
        if (type.isInstance(raw)) {
            return raw;
        }
        try {
            if (type == String.class) {
                return String.valueOf(raw);
            }
            if (type == int.class || type == Integer.class) {
                return raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
            }
            if (type == long.class || type == Long.class) {
                return raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
            }
            if (type == double.class || type == Double.class) {
                return raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
            }
            if (type == float.class || type == Float.class) {
                return raw instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(raw).trim());
            }
            if (type == boolean.class || type == Boolean.class) {
                if (raw instanceof Boolean b) return b;
                return Boolean.parseBoolean(String.valueOf(raw).trim());
            }
            if (type == short.class || type == Short.class) {
                return raw instanceof Number n ? n.shortValue() : Short.parseShort(String.valueOf(raw).trim());
            }
            if (type == byte.class || type == Byte.class) {
                return raw instanceof Number n ? n.byteValue() : Byte.parseByte(String.valueOf(raw).trim());
            }
            if (type == char.class || type == Character.class) {
                String s = String.valueOf(raw);
                return s.isEmpty() ? '\0' : s.charAt(0);
            }
        } catch (RuntimeException e) {
            throw new ToolException("参数 " + name + " 无法转换为 " + type.getSimpleName() + "：" + raw, e);
        }
        // 其它类型原样交给方法；类型不符会由反射抛 IllegalArgumentException。
        return raw;
    }

    // ==================== JSON Schema ====================

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Java 类型 → JSON Schema 片段（只描述事实，不猜业务约束）。 */
    static Map<String, Object> typeSchema(Class<?> type, Type generic) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (type == String.class || type == char.class || type == Character.class) {
            schema.put("type", "string");
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            schema.put("type", "integer");
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class
                || type == BigDecimal.class) {
            schema.put("type", "number");
        } else if (type == boolean.class || type == Boolean.class) {
            schema.put("type", "boolean");
        } else if (type.isEnum()) {
            schema.put("type", "string");
            List<String> values = new ArrayList<>();
            for (Object c : type.getEnumConstants()) {
                values.add(String.valueOf(c));
            }
            schema.put("enum", values);
        } else if (type.isArray()) {
            schema.put("type", "array");
            schema.put("items", typeSchema(type.getComponentType(), type.getComponentType()));
        } else if (Collection.class.isAssignableFrom(type)) {
            schema.put("type", "array");
            Class<?> item = itemClass(generic);
            if (item != null) {
                schema.put("items", typeSchema(item, item));
            }
        } else if (Map.class.isAssignableFrom(type)) {
            schema.put("type", "object");
        } else {
            schema.put("type", "object");
        }
        return schema;
    }

    private static Class<?> itemClass(Type generic) {
        if (generic instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    /** 工具注册 / 调用失败。编排层捕获后降级（规则引擎或既有 switch）。 */
    public static class ToolException extends RuntimeException {
        public ToolException(String message) {
            super(message);
        }

        public ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
