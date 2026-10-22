package com.minke.mallmanagement.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局路由字典 —— 大模型唯一可以选择的页面集合。
 *
 * <h2>【为什么要有它】</h2>
 * 旧的意图识别靠一条条正则穷举「开店 / 秒杀 / 售后…」，每加一个页面就要加一条规则。
 * 现在模型只做一件事：把用户的话映射到**这里列出的某个真实路径**（{@code target_path}）。
 * 「从当前页怎么走过去」不在模型职责内 —— 前端用同一份图的 BFS 计算。
 *
 * <h2>【与前端的关系】</h2>
 * 本表与 {@code src/config/navGraph.js} 是**两份镜像**：路径必须与
 * {@code src/router/index.js} 逐字一致；前端那份负责算路（节点 + 边），本表负责给模型
 * 中文说明（页面是什么、用户通常怎么说）。
 *
 * <h2>【角色】</h2>
 * 同一句「管理我的钱」在客户壳和商家壳落点不同。{@link #promptCatalog(Integer)} 只把
 * 当前角色**能进**的页面列给模型；万一模型仍给了另一个壳的路径，
 * {@link #resolve(String, Integer)} 会按角色前缀改写，改写不到就返回 {@code null}
 * （编排层降级规则引擎，不会把用户送进一个角色守卫会踢走的页面）。
 */
public final class RouteCatalog {

    /** 两个壳都能看到的页面。 */
    private static final int BOTH = 0;
    private static final int CUSTOMER = 1;
    private static final int MERCHANT = 2;

    /**
     * 降级匹配的最短命中长度。
     *
     * <p>取 2：像「价格」「余额」「会员」这种两字名词本身就是页面线索，且所有命中都要求
     * **唯一最高分**（并列就不猜），所以两字词的误伤面被并列判据挡住了。
     */
    private static final int MIN_KEYWORD_LEN = 2;

    /** 从 description 里抠出「用户说「a / b / c」」这段。 */
    private static final Pattern UTTERANCE = Pattern.compile("用户说「([^」]+)」");

    /**
     * 一个可选页面。
     *
     * @param path        真实路由（与 router/index.js 逐字一致）
     * @param title       页面名
     * @param description 给模型看的中文说明 + 用户可能的说法
     * @param audience    0 共用 / 1 客户壳 / 2 商家壳
     */
    public record Route(String path, String title, String description, int audience) {
    }

    private static final List<Route> ROUTES = List.of(
            // ── 商家壳 ────────────────────────────────────────────────────────
            new Route("/merchant", "商家首页",
                    "商城主页：浏览商品、搜索、进入平台活动。用户说「回首页 / 逛商城 / 看看商品」时选它。", MERCHANT),
            new Route("/merchant/search", "商品搜索",
                    "按关键词搜索商品。用户说「搜商品 / 找某样东西」时选它。", MERCHANT),
            new Route("/merchant/msg", "消息中心",
                    "私信与系统通知。用户说「看消息 / 通知 / 有人找我」时选它。", MERCHANT),
            new Route("/merchant/order", "订单处理",
                    "商家订单中心：发货、收货与订单状态流转。用户说「处理订单 / 发货 / 待发货 / 订单管理」时选它。", MERCHANT),
            new Route("/merchant/order/cart", "购物车",
                    "购物车与结算。用户说「购物车 / 结算 / 买东西」时选它。", MERCHANT),
            new Route("/merchant/orders", "我的订单",
                    "买家视角的我的订单列表（含售后进度）。用户说「我的订单 / 我买的东西」时选它。", MERCHANT),
            new Route("/merchant/user", "个人中心",
                    "用户中心：个人资料、商品、订单与资产的枢纽。用户说「个人中心 / 用户中心 / 我的主页」时选它。", MERCHANT),
            new Route("/merchant/user/info", "个人信息",
                    "头像、昵称与账号资料。用户说「改头像 / 改昵称 / 账号资料」时选它。", MERCHANT),
            new Route("/merchant/user/add", "上架商品",
                    "发布新商品：主图、名称、分类、价格、详情与提交审核。用户说「上架 / 发布商品 / 卖东西 / 新增商品」时选它。", MERCHANT),
            new Route("/merchant/user/list", "查看商品",
                    "商品管理：在售 / 待审 / 驳回商品与 SKU，可编辑价格与库存。"
                            + "用户说「商品管理 / 改价 / 改价格 / 价格 / 改库存 / 编辑商品」时选它。", MERCHANT),
            new Route("/merchant/user/history", "历史购买记录",
                    "已完成的历史订单。用户说「历史订单 / 以前买过什么」时选它。", MERCHANT),
            new Route("/merchant/user/friend", "好友",
                    "好友与私信。用户说「好友 / 私信」时选它。", MERCHANT),
            new Route("/merchant/user/member", "会员中心",
                    "会员等级、积分、余额、优惠券与资产明细；平台没有独立的钱包页。"
                            + "用户说「管理我的钱 / 我的钱 / 余额 / 积分 / 会员 / 资产 / 优惠券 / 钱包」时选它。", MERCHANT),
            new Route("/merchant/user/apply", "商家入驻申请",
                    "商家入驻 / 品牌加盟申请。用户说「开店 / 入驻 / 品牌加盟」时选它。", MERCHANT),
            new Route("/merchant/user/favorites", "我的收藏",
                    "收藏的商品。用户说「收藏 / 我收藏的东西」时选它。", MERCHANT),
            new Route("/merchant/stats", "经营数据",
                    "店铺经营看板：销售额、订单量、趋势与库存。用户说「店铺数据 / 经营情况 / 销售报表 / 最近卖得怎么样」时选它。", MERCHANT),
            new Route("/merchant/seckill", "秒杀报名",
                    "选择商品参加平台秒杀。用户说「报名秒杀 / 参加活动 / 把商品上架到秒杀」时选它。", MERCHANT),
            new Route("/merchant/after-sales", "售后工作台",
                    "退款 / 退货 / 售后队列与处理。用户说「处理退款 / 售后 / 退货」时选它。", MERCHANT),

            // ── 客户壳 ────────────────────────────────────────────────────────
            new Route("/customer", "商城首页",
                    "商城主页：浏览商品、搜索、进入平台活动。用户说「回首页 / 逛商城 / 看看商品」时选它。", CUSTOMER),
            new Route("/customer/search", "商品搜索",
                    "按关键词搜索商品。用户说「搜商品 / 找某样东西」时选它。", CUSTOMER),
            new Route("/customer/msg", "消息中心",
                    "私信与系统通知。用户说「看消息 / 通知 / 有人找我」时选它。", CUSTOMER),
            new Route("/customer/cart", "购物车",
                    "购物车与结算。用户说「购物车 / 结算 / 买东西」时选它。", CUSTOMER),
            new Route("/customer/orders", "我的订单",
                    "我的订单列表（含售后进度）。"
                            + "用户说「我的订单 / 我买的东西 / 处理退款 / 退货」时选它。", CUSTOMER),
            new Route("/customer/user", "个人中心",
                    "用户中心：个人资料、订单与资产的枢纽。用户说「个人中心 / 用户中心 / 我的主页」时选它。", CUSTOMER),
            new Route("/customer/user/info", "个人信息",
                    "头像、昵称与账号资料。用户说「改头像 / 改昵称 / 账号资料」时选它。", CUSTOMER),
            new Route("/customer/user/history", "历史购买记录",
                    "已完成的历史订单。用户说「历史订单 / 以前买过什么」时选它。", CUSTOMER),
            new Route("/customer/user/friend", "好友",
                    "好友与私信。用户说「好友 / 私信」时选它。", CUSTOMER),
            new Route("/customer/user/apply", "商家入驻申请",
                    "商家入驻 / 品牌加盟申请。用户说「开店 / 入驻 / 品牌加盟」时选它。", CUSTOMER),
            new Route("/customer/user/member", "会员中心",
                    "会员等级、积分、余额、优惠券与资产明细；平台没有独立的钱包页。"
                            + "用户说「管理我的钱 / 我的钱 / 余额 / 积分 / 会员 / 资产 / 优惠券 / 钱包」时选它。", CUSTOMER),
            new Route("/customer/user/favorites", "我的收藏",
                    "收藏的商品。用户说「收藏 / 我收藏的东西」时选它。", CUSTOMER)
    );

    /** 模型偶尔会把「用户心里的地址」写得比真实路由短 / 旧；这里做确定性归位（不是意图正则）。 */
    private static final Map<String, String> ALIASES = aliases();

    private RouteCatalog() {
    }

    private static Map<String, String> aliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("/merchant/aftersale", "/merchant/after-sales");
        map.put("/merchant/after_sales", "/merchant/after-sales");
        map.put("/merchant/refund", "/merchant/after-sales");
        map.put("/merchant/publish", "/merchant/user/add");
        map.put("/merchant/goods/add", "/merchant/user/add");
        map.put("/merchant/goods", "/merchant/user/list");
        map.put("/merchant/product", "/merchant/user/list");
        map.put("/merchant/wallet", "/merchant/user/member");
        map.put("/merchant/user/wallet", "/merchant/user/member");
        map.put("/customer/wallet", "/customer/user/member");
        map.put("/customer/user/wallet", "/customer/user/member");
        map.put("/customer/refund", "/customer/orders");
        return map;
    }

    /** 当前角色能看到的路由（模型只能从中选）。 */
    public static List<Route> routesFor(Integer role) {
        int audience = audienceOf(role);
        List<Route> list = new ArrayList<>();
        for (Route route : ROUTES) {
            if (route.audience() == BOTH || audience == 3 || route.audience() == audience) {
                list.add(route);
            }
        }
        return list;
    }

    /** 注入 System Prompt 的那一段。 */
    public static String promptCatalog(Integer role) {
        StringBuilder sb = new StringBuilder();
        sb.append("可选页面（target_path 只能逐字取下面某一行的路径，不要自己拼）：\n");
        for (Route route : routesFor(role)) {
            sb.append("- ").append(route.path())
                    .append(" — ").append(route.title())
                    .append(" — ").append(route.description())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 给**用户**看的页面说明：去掉「用户说「…」时选它」这段给模型的提示。
     *
     * <p>字典里的 description 是写给模型的（含用户可能的说法），直接回给用户会像机器在念稿。
     */
    public static String userSummary(Route route) {
        if (route == null) {
            return "";
        }
        String description = route.description();
        int cut = description.indexOf("用户说");
        return (cut > 0 ? description.substring(0, cut) : description).trim();
    }

    /**
     * 规则降级用：在字典里按「用户可能会怎么说」做最长子串匹配。
     *
     * <p>⚠️ 这不是又长出一批意图正则 —— 话术全部来自本表 description 里的
     * 「用户说「a / b / c」」，加页面时只维护那一处。模型超时 / 未配置 Key 时才会走到这里。
     *
     * <p>判据刻意保守：命中至少 {@value #MIN_KEYWORD_LEN} 个字，且**唯一最高分**；
     * 两个页面并列（如「我的订单」同时像订单列表和订单处理）就不猜，交给兜底话术。
     *
     * @return 归位后的真实路径；{@code null} = 没把握
     */
    public static String guess(String message, Integer role) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = message.replaceAll("\\s+", "");
        String best = null;
        int bestLen = 0;
        boolean tie = false;
        for (Route route : routesFor(role)) {
            int hit = hitLength(route, text);
            if (hit > bestLen) {
                bestLen = hit;
                best = route.path();
                tie = false;
            } else if (hit > 0 && hit == bestLen) {
                tie = true;
            }
        }
        return bestLen >= MIN_KEYWORD_LEN && !tie ? best : null;
    }

    /** 页面名与「用户说」里的说法，命中最长的那一个。 */
    private static int hitLength(Route route, String text) {
        int best = text.contains(route.title()) ? route.title().length() : 0;
        Matcher matcher = UTTERANCE.matcher(route.description());
        if (matcher.find()) {
            for (String part : matcher.group(1).split("/")) {
                String keyword = part.trim();
                if (keyword.length() > best && text.contains(keyword)) {
                    best = keyword.length();
                }
            }
        }
        return best;
    }

    /** 精确查找（不做别名 / 角色改写）。 */
    public static Route find(String path) {
        String normalized = normalize(path);
        for (Route route : ROUTES) {
            if (route.path().equals(normalized)) {
                return route;
            }
        }
        return null;
    }

    /**
     * 把模型给的路径归位到当前角色的真实页面。
     *
     * @return 归位后的路径；{@code null} = 当前角色没有对应页面（编排层降级）
     */
    public static String resolve(String rawPath, Integer role) {
        String path = normalize(rawPath);
        if (path.isEmpty()) {
            return null;
        }
        String alias = ALIASES.get(path);
        if (alias != null) {
            path = alias;
        }

        // ⚠️ 精确命中也要先看角色：`/merchant/user/member` 对客户来说是「存在的页面」，
        //    但客户进不去（路由守卫会踢回首页）。命中却不可达 ⇒ 继续走下面的串壳改写。
        int audience = audienceOf(role);
        Route exact = find(path);
        if (exact != null
                && (exact.audience() == BOTH || audience == 3 || exact.audience() == audience)) {
            return path;
        }

        // 模型串壳：按角色把另一个壳的路径改写过来。
        if (audience == MERCHANT && path.startsWith("/customer")) {
            String mapped = "/merchant" + path.substring("/customer".length());
            if ("/merchant/cart".equals(mapped)) {
                mapped = "/merchant/order/cart";
            }
            return find(mapped) == null ? null : mapped;
        }
        if (audience == CUSTOMER && path.startsWith("/merchant")) {
            String mapped = "/customer" + path.substring("/merchant".length());
            if ("/customer/order/cart".equals(mapped)) {
                mapped = "/customer/cart";
            }
            if ("/customer/order".equals(mapped)) {
                mapped = "/customer/orders";
            }
            if ("/customer/after-sales".equals(mapped)) {
                mapped = "/customer/orders";
            }
            // 客户没有发布页 —— 「怎么上架商品」最近的真实落点是先入驻。
            if ("/customer/user/add".equals(mapped)) {
                mapped = "/customer/user/apply";
            }
            return find(mapped) == null ? null : mapped;
        }
        return null;
    }

    /** 模型给的路径是否属于当前角色可选集合。 */
    public static boolean isAllowed(String rawPath, Integer role) {
        return resolve(rawPath, role) != null;
    }

    private static int audienceOf(Integer role) {
        if (role == null) {
            return 3;   // 身份未知（匿名 / 单测）⇒ 两个壳都列，最终落点由前端角色守卫兜底
        }
        return switch (role) {
            case 1 -> CUSTOMER;
            case 2 -> MERCHANT;
            default -> 3;   // 管理员两边都能进；本端点对管理员一般不可达，留作兜底
        };
    }

    /** 去 query / hash / 末尾斜杠，统一小写。 */
    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        int cut = p.indexOf('?');
        if (cut >= 0) {
            p = p.substring(0, cut);
        }
        cut = p.indexOf('#');
        if (cut >= 0) {
            p = p.substring(0, cut);
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.toLowerCase(Locale.ROOT);
    }
}
