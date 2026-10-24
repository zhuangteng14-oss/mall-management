package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.tool.ToolDescriptor;
import com.minke.mallmanagement.chat.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 大模型意图识别（阶段一）：把一句话交给 DeepSeek-V4.1-Flash，取回意图与槽位。
 *
 * <h2>【边界】</h2>
 * 本类只做「分类 + 抽槽」，不查库、不拼业务话术；路由与降级在
 * {@link ChatAgentService}。模型输出必须是 JSON，任何超时 / 非 200 / 非 JSON /
 * 不支持的意图都抛 {@link LlmException}，由编排层降级到规则引擎。
 *
 * <h2>【超时】</h2>
 * 连接超时与整次请求超时都是 {@value #TIMEOUT_SECONDS} 秒硬上限。
 * 5 秒沿用切换前 GLM-4-Flash 的尾延迟实测（p95≈4.8s / p99≈5.7s）；DeepSeek
 * 切换后需重测，但超时一律降级规则引擎，不会无限等待。
 * 同步 {@code send} 在调用线程执行，不额外占线程池。
 *
 * <h2>【保密】</h2>
 * API Key 只从配置 / 环境变量读取；本类不打日志、不把 Authorization 头写进异常消息。
 */
@Service
public class LlmIntentClassifier {

    /** 诊断行：单次出网的耗时 / 超时类型 / usage / 响应头 request-id。 */
    static final String CALL_MARKER = "LLM_CALL";
    /** 诊断行：System Prompt 的字符构成（基础规则 / 路由字典 / 工具目录 / 输出格式）。 */
    static final String PROMPT_MARKER = "LLM_PROMPT";

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    static final int TIMEOUT_SECONDS = 5;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(TIMEOUT_SECONDS);

    private static final String DEFAULT_URL =
            "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-flash";
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
            // 2026-10-24：纯 LLM（无语义路由）也要能产出这三个业务导航意图；
            // 它们同时是 SemanticRouteRegistry 的标准问法意图。
            ChatIntent.GOODS_MANAGE,
            ChatIntent.MERCHANT_DASHBOARD,
            ChatIntent.ASSET_QUERY,
            ChatIntent.FAQ,
            // Prompt 已列出这两类寒暄；白名单必须同步，否则模型答对也被判成解析失败。
            ChatIntent.GREETING,
            ChatIntent.HELP,
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

            意图：
            - 页面导航 ⇒ GUIDE_NAVIGATION（默认）；只有用户亲口说「直接 / 立刻 / 马上 / 一键 /
              带我去 / 我要去」才用 DIRECT_NAVIGATION。target_path 必须逐字取自下方路由字典。
            - 数据查询 ⇒ ORDER_QUERY（订单进度）、REFUND_QUERY（售后进度）、
              COMMENT_QUERY（评论内容）、GOODS_QUERY（某件商品的价格 / 参数 / 库存）。
              数据查询**不要**给 target_path。
            - 购物 / 买家行为 ⇒ **GOODS_QUERY**（商家身份同样适用）：问「在哪购买 / 怎么买 /
              想买 / 帮我找 / 搜索某商品 / 某商品多少钱」都归这里，**不是** GOODS_MANAGE，
              也**不是** MERCHANT_DASHBOARD。把商品名或型号（如 护膝 / 蓝牙耳机 / oppo12）
              填进 slots.goodsKeyword，不要填 target_path。
            - 业务动作 ⇒ OPEN_STORE（开店 / 入驻）、ADD_TO_SECKILL（报名秒杀）、
              FAQ / GREETING / HELP / UNKNOWN。
            规则：
            1. 「怎么处理退款」问的是「去哪办」⇒ GUIDE_NAVIGATION（商家售后工作台 / 客户我的订单），
               不是 REFUND_QUERY；「我的退款进度」才是 REFUND_QUERY。
            2. 「上架 / 发布商品」不是开店：前者去发布页，后者才是 OPEN_STORE。
            3. 钱 / 余额 / 积分 / 资产 / 优惠券 ⇒ 导航到会员中心。
            4. 槽位：orderId 只放订单号；商品名或商品编号（可含冒号、连字符）放
               goodsKeyword，**不是** orderId；need_publish 仅在「商品还没上架」且目标是秒杀时为 true。
            5. tool 只能填下方「业务工具」清单里的名字；参数按该工具的 schema 用同名键填进 slots。
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
            @Value("${chat.llm.url:https://api.deepseek.com/chat/completions}") String url,
            @Value("${chat.llm.api-key:}") String apiKey,
            @Value("${chat.llm.model:deepseek-flash}") String model,
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

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            // 诊断：HttpConnectTimeoutException 是连接阶段触顶；裸 HttpTimeoutException 是响应阶段。
            logCall(startedAt, "timeout", e.getClass().getSimpleName(), null);
            throw new LlmException("请求超时（>" + requestTimeout.toSeconds() + "s）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logCall(startedAt, "interrupted", e.getClass().getSimpleName(), null);
            throw new LlmException("请求被中断", e);
        } catch (Exception e) {
            // 连接拒绝 / DNS / TLS 都在这里；异常消息只带类型，不回显 Authorization。
            logCall(startedAt, "error", e.getClass().getSimpleName(), null);
            throw new LlmException("请求失败：" + e.getClass().getSimpleName(), e);
        }

        logCall(startedAt, "ok", "-", response);
        if (response.statusCode() != 200) {
            throw new LlmException("HTTP " + response.statusCode());
        }

        String content = extractContent(response.body());
        return parseContent(content, role);
    }

    /** 固定规则 + 当前角色的路由字典 + 当前角色的工具目录 +（可选）当前页。 */
    private String systemPrompt(Integer role, String currentPath) {
        String routeCatalog = RouteCatalog.promptCatalog(role);
        String toolCatalog = toolRegistry == null ? "" : toolRegistry.promptCatalog(role);
        String safePath = safeCurrentPath(currentPath);
        String pathLine = safePath == null ? "" : "用户当前所在页面：" + safePath + '\n';

        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append('\n').append(routeCatalog);
        if (toolRegistry != null) {
            // 动态拼装：只列当前角色能调用的工具（含反射生成的 JSON Schema）。
            sb.append('\n').append(toolCatalog);
        }
        sb.append(pathLine).append(OUTPUT_FORMAT);
        String prompt = sb.toString();
        log.info("{} chars={} baseChars={} routeChars={} toolChars={} outputChars={} pathChars={} estTokens={}",
                PROMPT_MARKER, prompt.length(), SYSTEM_PROMPT.length(), routeCatalog.length(),
                toolCatalog.length(), OUTPUT_FORMAT.length(), pathLine.length(),
                estimateTokens(prompt));
        if (log.isDebugEnabled()) {
            // 诊断：只在显式开 DEBUG 时落全文，供离线复现同一 Prompt（不含任何凭据）。
            log.debug("{}_TEXT {}", PROMPT_MARKER, prompt);
        }
        return prompt;
    }

    /**
     * 诊断：单次出网的耗时与元数据。**只读**，不参与任何分支。
     *
     * <p>超时时 {@code response} 为 {@code null}，{@code cause} 是异常类型
     * （{@code HttpConnectTimeoutException} = 连接阶段；{@code HttpTimeoutException} = 响应阶段）。
     */
    private void logCall(long startedAtNanos, String outcome, String cause,
                         HttpResponse<String> response) {
        long latencyMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        String http = "-";
        String requestId = "-";
        String inputTokens = "-";
        String outputTokens = "-";
        if (response != null) {
            http = String.valueOf(response.statusCode());
            requestId = response.headers().firstValue("x-request-id")
                    .or(() -> response.headers().firstValue("request-id"))
                    .orElse("-");
            if (response.statusCode() == 200) {
                try {
                    JsonNode usage = objectMapper.readTree(response.body()).path("usage");
                    if (usage.has("prompt_tokens")) {
                        inputTokens = usage.path("prompt_tokens").asText();
                    }
                    if (usage.has("completion_tokens")) {
                        outputTokens = usage.path("completion_tokens").asText();
                    }
                } catch (Exception ignored) {
                    // usage 只是诊断信息，解析失败不影响分类结果。
                }
            }
        }
        log.info("{} model={} outcome={} latencyMs={} http={} inputTokens={} outputTokens={} requestId={} cause={}",
                CALL_MARKER, model, outcome, latencyMs, http, inputTokens, outputTokens,
                requestId, cause);
    }

    /** 中文 1 字 ≈ 1.5 token，其余字符 ≈ 4 字符 / token（粗估，仅用于对比段落占比）。 */
    static double estimateTokens(String text) {
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            tokens += (c >= 0x4E00 && c <= 0x9FFF) ? 1.5 : 0.25;
        }
        return Math.round(tokens * 10) / 10.0;
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

    /** 从 OpenAI 兼容响应（DeepSeek / GLM 同形）的 {@code choices[0].message.content} 取正文。 */
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
        Map<String, Object> args = parseArgs(slots);
        if (intent == ChatIntent.GOODS_QUERY) {
            // Prompt 的口径是 slots.goodsKeyword；个别模型会把商品名塞进 target_path。
            // 只接受「不像路径」的值（不以 / 开头），否则宁可丢掉，也不能把页面当商品名。
            String keyword = parseGoodsKeyword(root, slots);
            if (!isBlank(keyword)) {
                args = args == null ? new LinkedHashMap<>() : new LinkedHashMap<>(args);
                args.put("goodsKeyword", keyword);
            }
        }
        return new Result(intent, orderId, needPublish, targetPath, tool, args);
    }

    /** 商品名 / 型号：slots.goodsKeyword 优先，其次顶层的 goodsKeyword / target_path。 */
    private static String parseGoodsKeyword(JsonNode root, JsonNode slots) {
        JsonNode node = slots == null ? null : slots.get("goodsKeyword");
        if (node == null || node.isNull()) {
            node = root.get("goodsKeyword");
        }
        if (node == null || node.isNull()) {
            node = root.get("goods_keyword");
        }
        if (node != null && !node.isNull()) {
            String value = node.asText("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        JsonNode path = root.get("target_path");
        if (path == null) {
            path = root.get("targetPath");
        }
        if (path != null && !path.isNull()) {
            String value = path.asText("").trim();
            if (!value.isEmpty() && !value.startsWith("/")) {
                return value;
            }
        }
        return null;
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
     * 解析 {@code target_path}：对导航意图与三个语义路由专用业务意图生效，
     * 且必须能在当前角色的路由字典里归位。
     *
     * <p>这三个专用意图（{@code GOODS_MANAGE / MERCHANT_DASHBOARD / ASSET_QUERY}）
     * 在语义路由命中时本就带路径；纯 LLM 路径下若模型也给出路径，必须原样解析 ——
     * 否则 ALLOWED 放行了意图、路径却被丢掉，下游 {@code answerRouted} 会退化成
     * 「这个页面在当前身份下打不开」。没给路径时仍返回 {@code null}，与旧行为一致。
     *
     * <p>数据类意图即使模型顺手给了路径也**丢掉** —— 否则「查订单」会被前端当成导航，
     * 把用户从订单卡旁边带走。
     */
    private static String parseTargetPath(JsonNode root, ChatIntent intent, Integer role) {
        boolean navigational = intent == ChatIntent.GUIDE_NAVIGATION
                || intent == ChatIntent.DIRECT_NAVIGATION;
        boolean routed = intent == ChatIntent.GOODS_MANAGE
                || intent == ChatIntent.MERCHANT_DASHBOARD
                || intent == ChatIntent.ASSET_QUERY;
        if (!navigational && !routed) {
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
        // 专用意图允许不带路径（语义路由正常时由路由层提供；纯 LLM 下模型可能只给意图）。
        if (routed && raw.isEmpty()) {
            return null;
        }
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
