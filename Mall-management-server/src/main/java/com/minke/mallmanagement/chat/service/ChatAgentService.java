package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.model.ResolvedIdentity;
import com.minke.mallmanagement.chat.model.UserIdentity;
import com.minke.mallmanagement.chat.tool.ToolContext;
import com.minke.mallmanagement.chat.tool.ToolExecutor;
import com.minke.mallmanagement.chat.tool.ToolRegistry;
import com.minke.mallmanagement.comment.dao.CommentDao;
import com.minke.mallmanagement.order.RefundStatusText;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** 兼容入口（阶段一之前的单测 / 没装评论工具时）：新依赖都置空。 */
    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine,
                            IdentityResolver identityResolver,
                            SessionMemoryService memoryService,
                            LlmIntentClassifier llmClassifier) {
        this(orderDao, refundService, orderStateMachine, identityResolver, memoryService,
                llmClassifier, null, null, null, null);
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
                llmClassifier, commentDao, replyComposer, null, null);
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
                            ToolExecutor toolExecutor) {
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
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return fail("说点什么吧～");
        }
        String message = rawMessage.trim();
        if (message.length() > MAX_MESSAGE_LEN) {
            return fail("消息太长了，请控制在 " + MAX_MESSAGE_LEN + " 字以内");
        }

        String normalized = ChatIntentRecognizer.normalize(message);
        FaqKnowledgeBase.Answer faq = FaqKnowledgeBase.match(normalized);

        // 阶段一：LLM 优先；任何异常（含 3s 超时）都由 decide() 打 WARN 并降级规则引擎。
        Decision decision = decide(message, normalized, faq, userId, role, currentPath);
        ChatIntent intent = decision.intent();
        String orderId = decision.orderId();

        // 插件化路径（2026-10-22）：先问注册表。模型给了 tool 就按名字调，
        // 没给就按意图找当前角色的内置工具；注册表没有对应工具（问候 / FAQ / 管理员）
        // 或执行失败 ⇒ 回退下面这张 switch（既有行为逐分支保留）。
        Map<String, Object> viaTool = executeTool(decision, message, userId, role);
        Map<String, Object> resp = viaTool != null ? viaTool : switch (intent) {
            case ORDER_QUERY -> answerOrders(message, userId, role, orderId);
            case REFUND_QUERY -> answerRefund(message, userId, role);
            case COMMENT_QUERY -> answerComments(message, userId, role);
            case OPEN_STORE -> answerOpenStore(userId, role);
            case ADD_TO_SECKILL -> answerAddToSeckill(userId, role, decision.needPublish());
            case NAVIGATE -> answerNavigate(decision.targetPath(), userId, role, decision.needPublish());
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
        return remember(userId, message, intent, orderIdOf(resp, orderId), resp);
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
                                            String userId, Integer role) {
        if (toolExecutor == null || toolRegistry == null) {
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
        ToolContext context = new ToolContext(userId, role, message);
        try {
            if (!isBlank(decision.tool())) {
                // 双保险：分类器已按角色校验过一次，这里再拒一次（工具名也可能来自规则层）。
                if (!toolRegistry.allows(decision.tool(), role)) {
                    log.warn("工具 {} 不在角色 {} 的可用列表内，回退内置分支", decision.tool(), role);
                    return null;
                }
                return nonEmpty(toolExecutor.invoke(decision.tool(), context, args));
            }
            return nonEmpty(toolExecutor.invokeForIntent(decision.intent(), context, args));
        } catch (ToolRegistry.ToolException e) {
            log.warn("工具执行失败，回退内置分支：{}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
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
     * <p>LLM 返回 UNKNOWN 时再走一次规则引擎 —— Prompt 没列 GREETING / HELP /
     * IDENTITY_CLAIM，这三类仍由规则识别；LLM 故障则整句交给规则引擎。
     */
    private Decision decide(String message, String normalized,
                            FaqKnowledgeBase.Answer faq, String userId,
                            Integer role, String currentPath) {
        if (llmClassifier != null && llmClassifier.isEnabled()) {
            try {
                LlmIntentClassifier.Result result = llmClassifier.classify(message, role, currentPath);
                ChatIntent intent = result.intent();
                String orderId = result.orderId();
                boolean needPublish = result.needPublish();
                String tool = result.tool();
                Map<String, Object> toolArgs = result.args();

                // 政策 FAQ 是本地确定性知识：即使模型把它判成 REFUND，也不能答成「查退款进度」。
                if (faq != null && faq.policy()) {
                    return new Decision(ChatIntent.FAQ, null, false, null);
                }
                // ⚠️ 只有「没选工具」的 UNKNOWN 才交给规则引擎。插件工具（intent=UNKNOWN）
                // 是模型明确选了要调的方法 —— 送去规则引擎反而会把这次调用丢掉。
                if (intent == ChatIntent.UNKNOWN && isBlank(tool)) {
                    return byRules(message, normalized, faq, userId, role);
                }
                // 「我要看评论」：旧 Prompt 教模型导航到商品管理 / 我的订单，但那些页面都看不到评论。
                // 问的是评论内容 ⇒ 走真实查询（工具挂载）。这条判据先于下面的导航纠偏，
                // 也先于工具：模型可能选成 navigate，但目标页里根本没有评论。
                if (looksLikeCommentQuery(normalized)
                        && (intent == ChatIntent.ORDER_QUERY || intent == ChatIntent.REFUND_QUERY
                            || intent == ChatIntent.NAVIGATE || intent == ChatIntent.UNKNOWN)) {
                    return new Decision(ChatIntent.COMMENT_QUERY, null, false, null);
                }
                // 小模型偶尔把「我要看评论」这类「看某个页面」判成 ORDER_QUERY。
                // 页面名以 RouteCatalog 为准：字典里有唯一且够长的说法、而用户又不是在问
                // 「我那一笔到哪了」，就按导航走。判定见 asksAboutStatus。
                if ((intent == ChatIntent.ORDER_QUERY || intent == ChatIntent.REFUND_QUERY)
                        && !asksAboutStatus(normalized)) {
                    String guessed = RouteCatalog.guess(message, role);
                    if (guessed != null && !namesPageItself(normalized, guessed)) {
                        return new Decision(ChatIntent.NAVIGATE, null, false, guessed);
                    }
                }
                // 「开店」类问题模型偶尔会认成「怎么上架商品」这类别的页面（「上架」两个字同时
                // 出现在两条意图里）。字典指向的若不是入驻申请页，就以字典为准 —— 指向申请页时
                // 保持 OPEN_STORE 不动（六态身份话术比「带你去申请页」更有用）。
                if (intent == ChatIntent.OPEN_STORE) {
                    String guessed = RouteCatalog.guess(message, role);
                    if (guessed != null && !guessed.endsWith("/user/apply")) {
                        return new Decision(ChatIntent.NAVIGATE, null, false, guessed);
                    }
                }
                // 售后类句子：模型判对了「要导航」但落点选错（实测「怎么处理退款」落到会员中心）。
                // 只在字典有唯一命中、且用户没亲口说出模型选的那个页面名时纠正。
                if (intent == ChatIntent.NAVIGATE && AFTER_SALES.matcher(normalized).find()) {
                    String guessed = RouteCatalog.guess(message, role);
                    if (guessed != null && !guessed.equals(result.targetPath())
                            && !namesPageItself(normalized, result.targetPath())) {
                        return new Decision(ChatIntent.NAVIGATE, null, false, guessed);
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
                log.warn("LLM 意图识别失败，降级规则引擎：{}: {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return byRules(message, normalized, faq, userId, role);
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

        // 「怎么处理退款」这类「去哪办」的句子，规则引擎只看得见「退款」两个字。
        // 与 decide() 里的字典纠偏同一条判据（在问自己那笔状态时不动），
        // 这样模型超时时落点也不变，探针不用赌那 3 秒。
        if (intent == ChatIntent.REFUND_QUERY && !asksAboutStatus(normalized)) {
            String guessed = RouteCatalog.guess(message, role);
            if (guessed != null && !namesPageItself(normalized, guessed)) {
                return new Decision(ChatIntent.NAVIGATE, null, false, guessed);
            }
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
                    return new Decision(ChatIntent.NAVIGATE, null, false, guessed);
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
     */
    /** 包内可见：{@link ChatToolSet} 的 {@code open_store} 工具调用它。 */
    Map<String, Object> answerOpenStore(String userId, Integer role) {
        ResolvedIdentity id = identityResolver.resolve(userId, role);
        String who = identitySentence(id);
        if (id.boundary() == ResolvedIdentity.Boundary.ADMIN) {
            return reply(ChatIntent.OPEN_STORE,
                    who + "管理员不需要入驻，请在管理端处理商家审核。",
                    null, null, defaultSuggestions());
        }
        if (id.boundary() == ResolvedIdentity.Boundary.DEGRADED_MERCHANT) {
            return reply(ChatIntent.OPEN_STORE,
                    who + "重新提交入驻申请，审核通过后即可恢复商家身份。",
                    null, applyAction("重新申请"), defaultSuggestions());
        }
        return switch (id.identity()) {
            case MERCHANT -> reply(ChatIntent.OPEN_STORE,
                    who + "如果要做品牌加盟，点下面的按钮，我带你到申请页，并高亮"
                            + "「店铺名称」和「提交申请」两处。",
                    null, applyAction("一键带我去开店"), defaultSuggestions());
            case MERCHANT_BRAND -> reply(ChatIntent.OPEN_STORE,
                    who + "如需新增品牌，可以到申请页提交品牌加盟材料。",
                    null, applyAction("去申请品牌加盟"), defaultSuggestions());
            case USER_PENDING -> reply(ChatIntent.OPEN_STORE,
                    who + "审核通过后账号会自动升级为商家，先不用重复提交。",
                    null, null, progressSuggestions());
            case USER_REJECTED -> reply(ChatIntent.OPEN_STORE,
                    who + "可以修改资料后重新提交，审核通过后账号自动升级为商家。",
                    null, applyAction("重新申请"), defaultSuggestions());
            default -> reply(ChatIntent.OPEN_STORE,
                    who + "开店三步：① 填写店铺名称与营业执照号；② 提交入驻申请；③ 平台审核通过后，"
                            + "账号自动升级为商家。点下面的按钮，我直接带你到申请页，并高亮要填的两处。",
                    null, applyAction("一键带我去开店"), defaultSuggestions());
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
                    null, applyAction("先去入驻"), List.of("查我的订单"));
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
     *   <li>入驻申请 ⇒ {@link #answerOpenStore}（六态话术 + 开店漫游）；</li>
     *   <li>秒杀报名 ⇒ {@link #answerAddToSeckill}（身份闸门 + 秒杀胶囊）。</li>
     * </ul>
     * 其余页面返回 {@code action.type = navigate}，前端用 NavGraph BFS 从当前页算路。
     *
     * @param needPublish 模型抽的 {@code need_publish} 槽位；只在秒杀分流里有意义
     *                    （「还没上架」⇒ 两个胶囊），别的页面忽略
     */
    /** 包内可见：{@link ChatToolSet} 的 {@code navigate} 工具调用它。 */
    Map<String, Object> answerNavigate(String targetPath, String userId, Integer role,
                                       boolean needPublish) {
        String path = RouteCatalog.resolve(targetPath, role);
        if (path == null) {
            return answerSmallTalk(ChatIntent.NAVIGATE,
                    "这个页面在当前身份下打不开。你可以问我订单、售后，或让我带你去个人中心。");
        }
        if (path.endsWith("/user/apply")) {
            return answerOpenStore(userId, role);
        }
        if ("/merchant/seckill".equals(path)) {
            return answerAddToSeckill(userId, role, needPublish);
        }

        RouteCatalog.Route route = RouteCatalog.find(path);
        String title = route == null ? path : route.title();
        // 字典里的 description 是写给模型的（含「用户说…时选它」），回给用户只取前一句。
        String text = (route == null ? "" : RouteCatalog.userSummary(route))
                + "点下面的按钮，我从当前页面一步步带你过去。";
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "navigate");
        action.put("targetPath", path);
        action.put("targetLabel", title);
        action.put("label", "带我去「" + title + "」");
        return reply(ChatIntent.NAVIGATE, text, null, action, defaultSuggestions());
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
                || intent == ChatIntent.IDENTITY_CLAIM;
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

    private static Map<String, Object> applyAction(String label) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "tour");
        action.put("tourId", "open-store");
        action.put("routeKey", "apply");
        action.put("label", label);
        return action;
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
