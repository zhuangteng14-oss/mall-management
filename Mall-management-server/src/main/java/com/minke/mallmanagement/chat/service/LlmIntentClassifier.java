package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.tool.ToolDescriptor;
import com.minke.mallmanagement.chat.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 大模型意图识别（阶段一）：把一句话交给 GLM-4-Flash，取回意图与槽位。
 *
 * <h2>【边界】</h2>
 * 本类只做「分类 + 抽槽」，不查库、不拼业务话术；路由与降级在
 * {@link ChatAgentService}。模型输出必须是 JSON，任何超时 / 非 200 / 非 JSON /
 * 不支持的意图都抛 {@link LlmException}，由编排层降级到规则引擎。
 *
 * <h2>【超时】</h2>
 * 连接超时与整次请求超时都是 {@value #TIMEOUT_SECONDS} 秒（用户要求的硬上限）。
 * 同步 {@code send} 在调用线程执行，不额外占线程池。
 *
 * <h2>【保密】</h2>
 * API Key 只从配置 / 环境变量读取；本类不打日志、不把 Authorization 头写进异常消息。
 */
@Service
public class LlmIntentClassifier {

    static final int TIMEOUT_SECONDS = 3;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(TIMEOUT_SECONDS);

    private static final String DEFAULT_URL =
            "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-4-flash";
    private static final int MAX_ORDER_ID_LEN = 64;

    /** 只接受 Prompt 里列出的意图；其余（含模型自创）一律视为解析失败。 */
    private static final Set<ChatIntent> ALLOWED = EnumSet.of(
            ChatIntent.ORDER_QUERY,
            ChatIntent.REFUND_QUERY,
            ChatIntent.COMMENT_QUERY,
            ChatIntent.GOODS_QUERY,
            ChatIntent.OPEN_STORE,
            ChatIntent.ADD_TO_SECKILL,
            ChatIntent.GUIDE_NAVIGATION,
            ChatIntent.DIRECT_NAVIGATION,
            // 旧协议：仍接受，解析后归一为 GUIDE_NAVIGATION。
            ChatIntent.NAVIGATE,
            ChatIntent.FAQ,
            ChatIntent.UNKNOWN);

    /**
     * Prompt 的固定部分；**工具目录**与**路由字典**都按角色在运行时追加：
     * <ul>
     *   <li>{@link ToolRegistry#promptCatalog(Integer)} —— 当前角色能调用的工具 + 反射生成的
     *       JSON Schema（2026-10-22 插件化改造，字典不再硬编码在这段文本里）；</li>
     *   <li>{@link RouteCatalog#promptCatalog(Integer)} —— 当前角色能去的页面。</li>
     * </ul>
     * 两个目录都是「按当前会话角色过滤」的 ⇒ 商家登录时模型看不到买家工具，反之亦然。
     */
    static final String SYSTEM_PROMPT = """
            你是一个电商智能客服意图路由器。请分析用户输入，严格输出 JSON 格式，不要输出任何多余文字。

            你会收到一份「可选页面」路由字典。把用户的话归到下面两类之一：
            A. 页面导航（用户想「去哪里 / 怎么去 / 怎么处理 / 怎么办理 / 我要看 / 我要管理 / 帮我打开」某个页面）
               ⇒ 从字典里选**一个** target_path，并按语气二选一：
               · 默认 A1（99% 的情况）⇒ intent = GUIDE_NAVIGATION：用户问「怎么去 / 怎么申请 /
                 怎么处理 / 去哪办 / 我要看某页」—— 前端会从当前页一步步引导。
               · 只有 A2：用户**亲口说「直接 / 立刻 / 马上 / 一键 / 带我去 / 我要去」** ⇒
                 intent = DIRECT_NAVIGATION：前端直接跳转，不走引导。
               例：「怎么上架商品」⇒ GUIDE_NAVIGATION「上架商品」；「怎么查看店铺数据」⇒ GUIDE_NAVIGATION
                   「经营数据」；「我想改一下那个 299 元耳机的价格」⇒ GUIDE_NAVIGATION「查看商品」；
                   「我要管理我的钱」⇒ GUIDE_NAVIGATION「会员中心」；
                   「怎么处理退款」⇒ GUIDE_NAVIGATION，商家「售后工作台」、客户「我的订单」；
                   「直接带我去申请品牌」⇒ DIRECT_NAVIGATION「商家入驻申请」；
                   「我要去商家后台」⇒ DIRECT_NAVIGATION「个人中心」。
               ⚠️ 拿不准就用 GUIDE_NAVIGATION —— 引导里面本来就有「跳过引导，直接前往」。
               ⚠️ 「怎么处理退款」问的是「去哪办」，**不是** ORDER_QUERY / REFUND_QUERY。
               ⚠️ 上面只说明「意思对应哪一行」，**路径必须从下方字典逐字抄**，别照例子拼。
               ⚠️ 「钱 / 余额 / 积分 / 资产 / 优惠券」相关一律选「会员中心」。
               ⚠️ 「上架 / 发布商品」不是开店：「怎么上架商品」⇒ GUIDE_NAVIGATION「上架商品」；
                  只有「开店 / 入驻 / 加盟 / 营业执照」才是 OPEN_STORE。
            B. 数据查询与业务（用户问**自己那笔**的状态，这类**不要**给 target_path）
               ⇒ ORDER_QUERY（查我的订单状态/进度）、REFUND_QUERY（查我的售后进度/结果）、
                  COMMENT_QUERY（查评论/评价内容、有没有人回复我）、
                  GOODS_QUERY（查**某个商品**的价格/详情/参数/库存）、
                  OPEN_STORE（开店/入驻）、ADD_TO_SECKILL（报名秒杀）、
                  FAQ（政策咨询）、GREETING（问候）、HELP（你能做什么）、UNKNOWN（闲聊/听不懂）。
               例：「我的订单到哪了」⇒ ORDER_QUERY；「我的退款进度」⇒ REFUND_QUERY；
                   「我要看评论」「有没有人给我评论」⇒ COMMENT_QUERY（问的是评论内容与回复状态，
                   平台没有独立的评价管理页，**不要**把它当导航）；
                   「怎么看 222:ij-555 商品的详细数据」「oppo12 多少钱」⇒ GOODS_QUERY，
                   商品名/编号原样放进 slots.goodsKeyword（**不要**放进 orderId）；
                   「怎么开店」⇒ OPEN_STORE；「我要报名秒杀」⇒ ADD_TO_SECKILL；
                   「我的新商品要上架秒杀页，还没上架呢」⇒ ADD_TO_SECKILL，且 need_publish = true。
               ⚠️ 「查某个商品的数据」是 GOODS_QUERY，**不是** NAVIGATE（搜索页），
                  也**不是** ORDER_QUERY：商品编号里的冒号、连字符不是订单号的一部分。

            B2. 工具调用：具体能用哪些工具、参数怎么填，见下方「业务工具」清单
               （该清单已按当前登录角色过滤；商家看不到买家工具，买家也看不到商家工具）。
               你只负责**选工具并填参数**，数据由后端真正查询，不要编造结果。

            规则：
            1. target_path 只能逐字取字典里的路径；字典里没有合适的页面就给 null。
            2. 判断口诀：问「怎么做 / 如何办 / 去哪办 / 在哪看」⇒ GUIDE_NAVIGATION；
               只有明说「直接 / 立刻 / 马上 / 一键 / 我要去 / 带我去」⇒ DIRECT_NAVIGATION；
               问「我那一笔 / 那一单 到哪了 / 进度 / 状态 / 结果 / 有没有」⇒ 数据查询；
               问「某个商品多少钱 / 详细数据 / 参数 / 库存」⇒ GOODS_QUERY。
               对照：「怎么处理退款」= GUIDE_NAVIGATION；「直接带我去申请品牌」= DIRECT_NAVIGATION；
                     「我的退款到哪了」= REFUND_QUERY；
                     「怎么看 222:ij-555 商品的详细数据」= GOODS_QUERY。
            3. 同样一句话在不同角色下落点不同：只从字典里给当前角色列出的页面中选。
            4. 提取槽位（slots）：
               - orderId：订单号，没有就给 null。⚠️ 商品名 / 商品编号（可含冒号、连字符）**不是**
                 订单号，绝不要放进 orderId。
               - goodsKeyword：商品名或商品编号，原样保留（如 222:ij-555、oppo12）；用户没提就给 null。
               - need_publish：布尔值。**仅当**目标是秒杀报名、且用户明说商品「还没上架 / 还没发布 /
                 还没有商品」时给 true；其他情况一律 false。
               - 工具参数：按「业务工具」清单里该工具的 schema，用**同名键**填进 slots。
            5. tool：只能逐字填「业务工具」清单里的工具名；闲聊 / 问候 / 清单里没有合适工具时给 null。
            6. 只输出 JSON 本身，不要 markdown 代码块，不要解释。
            """;

    static final String OUTPUT_FORMAT = """
            输出格式：{"intent": "...", "tool": null, "target_path": null, "slots": {"orderId": null, "goodsKeyword": null, "need_publish": false}}
            """;

    private final boolean enabled;
    private final String url;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    /** 工具目录来源（可为 {@code null}：早期单测 / 未装注册表的场景 ⇒ Prompt 不出现工具段）。 */
    private final ToolRegistry toolRegistry;

    @Autowired
    public LlmIntentClassifier(
            @Value("${chat.llm.enabled:true}") boolean enabled,
            @Value("${chat.llm.url:https://open.bigmodel.cn/api/paas/v4/chat/completions}") String url,
            @Value("${chat.llm.api-key:}") String apiKey,
            @Value("${chat.llm.model:glm-4-flash}") String model,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry) {
        this(enabled, url, apiKey, model, objectMapper,
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                DEFAULT_TIMEOUT, toolRegistry);
    }

    /** 测试用：可换成本地 HttpServer、可把超时缩短到毫秒级；不含工具注册表。 */
    LlmIntentClassifier(boolean enabled, String url, String apiKey, String model,
                        ObjectMapper objectMapper, HttpClient httpClient,
                        Duration requestTimeout) {
        this(enabled, url, apiKey, model, objectMapper, httpClient, requestTimeout, null);
    }

    /** 测试用：带工具注册表（验证 Prompt 里工具列表按角色隔离）。 */
    LlmIntentClassifier(boolean enabled, String url, String apiKey, String model,
                        ObjectMapper objectMapper, HttpClient httpClient,
                        Duration requestTimeout, ToolRegistry toolRegistry) {
        this.enabled = enabled;
        this.url = isBlank(url) ? DEFAULT_URL : url.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = isBlank(model) ? DEFAULT_MODEL : model.trim();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout == null ? DEFAULT_TIMEOUT : requestTimeout;
        this.toolRegistry = toolRegistry;
    }

    /** 开关打开且配了 Key 才真正出网；否则编排层直接走规则引擎，不产生 WARN。 */
    public boolean isEnabled() {
        return enabled && !isBlank(apiKey) && !isBlank(url);
    }

    /**
     * 分类一句话（不带角色 / 当前页 —— 单测与旧调用点的兼容入口）。
     *
     * @throws LlmException 未启用、超时、非 200、响应不是合法 JSON、意图不在白名单
     */
    public Result classify(String message) {
        return classify(message, null, null);
    }

    /**
     * 分类一句话，并把「当前角色能进哪些页面」注入 Prompt。
     *
     * @param message     用户原话
     * @param role        1 客户 / 2 商家 / 3 管理员；{@code null} = 未知（两壳都列）
     * @param currentPath 用户当前所在路由，仅作模型判断的上下文；不参与出参
     */
    public Result classify(String message, Integer role, String currentPath) {
        if (!isEnabled()) {
            throw new LlmException("LLM 未启用或未配置 API Key");
        }
        if (isBlank(message)) {
            throw new LlmException("空消息");
        }

        String requestBody;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.1);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt(role, currentPath)),
                    Map.of("role", "user", "content", message)));
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new LlmException("构造请求失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmException("请求超时（>" + requestTimeout.toSeconds() + "s）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("请求被中断", e);
        } catch (Exception e) {
            // 连接拒绝 / DNS / TLS 都在这里；异常消息只带类型，不回显 Authorization。
            throw new LlmException("请求失败：" + e.getClass().getSimpleName(), e);
        }

        if (response.statusCode() != 200) {
            throw new LlmException("HTTP " + response.statusCode());
        }

        String content = extractContent(response.body());
        return parseContent(content, role);
    }

    /** 固定规则 + 当前角色的路由字典 + 当前角色的工具目录 +（可选）当前页。 */
    private String systemPrompt(Integer role, String currentPath) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append('\n').append(RouteCatalog.promptCatalog(role));
        if (toolRegistry != null) {
            // 动态拼装：只列当前角色能调用的工具（含反射生成的 JSON Schema）。
            sb.append('\n').append(toolRegistry.promptCatalog(role));
        }
        String safePath = safeCurrentPath(currentPath);
        if (safePath != null) {
            sb.append("用户当前所在页面：").append(safePath).append('\n');
        }
        sb.append(OUTPUT_FORMAT);
        return sb.toString();
    }

    /**
     * 当前页来自浏览器（可被随意构造），只接受「斜杠开头的纯路由形状」。
     *
     * <p>否则一段带换行的中文就能直接改写 System Prompt —— 这条路径只多了个
     * 上下文，不值得为它开一个注入面。
     */
    static String safeCurrentPath(String currentPath) {
        if (isBlank(currentPath)) {
            return null;
        }
        String p = currentPath.trim();
        if (p.length() > 120 || p.charAt(0) != '/') {
            return null;
        }
        for (int i = 1; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '/' || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return p;
    }

    /** 从 GLM 的 {@code choices[0].message.content} 取正文。 */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || isBlank(content.asText())) {
                throw new LlmException("响应缺少 choices[0].message.content");
            }
            return content.asText();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("响应不是合法 JSON", e);
        }
    }

    /** 容忍模型偶尔套的 markdown 代码块 / 前后解释，只取第一个 { 到最后一个 }。 */
    private Result parseContent(String raw, Integer role) {
        String json = raw == null ? "" : raw.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException("模型输出不是 JSON 对象");
        }
        json = json.substring(start, end + 1);

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new LlmException("模型输出 JSON 解析失败", e);
        }
        if (!root.isObject()) {
            throw new LlmException("模型输出不是 JSON 对象");
        }

        String intentName = root.path("intent").asText("").trim().toUpperCase(Locale.ROOT);
        ChatIntent intent;
        try {
            intent = ChatIntent.valueOf(intentName);
        } catch (IllegalArgumentException e) {
            throw new LlmException("不支持的意图：" + intentName);
        }
        if (!ALLOWED.contains(intent)) {
            throw new LlmException("不支持的意图：" + intentName);
        }
        // 旧协议归一：NAVIGATE 等价于默认引导。直达与否由编排层的词面判据二次确认。
        if (intent == ChatIntent.NAVIGATE) {
            intent = ChatIntent.GUIDE_NAVIGATION;
        }

        JsonNode slots = root.path("slots");
        String orderId = null;
        JsonNode idNode = slots.get("orderId");
        if (idNode != null && !idNode.isNull()) {
            orderId = idNode.asText("").trim();
            if (orderId.isEmpty()) {
                orderId = null;
            } else if (orderId.length() > MAX_ORDER_ID_LEN) {
                throw new LlmException("orderId 槽位过长");
            }
        }

        JsonNode publishNode = slots.get("need_publish");
        boolean needPublish = false;
        if (publishNode != null && !publishNode.isNull()) {
            needPublish = publishNode.isBoolean()
                    ? publishNode.booleanValue()
                    : "true".equalsIgnoreCase(publishNode.asText(""));
        }

        // 工具：必须是注册表里存在、且当前角色可用的名字（防「商家让模型调买家工具」）。
        String tool = parseTool(root, role);
        intent = withToolIntent(intent, tool);
        // 商品查询不带订单号：模型有时会把「222:ij-555」误填进 orderId，留着会污染
        // 会话里的「单号」上下文（下一句「到哪了」会被当成查那一单）。
        if (intent == ChatIntent.GOODS_QUERY) {
            orderId = null;
        }
        String targetPath = parseTargetPath(root, intent, role);
        return new Result(intent, orderId, needPublish, targetPath, tool, parseArgs(slots));
    }

    /**
     * 解析 {@code tool}：{@code null} / 空串 = 没选工具；其余必须在注册表里且当前角色可用。
     *
     * <p>⚠️ 这条校验是角色隔离的**后端一侧**：Prompt 里不列买家工具只是「不让模型看见」，
     * 模型仍可能凭旧记忆编出 {@code buyer_orders}；这里直接拒掉，由编排层降级规则引擎。
     */
    private String parseTool(JsonNode root, Integer role) {
        JsonNode node = root.get("tool");
        if (node == null) {
            node = root.path("slots").get("tool");
        }
        if (node == null || node.isNull()) {
            return null;
        }
        String name = node.asText("").trim();
        if (name.isEmpty() || "null".equalsIgnoreCase(name)) {
            return null;
        }
        if (toolRegistry == null) {
            return name;
        }
        ToolDescriptor tool = toolRegistry.find(name);
        if (tool == null) {
            throw new LlmException("未注册的工具：" + name);
        }
        if (!toolRegistry.allows(tool, role)) {
            throw new LlmException("工具 " + name + " 不适用于当前角色");
        }
        return name;
    }

    /** 内置工具的 intent 以注册表里的注解为准（模型可能与工具名对不上）。 */
    private ChatIntent withToolIntent(ChatIntent parsed, String tool) {
        if (tool == null || toolRegistry == null) {
            return parsed;
        }
        // 直达模式先于工具注解：navigate 工具声明的是默认引导，但用户若明说「直接去」，
        // 编排层仍要产出 jump 动作（见 ChatAgentService.executeTool 的旁路）。
        if (parsed == ChatIntent.DIRECT_NAVIGATION) {
            return parsed;
        }
        ToolDescriptor descriptor = toolRegistry.find(tool);
        if (descriptor == null || descriptor.intent() == null
                || descriptor.intent() == ChatIntent.UNKNOWN) {
            return parsed;
        }
        return descriptor.intent();
    }

    /** slots → 工具参数表（插件工具按 schema 取值；内置工具只用其中几个键）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(JsonNode slots) {
        if (slots == null || !slots.isObject() || slots.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(slots, Map.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析 {@code target_path}：只对 NAVIGATE 生效，且必须能在当前角色的路由字典里归位。
     *
     * <p>数据类意图即使模型顺手给了路径也**丢掉** —— 否则「查订单」会被前端当成导航，
     * 把用户从订单卡旁边带走。
     */
    private static String parseTargetPath(JsonNode root, ChatIntent intent, Integer role) {
        if (intent != ChatIntent.GUIDE_NAVIGATION && intent != ChatIntent.DIRECT_NAVIGATION) {
            return null;
        }
        JsonNode node = root.get("target_path");
        if (node == null) {
            node = root.get("targetPath");
        }
        if (node == null) {
            node = root.path("slots").get("target_path");
        }
        String raw = node == null || node.isNull() ? "" : node.asText("").trim();
        String resolved = RouteCatalog.resolve(raw, role);
        if (resolved == null) {
            throw new LlmException("target_path 不在路由字典内：" + raw);
        }
        return resolved;
    }

    /**
     * 模型的分类结果。
     *
     * <p>{@code needPublish} 只在 ADD_TO_SECKILL 时有业务意义；{@code targetPath} 只在
     * NAVIGATE 时有值，且已按当前角色归位到真实路由；{@code tool} 是模型从动态工具目录里选的
     * 工具名（{@code null} = 按 intent 走内置分发）；{@code args} 是 slots 原文。
     */
    public record Result(ChatIntent intent, String orderId, boolean needPublish, String targetPath,
                         String tool, Map<String, Object> args) {

        /** 兼容旧调用点 / 单测：不带 target_path 的三参构造。 */
        public Result(ChatIntent intent, String orderId, boolean needPublish) {
            this(intent, orderId, needPublish, null, null, null);
        }

        /** 兼容旧调用点 / 单测：带 target_path、不带工具。 */
        public Result(ChatIntent intent, String orderId, boolean needPublish, String targetPath) {
            this(intent, orderId, needPublish, targetPath, null, null);
        }
    }

    /** 分类失败；编排层捕获后降级规则引擎。 */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
