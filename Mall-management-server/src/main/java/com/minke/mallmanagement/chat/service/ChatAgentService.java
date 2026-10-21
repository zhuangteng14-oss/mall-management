package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;
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

    /** 售后状态 → 中文。逐字对齐 {@code order.RefundStatus} 的列注释与前端 {@code REFUND_TEXT}。 */
    private static final Map<Integer, String> REFUND_LABEL = Map.of(
            0, "已经结单",
            1, "待商家审核",
            2, "商家已拒绝",
            3, "平台处理中",
            4, "平台已拒绝",
            5, "已退款");

    private final OrderDao orderDao;
    private final OrderRefundService refundService;
    private final OrderStateMachine orderStateMachine;

    public ChatAgentService(OrderDao orderDao,
                            OrderRefundService refundService,
                            OrderStateMachine orderStateMachine) {
        this.orderDao = orderDao;
        this.refundService = refundService;
        this.orderStateMachine = orderStateMachine;
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

        ChatIntentRecognizer.Match match = ChatIntentRecognizer.recognize(message);
        return switch (match.intent()) {
            case ORDER_QUERY -> answerOrders(userId, role, match.orderId());
            case REFUND_QUERY -> answerRefund(userId, role);
            case OPEN_STORE -> answerOpenStore(role);
            case GREETING -> answerSmallTalk(match.intent(),
                    "你好呀～我可以帮你查订单、查售后进度，也能一键带你去开店。");
            case HELP -> answerSmallTalk(match.intent(),
                    "我会这些：① 查最新订单状态；② 查退款进度；③ 带你去开店（含两步高亮引导）。");
            default -> answerSmallTalk(ChatIntent.UNKNOWN,
                    "我暂时听不懂这句话。你可以问我：查订单 / 查售后 / 怎么开店。");
        };
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
        String statusLabel = REFUND_LABEL.getOrDefault(status, "未知状态");
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

    private Map<String, Object> answerOpenStore(Integer role) {
        String text;
        if (isMerchant(role)) {
            text = "你已经是商家了。如果要做品牌加盟，点下面的按钮，我带你到申请页，并高亮"
                    + "「店铺名称」和「提交申请」两处。";
        } else {
            text = "开店三步：① 填写店铺名称与营业执照号；② 提交入驻申请；③ 平台审核通过后，"
                    + "账号自动升级为商家。点下面的按钮，我直接带你到申请页，并高亮要填的两处。";
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "tour");
        action.put("tourId", "open-store");
        action.put("routeKey", "apply");
        action.put("label", "一键带我去开店");

        List<Object> suggestions = new ArrayList<>();
        suggestions.add("查我的订单");
        suggestions.add("查售后进度");
        return reply(ChatIntent.OPEN_STORE, text, null, action, suggestions);
    }

    // ==================== 通用 ====================

    private Map<String, Object> answerSmallTalk(ChatIntent intent, String text) {
        List<Object> suggestions = new ArrayList<>();
        suggestions.add("查我的订单");
        suggestions.add("查售后进度");
        suggestions.add("怎么开店");
        return reply(intent, text, null, null, suggestions);
    }

    private Map<String, Object> notLoggedIn(ChatIntent intent) {
        List<Object> suggestions = new ArrayList<>();
        suggestions.add("怎么开店");
        return reply(intent,
                "登录后才能查询你的订单和售后。你可以先问我「怎么开店」，或登录后再来。",
                null, null, suggestions);
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
