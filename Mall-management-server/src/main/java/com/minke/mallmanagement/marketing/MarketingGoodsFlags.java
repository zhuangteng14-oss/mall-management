package com.minke.mallmanagement.marketing;

import com.minke.mallmanagement.marketing.seckill.dao.SeckillDao;
import com.minke.mallmanagement.marketing.subsidy.dao.SubsidyDao;

/**
 * 「这件商品此刻是不是在秒杀 / 是不是国补 / 那个秒杀是不是已售完」的**唯一定义处**。
 *
 * <p>块 5〈Bug 2/3〉2026-10-12 新增。消费方是商品域的两条**公开**列表 SQL
 * （{@code GoodsCacheService.listHome} ⇒ {@code /goods/goodsSelectHome}、
 * {@code GoodsDao.searchByKeyword} ⇒ {@code /goods/productSearch}）—— 四个商品列表面
 * （主页「为你推荐」/ 搜索页 / 店铺页本店商品 / 店铺页底部跨店推荐）**全部只吃这两个端点**。
 *
 * <h2>为什么放在营销域、而不是在商品域的 SQL 里就地写 CASE</h2>
 * 「在不在秒杀」「规则在不在期」在本仓各只有一处定义，且两处都**不好抄**：
 * {@link SeckillDao#stage(String)} 是「库时钟现算的四态 CASE」；
 * {@link SubsidyDao#IN_EFFECT} 自带一条「必须比库时钟 {@code NOW()}、不能用 JVM 本地钟」的
 * 订正记录（差 8 小时的那个错**不报错**，只是标签该出不出的）。
 * ⇒ 抄第二份就是「同一件事两个真相」。本类**不复制**它们，只**拼装**：谓词体一律取自上面两个类的原始定义。
 *
 * <h2>三个标记的口径（对齐用户裁定）</h2>
 * <ul>
 *   <li>{@code IsSeckill} —— 该商品挂着一条 {@code Status='active'} 且 {@code stage()='live'} 的秒杀活动。
 *       {@code live} 用的是 {@link SeckillDao#stage(String)} 的原样输出，**不是**另写一个
 *       「时间窗内」的近似 —— 那个近似会把 {@code signup}（报名中）也当成在秒杀。</li>
 *   <li>{@code IsSoldOut} —— 在 {@code IsSeckill} 之上再加
 *       {@code TotalStock > 0 AND SoldStock >= TotalStock}（与 {@code SeckillService} 现算
 *       {@code SoldOut} 的口径逐字相同）。</li>
 *   <li>{@code IsSubsidy} —— {@code subsidy_goods.Status = 'approved'} 且规则 {@link SubsidyDao#IN_EFFECT}。</li>
 * </ul>
 *
 * <h2>⚠️ 三个都是 {@code EXISTS} 标量、**无条件**进投影 —— 过滤/打标签由消费侧做</h2>
 * 同一个 {@code goodsSelectHome} 同时喂「已售完要过滤掉」的共享页与「已售完要显示（灰 + 红斜标）」
 * 的店铺页 ⇒ **服务端筛不了**。这也是本类只回标记、不替调用方下结论的原因。
 *
 * <p>⚠️ 与 {@code GoodsCacheService} 里那条 {@code SoldQty}（LEFT JOIN {@code order_item}）同款：
 * 商品域的列表端点 JOIN/子查别的域的表来补派生列。**先例已存在于本块**（块 5 · B 组续笔），
 * 这里只是把那件事的谓词部分收进一个具名的地方，别让它散成第二份 CASE。
 */
public final class MarketingGoodsFlags {

    private MarketingGoodsFlags() {
    }

    /**
     * 一条商品的「是不是在秒杀（{@code live}）」EXISTS 片段。{@code g} 是外层商品表的别名。
     *
     * <p>⚠️ 派生表里的 {@code Stage} 列就是 {@link SeckillDao#stage(String)} 拼出来的那个 CASE
     * （它自带 {@code AS Stage} 别名）⇒ 与 {@code /marketing/seckill/*} 两条列出走的**同一个**四态定义。
     */
    public static String existsIsSeckill(String g) {
        return "EXISTS (SELECT 1 FROM (" + liveActivities("t") + ") x "
                + "WHERE x.GoodsId = " + g + ".Id AND x.Stage = 'live')";
    }

    /** 「在秒杀 **且** 那场秒杀已售完」。库存口径见类注释。 */
    public static String existsIsSoldOut(String g) {
        return "EXISTS (SELECT 1 FROM (" + liveActivities("t") + ") x "
                + "WHERE x.GoodsId = " + g + ".Id AND x.Stage = 'live' "
                + "AND x.TotalStock > 0 AND x.SoldStock >= x.TotalStock)";
    }

    /** 「国补已确认且在期」。{@code IN_EFFECT} 里的别名 {@code r} 由它自己带，这里只负责 JOIN 上。 */
    public static String existsIsSubsidy(String g) {
        return "EXISTS (SELECT 1 FROM mall_management_sql.subsidy_goods sg "
                + "JOIN mall_management_sql.subsidy_rule r ON r.RuleId = sg.RuleId "
                + "WHERE sg.GoodsId = " + g + ".Id AND sg.Status = 'approved' AND " + SubsidyDao.IN_EFFECT + ")";
    }

    /**
     * 秒杀活动的派生表（只取 {@code Status='active'} 的）。
     *
     * <p>⚠️ <b>不</b>在这里过滤时间窗 —— 「在不在期」正是 {@code Stage} 那一列要说的事，
     * 在这里再筛一次就会造出第二份判据。{@code IsSoldOut} 也只在自己的 WHERE 里加库存条件。
     */
    private static String liveActivities(String t) {
        return "SELECT " + t + ".GoodsId, " + SeckillDao.stage(t)
                + ", " + t + ".TotalStock, " + t + ".SoldStock "
                + "FROM mall_management_sql.seckill_activity " + t
                + " WHERE " + t + ".Status = 'active'";
    }
}
