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

    /** 只接受 Prompt 里列出的意图；其余（含模型自创）一律视为解析失败。 */
    private static final Set<ChatIntent> ALLOWED = EnumSet.of(
            ChatIntent.ORDER_QUERY,
            ChatIntent.REFUND_QUERY,
            ChatIntent.OPEN_STORE,
            ChatIntent.ADD_TO_SECKILL,
            ChatIntent.NAVIGATE,
            ChatIntent.FAQ,
            ChatIntent.UNKNOWN);

    /**
     * Prompt 的固定部分；路由字典由 {@link RouteCatalog#promptCatalog(Integer)} 按角色追加。
     *
     * <p>核心变化（2026-10-22）：不再让模型穷举意图，而是让它在**路由字典**里挑一个
     * {@code target_path}。「怎么走」不归模型管 —— 前端 NavGraph 用 BFS 算。
     */
    static final String SYSTEM_PROMPT = """
            你是一个电商智能客服意图路由器。请分析用户输入，严格输出 JSON 格式，不要输出任何多余文字。

            你会收到一份「可选页面」路由字典。把用户的话归到下面两类之一：
            A. 页面导航（用户想「去哪里 / 怎么去 / 怎么处理 / 怎么办理 / 我要看 / 我要管理 / 帮我打开」某个页面）
               ⇒ intent = NAVIGATE，并从字典里选**一个** target_path。
               例：「怎么上架商品」⇒ 字典里的「上架商品」；「我要去看店铺数据」⇒「经营数据」；
                   「我想改一下那个 299 元耳机的价格」⇒「查看商品」（改价与库存都在那一页）；
                   「我要管理我的钱」⇒「会员中心」（平台没有独立的钱包页）；
                   「我要看评论」⇒ 商家选「查看商品」，客户选「我的订单」（平台没有独立评价页）；
                   「怎么处理退款」⇒ 商家选「售后工作台」，客户选「我的订单」；
                   ⚠️ 「怎么处理退款」问的是「去哪办」，**不是** ORDER_QUERY / REFUND_QUERY。
               ⚠️ 上面只说明「意思对应哪一行」，**路径必须从下方字典逐字抄**，别照例子拼。
               ⚠️ 「钱 / 余额 / 积分 / 资产 / 优惠券」相关一律选「会员中心」。
               ⚠️ 「我要看评论」「怎么处理退款」「我要管理我的钱」都是 NAVIGATE（想看/想办某个页面），
                  不要判成 ORDER_QUERY / REFUND_QUERY。
               ⚠️ 「上架 / 发布商品」不是开店：「怎么上架商品」⇒ NAVIGATE「上架商品」；
                  只有「开店 / 入驻 / 加盟 / 营业执照」才是 OPEN_STORE。
            B. 数据查询与业务（用户问**自己那笔**的状态，这类**不要**给 target_path）
               ⇒ ORDER_QUERY（查我的订单状态/进度）、REFUND_QUERY（查我的售后进度/结果）、
                  OPEN_STORE（开店/入驻）、ADD_TO_SECKILL（报名秒杀）、
                  FAQ（政策咨询）、GREETING（问候）、HELP（你能做什么）、UNKNOWN（闲聊/听不懂）。
               例：「我的订单到哪了」⇒ ORDER_QUERY；「我的退款进度」⇒ REFUND_QUERY；
                   「怎么开店」⇒ OPEN_STORE；「我要报名秒杀」⇒ ADD_TO_SECKILL；
                   「我的新商品要上架秒杀页，还没上架呢」⇒ ADD_TO_SECKILL，且 need_publish = true。

            规则：
            1. target_path 只能逐字取字典里的路径；字典里没有合适的页面就给 null。
            2. 判断口诀：问「怎么做 / 如何办 / 去哪办 / 在哪看」⇒ NAVIGATE；
               问「我那一笔 / 那一单 到哪了 / 进度 / 状态 / 结果 / 有没有」⇒ 数据查询。
               对照：「怎么处理退款」= NAVIGATE；「我的退款到哪了」= REFUND_QUERY。
            3. 同样一句话在不同角色下落点不同：只从字典里给当前角色列出的页面中选。
            4. 提取槽位：
               - orderId：订单号，没有就给 null。
               - need_publish：布尔值。**仅当**目标是秒杀报名、且用户明说商品「还没上架 / 还没发布 /
                 还没有商品」时给 true；其他情况一律 false。
            5. 只输出 JSON 本身，不要 markdown 代码块，不要解释。
            """;

    static final String OUTPUT_FORMAT = """
            输出格式：{"intent": "...", "target_path": null, "slots": {"orderId": null, "need_publish": false}}
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

    /** 固定规则 + 当前角色的路由字典 +（可选）当前页。 */
    private static String systemPrompt(Integer role, String currentPath) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT);
        sb.append('\n').append(RouteCatalog.promptCatalog(role));
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

        String targetPath = parseTargetPath(root, intent, role);
        return new Result(intent, orderId, needPublish, targetPath);
    }

    /**
     * 解析 {@code target_path}：只对 NAVIGATE 生效，且必须能在当前角色的路由字典里归位。
     *
     * <p>数据类意图即使模型顺手给了路径也**丢掉** —— 否则「查订单」会被前端当成导航，
     * 把用户从订单卡旁边带走。
     */
    private static String parseTargetPath(JsonNode root, ChatIntent intent, Integer role) {
        if (intent != ChatIntent.NAVIGATE) {
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
     * NAVIGATE 时有值，且已按当前角色归位到真实路由。
     */
    public record Result(ChatIntent intent, String orderId, boolean needPublish, String targetPath) {
        /** 兼容旧调用点 / 单测：不带 target_path 的三参构造。 */
        public Result(ChatIntent intent, String orderId, boolean needPublish) {
            this(intent, orderId, needPublish, null);
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
