package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能客服的**规则识别器** —— 纯静态、无 Spring 依赖、无 I/O。
 *
 * <p>为什么不用大模型：本功能只需要把「查订单 / 查售后 / 怎么开店」三类短句分流到既有查询，
 * 规则匹配的确定性与延迟都优于一次远端调用；引入 LLM 还会带来密钥、超时、审计与幻觉问题。
 *
 * <h2>订单号提取的三条硬规则（2026-10-21 评审 P0-1）</h2>
 * <ol>
 *   <li><b>显式前缀优先</b>：{@code 单号 / 订单号} 后面跟的 token 直接采用，哪怕比隐式的短。</li>
 *   <li><b>隐式 token 必须同时含字母和数字，且长度 ≥ 10</b> —— 纯数字日期（{@code 20261020}）
 *       不会被误当成单号。</li>
 *   <li><b>多个隐式 token 取最长</b>；同长取先出现的。</li>
 * </ol>
 *
 * <p>识别到单号但文本里没有任何订单关键词时，仍归为 {@link ChatIntent#ORDER_QUERY} ——
 * 用户单独贴一串单号，意图就是查单。
 */
public final class ChatIntentRecognizer {

    /** 识别结果。{@code explicitOrderId} 只用于测试/日志区分来源，业务侧只关心 {@code orderId}。 */
    public record Match(ChatIntent intent, String orderId, boolean explicitOrderId) {
        public boolean hasOrderId() {
            return orderId != null && !orderId.isBlank();
        }
    }

    /** 显式前缀：订单号 / 单号 / orderid（规范化后已小写）。 */
    private static final Pattern EXPLICIT_ORDER_NO =
            Pattern.compile("(?:订单号|单号|orderid)[:：]?([a-z0-9]{4,40})");

    /** 隐式 token：至少 10 位字母数字，且必须同时含字母和数字（见类注释规则 2）。 */
    private static final Pattern IMPLICIT_TOKEN = Pattern.compile("[a-z0-9]{10,40}");

    private static final Pattern REFUND = Pattern.compile(
            "售后|退款|退货|退钱|退单|退费|退款进度|退款到哪|什么时候退|钱.{0,6}(退|到账)");

    private static final Pattern ORDER = Pattern.compile(
            "订单|物流|快递|发货|收货|到货|我的单|单号|买的东西|包裹");

    /**
     * 评论 / 评价（2026-10-22 业务工具挂载）。
     *
     * <p>排在订单之后：「我的订单评论」先按订单查更贴近用户意图；单独说「看评论 / 有没有人回复我」
     * 才落到 {@link ChatIntent#COMMENT_QUERY}。
     *
     * <p>⚠️ 只认「内容」本身，不认「怎么去评价页」—— 平台没有独立评价页，这类问法由
     * {@code ChatAgentService} 的字典纠偏处理（本类只管分流，不做页面映射）。
     */
    private static final Pattern COMMENT = Pattern.compile(
            "评论|评价|有人回复|回复我|留言");

    private static final Pattern OPEN_STORE = Pattern.compile(
            "开店|入驻|开个店|开一家店|开店铺|成为商家|卖东西|卖货|上架|加盟|营业执照");

    /**
     * 报名秒杀 / 参加平台活动（N3 / Q5=A）。
     *
     * <p>⚠️ 必须排在 {@link #OPEN_STORE} **前面**：{@code 上架.*秒杀} 里含「上架」，
     * 而开店的模式也认「上架」；先问「怎么把商品上架到秒杀」的用户要的是报名，不是入驻。
     */
    private static final Pattern SECKILL = Pattern.compile(
            "秒杀|限时抢购|参加活动|报名活动|上架.*秒杀|加入.*秒杀");

    /**
     * 用户自述「我是商家 / 我是品牌商家」（增量-A 规则 2）。
     *
     * <p>只识别自述，**不据此判定身份** —— 是否真是商家由 {@code IdentityResolver} 查库。
     * 放在开店之后：「我是商家，怎么开店」仍走开店分支（那边同样先报身份）。
     */
    private static final Pattern SELF_CLAIM = Pattern.compile(
            "我是(商家|卖家|品牌商家|品牌|商户|普通用户|普通客户)");

    /** 纯问候才命中 —— 「你好，怎么开店」必须继续往下走。 */
    private static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|嗨|hi|hello|在吗|在不在|早上好|晚上好|哈喽)[!！。.~～]*$");

    private static final Pattern HELP = Pattern.compile(
            "能做什么|帮助|怎么用|功能|你会什么|能干啥");

    private ChatIntentRecognizer() {
    }

    /**
     * 识别一句话的意图。
     *
     * @param raw 用户原文；可为 {@code null}
     */
    public static Match recognize(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) {
            return new Match(ChatIntent.UNKNOWN, null, false);
        }

        OrderNo found = extractOrderNo(text);

        ChatIntent intent;
        if (REFUND.matcher(text).find()) {
            intent = ChatIntent.REFUND_QUERY;
        } else if (ORDER.matcher(text).find()) {
            intent = ChatIntent.ORDER_QUERY;
        } else if (COMMENT.matcher(text).find()) {
            intent = ChatIntent.COMMENT_QUERY;
        } else if (SECKILL.matcher(text).find()) {
            intent = ChatIntent.ADD_TO_SECKILL;
        } else if (OPEN_STORE.matcher(text).find()) {
            intent = ChatIntent.OPEN_STORE;
        } else if (SELF_CLAIM.matcher(text).find()) {
            intent = ChatIntent.IDENTITY_CLAIM;
        } else if (GREETING.matcher(text).matches()) {
            intent = ChatIntent.GREETING;
        } else if (HELP.matcher(text).find()) {
            intent = ChatIntent.HELP;
        } else if (found != null) {
            // 用户只贴了一串单号 —— 意图仍然是查单。
            intent = ChatIntent.ORDER_QUERY;
        } else {
            intent = ChatIntent.UNKNOWN;
        }

        return new Match(intent, found == null ? null : found.value, found != null && found.explicit);
    }

    /** 规范化：去空白（含全角空格）、小写、全角冒号/问号折半角。 */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\u3000]+", "")
                .replace('：', ':')
                .replace('？', '?');
    }

    private record OrderNo(String value, boolean explicit) {
    }

    /**
     * 显式优先；否则在全部隐式 token 里取最长的（同长取先出现）。
     *
     * <p>⚠️ 显式 token 只要求「字母数字」，不要求同时含字母和数字 —— 用户既然写了「单号」，
     * 就按他给的查，查不到由 Service 兜底回「你最近的订单是…」。
     */
    static OrderNo extractOrderNo(String normalized) {
        Matcher explicit = EXPLICIT_ORDER_NO.matcher(normalized);
        if (explicit.find()) {
            return new OrderNo(explicit.group(1), true);
        }

        List<String> candidates = new ArrayList<>();
        Matcher tokens = IMPLICIT_TOKEN.matcher(normalized);
        while (tokens.find()) {
            String token = tokens.group();
            if (hasLetter(token) && hasDigit(token)) {
                candidates.add(token);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        String longest = candidates.get(0);
        for (String c : candidates) {
            if (c.length() > longest.length()) {
                longest = c;
            }
        }
        return new OrderNo(longest, false);
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
