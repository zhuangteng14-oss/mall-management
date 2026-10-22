package com.minke.mallmanagement.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minke.mallmanagement.chat.model.ChatIntent;
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

    /** 只接受 Prompt 里列出的六种意图；其余（含模型自创）一律视为解析失败。 */
    private static final Set<ChatIntent> ALLOWED = EnumSet.of(
            ChatIntent.ORDER_QUERY,
            ChatIntent.REFUND_QUERY,
            ChatIntent.OPEN_STORE,
            ChatIntent.ADD_TO_SECKILL,
            ChatIntent.FAQ,
            ChatIntent.UNKNOWN);

    /** 与用户给定的 System Prompt 逐字一致（额外一句「不要 markdown 代码块」只为少一层剥离）。 */
    static final String SYSTEM_PROMPT = """
            你是一个电商智能客服意图路由器。请分析用户输入，严格输出 JSON 格式，不要输出任何多余文字。
            支持的意图：ORDER_QUERY（查订单）、REFUND_QUERY（查售后）、OPEN_STORE（开店）、ADD_TO_SECKILL（秒杀）、FAQ（政策咨询）、UNKNOWN（闲聊）。
            请提取关键槽位（Slots），如：订单号（orderId）、是否需要上架前置（need_publish 布尔值）。
            输出格式：{"intent": "...", "slots": {"orderId": null, "need_publish": false}}
            只输出这个 JSON 对象本身，不要 markdown 代码块，不要解释。
            """;

    private final boolean enabled;
    private final String url;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    @Autowired
    public LlmIntentClassifier(
            @Value("${chat.llm.enabled:true}") boolean enabled,
            @Value("${chat.llm.url:https://open.bigmodel.cn/api/paas/v4/chat/completions}") String url,
            @Value("${chat.llm.api-key:}") String apiKey,
            @Value("${chat.llm.model:glm-4-flash}") String model,
            ObjectMapper objectMapper) {
        this(enabled, url, apiKey, model, objectMapper,
                HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                DEFAULT_TIMEOUT);
    }

    /** 测试用：可换成本地 HttpServer、可把超时缩短到毫秒级。 */
    LlmIntentClassifier(boolean enabled, String url, String apiKey, String model,
                        ObjectMapper objectMapper, HttpClient httpClient,
                        Duration requestTimeout) {
        this.enabled = enabled;
        this.url = isBlank(url) ? DEFAULT_URL : url.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = isBlank(model) ? DEFAULT_MODEL : model.trim();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout == null ? DEFAULT_TIMEOUT : requestTimeout;
    }

    /** 开关打开且配了 Key 才真正出网；否则编排层直接走规则引擎，不产生 WARN。 */
    public boolean isEnabled() {
        return enabled && !isBlank(apiKey) && !isBlank(url);
    }

    /**
     * 分类一句话。
     *
     * @throws LlmException 未启用、超时、非 200、响应不是合法 JSON、意图不在白名单
     */
    public Result classify(String message) {
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
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
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
        return parseContent(content);
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
    private Result parseContent(String raw) {
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

        return new Result(intent, orderId, needPublish);
    }

    /** 模型的分类结果；{@code needPublish} 只在 ADD_TO_SECKILL 时有业务意义。 */
    public record Result(ChatIntent intent, String orderId, boolean needPublish) {
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
