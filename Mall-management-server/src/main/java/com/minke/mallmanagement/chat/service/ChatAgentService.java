package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
import com.minke.mallmanagement.chat.model.ResolvedIdentity;
import com.minke.mallmanagement.chat.model.UserIdentity;
import com.minke.mallmanagement.order.RefundStatusText;
import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.model.OrderStateMachine;
import com.minke.mallmanagement.order.refund.service.OrderRefundService;
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

    private final OrderDao orderDao;
    private final OrderRefundService refundService;
    private final OrderStateMachine orderStateMachine;
    private final IdentityResolver identityResolver;
    private final SessionMemoryService memoryService;

    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine,
                            IdentityResolver identityResolver,
                            SessionMemoryService memoryService) {
        this.orderDao = orderDao;
        this.refundService = refundService;
        this.orderStateMachine = orderStateMachine;
        this.identityResolver = identityResolver;
        this.memoryService = memoryService;
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
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return fail("说点什么吧～");
        }
        String message = rawMessage.trim();
        if (message.length() > MAX_MESSAGE_LEN) {
            return fail("消息太长了，请控制在 " + MAX_MESSAGE_LEN + " 字以内");
        }

        String normalized = ChatIntentRecognizer.normalize(message);
        FaqKnowledgeBase.Answer faq = FaqKnowledgeBase.match(normalized);
        // ⚠️ 政策类 FAQ 排在任务意图**前面**：用户给的例子「拆封了能退货吗」里含「退货」，
        //    会先命中 REFUND 正则 ⇒ 只在 UNKNOWN 分支查 FAQ 的话，这条示例永远答不到。
        //    其余 FAQ 仍按增量要求挂在 UNKNOWN 分支。
        if (faq != null && faq.policy()) {
            return remember(userId, message, ChatIntent.FAQ, null, answerFaq(faq.text()));
        }

        ChatIntentRecognizer.Match match = ChatIntentRecognizer.recognize(message);
        ChatIntent intent = match.intent();
        String orderId = match.orderId();

        if (intent == ChatIntent.UNKNOWN) {
            if (faq != null) {
                return remember(userId, message, ChatIntent.FAQ, null, answerFaq(faq.text()));
            }
            // 短期记忆：只有「那…呢 / 到哪了 / 进度」这类短追问才接上一轮意图，
            // 避免把「今天天气不错」也当成订单问题。
            SessionMemoryService.Context context = memoryService.lastContext(userId);
            if (context != null && isFollowUp(normalized)) {
                intent = context.intent();
                if (intent == ChatIntent.ORDER_QUERY && isBlank(orderId)) {
                    orderId = context.orderId();
                }
            }
        }

        Map<String, Object> resp = switch (intent) {
            case ORDER_QUERY -> answerOrders(userId, role, orderId);
            case REFUND_QUERY -> answerRefund(userId, role);
            case OPEN_STORE -> answerOpenStore(userId, role);
            case ADD_TO_SECKILL -> answerAddToSeckill(userId, role);
            case IDENTITY_CLAIM -> answerIdentityClaim(message, userId, role);
            case GREETING -> answerSmallTalk(match.intent(),
                    "你好呀～我可以帮你查订单、查售后进度，也能一键带你去开店。");
            case HELP -> answerSmallTalk(match.intent(),
                    "我会这些：① 查最新订单状态；② 查退款进度；③ 带你去开店（含两步高亮引导）。");
            case FAQ -> answerFaq(faq == null ? "我暂时听不懂这句话。" : faq.text());
            default -> answerSmallTalk(ChatIntent.UNKNOWN,
                    "我暂时听不懂这句话。你可以问我：查订单 / 查售后 / 怎么开店。");
        };
        return remember(userId, message, intent, orderIdOf(resp, orderId), resp);
    }

    // ==================== 订单 ====================

    private Map<String, Object> answerOrders(String userId, Integer role, String wantedOrderId) {
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
            return reply(ChatIntent.ORDER_QUERY, text, dataOrders(total, List.of()), null,
                    orderSuggestions());
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
        return reply(ChatIntent.ORDER_QUERY, text.toString(), dataOrders(shownTotal, summaries), null,
                orderSuggestions());
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

    private Map<String, Object> answerRefund(String userId, Integer role) {
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
            return reply(ChatIntent.REFUND_QUERY, text, null, null, refundSuggestions());
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

        return reply(ChatIntent.REFUND_QUERY, text.toString(), data, null, refundSuggestions());
    }

    private List<Object> refundSuggestions() {
        List<Object> list = new ArrayList<>();
        list.add("查我的订单");
        list.add("怎么开店");
        return list;
    }

    // ==================== 开店 ====================

    /**
     * 开店 / 入驻（N2）：第一句固定先报系统解析出的身份，再按六态分流。
     *
     * <p>不反问「您是商家还是用户」—— 身份来自会话 + 库，用户看到判断后可当场纠错；
     * 每次调用都重新 {@link IdentityResolver#resolve}，审核状态一变下一句就能反映。
     */
    private Map<String, Object> answerOpenStore(String userId, Integer role) {
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
    private Map<String, Object> answerAddToSeckill(String userId, Integer role) {
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
            case MERCHANT, MERCHANT_BRAND -> reply(ChatIntent.ADD_TO_SECKILL,
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
                "您当前还未登录。登录后才能查询你的订单和售后。你可以先问我「怎么开店」，或登录后再来。",
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
