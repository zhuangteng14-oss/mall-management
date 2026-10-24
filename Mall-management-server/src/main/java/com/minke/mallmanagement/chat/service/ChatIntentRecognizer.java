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

    /**
     * 商品查询（2026-10-22 修复）：问「某个商品」的价格 / 详情 / 参数 / 库存。
     *
     * <p>⚠️ 只认「查数据」，不认「去办事」：{@code GOODS_ACTION} 里的上架 / 改价 / 管理
     * 等动作一律排除（那些是 NAVIGATE）。订单 / 售后 / 评论关键词也先于本规则命中
     * （见 {@link #recognize} 的顺序）。
     */
    private static final Pattern GOODS = Pattern.compile(
            "商品|宝贝|货品|产品");

    private static final Pattern GOODS_QUERY_MARK = Pattern.compile(
            "详情|详细|数据|参数|规格|介绍|信息|价格|多少钱|库存|有货|缺货|怎么样|好不好|查|看|找");

    /** 「去办事」类动作：出现即不算商品查询（与 NAVIGATE / OPEN_STORE 的边界）。 */
    private static final Pattern GOODS_ACTION = Pattern.compile(
            "上架|下架|发布|管理|编辑|修改|改价|删除|新增|添加|入驻|开店|秒杀|报名");

    /**
     * 逛商城（浏览）类说法：{@code 去首页 / 逛逛 / 看看商品} 是 RouteCatalog 里
     * 「商城主页」的用户说法，不能被商品查询抢走（没有具体商品时才排除）。
     */
    private static final Pattern GOODS_BROWSE = Pattern.compile(
            "逛|浏览|随便看看|看看商品|商品列表|首页|商城");

    /**
     * 商品名 / 编号的候选 token（规范化后的小写文本）：
     * <ul>
     *   <li>带冒号的复合编号：{@code 222:ij-555}（冒号、连字符原样保留）；</li>
     *   <li>同时含字母和数字的型号：{@code oppo12}、{@code ij-555}、{@code gd-101-9552}。</li>
     * </ul>
     * 纯数字不当 token（可能是价格 / 数量 / 订单号）。
     */
    private static final Pattern GOODS_TOKEN = Pattern.compile(
            "[a-z0-9]+(?::[a-z0-9._-]+)+|[a-z0-9]*[a-z][a-z0-9._-]*[0-9][a-z0-9._-]*");

    /** 显式商品编号前缀（规范化后）。 */
    private static final Pattern GOODS_ID_PREFIX = Pattern.compile(
            "(?:商品号|商品id|商品编号|编号|货号)[:：]?([0-9]{1,12})");

    /**
     * 购物 / 买家行为标记（2026-10-24 跨域修复）。
     *
     * <p>商家也是消费者：{@code 护膝在哪买} 这类句子在 Role=2 下同样要能给出去商城搜索的
     * 引导，不能因为当前身份是商家就判成「管理商品」。本标记只负责把句子拉进
     * {@link ChatIntent#GOODS_QUERY}；是否给「搜索」动作由 {@link #wantsSearch} 决定。
     */
    private static final Pattern SHOPPING_MARK = Pattern.compile(
            "在哪(?:里)?(?:买|购买|购)|哪里(?:买|购买)|怎么(?:买|购买)|如何(?:买|购买)|"
                    + "想(?:要)?买|要买|买个|买一个|买一件|买一台|买一双|买一只|购买|入手|"
                    + "搜索|搜一下|搜|找一?个|找一下|帮我找|有没有(?:卖|货)|有卖|多少钱|什么价");

    /**
     * 明确的「找 / 买 / 搜」动作 —— 命中即给「去商城搜索」动作（而不只是商品卡）。
     * {@code 多少钱 / 价格} 不在此列：查价能命中库内商品时，商品卡更直接。
     */
    private static final Pattern SEARCH_ACTION = Pattern.compile(
            "在哪(?:里)?(?:买|购买|购)|哪里(?:买|购买)|怎么(?:买|购买)|如何(?:买|购买)|"
                    + "购买|想(?:要)?买|要买|买个|买一个|买一件|买一台|买一双|买一只|买|入手|"
                    + "搜索|搜一下|搜|找一?个|找一下|帮我找");

    /** 动作触发词之后的花括号：优先长词，避免「买」抢在「购买」前面只吃到半截。 */
    private static final Pattern SHOPPING_AFTER = Pattern.compile(
            "(?:帮我找|搜索|搜一下|搜|查找|查一下|找一下|找一个|找个|想要买|想买|要买|"
                    + "买一个|买一件|买一台|买一双|买一只|买个|购买|买|入手)"
                    + "([\\u4e00-\\u9fa5a-z0-9:_-]{2,24})");

    /** 「商品名 + 在哪买 / 多少钱」：动作词在名词之后时，从名词一侧取。 */
    private static final Pattern SHOPPING_BEFORE = Pattern.compile(
            "([\\u4e00-\\u9fa5a-z0-9:_-]{2,24}?)(?:在哪(?:里)?(?:买|购买|购)|哪里(?:买|购买)|"
                    + "怎么(?:买|购买)|多少钱|什么价|价格|有卖|有货|有卖吗|有没有|有吗|"
                    + "怎么卖|好用吗|怎么样)");

    /** 名词候选的截断点（出现即止，避免把整句后半段当商品名）。 */
    private static final List<String> NOUN_STOPS = List.of(
            "在哪", "哪里", "怎么买", "怎么购买", "如何买", "多少钱", "什么价", "价格",
            "有卖", "有货", "吗", "呢", "呀", "啊", "吧", "？", "?", "。", "，", ",", "！", "!");

    /** 名词候选两端的口头语 / 量词 / 泛称。 */
    private static final List<String> NOUN_LEAD = List.of(
            "这个", "那个", "这款", "那款", "一款", "一个", "一件", "一双", "一台", "一只",
            "这", "那", "我", "想", "要", "个");
    private static final List<String> NOUN_TAIL = List.of(
            "这个商品", "这款商品", "的商品", "商品", "东西", "宝贝", "货品", "产品", "物品");
    private static final List<String> GENERIC_NOUNS = List.of(
            "商品", "东西", "宝贝", "货品", "产品", "物品", "一下", "一个", "这个", "那个");

    /**
     * ⚠️ 「品牌申请 / 申请品牌 / 品牌入驻」也算开店（2026-10-22 交互纠偏）：
     * 用户问「怎么申请品牌」时落点就是入驻申请页。只加组合词，**不能**单加「品牌」——
     * 「我是品牌商家」会先命中这里，抢走 {@link ChatIntent#IDENTITY_CLAIM}。
     */
    private static final Pattern OPEN_STORE = Pattern.compile(
            "开店|入驻|开个店|开一家店|开店铺|成为商家|卖东西|卖货|上架|加盟|营业执照"
                    + "|品牌申请|申请品牌|品牌入驻");

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
        } else if (looksLikeShopping(text)) {
            // 买 / 搜 / 在哪购买 —— 与查价同归 GOODS_QUERY；商家身份同样适用。
            intent = ChatIntent.GOODS_QUERY;
        } else if (looksLikeGoodsQuery(text)) {
            intent = ChatIntent.GOODS_QUERY;
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

    /**
     * 这句话是不是在查**商品本身**（价格 / 详情 / 参数 / 库存）。
     *
     * <p>判据三条同时成立：① 有查询标记；② 没有上架 / 改价 / 管理等「去办事」动作；
     * ③ 提到「商品」或给出了商品名 / 编号。订单 / 售后 / 评论 / 秒杀 / 开店在
     * {@link #recognize} 里都排在前面，所以「我的订单里的商品」不会被抢走。
     *
     * @param normalized {@link #normalize} 之后的文本
     */
    static boolean looksLikeGoodsQuery(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (GOODS_ACTION.matcher(normalized).find()) {
            return false;
        }
        if (!GOODS_QUERY_MARK.matcher(normalized).find()) {
            return false;
        }
        if (extractGoodsKeyword(normalized) == null && GOODS_BROWSE.matcher(normalized).find()) {
            return false;
        }
        return GOODS.matcher(normalized).find() || extractGoodsKeyword(normalized) != null;
    }

    /**
     * 从规范化文本里取商品名 / 编号（原样保留冒号与连字符）。
     *
     * <p>优先级：显式「编号 / 货号 + 数字」&gt; 带冒号的复合编号 &gt; 含字母和数字的型号。
     * 只返回一个；纯数字不认（除非前面有「编号」这类显式前缀）。
     *
     * @param normalized {@link #normalize} 之后的文本
     * @return 商品名 / 编号；没提到返回 {@code null}
     */
    static String extractGoodsKeyword(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        Matcher explicit = GOODS_ID_PREFIX.matcher(normalized);
        if (explicit.find()) {
            return explicit.group(1);
        }
        Matcher tokens = GOODS_TOKEN.matcher(normalized);
        String longest = null;
        while (tokens.find()) {
            String token = tokens.group();
            if (token.length() < 3) {
                continue;
            }
            boolean compound = token.indexOf(':') >= 0;
            if (!compound && !(hasLetter(token) && hasDigit(token))) {
                continue;
            }
            if (longest == null || token.length() > longest.length()) {
                longest = token;
            }
        }
        return longest;
    }

    /**
     * 这句话是不是**买家行为**（想买 / 在哪买 / 搜索某商品 / 问某商品多少钱）。
     *
     * <p>与 {@link #looksLikeGoodsQuery} 的区别：后者只认「查数据」，本方法覆盖
     * 「买 / 搜」这类动作；两者都归 {@link ChatIntent#GOODS_QUERY}，但本方法会让回复
     * 带上「去商城搜索」动作。订单 / 售后 / 评论 / 秒杀 / 开店优先，管理 / 上架等
     * 「去办事」动作一律排除 —— 商家说「管理商品」不会被当成买家。
     *
     * @param normalized {@link #normalize} 之后的文本
     */
    static boolean looksLikeShopping(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (REFUND.matcher(normalized).find()
                || ORDER.matcher(normalized).find()
                || COMMENT.matcher(normalized).find()
                || SECKILL.matcher(normalized).find()
                || OPEN_STORE.matcher(normalized).find()) {
            return false;
        }
        if (GOODS_ACTION.matcher(normalized).find()) {
            return false;
        }
        if (!SHOPPING_MARK.matcher(normalized).find()) {
            return false;
        }
        return extractShoppingKeyword(normalized) != null
                || GOODS.matcher(normalized).find();
    }

    /** 是否要给「去商城搜索」的明确动作（不只是商品卡）。 */
    static boolean wantsSearch(String normalized) {
        return normalized != null
                && looksLikeShopping(normalized)
                && SEARCH_ACTION.matcher(normalized).find();
    }

    /**
     * 从购物句里取商品名 —— 支持中文名（护膝 / 蓝牙耳机 / 手机壳）与型号（oppo12）。
     *
     * <p>顺序：先复用 {@link #extractGoodsKeyword}（显式编号 / 字母数字型号），再从
     * 「搜索 / 买 / 找」后面的名词取，最后从「商品名 + 在哪买 / 多少钱」的名词一侧取。
     * 只返回一个；泛称（商品 / 东西）与不足 2 字的碎片返回 {@code null}。
     *
     * @param normalized {@link #normalize} 之后的文本
     */
    static String extractShoppingKeyword(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String code = extractGoodsKeyword(normalized);
        if (!isBlank(code)) {
            return code;
        }
        Matcher after = SHOPPING_AFTER.matcher(normalized);
        if (after.find()) {
            String noun = trimNoun(after.group(1));
            if (noun != null) {
                return noun;
            }
        }
        Matcher before = SHOPPING_BEFORE.matcher(normalized);
        while (before.find()) {
            String noun = trimNoun(before.group(1));
            if (noun != null) {
                return noun;
            }
        }
        return null;
    }

    /** 截掉「在哪买 / 多少钱」等尾巴与「这个 / 一个 / 商品」等口头语。 */
    private static String trimNoun(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int cut = s.length();
        for (String stop : NOUN_STOPS) {
            int i = s.indexOf(stop);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        s = s.substring(0, cut);
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (String lead : NOUN_LEAD) {
                if (s.startsWith(lead) && s.length() > lead.length()) {
                    s = s.substring(lead.length());
                    changed = true;
                }
            }
            for (String tail : NOUN_TAIL) {
                if (s.endsWith(tail) && s.length() > tail.length()) {
                    s = s.substring(0, s.length() - tail.length());
                    changed = true;
                }
            }
            if (s.endsWith("的") && s.length() > 1) {
                s = s.substring(0, s.length() - 1);
                changed = true;
            }
        }
        s = s.trim();
        if (s.length() < 2 || GENERIC_NOUNS.contains(s)) {
            return null;
        }
        return s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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
