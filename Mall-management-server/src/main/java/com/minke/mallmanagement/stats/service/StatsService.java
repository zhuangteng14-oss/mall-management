package com.minke.mallmanagement.stats.service;

import com.minke.mallmanagement.stats.dao.StatsDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经营数据看板的服务层（可视化段 commit 2）。四件事：**区间读 / 商家读 / 全量重建 / 滚动刷新**。
 *
 * <h2>【本类最重要的一个设计：写入口只有一个】</h2>
 * 「从 {@code order} 算出 {@code stats_daily}」这段逻辑**只有一条路径** ——
 * {@link #computeAndUpsert(String, String)}。{@link #rebuild()}（全量）与
 * {@link com.minke.mallmanagement.stats.job.StatsAggregateJob}（滚动窗口）**都调它**，
 * 只差传进来的日期区间。
 * <p>这么写是为了堵死一个很具体的失效：如果 job 与 rebuild 各写一份聚合 SQL，某天有人改了
 * 一边的 {@code COUNT(DISTINCT)} 口径或忘了 {@code CAST(... AS DECIMAL)}，两条路径就会产出
 * **两套都「看起来对」的数** —— 而看板上的数字**没有第二处可以对照**，错了不会有人发现。
 *
 * <h2>【区间语义：三种给法，优先级从高到低】</h2>
 * <ol>
 *   <li>{@code days = N}（N &gt; 0）⇒ 相对**库时钟**的最近 N 天 {@code [今天-(N-1), 今天]}；</li>
 *   <li>否则 {@code from} / {@code to} 给了哪个用哪个（闭区间，缺的一头敞开）；</li>
 *   <li>都没给 ⇒ <b>不设日期谓词</b>，回全部已聚合的日（演示/排障默认值，见 {@link #resolveRange}）。</li>
 * </ol>
 * <b>只有第 1 种会给环比</b>（{@code prevTotals}）—— 理由见 {@link #previousRange}。
 *
 * <h2>【{@code Aov}（客单价）是**读时现除**，不落库】</h2>
 * 同 {@code stats_daily} 建表注释：派生值一旦落列，分子分母被重导/修口径之后它会**静默漂开**，
 * 而漂开的派生值没有任何东西能发现。故 {@link #withAov} 在每次响应里现算。
 */
@Service
public class StatsService {

    private final StatsDao statsDao;

    public StatsService(StatsDao statsDao) {
        this.statsDao = statsDao;
    }

    // ==================== 读 ====================

    /**
     * 管理端看板：全站逐日 + 区间合计 + 环比 + 各商家汇总。
     *
     * <p>⚠️ {@code Merchants} 那一列走的是 {@code Scope = merchant} 的行，**它的合计不等于全站合计**
     * —— 两者是同一批订单的两种切法，但 {@code Buyers} 不可加（见 {@code StatsDao.sumDaily} 注释）。
     * 页面把两者**分两块摆**，不要做「商家合计应等于全站」的断言，那条不成立。
     */
    public Map<String, Object> adminStats(String from, String to, Integer days) {
        Map<String, Object> result = new HashMap<>();
        try {
            Range range = resolveRange(from, to, days);
            List<Map<String, Object>> siteDaily = withAov(statsDao.selectDaily(StatsDao.SCOPE_SITE, null, range.from, range.to));
            Map<String, Object> totals = withAov(statsDao.sumDaily(StatsDao.SCOPE_SITE, null, range.from, range.to));
            List<Map<String, Object>> merchants = statsDao.selectMerchantSummary(range.from, range.to);

            result.put("success", true);
            result.put("builtRows", statsDao.countAll());
            result.put("range", range.toMap());
            result.put("days", siteDaily);
            result.put("totals", totals);
            result.put("prevTotals", previousTotals(range));
            result.put("merchants", merchants);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "经营数据读取失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 商家端看板：本店逐日 + 区间合计 + 环比 + 商品榜（商品榜实时聚合 {@code order_item}）。
     *
     * <p>⚠️ <b>刻意是「全有或全无」</b>：商品榜那一步若抛异常，整条响应判 {@code success:false}
     * （逐日 / 合计的键仍在，但页面必须按失败渲染）。<b>不要</b>改成「商品榜单独兜底成空数组」——
     * {@code topGoods} 空**本来就是预期状态**（{@code order_item} 不回填存量单，见
     * {@code StatsDao.selectTopGoods}），把一次真失败显示成那个「正常的空」正是本仓最忌讳的失效。
     */
    public Map<String, Object> merchantStats(String merchantId, String from, String to, Integer days, Integer goodsLimit) {
        if (isBlank(merchantId)) {
            return fail("缺少参数：MerchantId");
        }
        Map<String, Object> result = new HashMap<>();
        String mid = merchantId.trim();
        try {
            Range range = resolveRange(from, to, days);
            List<Map<String, Object>> daily = withAov(statsDao.selectDaily(StatsDao.SCOPE_MERCHANT, mid, range.from, range.to));
            Map<String, Object> totals = withAov(statsDao.sumDaily(StatsDao.SCOPE_MERCHANT, mid, range.from, range.to));

            result.put("success", true);
            result.put("MerchantId", mid);
            result.put("builtRows", statsDao.countAll());
            result.put("range", range.toMap());
            result.put("days", daily);
            result.put("totals", totals);
            result.put("prevTotals", previousTotals(range));
            // ⚠️ 商品榜**不随区间筛**：order_item 上没有日期谓词（粒度是「商品」不是「日」）。
            //    今日 order_item 是 0 行 ⇒ 这里恒回空数组，页面按「历史单无明细」呈现（裁定 §三）。
            result.put("topGoods", statsDao.selectTopGoods(mid, goodsLimit == null ? 0 : goodsLimit));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "经营数据读取失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 写 ====================

    /**
     * 全量重建：清空 {@code stats_daily}、从 {@code order} 重算两个口径、重填。
     *
     * <p>⚠️ <b>{@code @Transactional} 是必须的，不是顺手加的</b>：中间那一步「已清空、尚未重填」
     * 如果被并发读看到，看板会闪一个**全 0 的正常页面** —— 那是比报错更坏的失效（它看起来是对的）。
     * 本仓订单链禁 {@code @Transactional}（那条禁令针对的是「订单锁 + 事务」的嵌套），本类与订单链
     * 无关，且这里要的正是「要么全清全填、要么原地不动」。
     *
     * <p>另外：{@code rebuild} 会在**同一次调用里**重算全表，故「订单被全量重导（DROP + 重插，
     * 日期集合变了）」之后，它是唯一能把 {@code stats_daily} 里那些**已经不存在的日期行**清掉的入口。
     * 滚动窗口的 job 做不到这件事（它只碰最近几天）。
     */
    @Transactional
    public Map<String, Object> rebuild() {
        Map<String, Object> result = new HashMap<>();
        try {
            int deleted = statsDao.deleteAll();
            int[] written = computeAndUpsert(null, null);
            result.put("success", true);
            result.put("deleted", deleted);
            result.put("siteRows", written[0]);
            result.put("merchantRows", written[1]);
            result.put("totalRows", statsDao.countAll());
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("msg", "重建失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 滚动刷新：只重算 {@code [from, to]} 这段窗口（**不清表**）。给 {@code @Scheduled} 用。
     *
     * <p>与 {@link #rebuild()} 共用 {@link #computeAndUpsert}，故两者口径**不可能**不同。
     * 窗口内没有订单的日子**不产生行**（聚合是 {@code GROUP BY} 出来的）—— 那是「这天没单」，
     * 不是「这天漏了」。前端画折线时按日期序列补 0，**别**在服务端补：服务端补零会让
     * 「没单」与「没聚合」在库里长得一样。
     */
    public int refreshWindow(String from, String to) {
        int[] written = computeAndUpsert(from, to);
        return written[0] + written[1];
    }

    // ==================== 内部 ====================

    /**
     * 唯一的「算 + 写」。返回 {@code [全站行数, 商家行数]}。
     *
     * <p>⚠️ 两次聚合是**两遍独立扫描**（见 {@code StatsDao.aggregateMerchant} 注释：全站买家数
     * 不能由商家行 ROLLUP 出来），不是把第一遍的结果再分组。
     */
    private int[] computeAndUpsert(String from, String to) {
        List<Map<String, Object>> site = statsDao.aggregateSite(from, to);
        for (Map<String, Object> row : site) {
            statsDao.upsert(StatsDao.SCOPE_SITE, str(row.get("StatDate")), "",
                    row.get("Orders"), row.get("Gmv"), row.get("Items"), row.get("Buyers"));
        }
        List<Map<String, Object>> merchants = statsDao.aggregateMerchant(from, to);
        for (Map<String, Object> row : merchants) {
            statsDao.upsert(StatsDao.SCOPE_MERCHANT, str(row.get("StatDate")), str(row.get("MerchantId")),
                    row.get("Orders"), row.get("Gmv"), row.get("Items"), row.get("Buyers"));
        }
        return new int[]{site.size(), merchants.size()};
    }

    /**
     * 环比窗口 —— <b>只在「最近 N 天」这一种给法下算得出来</b>。
     *
     * <p>为什么不做「任意区间取前一等长区间」：那要先知道区间的长度，而 {@code from}/{@code to}
     * 是调用方给的**闭区间字符串**，两端可能只给一头（另一头敞开）—— 敞开的区间**没有长度**，
     * 硬凑一个「前一等长区间」等于编一个调用方没要的区间。⇒ 只对 {@code days} 这一支给环比，
     * 其余情况 {@code prevTotals = null}，页面据此**不画环比箭头**（而不是画一个 0%）。
     *
     * @return 前一等长区间的合计（已算好 {@code aov}）；不适用 ⇒ {@code null}
     */
    private Map<String, Object> previousTotals(Range range) {
        if (range.days == null || range.days <= 0) {
            return null;
        }
        String prevTo = statsDao.dateMinus(range.days);
        String prevFrom = statsDao.dateMinus(range.days * 2 - 1);
        return withAov(statsDao.sumDaily(StatsDao.SCOPE_SITE, null, prevFrom, prevTo));
    }

    /** 区间。{@code days} 非空表示「相对库时钟的最近 N 天」，是唯一能给环比的形态。 */
    private Range resolveRange(String from, String to, Integer days) {
        if (days != null && days > 0) {
            String end = statsDao.currentDate();
            String start = statsDao.dateMinus(days - 1);
            return new Range(start, end, days);
        }
        // 全空 ⇒ 不加日期谓词：演示与排障时「先看看库里有什么」比「先猜一个区间」有用得多。
        // ⚠️ 但这不等于「等价于 days=很大」—— 它连 prevTotals 都没有（见 previousTotals）。
        return new Range(blankToNull(from), blankToNull(to), null);
    }

    /** 给每一行补 {@code aov}（客单价 = Gmv / Orders，Orders = 0 ⇒ 0）。 */
    private static List<Map<String, Object>> withAov(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            row.put("aov", aov(row.get("Gmv"), row.get("Orders")));
        }
        return rows;
    }

    /** 给合计补 {@code aov}。合计那一行是 DAO 直接回的，键名同样是 {@code Gmv}/{@code Orders}。 */
    private static Map<String, Object> withAov(Map<String, Object> totals) {
        totals.put("aov", aov(totals.get("Gmv"), totals.get("Orders")));
        return totals;
    }

    /**
     * 客单价。⚠️ 分母为 0 时回 {@code 0} 而不是 {@code null}：页面把「0 单」与「单价算不出」
     * 显示成同一个 0 是**可接受的**（两者都该显示 ¥0.00），而回 {@code null} 会让前端多一条分支。
     */
    private static BigDecimal aov(Object gmv, Object orders) {
        BigDecimal o = decimal(orders);
        if (o.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return decimal(gmv).divide(o, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (v == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("msg", msg);
        return m;
    }

    /** 解析后的区间。{@code from}/{@code to} 可为 {@code null}（那一头敞开）。 */
    private static final class Range {
        final String from;
        final String to;
        final Integer days;

        Range(String from, String to, Integer days) {
            this.from = from;
            this.to = to;
            this.days = days;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("from", from);
            m.put("to", to);
            m.put("days", days);
            return m;
        }
    }
}
