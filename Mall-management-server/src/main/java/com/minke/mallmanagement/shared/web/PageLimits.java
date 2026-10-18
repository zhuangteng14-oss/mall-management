package com.minke.mallmanagement.shared.web;

/**
 * 分页上限的**唯一出处**（D3-3）。
 *
 * <h2>【为什么必须收口到一处】</h2>
 * 收口前，各域把上限写在自己的常量里：管理端 11 处 {@code MAX_LIMIT = 500}、
 * 攻击记录与订单流 2 处 {@code 200}，另有几处已经自行改成了 100。它们都是「别把列表拉爆」
 * 的同一个念头，却给了调用方**十几个不同的答案** —— 前端每个页面都得记住自己那一个，
 * 新加一个列表页又会再抄一个数字。D3 起统一为 {@link #MAX_PAGE_SIZE}，本类是该值的唯一来源。
 *
 * <h2>【刻意保留的例外（**不是**漏改）】</h2>
 * <ul>
 *   <li>{@code ReplayService#MAX_LIMIT = 50000}：演练回放是**离线批量读**，降到 100 会让
 *       「一次回放整批事件」不可用。它的上限只防「把整张表拉出来」，不是分页控件。</li>
 *   <li>{@code StatsDao#MAX_LIMIT = 50}：看板商品榜是 Top-N，本来就低于 100，不参与收口。</li>
 * </ul>
 *
 * <h2>【{@code null} / 非正数的语义】</h2>
 * 本仓约定：{@code limit} 缺省或非法时**落回默认值**（而不是报错）—— 分页控件不会发非正数，
 * 但 curl 会。{@link #clamp(Integer)} 因此把 {@code null} 与 {@code <= 0} 都收成
 * {@link #DEFAULT_PAGE_SIZE}；需要别的默认值（如攻击记录默认 20）的调用方自己先判空，
 * 只把**正数**交给本类夹上限。
 */
public final class PageLimits {

    /** 分页接口一次最多回多少行。改它 = 改全仓所有分页端点的上限（Replay/Stats 两个例外见类注释）。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 未传 {@code limit} 时的默认行数。与上限同值：默认值高于上限没有意义。 */
    public static final int DEFAULT_PAGE_SIZE = MAX_PAGE_SIZE;

    private PageLimits() {
    }

    /**
     * 夹取分页上限。
     *
     * @param limit 调用方给的行数；{@code null} / {@code <= 0} ⇒ {@link #DEFAULT_PAGE_SIZE}
     * @return {@code [1, MAX_PAGE_SIZE]} 内的生效值
     */
    public static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /** {@link #clamp(Integer)} 的原始类型重载（管理端 DAO 的入参是 {@code int}）。 */
    public static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
