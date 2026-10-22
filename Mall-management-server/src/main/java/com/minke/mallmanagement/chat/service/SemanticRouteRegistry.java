package com.minke.mallmanagement.chat.service;

import com.minke.mallmanagement.chat.model.ChatIntent;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义路由注册表 —— 每个核心意图 3~5 条「标准问法」。
 *
 * <h2>【它和 RouteCatalog 的分工】</h2>
 * <ul>
 *   <li>{@link RouteCatalog} 管「页面存在哪、当前角色能进哪」；</li>
 *   <li>本表管「用户这句话像哪个意图 / 哪页」。给每条问法预先算向量，运行时只做余弦比较。</li>
 * </ul>
 * 路径仍以 {@link RouteCatalog#resolve(String, Integer)} 归位；本表只写「候选目标」，
 * 不复制一份可执行路由。新增页面时先加 RouteCatalog，再在这里补 3~5 条真实说法。
 *
 * <h2>【角色】</h2>
 * {@code 0} 两个壳共用，{@code 1} 客户，{@code 2} 商家。商品管理 / 商家工作台 / 店铺数据
 * 只对商家开放；客户问到时不会硬塞一个进不去的页面，而是交给澄清（低于阈值时）。
 */
final class SemanticRouteRegistry {

    static final int BOTH = 0;
    static final int CUSTOMER = 1;
    static final int MERCHANT = 2;

    /**
     * @param intent     命中的意图
     * @param targetPath 导航类候选路径；数据类为 {@code null}
     * @param audience   0 共用 / 1 客户 / 2 商家
     * @param utterances 标准问法（3~5 条，全部来自真实用户语感，不写正则）
     */
    record SemanticRoute(ChatIntent intent, String targetPath, int audience,
                         List<String> utterances) {
    }

    private SemanticRouteRegistry() {
    }

    private static final List<SemanticRoute> ROUTES = List.of(
            // ── 用户点名的三个核心意图 ────────────────────────────────────────
            route(ChatIntent.GOODS_MANAGE, "/merchant/user/list", MERCHANT,
                    "我要管理商品", "怎么看我的商品", "我上架的货在哪", "商品列表",
                    "我想改一下商品价格"),
            route(ChatIntent.MERCHANT_DASHBOARD, "/merchant", MERCHANT,
                    "怎么去商家工作台", "我要进后台", "商家控制台在哪", "商家后台怎么进",
                    "打开商家中心"),
            route(ChatIntent.ORDER_QUERY, null, BOTH,
                    "查我的订单", "我的快递到哪了", "买了什么", "订单状态怎么样",
                    "我的订单进度"),

            // ── 数据查询 ──────────────────────────────────────────────────────
            route(ChatIntent.REFUND_QUERY, null, BOTH,
                    "查退款进度", "我的售后处理到哪了", "退货结果怎么样", "退款到账了吗"),
            route(ChatIntent.COMMENT_QUERY, null, BOTH,
                    "我要看评论", "有没有人给我评论", "评价内容在哪看", "买家留言在哪"),
            route(ChatIntent.GOODS_QUERY, null, BOTH,
                    "这个商品多少钱", "查商品详情", "商品还有库存吗", "帮我找一下商品",
                    "怎么看商品的详细数据"),

            // ── 业务动作 ──────────────────────────────────────────────────────
            route(ChatIntent.OPEN_STORE, "/customer/user/apply", BOTH,
                    "怎么申请品牌", "开店入驻流程", "品牌加盟怎么申请", "怎么开店铺"),
            route(ChatIntent.ADD_TO_SECKILL, "/merchant/seckill", MERCHANT,
                    "我要报名秒杀", "参加平台活动", "商品上架到秒杀",
                    "我的新商品要上架秒杀页"),

            // 「钱花哪了」不是查订单 —— 余额 / 流水都在会员中心。
            route(ChatIntent.ASSET_QUERY, "/customer/user/member", BOTH,
                    "我花出去的钱去哪了", "查询余额和流水", "我的钱花哪了", "账户资产明细",
                    "我要管理我的钱"),

            // ── 页面导航（沿用 GUIDE_NAVIGATION，默认逐步引导） ─────────────────
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/stats", MERCHANT,
                    "怎么看店铺经营数据", "销售额和订单量", "店铺最近卖得怎么样"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/user/add", MERCHANT,
                    "怎么上架商品", "发布新商品", "新增商品怎么操作"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/after-sales", MERCHANT,
                    "怎么处理退款", "售后订单怎么处理", "退款怎么操作", "我要处理售后"),
            route(ChatIntent.GUIDE_NAVIGATION, "/customer/orders", CUSTOMER,
                    "怎么处理退款", "怎么申请退款", "退货流程是什么"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/order/cart", MERCHANT,
                    "购物车在哪", "去购物车", "怎么结算"),
            route(ChatIntent.GUIDE_NAVIGATION, "/customer/cart", CUSTOMER,
                    "购物车在哪", "去购物车", "怎么结算"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/user/favorites", MERCHANT,
                    "我的收藏", "我收藏的东西", "收藏的商品"),
            route(ChatIntent.GUIDE_NAVIGATION, "/customer/user/favorites", CUSTOMER,
                    "我的收藏", "我收藏的东西", "收藏的商品"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/user", MERCHANT,
                    "怎么去个人中心", "用户中心在哪", "打开我的主页"),
            route(ChatIntent.GUIDE_NAVIGATION, "/customer/user", CUSTOMER,
                    "怎么去个人中心", "用户中心在哪", "打开我的主页"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant/msg", MERCHANT,
                    "看消息", "通知在哪看", "有人找我吗"),
            route(ChatIntent.GUIDE_NAVIGATION, "/customer/msg", CUSTOMER,
                    "看消息", "通知在哪看", "有人找我吗"),
            route(ChatIntent.GUIDE_NAVIGATION, "/merchant", MERCHANT,
                    "回首页", "逛商城", "看看商品"),
            route(ChatIntent.GUIDE_NAVIGATION, "/customer", CUSTOMER,
                    "回首页", "逛商城", "看看商品"),

            // ── 规则兜底类（语义层也登记，避免「你好」被澄清） ────────────────
            route(ChatIntent.IDENTITY_CLAIM, null, BOTH,
                    "我是商家", "我是卖家", "我是品牌商家"),
            route(ChatIntent.FAQ, null, BOTH,
                    "拆封了能退货吗", "平台售后政策是什么", "七天无理由怎么算"),
            route(ChatIntent.GREETING, null, BOTH,
                    "你好", "在吗"),
            route(ChatIntent.HELP, null, BOTH,
                    "你能做什么", "你会哪些功能")
    );

    private static SemanticRoute route(ChatIntent intent, String targetPath, int audience,
                                       String... utterances) {
        return new SemanticRoute(intent, targetPath, audience, List.of(utterances));
    }

    /** 当前角色可见的语义路由；身份未知（{@code null}）或管理员 = 全部。 */
    static List<SemanticRoute> routesFor(Integer role) {
        if (role == null || role != 1 && role != 2) {
            return ROUTES;
        }
        List<SemanticRoute> list = new ArrayList<>();
        for (SemanticRoute route : ROUTES) {
            if (route.audience() == BOTH || route.audience() == role) {
                list.add(route);
            }
        }
        return list;
    }

    /** 启动预加载用：全部标准问法（角色过滤在查询时做，向量只需算一次）。 */
    static List<SemanticRoute> all() {
        return ROUTES;
    }
}
