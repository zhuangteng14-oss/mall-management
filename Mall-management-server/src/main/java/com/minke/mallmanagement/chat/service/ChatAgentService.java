package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.model.ResolvedIdentity;
import com.minke.mallmanagement.chat.model.UserIdentity;
import com.minke.mallmanagement.chat.tool.ToolContext;
import com.minke.mallmanagement.chat.tool.ToolExecutor;
import com.minke.mallmanagement.chat.tool.ToolRegistry;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.goods.dao.GoodsDao;
import com.minke.mallmanagement.order.RefundStatusText;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
import com.minke.mallmanagement.shared.web.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 智能客服的编排层：意图 → 只读查询 → 中文回复。
 *
 * <h2>【身份只从会话来，不收客户端 id】</h2>
 * 本服务的方法签名只吃 {@code userId}/{@code role}，由 Controller 从
 * {@code AuthSession.currentUserId / currentRole} 取出。**没有任何「代表调用者」的入参**
 * ⇒ 不存在「传别人的 customerId 看别人的订单」这一类越权面。
 *
 * <h2>【只读】</h2>
 * 全部查询走既有 DAO / Service，不写库、不加锁、不开事务、不碰缓存。单次请求最多
 * 1 次 COUNT + 1 次 LIMIT 3（或按单号 LIMIT 1）+ 0/1 次售后查询。
 *
 * <h2>【回复里的用户数据】</h2>
 * 商品名、退款原因等来自数据库，原样放进 {@code reply}；前端用 Vue 插值渲染（不走
 * {@code v-html}），因此不需要在本层做 HTML 转义 —— 转义反而会让「A&B」显示成「A&amp;B」。
 */
@Service
public class ChatAgentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAgentService.class);

    /**
     * 结构化 Trace 日志的固定前缀（2026-10-24 只读统计用）。
     *
     * <p>⚠️ 只打一行 key=value 日志，**不接** OTel / Jaeger / Langfuse：本仓没有可观测性后端，
     * 导出脚本按这个标记抓行即可。字段口径见 {@link Trace}。
     */
    static final String TRACE_MARKER = "CHAT_TRACE";

    private static final int MAX_MESSAGE_LEN = 200;
    private static final int RECENT_LIMIT = 3;

    /** 订单状态名 → 中文。键来自 {@link OrderStateMachine#stateName(int, int, int)}。 */
    private static final Map<String, String> STATE_LABEL = Map.of(
            "CREATED", "待付款",
            "PAID", "已付款（待发货）",
            "SHIPPED", "已发货（待收货）",
            "RECEIVED_NODELIVERY", "已收货（未发货）",
            "COMPLETED", "已完成",
            "ILLEGAL_DELIVERED_UNPAID", "状态异常（未付款已发货）",
            "ILLEGAL_RECEIVED_UNPAID", "状态异常（未付款已收货）",
            "ILLEGAL_DELIVERED_RECEIVED_UNPAID", "状态异常（未付款已发货已收货）",
            "UNCLASSIFIED", "无法归类");

    /** 售后状态 → 中文。唯一源在 {@link RefundStatusText}（N5 / Q8=A），本类不再自持一份。 */

    /** 「那…呢 / 到哪了 / 进度」这类短追问 —— 仅在意图识别为 UNKNOWN 时用来接上一轮上下文。 */
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(那|那么|这个|那个|它|还有|然后)|(呢|到哪|怎么样了|怎么样|处理了吗|进度)");

    /** 用户自述「我是商家」—— 只用于**对照**系统解析结果，不单独当身份依据（增量-A 规则 2）。 */
    private static final Pattern CLAIM_MERCHANT = Pattern.compile(
            "我是(商家|卖家|品牌商家|品牌|商户)");

    /**
     * 「明确要求直达」的确定性判据（2026-10-22 交互纠偏）。
     *
     * <p>默认一律给【开始引导】（BFS 逐步高亮）；只有这句话本身在说「别教我、直接送我到」
     * 时才给【一键直达】。判据放在后端而不是只靠 Prompt：小模型对「我要去… / 马上打开…」
     * 的语感不稳定，而「用户有没有亲口说直接」是词面事实。
     *
     * <p>⚠️ 不能把「我要看 / 我想看」算进来：「我要去看店铺数据」仍然是去某个页面的
     * 普通导航，默认引导即可；真正要跳的是「直接 / 立刻 / 马上 / 一键」+ 位移动词。
     */
    private static final Pattern DIRECT_NAV = Pattern.compile(
            "^(?:请|帮我|给我)?(?:直接|立刻|马上|现在就|一键)"
                    + "(?:带我去|带我|去|到|打开|进入|前往|跳转到?|跳到)"
                    + "|^(?:我要|我想|帮我|带我)(?:直接|立刻|马上)?"
                    + "(?:去|到|打开|进入|前往|跳转到?|跳到)");

    /**
     * 「商品还没上架」这个**前置条件**（不是意图，不决定去哪）。
     *
     * <p>只影响秒杀分流里给一枚还是两枚胶囊。模型抽 {@code need_publish} 会超时 / 漏槽，
     * 而用户是否明说了「还没上架」是确定性事实 —— 两者取或，谁先到都不会丢这句提醒。
     */
    private static final Pattern NOT_PUBLISHED = Pattern.compile(
            "(?:还没|没|未|尚未|还没有)(?:上架|发布)");

    /**
     * 「评论 / 评价」类问句（2026-10-22 业务工具挂载）。
     *
     * <p>平台没有独立的评价管理页，而小模型仍可能把「我要看评论」判成 NAVIGATE（按旧 Prompt
     * 落到商品管理）或 ORDER_QUERY —— 这里做一次确定性纠偏：只要问的是**评论内容本身**，
     * 就走真实 DAO 查询，而不是把用户带去一个看不到评论的页面。
     *
     * <p>排除「怎么 / 如何 写评论」：那是操作指引，不是查数据。
     */
    private static final Pattern COMMENT_MARKERS = Pattern.compile("评论|评价|留言");
    private static final Pattern COMMENT_HOWTO = Pattern.compile(
            "(?:怎么|如何|怎样|在哪)(?:去)?(?:写|发|发布|添加|给)?(?:评论|评价|留言)");

    /**
     * 售后类问句（2026-10-22）：问「退款 / 退货 / 售后」时，落点只有商家售后工作台或客户我的订单。
     *
     * <p>模型偶尔把「怎么处理退款」的 target_path 选成会员中心（客户侧 2026-10-22 实测 2/3 次，
     * 上一句刚问过「我的钱」时更容易串）。字典对这类说法有唯一命中，就以字典为准 ——
     * 与上面 ORDER_QUERY / REFUND_QUERY 的纠偏同一条口径。
     */
    private static final Pattern AFTER_SALES = Pattern.compile("退款|退货|售后");

    private final OrderDao orderDao;
    private final OrderRefundService refundService;
    private final OrderStateMachine orderStateMachine;
    private final IdentityResolver identityResolver;
    private final SessionMemoryService memoryService;
    private final LlmIntentClassifier llmClassifier;
    private final CommentDao commentDao;
    private final LlmReplyComposer replyComposer;
    /** 工具注册表 / 执行器（2026-10-22 插件化）。单测走兼容构造器时为 {@code null} ⇒ 全部回退 switch。 */
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    /** 商品查询（2026-10-22 修复）：查商品名 / 编号对应的真实商品行。 */
    private final GoodsDao goodsDao;
    /**
     * 语义路由（2026-10-22 企业级改造）：启动预加载标准问法向量，按余弦相似度判意图。
     *
     * <p>用 setter 注入而不是构造器：既有的 11 参构造器被大量单测 / 冒烟切片直接 {@code new}，
     * 语义层是可选增强，缺失时自动退回 LLM 分类器与规则引擎。冒烟切片没登记本 bean 也不会
     * 因缺依赖起不来。
     */
    private SemanticRouter semanticRouter;

    /** 兼容入口（阶段一之前的单测 / 没装评论工具时）：新依赖都置空。 */
    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine,
                            IdentityResolver identityResolver,
                            SessionMemoryService memoryService,
                            LlmIntentClassifier llmClassifier) {
        this(orderDao, refundService, orderStateMachine, identityResolver, memoryService,
                llmClassifier, null, null, null, null, null);
    }

    /** 兼容入口（2026-10-22 之前的单测）：不装工具注册表。 */
    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine,
                            IdentityResolver identityResolver,
                            SessionMemoryService memoryService,
                            LlmIntentClassifier llmClassifier,
                            CommentDao commentDao,
                            LlmReplyComposer replyComposer) {
        this(orderDao, refundService, orderStateMachine, identityResolver, memoryService,
                llmClassifier, commentDao, replyComposer, null, null, null);
    }

    /** 兼容入口（2026-10-22 插件化单测）：不装商品查询。 */
    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine,
                            IdentityResolver identityResolver,
                            SessionMemoryService memoryService,
                            LlmIntentClassifier llmClassifier,
                            CommentDao commentDao,
                            LlmReplyComposer replyComposer,
                            ToolRegistry toolRegistry,
                            ToolExecutor toolExecutor) {
        this(orderDao, refundService, orderStateMachine, identityResolver, memoryService,
                llmClassifier, commentDao, replyComposer, toolRegistry, toolExecutor, null);
    }

    @Autowired
    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine,
                            IdentityResolver identityResolver,
                            SessionMemoryService memoryService,
                            LlmIntentClassifier llmClassifier,
                            CommentDao commentDao,
                            LlmReplyComposer replyComposer,
                            ToolRegistry toolRegistry,
                            ToolExecutor toolExecutor,
                            GoodsDao goodsDao) {
        this.orderDao = orderDao;
        this.refundService = refundService;
        this.orderStateMachine = orderStateMachine;
        this.identityResolver = identityResolver;
        this.memoryService = memoryService;
        this.llmClassifier = llmClassifier;
        this.commentDao = commentDao;
        this.replyComposer = replyComposer;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.goodsDao = goodsDao;
    }

    /** 语义路由是可选增强：Spring 有该 bean 就注入；单测 / 旧切片没有也不影响启动。 */
    @Autowired(required = false)
    void setSemanticRouter(SemanticRouter semanticRouter) {
        this.semanticRouter = semanticRouter;
    }

    /**
     * 回答一句话。
     *
     * @param rawMessage 用户原文
     * @param userId     会话里的业务号（客户 customerid / 商家 merchantid），未登录为 {@code null}
     * @param role       1 客户 / 2 商家 / 3 管理员；未登录为 {@code null}
     * @return 顶层平铺的响应体（由 Controller 包进 {@code Result.of}）
     */
    public Map<String, Object> ask(String rawMessage, String userId, Integer role) {
        return ask(rawMessage, userId, role, null);
    }

    /**
     * 回答一句话（带当前页）。
     *
     * @param currentPath 前端传的当前路由，只用于让大模型知道「用户站在哪」；不参与鉴权
     */
    public Map<String, Object> ask(String rawMessage, String userId, Integer role, String currentPath) {
        // 结构化 Trace（2026-10-24）：无论正常返回、参数校验还是异常，finally 都打一行。
        long startedAt = System.nanoTime();
        Trace trace = new Trace(currentTraceId());
        try {
            return askTraced(rawMessage, userId, role, currentPath, trace);
        } catch (RuntimeException e) {
            if ("none".equals(trace.degradeReason)) {
                trace.degradeReason = "exception:" + e.getClass().getSimpleName();
            }
            throw e;
        } finally {
            trace.log(startedAt);
        }
    }

    private Map<String, Object> askTraced(String rawMessage, String userId, Integer role,
                                          String currentPath, Trace trace) {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            trace.finalBranch = "validation";
            return fail("说点什么吧～");
        }
        String message = rawMessage.trim();
        if (message.length() > MAX_MESSAGE_LEN) {
            trace.finalBranch = "validation";
            return fail("消息太长了，请控制在 " + MAX_MESSAGE_LEN + " 字以内");
        }

        String normalized = ChatIntentRecognizer.normalize(message);
        FaqKnowledgeBase.Answer faq = FaqKnowledgeBase.match(normalized);

        // 阶段一：LLM 优先；任何异常（含 3s 超时）都由 decide() 打 WARN 并降级规则引擎。
        Decision decision = decide(message, normalized, faq, userId, role, currentPath, trace);
        ChatIntent intent = decision.intent();
        String orderId = decision.orderId();

        // 插件化路径（2026-10-22）：先问注册表。模型给了 tool 就按名字调，
        // 没给就按意图找当前角色的内置工具；注册表没有对应工具（问候 / FAQ / 管理员）
        // 或执行失败 ⇒ 回退下面这张 switch（既有行为逐分支保留）。
        Map<String, Object> viaTool = executeTool(decision, message, userId, role, trace);
        Map<String, Object> resp = viaTool != null ? viaTool : switch (intent) {
            case ORDER_QUERY -> answerOrders(message, userId, role, orderId);
            case REFUND_QUERY -> answerRefund(message, userId, role);
            case COMMENT_QUERY -> answerComments(message, userId, role);
            case GOODS_QUERY -> answerGoods(message, userId, role, arg(decision, "goodsKeyword"));
            case OPEN_STORE -> answerOpenStore(userId, role, isDirectNavigation(normalized));
            case ADD_TO_SECKILL -> answerAddToSeckill(userId, role, decision.needPublish());
            case GUIDE_NAVIGATION -> answerNavigate(decision.targetPath(), userId, role,
                    decision.needPublish(), false);
            case DIRECT_NAVIGATION -> answerNavigate(decision.targetPath(), userId, role,
                    decision.needPublish(), true);
            // 语义路由的三个业务导航意图：保留意图名（探针 / 排障能看到精确命中），
            // 动作仍复用同一套 tour/jump；是否直达只看用户有没有明说「直接去」。
            case GOODS_MANAGE, MERCHANT_DASHBOARD, ASSET_QUERY ->
                    answerRouted(intent, decision.targetPath(), userId, role,
                            decision.needPublish(), isDirectNavigation(normalized));
            // 旧值兜底：模型 / 旧调用点仍可能给出 NAVIGATE ⇒ 按默认引导处理。
            case NAVIGATE -> answerNavigate(decision.targetPath(), userId, role,
                    decision.needPublish(), isDirectNavigation(normalized));
            case CLARIFY -> answerClarify(role, userId, message);
            case IDENTITY_CLAIM -> answerIdentityClaim(message, userId, role);
            case GREETING -> answerSmallTalk(intent,
                    "你好呀～我可以帮你查订单、查售后进度，也能一键带你去开店。");
            case HELP -> answerSmallTalk(intent,
                    "我会这些：① 查最新订单状态；② 查退款进度；③ 带你去开店（含两步高亮引导）。");
            case FAQ -> answerFaq(faq == null
                    ? "这类政策问题我还在学习中，建议联系店铺客服确认。"
                    : faq.text());
            default -> answerSmallTalk(ChatIntent.UNKNOWN,
                    "我暂时听不懂这句话。你可以问我：查订单 / 查售后 / 怎么开店。");
        };
        trace.finalIntent = intent.name();
        if (viaTool != null) {
            trace.finalBranch = "tool:" + (trace.toolName == null ? "intent" : trace.toolName);
        } else if (trace.toolName != null) {
            // 工具层尝试过但没给出可用结果 ⇒ 落回内置 switch，trace 里保留工具名便于对账。
            trace.finalBranch = "fallback:" + intent.name();
        } else {
            trace.finalBranch = "switch:" + intent.name();
        }
        return remember(userId, message, intent, orderIdOf(resp, orderId), resp);
    }

    /** 请求级 traceId：优先沿用 {@link TraceIdFilter} 写在 MDC 里的那个。 */
    private static String currentTraceId() {
        String id = MDC.get(TraceIdFilter.MDC_KEY);
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    /**
     * 一次问答的结构化 Trace（只读统计口径，2026-10-24）。
     *
     * <p>字段与用户的验收清单逐项对应；打一行 {@code CHAT_TRACE key=value …}，
     * 空值写 {@code -}。**不记录用户原文**（避免把聊天内容写进日志）。
     */
    private static final class Trace {
        final String traceId;
        String finalIntent;
        Double semanticScore;
        Double secondScore;
        Boolean semanticConfident;
        boolean llmUsed;
        String llmFallback = "not_reached";
        /** LLM 出网单独耗时（毫秒）；未调用为 null。 */
        Long llmLatencyMs;
        /** none / connect_timeout / request_timeout / other / -（未调用）。 */
        String llmTimeoutType = "-";
        String toolName;
        Boolean toolSuccess;
        String finalBranch = "unknown";
        String degradeReason = "none";

        Trace(String traceId) {
            this.traceId = traceId;
        }

        void log(long startedAtNanos) {
            long totalLatencyMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            log.info("{} traceId={} intent={} semanticScore={} secondScore={} semanticConfident={} "
                            + "llmUsed={} llmFallback={} llmLatencyMs={} llmTimeoutType={} "
                            + "toolName={} toolSuccess={} totalLatencyMs={} "
                            + "finalBranch={} degradeReason={}",
                    TRACE_MARKER, traceId, dash(finalIntent), num(semanticScore), num(secondScore),
                    bool(semanticConfident), llmUsed, llmFallback, milli(llmLatencyMs), llmTimeoutType,
                    dash(toolName), bool(toolSuccess), totalLatencyMs, finalBranch, degradeReason);
        }

        private static String dash(String value) {
            return value == null || value.isBlank() ? "-" : value;
        }

        private static String num(Double value) {
            return value == null ? "-" : String.format(Locale.ROOT, "%.4f", value);
        }

        private static String milli(Long value) {
            return value == null ? "-" : String.valueOf(value);
        }

        private static String bool(Boolean value) {
            return value == null ? "-" : String.valueOf(value);
        }
    }

    /**
     * 走注册表执行工具。
     *
     * <p>身份只从 {@code userId}/{@code role} 注入 {@link ToolContext} —— 工具方法签名里
     * **没有**「代表谁」的业务参数，模型也无法从 slots 里指定身份。
     *
     * @return 响应体；注册表没装 / 没匹配到工具 / 执行失败 时返回 {@code null}，由调用方回退
     */
    private Map<String, Object> executeTool(Decision decision, String message,
                                            String userId, Integer role, Trace trace) {
        if (toolExecutor == null || toolRegistry == null) {
            return null;
        }
        // 「直接带我去…」必须走直达动作，而 navigate / open_store 工具只产出引导动作。
        // 这两类不带业务数据，交给下面的 switch 用 direct 标志重答一次即可。
        if (isDirectNavigation(ChatIntentRecognizer.normalize(message))
                && (decision.intent() == ChatIntent.DIRECT_NAVIGATION
                    || decision.intent() == ChatIntent.OPEN_STORE)) {
            return null;
        }
        Map<String, Object> args = decision.args() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(decision.args());
        if (!isBlank(decision.orderId())) {
            args.putIfAbsent("orderId", decision.orderId());
        }
        if (decision.targetPath() != null) {
            args.putIfAbsent("target_path", decision.targetPath());
        }
        if (decision.needPublish()) {
            args.putIfAbsent("need_publish", true);
        }
        // 商品名 / 编号：模型漏抽时用确定性规则补一刀（编号里的冒号、连字符原样保留）。
        if (decision.intent() == ChatIntent.GOODS_QUERY && isBlank(str(args.get("goodsKeyword")))) {
            String keyword = ChatIntentRecognizer.extractShoppingKeyword(
                    ChatIntentRecognizer.normalize(message));
            if (!isBlank(keyword)) {
                args.put("goodsKeyword", keyword);
            }
        }
        ToolContext context = new ToolContext(userId, role, message);
        try {
            Map<String, Object> out;
            if (!isBlank(decision.tool())) {
                trace.toolName = decision.tool();
                // 双保险：分类器已按角色校验过一次，这里再拒一次（工具名也可能来自规则层）。
                if (!toolRegistry.allows(decision.tool(), role)) {
                    trace.toolSuccess = false;
                    trace.degradeReason = "tool_role_denied";
                    log.warn("工具 {} 不在角色 {} 的可用列表内，回退内置分支", decision.tool(), role);
                    return null;
                }
                out = nonEmpty(toolExecutor.invoke(decision.tool(), context, args));
            } else {
                var tool = toolRegistry.forIntent(decision.intent(), role);
                trace.toolName = tool == null ? null : tool.name();
                if (tool == null) {
                    // 当前意图没有挂工具（如 GOODS_MANAGE / 问候）⇒ 不算「工具失败」。
                    return null;
                }
                out = nonEmpty(toolExecutor.invokeForIntent(decision.intent(), context, args));
            }
            trace.toolSuccess = out != null;
            if (out == null) {
                trace.degradeReason = "tool_empty";
            }
            return out;
        } catch (ToolRegistry.ToolException e) {
            trace.toolSuccess = false;
            trace.degradeReason = "tool_failed";
            log.warn("工具执行失败，回退内置分支：{}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            trace.toolSuccess = false;
            trace.degradeReason = "tool_exception";
            log.warn("工具执行异常，回退内置分支：{}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> nonEmpty(Map<String, Object> resp) {
        return resp == null || resp.isEmpty() ? null : resp;
    }

    /**
     * 双引擎路由：LLM 优先，规则兜底。
     *
     * <p>LLM 返回 UNKNOWN 时再走一次规则引擎 —— Prompt 没列 IDENTITY_CLAIM，
     * 这类仍由规则识别；LLM 故障则整句交给规则引擎。
     */
    private Decision decide(String message, String normalized,
                            FaqKnowledgeBase.Answer faq, String userId,
                            Integer role, String currentPath, Trace trace) {
        // 政策 FAQ 是本地确定性知识，先于任何模型 / 向量判断 ——
        // 「拆封了能退货吗」里有「退货」，不能被当成查售后进度。
        if (faq != null && faq.policy()) {
            trace.llmFallback = "policy_faq";
            return new Decision(ChatIntent.FAQ, null, false, null);
        }

        // 语义路由优先（2026-10-22）：标准问法在启动时已向量化，这里只算余弦。
        // 低于阈值 ⇒ 不硬猜，澄清；语义层不可用 ⇒ 返回 null，继续走下面的 LLM / 规则。
        SemanticRouter.Result semantic = semanticRoute(message, role, trace);
        if (semantic != null) {
            trace.semanticScore = semantic.score();
            trace.secondScore = semantic.secondScore();
            trace.semanticConfident = semantic.confident();
            if (!semantic.confident()) {
                trace.llmFallback = "semantic_not_confident";
                Decision deterministic = deterministicFallback(message, normalized, faq, userId, role);
                if (deterministic != null) {
                    return deterministic;
                }
                // 语义低分不再直接澄清：先让 DeepSeek 再判一次，超时 / 失败才落 CLARIFY。
                Decision viaLlm = llmFallbackDecision(message, normalized, faq, role, currentPath, trace);
                return viaLlm != null ? viaLlm : new Decision(ChatIntent.CLARIFY, null, false, null);
            }
            trace.llmFallback = "semantic_confident";
            Decision semanticDecision = semanticDecision(semantic, message, normalized, userId);
            // 语义层偶尔会把「在哪买」吸到 GOODS_MANAGE；买家行为一律以购物规则为准。
            if (ChatIntentRecognizer.looksLikeShopping(normalized)
                    && semanticDecision.intent() != ChatIntent.GOODS_QUERY) {
                return shoppingDecision(normalized);
            }
            return semanticDecision;
        }

        if (llmClassifier != null && llmClassifier.isEnabled()) {
            trace.llmUsed = true;
            long llmStartedAt = System.nanoTime();
            try {
                LlmIntentClassifier.Result result = llmClassifier.classify(message, role, currentPath);
                trace.llmLatencyMs = (System.nanoTime() - llmStartedAt) / 1_000_000L;
                trace.llmTimeoutType = "none";
                // 模型已经给出分类 ⇒ 后续的确定性纠偏不再算「降级」。
                trace.llmFallback = "none";
                ChatIntent intent = result.intent();
                boolean direct = isDirectNavigation(normalized);
                // 旧协议 NAVIGATE / 新协议 GUIDE_NAVIGATION 都按「用户有没有明说直达」分流：
                // 默认引导；只有「直接带我去 / 我要去」这类词面才走直达。
                if (intent == ChatIntent.NAVIGATE || intent == ChatIntent.GUIDE_NAVIGATION) {
                    intent = direct ? ChatIntent.DIRECT_NAVIGATION : ChatIntent.GUIDE_NAVIGATION;
                } else if (intent == ChatIntent.DIRECT_NAVIGATION && !direct) {
                    // 模型「脑补」出直达：用户没说直接 ⇒ 退回默认引导。
                    intent = ChatIntent.GUIDE_NAVIGATION;
                }
                String orderId = result.orderId();
                boolean needPublish = result.needPublish();
                String tool = result.tool();
                Map<String, Object> toolArgs = result.args();

                // 政策 FAQ 是本地确定性知识：即使模型把它判成 REFUND，也不能答成「查退款进度」。
                if (faq != null && faq.policy()) {
                    return new Decision(ChatIntent.FAQ, null, false, null);
                }
                // 购物 / 买家行为优先于模型分类：商家问「护膝在哪买」时，小模型容易
                // 顺着身份判成 GOODS_MANAGE。规则判据确定性地拉回 GOODS_QUERY。
                if (ChatIntentRecognizer.looksLikeShopping(normalized)) {
                    return shoppingDecision(normalized);
                }
                // ⚠️ 只有「没选工具」的 UNKNOWN 才交给规则引擎。插件工具（intent=UNKNOWN）
                // 是模型明确选了要调的方法 —— 送去规则引擎反而会把这次调用丢掉。
                if (intent == ChatIntent.UNKNOWN && isBlank(tool)) {
                    trace.llmFallback = "llm_unknown";
                    return byRules(message, normalized, faq, userId, role);
                }
                // 「我要看评论」：旧 Prompt 教模型导航到商品管理 / 我的订单，但那些页面都看不到评论。
                // 问的是评论内容 ⇒ 走真实查询（工具挂载）。这条判据先于下面的导航纠偏，
                // 也先于工具：模型可能选成 navigate，但目标页里根本没有评论。
                if (looksLikeCommentQuery(normalized)
                        && (intent == ChatIntent.ORDER_QUERY || intent == ChatIntent.REFUND_QUERY
                            || isNavigation(intent) || intent == ChatIntent.UNKNOWN)) {
                    return new Decision(ChatIntent.COMMENT_QUERY, null, false, null);
                }
                // 「查某个商品的详细数据」：小模型会把它判成 NAVIGATE（去搜索页）、ORDER_QUERY
                // （把编号当订单号）甚至 UNKNOWN。带商品名 / 编号且问的是数据 ⇒ 一律走商品查询；
                // 模型已经显式选了 query_goods 时不打断它的参数。
                if (ChatIntentRecognizer.looksLikeGoodsQuery(normalized)
                        && (intent != ChatIntent.GOODS_QUERY || isBlank(tool))) {
                    String keyword = ChatIntentRecognizer.extractGoodsKeyword(normalized);
                    return new Decision(ChatIntent.GOODS_QUERY, null, false, null, null,
                            isBlank(keyword) ? null : Map.of("goodsKeyword", keyword));
                }
                // 小模型偶尔把「我要看评论」这类「看某个页面」判成 ORDER_QUERY。
                // 页面名以 RouteCatalog 为准：字典里有唯一且够长的说法、而用户又不是在问
                // 「我那一笔到哪了」，就按导航走。判定见 asksAboutStatus。
                if ((intent == ChatIntent.ORDER_QUERY || intent == ChatIntent.REFUND_QUERY)
                        && !asksAboutStatus(normalized)) {
                    String guessed = RouteCatalog.guess(message, role);
                    if (guessed != null && !namesPageItself(normalized, guessed)) {
                        return navDecision(guessed, direct);
                    }
                }
                // 「开店」类问题模型偶尔会认成「怎么上架商品」这类别的页面（「上架」两个字同时
                // 出现在两条意图里）。字典指向的若不是入驻申请页，就以字典为准 —— 指向申请页时
                // 保持 OPEN_STORE 不动（六态身份话术比「带你去申请页」更有用）。
                if (intent == ChatIntent.OPEN_STORE) {
                    String guessed = RouteCatalog.guess(message, role);
                    if (guessed != null && !guessed.endsWith("/user/apply")) {
                        return navDecision(guessed, direct);
                    }
                }
                // 售后类句子：模型判对了「要导航」但落点选错（实测「怎么处理退款」落到会员中心）。
                // 只在字典有唯一命中、且用户没亲口说出模型选的那个页面名时纠正。
                if (isNavigation(intent) && AFTER_SALES.matcher(normalized).find()) {
                    String guessed = RouteCatalog.guess(message, role);
                    if (guessed != null && !guessed.equals(result.targetPath())
                            && !namesPageItself(normalized, result.targetPath())) {
                        return navDecision(guessed, direct);
                    }
                }
                // 「到哪了」这类短追问：模型可能判对意图但抽不到单号，用上一轮补。
                if (intent == ChatIntent.ORDER_QUERY && isBlank(orderId) && isFollowUp(normalized)) {
                    orderId = rememberedOrderId(userId);
                }
                return new Decision(intent, orderId,
                        needPublish || (intent == ChatIntent.ADD_TO_SECKILL && declaresNotPublished(normalized)),
                        result.targetPath(), tool, toolArgs);
            } catch (RuntimeException e) {
                if (trace.llmLatencyMs == null) {
                    trace.llmLatencyMs = (System.nanoTime() - llmStartedAt) / 1_000_000L;
                }
                trace.llmTimeoutType = timeoutType(e);
                trace.llmFallback = "llm_error:" + e.getClass().getSimpleName();
                trace.degradeReason = "llm_failed";
                log.warn("LLM 意图识别失败，降级规则引擎：{}: {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        } else {
            trace.llmFallback = "llm_disabled";
        }
        return byRules(message, normalized, faq, userId, role);
    }

    /**
     * 诊断：把 LLM 失败的原因归到「连接阶段 / 响应阶段 / 其他」。
     * {@code HttpConnectTimeoutException} 继承自 {@code HttpTimeoutException}，必须先判子类。
     */
    private static String timeoutType(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return "connect_timeout";
            }
            if (t instanceof HttpTimeoutException) {
                return "request_timeout";
            }
        }
        return "other";
    }

    /** 语义层调用包一层 try：语义路由故障绝不能把聊天打成 500。 */
    private SemanticRouter.Result semanticRoute(String message, Integer role, Trace trace) {
        if (semanticRouter == null) {
            trace.llmFallback = "semantic_absent";
            return null;
        }
        try {
            // ⚠️ 不在这里先判 isEnabled()：{@code route()} 自己会判（单测也常直接 stub
            // route() 而不 stub isEnabled()）。这里只记录「为什么没拿到结果」。
            SemanticRouter.Result result = semanticRouter.route(message, role);
            if (result == null) {
                trace.llmFallback = semanticRouter.isEnabled()
                        ? "semantic_unavailable" : "semantic_disabled";
            }
            return result;
        } catch (RuntimeException e) {
            trace.llmFallback = "semantic_error:" + e.getClass().getSimpleName();
            trace.degradeReason = "semantic_failed";
            log.warn("语义路由异常，降级到意图分类器：{}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 语义相似度不够（&lt; 0.75）时的确定性兜底。
     *
     * <p>只认三种「用户已经把对象说死了」的情况，避免 0.75 的护栏把明确的请求也拦掉：
     * ① 用户直接贴了商品编号 / 型号；② 直接贴了订单号；③ 「那…呢 / 到哪了」这类
     * 承接上一轮的短追问。其余一律澄清。
     */
    private Decision deterministicFallback(String message, String normalized,
                                           FaqKnowledgeBase.Answer faq, String userId,
                                           Integer role) {
        if (faq != null) {
            return new Decision(ChatIntent.FAQ, null, false, null);
        }
        if (ChatIntentRecognizer.looksLikeShopping(normalized)) {
            return shoppingDecision(normalized);
        }
        ChatIntentRecognizer.Match rule = ChatIntentRecognizer.recognize(message);
        String goodsKeyword = ChatIntentRecognizer.extractGoodsKeyword(normalized);
        boolean dataOrAction = rule.intent() == ChatIntent.ORDER_QUERY
                || rule.intent() == ChatIntent.REFUND_QUERY
                || rule.intent() == ChatIntent.COMMENT_QUERY
                || rule.intent() == ChatIntent.ADD_TO_SECKILL
                || rule.intent() == ChatIntent.OPEN_STORE;
        if (!dataOrAction && !isBlank(goodsKeyword)
                && ChatIntentRecognizer.looksLikeGoodsQuery(normalized)) {
            return new Decision(ChatIntent.GOODS_QUERY, null, false, null, null,
                    Map.of("goodsKeyword", goodsKeyword));
        }
        if (rule.hasOrderId()
                && (rule.intent() == ChatIntent.ORDER_QUERY || rule.intent() == ChatIntent.REFUND_QUERY)) {
            return new Decision(rule.intent(), rule.orderId(), false, null);
        }
        SessionMemoryService.Context context = context(userId);
        if (context != null && isFollowUp(normalized)
                && context.intent() != ChatIntent.UNKNOWN
                && context.intent() != ChatIntent.CLARIFY) {
            String orderId = context.intent() == ChatIntent.ORDER_QUERY ? context.orderId() : null;
            return new Decision(context.intent(), orderId, false, null);
        }
        return null;
    }

    /**
     * 购物 / 买家行为 → 统一决策：{@link ChatIntent#GOODS_QUERY} + 商品名槽位。
     *
     * <p>意图名保持 GOODS_QUERY（前端 / 探针的既有口径不变）；{@code answerGoods} 再根据
     * {@link ChatIntentRecognizer#wantsSearch} 决定是给商品卡还是「去商城搜索」动作。
     */
    private static Decision shoppingDecision(String normalized) {
        String keyword = ChatIntentRecognizer.extractShoppingKeyword(normalized);
        return new Decision(ChatIntent.GOODS_QUERY, null, false, null, null,
                isBlank(keyword) ? null : Map.of("goodsKeyword", keyword));
    }

    /**
     * 语义相似度低时的 **LLM 二次判定**（2026-10-24）。
     *
     * <p>只有确定性命中不了才走到这里；模型给 UNKNOWN / CLARIFY 或超时 / 异常 ⇒ 返回
     * {@code null}，由调用方落 CLARIFY。返回的意图仍要过购物规则与直达词面这两道闸。
     */
    private Decision llmFallbackDecision(String message, String normalized,
                                         FaqKnowledgeBase.Answer faq, Integer role,
                                         String currentPath, Trace trace) {
        if (llmClassifier == null || !llmClassifier.isEnabled()) {
            trace.llmFallback = "semantic_low_llm_disabled";
            return null;
        }
        trace.llmUsed = true;
        long startedAt = System.nanoTime();
        try {
            LlmIntentClassifier.Result result = llmClassifier.classify(message, role, currentPath);
            trace.llmLatencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            trace.llmTimeoutType = "none";
            trace.llmFallback = "semantic_low_llm";
            ChatIntent intent = result.intent();
            if (intent == ChatIntent.UNKNOWN || intent == ChatIntent.CLARIFY) {
                return null;
            }
            if (faq != null && faq.policy()) {
                return new Decision(ChatIntent.FAQ, null, false, null);
            }
            if (ChatIntentRecognizer.looksLikeShopping(normalized)) {
                return shoppingDecision(normalized);
            }
            boolean direct = isDirectNavigation(normalized);
            if (isNavigation(intent)) {
                // 模型只给了导航意图却没给可归位的路径 ⇒ 不猜，交回澄清。
                if (isBlank(result.targetPath())) {
                    return null;
                }
                intent = direct ? ChatIntent.DIRECT_NAVIGATION : ChatIntent.GUIDE_NAVIGATION;
            }
            Map<String, Object> args = result.args();
            String goodsKeyword = args == null ? null : str(args.get("goodsKeyword"));
            if (intent == ChatIntent.GOODS_QUERY && isBlank(goodsKeyword)) {
                String keyword = ChatIntentRecognizer.extractShoppingKeyword(normalized);
                if (!isBlank(keyword)) {
                    args = new LinkedHashMap<>(args == null ? Map.of() : args);
                    args.put("goodsKeyword", keyword);
                }
            }
            return new Decision(intent, result.orderId(), result.needPublish(),
                    result.targetPath(), result.tool(), args);
        } catch (RuntimeException e) {
            if (trace.llmLatencyMs == null) {
                trace.llmLatencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            }
            trace.llmTimeoutType = timeoutType(e);
            trace.llmFallback = "semantic_low_llm_error:" + e.getClass().getSimpleName();
            trace.degradeReason = "llm_failed";
            log.warn("语义低分后的 LLM 二次判定失败，落澄清：{}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 语义命中的结果 → 编排层决策。
     *
     * <p>语义层只说「像哪个意图 / 哪页」，不抽业务槽位：订单号仍由规则提取器从原文取，
     * 商品编号原样保留；「直接去」仍由 {@link #isDirectNavigation} 的词面判据决定。
     */
    private Decision semanticDecision(SemanticRouter.Result result, String message,
                                      String normalized, String userId) {
        ChatIntent intent = result.intent();
        if (intent == ChatIntent.GUIDE_NAVIGATION && isDirectNavigation(normalized)) {
            intent = ChatIntent.DIRECT_NAVIGATION;
        }
        String orderId = null;
        if (intent == ChatIntent.ORDER_QUERY) {
            ChatIntentRecognizer.Match rule = ChatIntentRecognizer.recognize(message);
            if (rule.intent() == ChatIntent.ORDER_QUERY) {
                orderId = rule.orderId();
            }
            if (isBlank(orderId) && isFollowUp(normalized)) {
                orderId = rememberedOrderId(userId);
            }
        }
        Map<String, Object> args = null;
        if (intent == ChatIntent.GOODS_QUERY) {
            String keyword = ChatIntentRecognizer.extractShoppingKeyword(normalized);
            if (!isBlank(keyword)) {
                args = Map.of("goodsKeyword", keyword);
            }
        }
        boolean needPublish = intent == ChatIntent.ADD_TO_SECKILL
                && declaresNotPublished(normalized);
        return new Decision(intent, orderId, needPublish, result.targetPath(), null, args);
    }

    /** 原有规则引擎（阶段一之前的行为，原样保留）。 */
    private Decision byRules(String message, String normalized,
                             FaqKnowledgeBase.Answer faq, String userId, Integer role) {
        // 政策类 FAQ 排在任务意图**前面**：用户给的例子「拆封了能退货吗」里含「退货」，
        // 会先命中 REFUND 正则 ⇒ 只在 UNKNOWN 分支查 FAQ 的话，这条示例永远答不到。
        // 其余 FAQ 仍按增量要求挂在 UNKNOWN 分支。
        if (faq != null && faq.policy()) {
            return new Decision(ChatIntent.FAQ, null, false, null);
        }

        ChatIntentRecognizer.Match match = ChatIntentRecognizer.recognize(message);
        ChatIntent intent = match.intent();
        String orderId = match.orderId();
        boolean direct = isDirectNavigation(normalized);

        // 「怎么处理退款」这类「去哪办」的句子，规则引擎只看得见「退款」两个字。
        // 与 decide() 里的字典纠偏同一条判据（在问自己那笔状态时不动），
        // 这样模型超时时落点也不变，探针不用赌那 3 秒。
        if (intent == ChatIntent.REFUND_QUERY && !asksAboutStatus(normalized)) {
            String guessed = RouteCatalog.guess(message, role);
            if (guessed != null && !namesPageItself(normalized, guessed)) {
                return navDecision(guessed, direct);
            }
        }

        // 「怎么上架商品」里的「上架」同时是开店的词面；字典明确指向发布页时以字典为准
        // （与 decide() 里的同名纠偏同一条判据）。指向入驻申请页则保留六态话术。
        if (intent == ChatIntent.OPEN_STORE) {
            String guessed = RouteCatalog.guess(message, role);
            if (guessed != null && !guessed.endsWith("/user/apply")) {
                return navDecision(guessed, direct);
            }
        }

        if (intent == ChatIntent.GOODS_QUERY) {
            String keyword = ChatIntentRecognizer.extractShoppingKeyword(normalized);
            return new Decision(intent, null, false, null, null,
                    isBlank(keyword) ? null : Map.of("goodsKeyword", keyword));
        }

        if (intent == ChatIntent.UNKNOWN) {
            if (faq != null) {
                return new Decision(ChatIntent.FAQ, null, false, null);
            }
            // 短期记忆：只有「那…呢 / 到哪了 / 进度」这类短追问才接上一轮意图，
            // 避免把「今天天气不错」也当成订单问题。
            SessionMemoryService.Context context = context(userId);
            if (context != null && isFollowUp(normalized)) {
                intent = context.intent();
                if (intent == ChatIntent.ORDER_QUERY && isBlank(orderId)) {
                    orderId = context.orderId();
                }
            } else {
                // 大模型超时 / 未配置 Key 时的降级导航：话术取自 RouteCatalog 的「用户说」，
                // 不在这里新增意图正则；没把握（并列 / 太短）就照旧走 UNKNOWN。
                String guessed = RouteCatalog.guess(message, role);
                if (guessed != null) {
                    return navDecision(guessed, direct);
                }
            }
        }
        return new Decision(intent, orderId,
                intent == ChatIntent.ADD_TO_SECKILL && declaresNotPublished(normalized), null);
    }

    /** 用户明说商品还没上架（与模型抽的 {@code need_publish} 取或）。 */
    private static boolean declaresNotPublished(String normalized) {
        return NOT_PUBLISHED.matcher(normalized).find();
    }

    /** 用户是否**明确要求直达**（词面判据，见 {@link #DIRECT_NAV}）。 */
    static boolean isDirectNavigation(String normalized) {
        return normalized != null && DIRECT_NAV.matcher(normalized).find();
    }

    /** 三个导航意图（含已废弃的 NAVIGATE）—— 纠偏分支要一视同仁。 */
    private static boolean isNavigation(ChatIntent intent) {
        return intent == ChatIntent.GUIDE_NAVIGATION
                || intent == ChatIntent.DIRECT_NAVIGATION
                || intent == ChatIntent.NAVIGATE;
    }

    /** 纠偏分支用的导航决策：默认引导，只有明说直达时才给 DIRECT。 */
    private static Decision navDecision(String path, boolean direct) {
        return new Decision(direct ? ChatIntent.DIRECT_NAVIGATION : ChatIntent.GUIDE_NAVIGATION,
                null, false, path);
    }

    /** 问的是评论内容本身（不是「怎么写评论」的操作指引）。 */
    private static boolean looksLikeCommentQuery(String normalized) {
        return COMMENT_MARKERS.matcher(normalized).find()
                && !COMMENT_HOWTO.matcher(normalized).find();
    }

    /**
     * 用户是不是**直接说了那个页面的名字**。
     *
     * <p>「查我的订单」里「我的订单」既是页面名也是业务对象 —— 这种要回答数据，不要被
     * 带去订单页（每轮回复都有这枚胶囊，落点必须稳定）；而「我要看评论」没提「我的订单」，
     * 是「想看某个页面」，交给导航。
     */
    private static boolean namesPageItself(String normalized, String path) {
        RouteCatalog.Route route = RouteCatalog.find(path);
        return route != null && normalized.contains(route.title());
    }

    private String rememberedOrderId(String userId) {
        SessionMemoryService.Context context = context(userId);
        return context != null && context.intent() == ChatIntent.ORDER_QUERY
                ? context.orderId() : null;
    }

    private SessionMemoryService.Context context(String userId) {
        return memoryService == null ? null : memoryService.lastContext(userId);
    }

    /**
     * 意图 + 槽位的内部决策。
     *
     * @param needPublish 只对秒杀报名有意义
     * @param targetPath  只对 NAVIGATE 有意义（已按角色归位到真实路由）
     * @param tool        模型从动态工具目录里选的工具名；{@code null} = 按 intent 走内置工具
     * @param args        模型填进 slots 的参数（插件工具用；内置工具只用 orderId / need_publish）
     */
    private record Decision(ChatIntent intent, String orderId, boolean needPublish, String targetPath,
                            String tool, Map<String, Object> args) {

        /** 规则引擎 / 纠偏分支用：不带工具。 */
        Decision(ChatIntent intent, String orderId, boolean needPublish, String targetPath) {
            this(intent, orderId, needPublish, targetPath, null, null);
        }
    }

    // ==================== 商品 ====================

    /**
     * 「查商品」：按商品名 / 编号查真实商品行（2026-10-22 修复）。
     *
     * <p>为什么不能走订单 / 导航：商品编号里可以带冒号（{@code 222:ij-555}），旧路把它当
     * 订单号查，答成「没找到单号…你最近有 3708 笔订单」；模型判 UNKNOWN 时又会命中
     * intent 留空的自检工具。这里按「先精确后模糊」查 goods 表，查不到就明确说未找到。
     *
     * <p>包内可见：{@link ChatToolSet} 的 {@code query_goods} 工具调用它。
     */
    Map<String, Object> answerGoods(String question, String userId, Integer role,
                                    String wantedKeyword) {
        String normalized = ChatIntentRecognizer.normalize(question);
        String keyword = isBlank(wantedKeyword)
                ? ChatIntentRecognizer.extractShoppingKeyword(normalized)
                : wantedKeyword.trim();
        // 买家行为（在哪买 / 想买 / 搜索 / 帮我找）：回复要给「去商城搜索」动作，
        // 而不是只给一张商品卡。商家身份同样适用 —— 商家也是消费者。
        boolean shopping = ChatIntentRecognizer.looksLikeShopping(normalized);
        boolean wantsSearch = ChatIntentRecognizer.wantsSearch(normalized);
        if (isBlank(keyword)) {
            if (shopping) {
                return reply(ChatIntent.GOODS_QUERY,
                        "您想找什么商品？把商品名或型号发我（例如「护膝」「蓝牙耳机」「oppo12」），"
                                + "我直接带您去商城搜索。",
                        null, searchAction(null, role),
                        List.of(searchSuggestion(null), "查我的订单"));
            }
            return reply(ChatIntent.GOODS_QUERY,
                    "请告诉我要查哪个商品：把商品名或编号发我（例如「oppo12」或「222:ij-555」），"
                            + "我帮你查价格、库存和在售状态。",
                    null, null, List.of("查我的订单", "怎么开店"));
        }
        if (goodsDao == null) {
            if (shopping) {
                return reply(ChatIntent.GOODS_QUERY,
                        "商品查询暂时不可用，不过您可以直接去商城搜索「" + keyword + "」。",
                        null, searchAction(keyword, role),
                        List.of(searchSuggestion(keyword), "查我的订单"));
            }
            return reply(ChatIntent.GOODS_QUERY,
                    "商品查询暂时不可用，请到商城搜索页按关键词搜索。", null, null, null);
        }
        List<Map<String, Object>> rows;
        try {
            rows = goodsDao.selectForChat(keyword, RECENT_LIMIT);
        } catch (RuntimeException e) {
            log.warn("商品查询失败（keyword={}）：{}: {}", keyword,
                    e.getClass().getSimpleName(), e.getMessage());
            if (shopping) {
                return reply(ChatIntent.GOODS_QUERY,
                        "商品查询暂时失败，您可以直接去商城搜索「" + keyword + "」。",
                        null, searchAction(keyword, role),
                        List.of(searchSuggestion(keyword), "查我的订单"));
            }
            return reply(ChatIntent.GOODS_QUERY,
                    "商品查询暂时失败，请稍后重试，或到商城搜索页按关键词搜索。", null, null, null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        if (rows == null || rows.isEmpty()) {
            data.put("found", false);
            data.put("total", 0);
            if (shopping) {
                return reply(ChatIntent.GOODS_QUERY,
                        "店里暂时没有搜到「" + keyword + "」，不过商城搜索还能找其他店铺的同款。"
                                + "点下面按钮，我直接带您去搜索页。",
                        data, searchAction(keyword, role),
                        List.of(searchSuggestion(keyword), "查我的订单"));
            }
            return reply(ChatIntent.GOODS_QUERY,
                    "未找到商品「" + keyword + "」。请核对商品名或编号后重试，"
                            + "也可以到商城搜索页按关键词搜索。",
                    data, null, List.of("查我的订单", "怎么开店"));
        }
        Map<String, Object> first = rows.get(0);
        String name = str(first.get("GoodName"));
        String type = str(first.get("GoodType"));
        double price = doubleOf(first.get("Price"));
        double original = doubleOf(first.get("OriginalPrice"));
        String state = goodsState(first);
        boolean onSale = "在售".equals(state);

        StringBuilder text = new StringBuilder();
        if (rows.size() > 1) {
            text.append("找到 ").append(rows.size()).append(" 个相近商品，最匹配的是：");
        }
        text.append("为您查到商品「").append(name).append("」，当前售价 ¥").append(money(price));
        if (original > price) {
            text.append("（划线价 ¥").append(money(original)).append("）");
        }
        text.append("，").append(goodsStockLabel(first));
        if (!isBlank(type)) {
            text.append("，分类 ").append(type);
        }
        text.append("，").append(onSale ? "正在热卖中。" : "当前" + state + "。");
        if (wantsSearch) {
            text.append("在商城搜索「").append(keyword).append("」就能看到它，"
                    + "点下面按钮我直接带您去搜索页。");
        } else if (onSale) {
            text.append("您可以点击下方按钮，我直接带您去详情页看看。");
        }

        List<Object> suggestions = new ArrayList<>();
        if (wantsSearch) {
            suggestions.add(searchSuggestion(keyword));
        }
        if (onSale) {
            suggestions.add(goodsSuggestion(name));
        }
        suggestions.add("查我的订单");
        suggestions.add("怎么开店");

        data.put("found", true);
        data.put("total", rows.size());
        data.put("goods", goodsCards(rows));
        Object action = wantsSearch ? searchAction(keyword, role) : null;
        return reply(ChatIntent.GOODS_QUERY, text.toString(), data, action, suggestions);
    }

    /**
     * 商城搜索落点：买家 {@code /customer/search}，商家 {@code /merchant/search}，
     * 关键词用 {@code kw} 透传（前端 GoodsSelect 读取）。
     *
     * <p>⚠️ 不走 {@link RouteCatalog#resolve}：它按设计会剥掉 {@code ?query}，
     * 而这里的关键词正是要带给搜索页的参数。
     */
    static String searchPath(Integer role, String keyword) {
        String base = isMerchant(role) ? "/merchant/search" : "/customer/search";
        if (isBlank(keyword)) {
            return base;
        }
        return base + "?kw=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 「去商城搜索」动作：直达搜索页并带上关键词（搜索页在所有壳里都能打开）。 */
    private static Map<String, Object> searchAction(String keyword, Integer role) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "jump");
        action.put("targetPath", searchPath(role, keyword));
        action.put("targetLabel", "商城搜索");
        action.put("label", "去商城搜索");
        return action;
    }

    /** 「去商城搜索」胶囊：前端据此 push 搜索页；与 action 并存时用户点哪个都能到。 */
    private static Map<String, Object> searchSuggestion(String keyword) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", "去商城搜索");
        item.put("routeKey", "search");
        if (!isBlank(keyword)) {
            item.put("keyword", keyword);
        }
        return item;
    }

    /**
     * 商品行的**买家视图** —— 只出「商品名 / 价格 / 库存 / 分类 / 在售状态」。
     *
     * <p>⚠️ {@code Id} / {@code MerchantId} / {@code ProductCode} 是内部定位字段
     * （2026-10-22 用户实测：买家不该看到 {@code 5N1k5K2guh} 这种店铺号）。脱敏做在这里，
     * 而不是指望前端不渲染 —— {@code data} 是直接下发到浏览器的。
     */
    private static List<Map<String, Object>> goodsCards(List<Map<String, Object>> rows) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", str(row.get("GoodName")));
            double price = doubleOf(row.get("Price"));
            double original = doubleOf(row.get("OriginalPrice"));
            card.put("price", price);
            if (original > price) {
                card.put("originalPrice", original);
            }
            int skuCount = intOf(row.get("SkuCount"), 0);
            card.put("stock", skuCount > 0 ? intOf(row.get("StockTotal"), 0) : null);
            card.put("stockLabel", goodsStockLabel(row));
            card.put("category", str(row.get("GoodType")));
            card.put("state", goodsState(row));
            cards.add(card);
        }
        return cards;
    }

    /**
     * 库存话术：有 SKU 行才谈数量；没有 SKU 行（{@code goods} 表本身无库存列）时如实说
     * 「以详情页为准」，绝不编一个数字。
     */
    private static String goodsStockLabel(Map<String, Object> row) {
        int skuCount = intOf(row.get("SkuCount"), 0);
        if (skuCount <= 0) {
            return "库存以详情页为准";
        }
        int total = intOf(row.get("StockTotal"), 0);
        if (total <= 0) {
            return "暂时无货";
        }
        if (total < 20) {
            return "仅剩 " + total + " 件";
        }
        return "库存充足";
    }

    /** 商品详情胶囊：前端据此触发「搜索框 → 搜索按钮 → 商品卡片 → 详情页」的跨页漫游。 */
    private static Map<String, Object> goodsSuggestion(String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", "带我去商品详情");
        item.put("routeKey", "goods");
        item.put("keyword", name);
        return item;
    }

    /** 商品行 → 状态说明（{@code Status=2} 且 {@code ShelfState=1} 才是在售）。 */
    private static String goodsState(Map<String, Object> row) {
        if (intOf(row.get("Status"), 2) != 2) {
            return "未上架（待审核或已驳回）";
        }
        if (intOf(row.get("ShelfState"), 1) != 1) {
            return "已下架";
        }
        return "在售";
    }

    /** 从 Decision 的参数里取一个字符串槽位（缺失返回空串，交给 isBlank 判断）。 */
    private static String arg(Decision decision, String name) {
        Map<String, Object> args = decision.args();
        return args == null ? null : str(args.get(name));
    }

    // ==================== 订单 ====================

    /** 包内可见：{@link ChatToolSet} 的 {@code buyer_orders} / {@code merchant_orders} 工具调用它。 */
    Map<String, Object> answerOrders(String question, String userId, Integer role,
                                     String wantedOrderId) {
        if (isBlank(userId)) {
            return notLoggedIn(ChatIntent.ORDER_QUERY);
        }
        boolean merchant = isMerchant(role);
        long total = merchant ? orderDao.countByMerchant(userId) : orderDao.countByCustomer(userId);
        List<Map<String, Object>> recent = merchant
                ? orderDao.selectRecentByMerchant(userId, RECENT_LIMIT)
                : orderDao.selectRecentByCustomer(userId, RECENT_LIMIT);

        Map<String, Object> picked = null;
        boolean idMiss = false;
        if (!isBlank(wantedOrderId)) {
            picked = merchant
                    ? orderDao.selectByOrderIdAndMerchant(userId, wantedOrderId)
                    : orderDao.selectByOrderIdAndCustomer(userId, wantedOrderId);
            if (picked == null) {
                idMiss = true;
                picked = recent.isEmpty() ? null : recent.get(0);
            }
        } else if (!recent.isEmpty()) {
            picked = recent.get(0);
        }

        if (picked == null) {
            String text;
            if (idMiss) {
                text = "没找到单号 " + wantedOrderId + "（可能不是你的单号），"
                        + (merchant ? "你店铺也还没有订单记录。" : "你也没有历史订单。");
            } else {
                text = merchant ? "你店铺还没有订单记录。" : "你还没有下单记录，可以去首页逛逛～";
            }
            return reply(ChatIntent.ORDER_QUERY,
                    composeOrFallback(question, ChatIntent.ORDER_QUERY,
                            orderFacts(merchant, total, List.of(), idMiss ? wantedOrderId : null), text),
                    dataOrders(total, List.of()), null, orderSuggestions());
        }

        StringBuilder text = new StringBuilder();
        if (idMiss) {
            // 按单号查不到也要回答「最近的订单」，避免答非所问（P0-1）。
            text.append("没找到单号 ").append(wantedOrderId)
                    .append("（可能不是你的单号）。");
        }
        if (!isBlank(wantedOrderId) && !idMiss) {
            text.append("订单 ").append(wantedOrderId).append("：")
                    .append(appendOrderBrief(picked)).append("。");
        } else {
            text.append(merchant ? "你店铺最近有 " : "你最近有 ")
                    .append(total)
                    .append(" 笔订单，最新一笔是「")
                    .append(appendOrderBrief(picked))
                    .append("」。");
        }
        String[] state = stateOf(picked);
        text.append("当前状态：").append(state[1]).append("。");

        List<Map<String, Object>> summaries = new ArrayList<>();
        if (!isBlank(wantedOrderId) && !idMiss) {
            summaries.add(summary(picked));
        } else {
            for (Map<String, Object> row : recent) {
                summaries.add(summary(row));
            }
        }
        // 按单号命中时这份回复只讲那一笔 ⇒ `total` 写结果集大小，避免前端多出
        // 「共 N 笔，这里显示最近 1 笔」这种答非所问的脚注。
        long shownTotal = (!isBlank(wantedOrderId) && !idMiss) ? summaries.size() : total;
        return reply(ChatIntent.ORDER_QUERY,
                composeOrFallback(question, ChatIntent.ORDER_QUERY,
                        orderFacts(merchant, total, summaries, idMiss ? wantedOrderId : null),
                        text.toString()),
                dataOrders(shownTotal, summaries), null, orderSuggestions());
    }

    /** 「商品名（单号 X），实付 ¥Y，下单时间 Z」—— 单笔订单的口语化摘要。 */
    private String appendOrderBrief(Map<String, Object> row) {
        String product = displayProduct(row.get("ProductInformation"));
        String orderId = str(row.get("OrderId"));
        String amount = money(row.get("Price"));
        String created = time(row.get("CreateTime"));
        StringBuilder sb = new StringBuilder();
        sb.append(product);
        if (!isBlank(orderId)) {
            sb.append("（单号 ").append(orderId).append("）");
        }
        sb.append("，实付 ¥").append(amount);
        if (!isBlank(created)) {
            sb.append("，下单时间 ").append(created);
        }
        return sb.toString();
    }

    private Map<String, Object> dataOrders(long total, List<Map<String, Object>> orders) {
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("orders", orders);
        return data;
    }

    private Map<String, Object> summary(Map<String, Object> row) {
        String[] state = stateOf(row);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderId", str(row.get("OrderId")));
        item.put("product", displayProduct(row.get("ProductInformation")));
        item.put("quantity", intOf(row.get("Quantity"), 1));
        item.put("amount", doubleOf(row.get("Price")));
        item.put("state", state[0]);
        item.put("stateLabel", state[1]);
        item.put("createTime", time(row.get("CreateTime")));
        item.put("cancelled", row.get("CancelledAt") != null);
        return item;
    }

    /**
     * 三位状态位 → {枚举名, 中文}；已取消优先于三位（{@code CancelledAt} 是独立列）。
     *
     * <p>{@link OrderStateMachine#stateName(int, int, int)} **永不抛**，越界值回
     * {@code UNCLASSIFIED}，所以这里不需要 try/catch。
     */
    private String[] stateOf(Map<String, Object> row) {
        if (row.get("CancelledAt") != null) {
            return new String[]{"CANCELLED", "已取消"};
        }
        String name = orderStateMachine.stateName(
                intOf(row.get("PayIf"), 0),
                intOf(row.get("DeliveryIf"), 0),
                intOf(row.get("ReceiveIf"), 0));
        return new String[]{name, STATE_LABEL.getOrDefault(name, name)};
    }

    private List<Object> orderSuggestions() {
        List<Object> list = new ArrayList<>();
        // 对象型 suggestion：前端据此渲染「跳转」胶囊，而不是把这句话再发回后端。
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("label", "查看全部订单");
        all.put("routeKey", "orders");
        list.add(all);
        list.add("查售后进度");
        list.add("怎么开店");
        return list;
    }

    // ==================== 售后 ====================

    /** 包内可见：{@link ChatToolSet} 的 {@code buyer_refunds} / {@code merchant_refunds} 工具调用它。 */
    Map<String, Object> answerRefund(String question, String userId, Integer role) {
        if (isBlank(userId)) {
            return notLoggedIn(ChatIntent.REFUND_QUERY);
        }
        boolean merchant = isMerchant(role);
        Map<String, Object> result = merchant
                ? refundService.merchantRefunds(userId, null, 1)
                : refundService.myRefunds(userId, 1);

        List<Map<String, Object>> rows = rowsOf(result);
        if (rows.isEmpty()) {
            String text = merchant
                    ? "你店铺目前没有售后记录。"
                    : "你最近没有售后记录。已收货的订单可在「历史订单」页发起售后；也可以问「查我的订单」。";
            return reply(ChatIntent.REFUND_QUERY,
                    composeOrFallback(question, ChatIntent.REFUND_QUERY,
                            "身份：" + (merchant ? "商家" : "客户") + "\n售后记录数：0\n", text),
                    null, null, refundSuggestions());
        }

        Map<String, Object> row = rows.get(0);
        int status = intOf(row.get("RefundStatus"), 0);
        String statusLabel = RefundStatusText.of(status);
        double amount = doubleOf(row.get("RefundAmount"));
        if (amount <= 0d) {
            amount = doubleOf(row.get("Price"));
        }
        String orderId = str(row.get("OrderId"));
        String reason = str(row.get("RefundReason"));
        String applyAt = time(row.get("RefundApplyAt"));
        String handledAt = time(row.get("RefundHandledAt"));
        String handler = str(row.get("RefundHandler"));
        int retry = intOf(row.get("RefundRetryCount"), 0);

        StringBuilder text = new StringBuilder();
        text.append(merchant ? "你店铺的订单 " : "订单 ")
                .append(orderId)
                .append(" 的售后当前是「")
                .append(statusLabel)
                .append("」");
        if (!merchant) {
            text.append("（第 ").append(retry).append("/2 次申请）");
        }
        text.append("。退款金额 ¥").append(money(amount));
        if (!isBlank(applyAt)) {
            text.append("，申请时间 ").append(applyAt);
        }
        if (merchant) {
            text.append("，买家原因：").append(isBlank(reason) ? "—" : reason);
        } else if (!isBlank(reason)) {
            text.append("，原因：").append(reason);
        }
        if (!isBlank(handler)) {
            text.append("，处理人：").append(handler);
        }
        if (!isBlank(handledAt)) {
            text.append("，处理时间：").append(handledAt);
        }
        text.append("。");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("refundStatus", status);
        data.put("refundStatusLabel", statusLabel);
        data.put("refundAmount", amount);
        data.put("reason", reason);
        data.put("applyAt", applyAt);
        data.put("handledAt", handledAt);
        data.put("handler", handler);
        data.put("retryCount", retry);

        String facts = "身份：" + (merchant ? "商家" : "客户") + "\n"
                + "订单号：" + orderId + "\n"
                + "退款状态：" + statusLabel + "\n"
                + "退款金额：¥" + money(amount) + "\n"
                + "申请时间：" + (isBlank(applyAt) ? "—" : applyAt) + "\n"
                + (merchant
                    ? "买家原因：" + (isBlank(reason) ? "—" : reason) + "\n"
                    : (isBlank(reason) ? "" : "原因：" + reason + "\n"))
                + "处理人：" + (isBlank(handler) ? "—" : handler) + "\n"
                + "处理时间：" + (isBlank(handledAt) ? "—" : handledAt) + "\n";
        return reply(ChatIntent.REFUND_QUERY,
                composeOrFallback(question, ChatIntent.REFUND_QUERY, facts, text.toString()),
                data, null, refundSuggestions());
    }

    private List<Object> refundSuggestions() {
        List<Object> list = new ArrayList<>();
        list.add("查我的订单");
        list.add("怎么开店");
        return list;
    }

    // ==================== 评论 / 评价（业务工具挂载） ====================

    /**
     * 「我要看评论」—— 真的去查 {@code comment} 表，而不是把用户带去一个看不到评论的页面。
     *
     * <p>商家：查自己商品下的评论（{@code goods.MerchantId} 圈定），带「收到几条 / 几条没回复」；
     * 客户：查自己发过的评论（{@code comment.AuthorId = 登录名}），带「商家回复了没有」。
     *
     * <p>两个分支都只读、只取最近 {@value #RECENT_LIMIT} 条；正文已在 SQL 里截到 60 字。
     */
    /** 包内可见：{@link ChatToolSet} 的 {@code buyer_comments} / {@code merchant_comments} 工具调用它。 */
    Map<String, Object> answerComments(String question, String userId, Integer role) {
        if (isBlank(userId)) {
            return notLoggedIn(ChatIntent.COMMENT_QUERY);
        }
        if (commentDao == null) {
            return reply(ChatIntent.COMMENT_QUERY,
                    "评论查询暂时不可用，请稍后再试。", null, null, defaultSuggestions());
        }
        boolean merchant = isMerchant(role);
        long total;
        long unanswered = 0;
        List<Map<String, Object>> rows;
        if (merchant) {
            total = commentDao.countByMerchant(userId);
            unanswered = commentDao.countUnansweredByMerchant(userId);
            rows = commentDao.selectRecentByMerchant(userId, RECENT_LIMIT);
        } else {
            // comment.AuthorId 是**登录名**，会话里给的是**业务号** ⇒ 这里必须换算（类注释「三个账号」）。
            String username = commentDao.selectUsernameByUserId(userId);
            total = isBlank(username) ? 0 : commentDao.countByAuthor(username);
            rows = isBlank(username) ? List.of() : commentDao.selectRecentByAuthor(username, RECENT_LIMIT);
        }

        List<Map<String, Object>> comments = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            comments.add(commentSummary(row));
        }

        String fallback = commentTemplateText(merchant, total, unanswered, comments);
        String facts = commentFacts(merchant, total, unanswered, comments);
        return reply(ChatIntent.COMMENT_QUERY,
                composeOrFallback(question, ChatIntent.COMMENT_QUERY, facts, fallback),
                dataComments(merchant, total, unanswered, comments), null, commentSuggestions());
    }

    private Map<String, Object> commentSummary(Map<String, Object> row) {
        String kind = str(row.get("Kind"));
        int merchantReplies = intOf(row.get("MerchantReplies"), 0);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.get("Id"));
        item.put("kind", kind);
        item.put("kindLabel", kindLabel(kind));
        item.put("goods", isBlank(str(row.get("GoodName"))) ? "（商品已下线）" : str(row.get("GoodName")));
        item.put("rating", intOf(row.get("Rating"), 0));
        item.put("content", str(row.get("Content")));
        item.put("createdAt", str(row.get("CreatedAt")));
        item.put("replyCount", intOf(row.get("ReplyCount"), 0));
        item.put("merchantReplied", merchantReplies > 0);
        return item;
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "review" -> "评价";
            case "question" -> "提问";
            default -> "评论";
        };
    }

    private Map<String, Object> dataComments(boolean merchant, long total, long unanswered,
                                             List<Map<String, Object>> comments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", merchant ? "merchant" : "customer");
        data.put("total", total);
        if (merchant) {
            data.put("unanswered", unanswered);
        }
        data.put("comments", comments);
        return data;
    }

    /** 确定性模板：模型超时 / 未配置时的兜底，内容与「事实」逐项对齐。 */
    private String commentTemplateText(boolean merchant, long total, long unanswered,
                                       List<Map<String, Object>> comments) {
        StringBuilder text = new StringBuilder();
        if (total == 0) {
            text.append(merchant
                    ? "您店铺的商品目前还没有收到评论或提问。买家确认收货后可以在商品详情里评价。"
                    : "您还没有发表过评论。确认收货后，可以在商品详情或历史订单里评价。");
            return text.toString();
        }
        if (merchant) {
            text.append("您店铺的商品共收到 ").append(total).append(" 条评论/提问");
            text.append(unanswered > 0
                    ? "，其中 " + unanswered + " 条还没有商家回复"
                    : "，目前都回复过了");
        } else {
            text.append("您一共发表过 ").append(total).append(" 条评论/评价");
        }
        text.append("。");
        if (!comments.isEmpty()) {
            Map<String, Object> first = comments.get(0);
            text.append("最近一条是").append(first.get("kindLabel"))
                    .append("：商品「").append(first.get("goods")).append("」");
            if ("评价".equals(first.get("kindLabel"))) {
                text.append("，").append(first.get("rating")).append(" 星");
            }
            text.append("「").append(excerpt(str(first.get("content")), 30)).append("」，")
                    .append(Boolean.TRUE.equals(first.get("merchantReplied"))
                            ? (merchant ? "您已经回复过" : "商家已经回复了")
                            : (merchant ? "还没有回复" : "商家还没有回复"))
                    .append("。");
        }
        text.append("平台没有独立的评价管理页，评论随商品详情展示。");
        return text.toString();
    }

    /** 给模型看的事实（每条一行，全部来自 DAO；模型不许编造）。 */
    private String commentFacts(boolean merchant, long total, long unanswered,
                                List<Map<String, Object>> comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("身份：").append(merchant ? "商家" : "客户").append('\n');
        sb.append(merchant ? "店铺商品收到的评论/提问总数：" : "我发表过的评论总数：")
                .append(total).append('\n');
        if (merchant) {
            sb.append("其中还没有商家回复：").append(unanswered).append('\n');
        }
        sb.append("平台没有独立的评价管理页，评论随商品详情展示。\n");
        sb.append("最近 ").append(comments.size()).append(" 条：\n");
        for (Map<String, Object> c : comments) {
            sb.append("- [").append(c.get("kindLabel"));
            if ("评价".equals(c.get("kindLabel"))) {
                sb.append(" ").append(c.get("rating")).append(" 星");
            }
            sb.append("] 商品「").append(c.get("goods")).append("」：“")
                    .append(str(c.get("content"))).append("”，共 ")
                    .append(c.get("replyCount")).append(" 条回复，")
                    .append(Boolean.TRUE.equals(c.get("merchantReplied")) ? "商家已回复" : "商家未回复")
                    .append('\n');
        }
        return sb.toString();
    }

    private List<Object> commentSuggestions() {
        List<Object> list = new ArrayList<>();
        list.add("查我的订单");
        list.add("查售后进度");
        list.add("怎么开店");
        return list;
    }

    // ==================== 工具结果 → 口语播报 ====================

    /**
     * 把 DAO 查出来的真实数据交给模型生成话术；模型不可用就回确定性模板。
     *
     * <p>「工具调用」在本仓的落法：模型负责**选**工具（意图），后端负责**执行**（DAO），
     * 再把结果回喂模型**写话术**。三步任何一步失败都不会让用户看到空白或 500。
     */
    private String composeOrFallback(String question, ChatIntent intent, String facts, String fallback) {
        if (replyComposer == null || !replyComposer.isEnabled()) {
            return fallback;
        }
        try {
            String composed = replyComposer.compose(question, intent.name(), facts);
            return isBlank(composed) ? fallback : composed;
        } catch (RuntimeException e) {
            log.warn("播报话术生成失败，回退模板：{}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return fallback;
        }
    }

    /** 订单工具的事实文本。 */
    private String orderFacts(boolean merchant, long total, List<Map<String, Object>> summaries,
                              String missingOrderId) {
        StringBuilder sb = new StringBuilder();
        sb.append("身份：").append(merchant ? "商家" : "客户").append('\n');
        sb.append("订单总数：").append(total).append('\n');
        if (!isBlank(missingOrderId)) {
            sb.append("用户提供的单号 ").append(missingOrderId).append(" 查无记录（不在其名下）。\n");
        }
        sb.append("最近 ").append(summaries.size()).append(" 笔：\n");
        for (Map<String, Object> row : summaries) {
            sb.append("- 单号 ").append(row.get("orderId"))
                    .append("｜商品 ").append(row.get("product"))
                    .append("｜¥").append(money(row.get("amount")))
                    .append("｜状态 ").append(row.get("stateLabel"))
                    .append("｜下单时间 ").append(row.get("createTime"))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 截断展示用：超过 {@code max} 字加省略号。 */
    private static String excerpt(String text, int max) {
        if (isBlank(text)) {
            return "（无正文）";
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    // ==================== 开店 ====================

    /**
     * 开店 / 入驻（N2）：第一句固定先报系统解析出的身份，再按六态分流。
     *
     * <p>不反问「您是商家还是用户」—— 身份来自会话 + 库，用户看到判断后可当场纠错；
     * 每次调用都重新 {@link IdentityResolver#resolve}，审核状态一变下一句就能反映。
     *
     * <p>2026-10-22 交互纠偏：动作不再是「直接跳到申请页」，而是默认给【开始引导】
     * （前端 BFS：用户中心 → 入驻入口 → 表单），只有用户明说「直接带我去…」才给
     * 【一键直达】。{@code direct} 由 {@link #isDirectNavigation} 的词面判据决定。
     */
    /** 包内可见：{@link ChatToolSet} 的 {@code open_store} 工具调用它。 */
    Map<String, Object> answerOpenStore(String userId, Integer role, boolean direct) {
        ResolvedIdentity id = identityResolver.resolve(userId, role);
        String who = identitySentence(id);
        if (id.boundary() == ResolvedIdentity.Boundary.ADMIN) {
            return reply(ChatIntent.OPEN_STORE,
                    who + "管理员不需要入驻，请在管理端处理商家审核。",
                    null, null, defaultSuggestions());
        }
        Map<String, Object> action = navAction(applyTargetPath(role), "商家入驻申请", direct);
        String how = direct
                ? "已为您准备好直达入口，点击即可前往。"
                : "好的，我来一步步教您。点击下方按钮开始。";
        if (id.boundary() == ResolvedIdentity.Boundary.DEGRADED_MERCHANT) {
            return reply(ChatIntent.OPEN_STORE,
                    who + "重新提交入驻申请，审核通过后即可恢复商家身份。" + how,
                    null, action, defaultSuggestions());
        }
        return switch (id.identity()) {
            case MERCHANT -> reply(ChatIntent.OPEN_STORE,
                    who + "如果要做品牌加盟，" + how,
                    null, action, defaultSuggestions());
            case MERCHANT_BRAND -> reply(ChatIntent.OPEN_STORE,
                    who + "如需新增品牌，可以到申请页提交品牌加盟材料。" + how,
                    null, action, defaultSuggestions());
            case USER_PENDING -> reply(ChatIntent.OPEN_STORE,
                    who + "审核通过后账号会自动升级为商家，先不用重复提交。",
                    null, null, progressSuggestions());
            case USER_REJECTED -> reply(ChatIntent.OPEN_STORE,
                    who + "可以修改资料后重新提交，审核通过后账号自动升级为商家。" + how,
                    null, action, defaultSuggestions());
            default -> reply(ChatIntent.OPEN_STORE,
                    who + "开店三步：① 填写店铺名称与营业执照号；② 提交入驻申请；③ 平台审核通过后，"
                            + "账号自动升级为商家。" + how,
                    null, action, defaultSuggestions());
        };
    }

    // ==================== 秒杀报名（N3 / Q5=A） ====================

    /**
     * 「我要报名秒杀」—— **只导航 + 身份分支**，不猜 GoodsId / 价格 / 库存，也不写秒杀表。
     *
     * <p>六个状态里：商家 / 品牌商家给「去秒杀报名」胶囊（前端 push
     * {@code /merchant/seckill}）；无申请复用开店漫游；审核中只答不导航；被拒给
     * 「重新申请」；未登录给「登录后去报名」，前端存 pendingTour，登录后按真实角色续接。
     */
    /** 包内可见：{@link ChatToolSet} 的 {@code seckill_signup} 工具调用它。 */
    Map<String, Object> answerAddToSeckill(String userId, Integer role, boolean needPublish) {
        ResolvedIdentity id = identityResolver.resolve(userId, role);
        String who = identitySentence(id);
        if (id.boundary() == ResolvedIdentity.Boundary.ADMIN) {
            return reply(ChatIntent.ADD_TO_SECKILL,
                    who + "管理员不参与秒杀报名，请在管理端配置活动。",
                    null, null, defaultSuggestions());
        }
        if (id.boundary() == ResolvedIdentity.Boundary.DEGRADED_MERCHANT) {
            return reply(ChatIntent.ADD_TO_SECKILL,
                    who + "重新入驻通过后可以报名秒杀。",
                    null, null, List.of(routeSuggestion("重新申请", "apply")));
        }
        return switch (id.identity()) {
            case MERCHANT, MERCHANT_BRAND -> needPublish
                    ? reply(ChatIntent.ADD_TO_SECKILL,
                            who + "商品还没上架。先在「上架商品」发布商品，再回秒杀报名页选它；"
                                    + "两个入口都在下面。",
                            null, null, List.of(
                                    routeSuggestion("去发布商品", "publish"),
                                    routeSuggestion("去秒杀报名", "seckill")))
                    : reply(ChatIntent.ADD_TO_SECKILL,
                            who + "带你去秒杀报名页，到了填商品和价格就行。",
                            null, null, List.of(routeSuggestion("去秒杀报名", "seckill")));
            case USER_NO_APPLICATION -> reply(ChatIntent.ADD_TO_SECKILL,
                    who + "需要先完成商家入驻，审核通过后才能报名秒杀。",
                    null, navAction(applyTargetPath(role), "商家入驻申请", false),
                    List.of("查我的订单"));
            case USER_PENDING -> reply(ChatIntent.ADD_TO_SECKILL,
                    who + "审核通过后就可以报名秒杀，现在先不用重复提交。",
                    null, null, progressSuggestions());
            case USER_REJECTED -> reply(ChatIntent.ADD_TO_SECKILL,
                    who + "修改资料后可以重新申请，通过后就能报名秒杀。",
                    null, null, List.of(routeSuggestion("重新申请", "apply")));
            default -> reply(ChatIntent.ADD_TO_SECKILL,
                    who + "登录后才能报名秒杀。登录后我会接着这一步。",
                    null, null, List.of(routeSuggestion("登录后去报名", "seckill")));
        };
    }

    // ==================== 页面导航（NavGraph） ====================

    /**
     * 大模型只给了 {@code target_path}；本方法把它翻成前端能点的动作。
     *
     * <p>两类目标仍复用已有的身份分支，不另写一套：
     * <ul>
     *   <li>入驻申请 ⇒ {@link #answerOpenStore}（六态话术 + 引导 / 直达）；</li>
     *   <li>秒杀报名 ⇒ {@link #answerAddToSeckill}（身份闸门 + 秒杀胶囊）。</li>
     * </ul>
     * 其余页面返回 {@code action.type = tour}（默认，前端 BFS 逐步高亮）或
     * {@code action.type = jump}（用户明说「直接去」，前端直接 push）。
     *
     * @param needPublish 模型抽的 {@code need_publish} 槽位；只在秒杀分流里有意义
     *                    （「还没上架」⇒ 两个胶囊），别的页面忽略
     * @param direct      用户是否明确要求直达（见 {@link #isDirectNavigation}）
     */
    /** 包内可见：{@link ChatToolSet} 的 {@code navigate} 工具调用它。 */
    Map<String, Object> answerNavigate(String targetPath, String userId, Integer role,
                                       boolean needPublish, boolean direct) {
        return answerRouted(direct ? ChatIntent.DIRECT_NAVIGATION : ChatIntent.GUIDE_NAVIGATION,
                targetPath, userId, role, needPublish, direct);
    }

    /**
     * 导航空动作的统一出口。
     *
     * <p>⚠️ 2026-10-22「幽灵前缀」修复：旧实现把 {@link RouteCatalog#userSummary} 拼在
     * 「好的，我来一步步教您…」前面。页面一旦选错（如「怎么看我的商品」被模型带到
     * 「我的收藏」），用户就会先看到一句「收藏的商品。」再接引导语 —— 像是客服记错了
     * 上一句。现在**不再拼任何页面描述**：话术只有一句，去哪里由 {@code action.targetPath}
     * 承载；页面标题只用于按钮 / 引导条，不参与回复拼接。
     *
     * @param intent 语义路由命中的业务意图（GOODS_MANAGE 等）或 GUIDE / DIRECT
     */
    Map<String, Object> answerRouted(ChatIntent intent, String targetPath, String userId,
                                     Integer role, boolean needPublish, boolean direct) {
        String path = RouteCatalog.resolve(targetPath, role);
        if (path == null) {
            return answerSmallTalk(intent,
                    "这个页面在当前身份下打不开。你可以问我订单、售后，或让我带你去个人中心。");
        }
        if (path.endsWith("/user/apply")) {
            return answerOpenStore(userId, role, direct);
        }
        if ("/merchant/seckill".equals(path)) {
            return answerAddToSeckill(userId, role, needPublish);
        }

        RouteCatalog.Route route = RouteCatalog.find(path);
        String title = route == null ? path : route.title();
        // 资产类问题要如实回答「钱在哪」；这句话是意图自带的话术，
        // 不是把页面摘要拼到引导语前面（幽灵前缀）。
        String text;
        if (intent == ChatIntent.ASSET_QUERY) {
            text = direct
                    ? "已为您准备好直达入口，点击即可前往会员中心查看钱包。"
                    : "钱包余额和收支流水都在会员中心，我一步步带您过去。";
        } else {
            text = direct
                    ? "已为您准备好直达入口，点击即可前往。"
                    : "好的，我来一步步教您。点击下方按钮开始。";
        }
        return reply(intent, text, null, navAction(path, title, direct), defaultSuggestions());
    }

    /**
     * 相似度低于 0.75、且确定性 / LLM 都没接住时的澄清问句（不猜意图）。
     *
     * <p>三条硬规则：
     * <ol>
     *   <li><b>按角色给胶囊</b>：买家只给「去搜商品 / 查我的订单 / 怎么去个人中心」，
     *       绝不出现「管理商品 / 工作台」；商家才给管理入口。</li>
     *   <li><b>有商品名词就转向搜索</b>：不硬抛一套通用选项，直接问「是想搜索相关商品吗」
     *       并给【去商城搜索】。</li>
     *   <li><b>连续两次 CLARIFY 换话术</b>：上一轮也是 CLARIFY 时不再重复同一组选项，
     *       改为「换个说法 / 联系人工客服」。</li>
     * </ol>
     */
    private Map<String, Object> answerClarify(Integer role, String userId, String message) {
        String keyword = ChatIntentRecognizer.extractShoppingKeyword(
                ChatIntentRecognizer.normalize(message));
        SessionMemoryService.Context context = context(userId);
        boolean repeated = context != null && context.intent() == ChatIntent.CLARIFY;

        if (repeated) {
            // 复读机修复：第二次澄清必须换话术，且不再出现任何角色选项。
            List<Object> chips = new ArrayList<>();
            chips.add(searchSuggestion(keyword));
            chips.add("联系人工客服");
            return reply(ChatIntent.CLARIFY,
                    "抱歉，我还是没太听懂。您可以直接输入商品名称去搜索，"
                            + "或者点下方按钮联系人工客服。",
                    null, isBlank(keyword) ? null : searchAction(keyword, role), chips);
        }

        if (!isBlank(keyword)) {
            List<Object> chips = new ArrayList<>();
            chips.add(searchSuggestion(keyword));
            addClarifyRoleChips(chips, role);
            return reply(ChatIntent.CLARIFY,
                    "没太听懂您的意思，您是想搜索「" + keyword + "」相关商品吗？",
                    null, searchAction(keyword, role), chips);
        }

        List<Object> chips = new ArrayList<>();
        chips.add(searchSuggestion(null));
        addClarifyRoleChips(chips, role);
        String text = isMerchant(role)
                ? "没太听懂您的意思。您可以问我：搜商品、查订单、管理商品，或者去工作台。"
                : "没太听懂您的意思。您可以问我：搜商品、查订单，或者去个人中心。";
        return reply(ChatIntent.CLARIFY, text, null, null, chips);
    }

    /** 澄清胶囊的角色差异：买家永远看不到「管理商品 / 工作台」。 */
    private static void addClarifyRoleChips(List<Object> chips, Integer role) {
        chips.add("查我的订单");
        if (isMerchant(role)) {
            chips.add("管理商品");
            chips.add("前往商家工作台");
        } else {
            chips.add("怎么去个人中心");
        }
    }

    // ==================== 身份自述（增量-A 规则 2） ====================

    /**
     * 用户说「我是商家」时**不盲信自述**：先报系统解析结果；若自述与解析不符，
     * 给「查入驻进度 / 去入驻」两个胶囊，让用户自己核对（而不是争论谁对）。
     */
    private Map<String, Object> answerIdentityClaim(String message, String userId, Integer role) {
        ResolvedIdentity id = identityResolver.resolve(userId, role);
        String who = identitySentence(id);
        boolean claimsMerchant = CLAIM_MERCHANT.matcher(ChatIntentRecognizer.normalize(message)).find();
        boolean actuallyMerchant = id.identity() == UserIdentity.MERCHANT
                || id.identity() == UserIdentity.MERCHANT_BRAND;
        if (claimsMerchant && !actuallyMerchant) {
            List<Object> chips = new ArrayList<>();
            chips.add(routeSuggestion("查入驻进度", "applyStatus"));
            chips.add(routeSuggestion("去入驻", "apply"));
            return reply(ChatIntent.IDENTITY_CLAIM,
                    who + "如果你确信账号状态不对，可以查一下入驻进度，或直接去入驻页重新提交。",
                    null, null, chips);
        }
        return reply(ChatIntent.IDENTITY_CLAIM,
                who + "需要我帮你查订单、售后，还是带你去开店？",
                null, null, defaultSuggestions());
    }

    /**
     * 六态话术的**第一句**（N2 表逐字口径），附带的店铺名 / 时间 / 原因 / 商家号 / 品牌
     * 都来自 {@link ResolvedIdentity}，本方法不再查库。
     *
     * <p>边界态（Q3=A）先于六态判断：ADMIN、DEGRADED_MERCHANT 不进 {@link UserIdentity}。
     */
    private String identitySentence(ResolvedIdentity id) {
        if (id.boundary() == ResolvedIdentity.Boundary.ADMIN) {
            return "您当前是管理员账号。";
        }
        if (id.boundary() == ResolvedIdentity.Boundary.DEGRADED_MERCHANT) {
            return "您当前是已降级/冻结商家。";
        }
        if (id.identity() == null) {
            return "您当前还未登录。";
        }
        return switch (id.identity()) {
            case ANONYMOUS -> "您当前还未登录。";
            case USER_NO_APPLICATION -> "系统显示您当前是普通客户，还没有提交过入驻申请。";
            case USER_PENDING -> "系统显示您当前是「入驻审核中」。" + pendingDetail(id);
            case USER_REJECTED -> "系统显示您当前是「入驻未通过」。"
                    + "原因：" + (isBlank(id.rejectReason()) ? "未填写" : id.rejectReason()) + "。";
            case MERCHANT -> "系统显示您当前是商家。"
                    + (isBlank(id.merchantId()) ? "" : "商家号：" + id.merchantId() + "。");
            case MERCHANT_BRAND -> "系统显示您当前是品牌商家。" + brandDetail(id);
        };
    }

    private static String pendingDetail(ResolvedIdentity id) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(id.shopName())) {
            sb.append("店铺「").append(id.shopName()).append("」");
        }
        if (!isBlank(id.submittedAt())) {
            sb.append(sb.length() == 0 ? "提交时间 " : "于 ").append(id.submittedAt());
        }
        if (sb.length() > 0) {
            sb.append("，");
        }
        return sb.append("正在等平台审核。").toString();
    }

    private static String brandDetail(ResolvedIdentity id) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(id.brandName())) {
            sb.append("品牌：").append(id.brandName()).append("，");
        }
        sb.append(isBlank(id.brandValidTo()) ? "长期有效。" : "有效期至 " + id.brandValidTo() + "。");
        return sb.toString();
    }

    // ==================== 通用 ====================

    /** FAQ 命中：标准答案 + 常规胶囊（增量「轻量知识库」）。 */
    private Map<String, Object> answerFaq(String text) {
        return reply(ChatIntent.FAQ, text, null, null, defaultSuggestions());
    }

    private Map<String, Object> answerSmallTalk(ChatIntent intent, String text) {
        return reply(intent, text, null, null, defaultSuggestions());
    }

    private Map<String, Object> notLoggedIn(ChatIntent intent) {
        List<Object> suggestions = new ArrayList<>();
        suggestions.add("怎么开店");
        return reply(intent,
                "您当前还未登录。登录后才能查询你的订单、售后和评论。你可以先问我「怎么开店」，或登录后再来。",
                null, null, suggestions);
    }

    // ==================== 短期记忆 / 小工具 ====================

    /**
     * 成功回复后写一行短期记忆（{@code chat:session:{userId}}，TTL 30 分钟）。
     *
     * <p>只记「有业务含义」的意图；FAQ / 问候 / 兜底不记，免得下一句「那退款呢」被
     * 上一句闲聊聊偏。Redis 故障由 {@link SessionMemoryService} 吞掉，不影响本次回复。
     */
    private Map<String, Object> remember(String userId, String message, ChatIntent intent,
                                         String orderId, Map<String, Object> resp) {
        if (memoryService != null
                && Boolean.TRUE.equals(resp.get("success"))
                && memoryWorthy(intent)) {
            memoryService.remember(userId, message, intent, orderId,
                    String.valueOf(resp.getOrDefault("reply", "")));
        }
        return resp;
    }

    private static boolean memoryWorthy(ChatIntent intent) {
        return intent == ChatIntent.ORDER_QUERY
                || intent == ChatIntent.REFUND_QUERY
                || intent == ChatIntent.OPEN_STORE
                || intent == ChatIntent.ADD_TO_SECKILL
                || intent == ChatIntent.IDENTITY_CLAIM
                // 连续澄清防复读：必须记住上一轮是 CLARIFY，否则第二轮永远看不到上下文。
                || intent == ChatIntent.CLARIFY;
    }

    /** 从回复体里取「这一轮实际讲的订单号」，供下一句「到哪了」复用。 */
    private static String orderIdOf(Map<String, Object> resp, String explicit) {
        if (!isBlank(explicit)) {
            return explicit;
        }
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = map.get("orderId");
        if (direct != null && !isBlank(String.valueOf(direct))) {
            return String.valueOf(direct);
        }
        Object orders = map.get("orders");
        if (orders instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            Object rowOrderId = first.get("orderId");
            if (rowOrderId != null && !isBlank(String.valueOf(rowOrderId))) {
                return String.valueOf(rowOrderId);
            }
        }
        return null;
    }

    /** 短追问判据：只对 ≤20 字的「那…呢 / 到哪了 / 进度」生效。 */
    private static boolean isFollowUp(String normalized) {
        return normalized.length() <= 20 && FOLLOW_UP.matcher(normalized).find();
    }

    /**
     * 用户是在问「我那一笔怎么样了」吗？
     *
     * <p>只用于一条窄判据：模型给了 ORDER_QUERY / REFUND_QUERY 时，若这句话在路由字典里
     * 有明确的页面说法，且**不是**在问自己记录的状态，就改判导航（见 {@code decide}）。
     * 「我的订单到哪了」必须留在这里，不能被带去订单页。
     */
    private static boolean asksAboutStatus(String normalized) {
        for (String marker : STATUS_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> STATUS_MARKERS = List.of(
            "到哪", "进度", "状态", "结果", "有没有", "怎么样", "了吗", "到账", "发货",
            "多少", "几个", "几笔");

    private static List<Object> defaultSuggestions() {
        List<Object> suggestions = new ArrayList<>();
        suggestions.add("查我的订单");
        suggestions.add("查售后进度");
        suggestions.add("怎么开店");
        return suggestions;
    }

    private static List<Object> progressSuggestions() {
        List<Object> suggestions = new ArrayList<>();
        suggestions.add(routeSuggestion("查入驻进度", "applyStatus"));
        suggestions.add("查我的订单");
        return suggestions;
    }

    /** 对象型 suggestion：前端据此渲染「跳转」胶囊，而不是把 label 当普通消息再发一遍。 */
    private static Map<String, Object> routeSuggestion(String label, String routeKey) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("routeKey", routeKey);
        return item;
    }

    /**
     * 导航动作（2026-10-22 双模式）。
     *
     * <ul>
     *   <li>{@code guide}：{@code type=tour} + 【开始引导】—— 前端从当前页 BFS 逐步高亮，
     *       漫游条里另有「跳过引导，直接前往」后门；</li>
     *   <li>{@code direct}：{@code type=jump} + 【一键直达】—— 前端忽略中间节点直接 push。</li>
     * </ul>
     */
    private static Map<String, Object> navAction(String path, String title, boolean direct) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", direct ? "jump" : "tour");
        action.put("targetPath", path);
        action.put("targetLabel", title);
        action.put("label", direct ? "一键直达" : "开始引导");
        return action;
    }

    /** 入驻申请页的落点（按角色分壳；未登录按客户侧，登录后由前端守卫纠正）。 */
    private static String applyTargetPath(Integer role) {
        return isMerchant(role) ? "/merchant/user/apply" : "/customer/user/apply";
    }

    private Map<String, Object> reply(ChatIntent intent, String text, Object data,
                                      Object action, List<Object> suggestions) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("intent", intent.name());
        resp.put("reply", text);
        if (data != null) {
            resp.put("data", data);
        }
        if (action != null) {
            resp.put("action", action);
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            resp.put("suggestions", suggestions);
        }
        return resp;
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("msg", msg);
        return resp;
    }

    private static boolean isMerchant(Integer role) {
        return role != null && role == 2;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> result) {
        if (result == null) {
            return List.of();
        }
        Object list = result.get("list");
        if (list instanceof List<?> raw) {
            return (List<Map<String, Object>>) raw;
        }
        return List.of();
    }

    /** 「名称:编码；名称:编码」→ 只取第一个商品名；过长截断。 */
    static String displayProduct(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return "商品";
        }
        int sep = s.indexOf('；');
        if (sep < 0) {
            sep = s.indexOf(';');
        }
        if (sep > 0) {
            s = s.substring(0, sep);
        }
        int colon = s.indexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    private static String money(Object raw) {
        return String.format(Locale.ROOT, "%.2f", doubleOf(raw));
    }

    /** 时间列有两种来源：字符串列（CreateTime）与 datetime 列（RefundApplyAt）。 */
    private static String time(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw);
        return s.indexOf('T') > 0 ? s.replace('T', ' ') : s;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static int intOf(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleOf(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
