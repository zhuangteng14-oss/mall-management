package com.minke.mallmanagement.observe.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 攻击演练的**靶子取数**（块 7 · commit 4）—— 只读，挑出「打谁」，一个字节都不写。
 *
 * <h2>【为什么要有这个类：一键跑不能要求人先填参数】</h2>
 * 验收清单第一条是「**固定一键跑 8 个场景**」⇒ 点一下「开始」就得有流量。
 * 若靶子（哪件 SKU、哪个账号、哪个敏感词）要前端手填，那一键跑就不成立了。
 * ⇒ 靶子在**跑之前**由本类从库里挑一次（每轮跑只挑一次，不是每请求都查）。
 *
 * <h2>【挑靶子的五条判据，逐条都不是随手 LIMIT 1】</h2>
 * <ol>
 *   <li><b>SKU</b>：{@code Stock > 0}（扣到 0 的场景演示不出「不再减」）+ {@code SkuCode <> ''}
 *       （空码的 SKU 下单链路会把它当没装可扣的量跳过 → 演示不出来）+ 父商品未删未下架
 *       （下架商品走的是另一条判据，会把「防护生效」和「商品不存在」混在一起看）+ **商家未冻结**
 *       （{@code user.DowngradeState <> 'frozen'} —— 冻结商家的每一单都会被 {@code orderAdd} 的
 *       冻结闸拒掉「该商家已暂停接单」，那样演示出来的 blocked 全是这一条，别的防护一条都看不见）。
 *       排序取 {@code Stock DESC} —— 库存最大的那件最能撑住一轮演练。</li>
 *   <li><b>账号</b>：{@code customerid} 非空（会话存的就是业务号）+ 未封禁
 *       （封禁账号打任何受保护端点都会停在封禁闸上，别的防护就看不见了）。
 *       ⚠️ 一并取 {@code username}：评论 / 举报那几条端点的入参是**登录名**
 *       （{@code IdentityGuard.requireSelfOrAdminByUsername}），光有业务号过不了那道判据。</li>
 *   <li><b>敏感词</b>：取**最长**的一个（长词更容易在一条正文里凑到 ≥40% 的占比 ⇒
 *       两档处置都能被触发；短词要写很长才到阈值）。</li>
 *   <li><b>另一件 SKU</b>：{@code SkuCode <> 主靶} —— 订单并发场景里用来造「同一商品不同单」的对照。</li>
 * </ol>
 *
 * <p>⚠️ <b>挑不到就抛</b>（由 {@code AttackEngine} 转成 4xx + 一句人话），**不**静默退化成空跑：
 * 一个「跑了 0 个请求但显示成功」的演练，比一个「跑不起来」的演练坏得多（TD-20 的口径）。
 */
@Repository
public class DrillTargetDao {

    private final JdbcClient jdbcClient;

    /**
     * 「商家未处于降级冻结态」这个半句 —— 抽出来是因为它要被拼进两条 SKU 查询。
     * ⚠️ 用 {@code NOT EXISTS} 而不是 {@code JOIN user}：{@code user.merchantid} 上没有唯一约束，
     * JOIN 到两条同 {@code merchantid} 的行会把 SKU 查成两行（{@code LIMIT 1} 于是变成掷骰子）。
     * 判据与 {@code UserGovernanceDao.isMerchantFrozen} **同一列同一取值**（那里是下单前的实时闸，
     * 这里是挑靶子时的预筛，两处必须一致）。
     */
    private static final String FROZEN_MERCHANT_EXCLUDED =
            "AND NOT EXISTS (SELECT 1 FROM mall_management_sql.`user` mu "
                    + "WHERE mu.merchantid = sku.MerchantId AND mu.DowngradeState = 'frozen') ";

    public DrillTargetDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /** 主靶：一件**有库存、有码、父商品在架、商家未冻结**的 SKU（含它所属商家的业务号与商品名）。 */
    public Map<String, Object> pickSku() {
        return first(jdbcClient.sql(
                        "SELECT sku.SkuCode AS SkuCode, sku.MerchantId AS MerchantId, "
                                + "sku.GoodName AS GoodName, sku.Price AS Price, sku.Stock AS Stock, "
                                + "sku.GoodsId AS GoodsId "
                                + "FROM mall_management_sql.goods_sku sku "
                                + "JOIN mall_management_sql.goods g ON g.Id = sku.GoodsId "
                                + "WHERE sku.Stock > 0 AND sku.SkuCode <> '' "
                                + "AND g.DeletedAt IS NULL AND g.ShelfState = 1 "
                                + FROZEN_MERCHANT_EXCLUDED
                                + "ORDER BY sku.Stock DESC, sku.Id ASC LIMIT 1")
                .query().listOfRows());
    }

    /** 次靶：另一件 SKU（同款判据，但码与主靶不同）。挑不到 ⇒ {@code null}（调用方自行降级）。 */
    public Map<String, Object> pickOtherSku(String excludedSkuCode) {
        return first(jdbcClient.sql(
                        "SELECT sku.SkuCode AS SkuCode, sku.MerchantId AS MerchantId, "
                                + "sku.GoodName AS GoodName, sku.Price AS Price, sku.Stock AS Stock, "
                                + "sku.GoodsId AS GoodsId "
                                + "FROM mall_management_sql.goods_sku sku "
                                + "JOIN mall_management_sql.goods g ON g.Id = sku.GoodsId "
                                + "WHERE sku.Stock > 0 AND sku.SkuCode <> '' AND sku.SkuCode <> ? "
                                + "AND g.DeletedAt IS NULL AND g.ShelfState = 1 "
                                + FROZEN_MERCHANT_EXCLUDED
                                + "ORDER BY sku.Stock DESC, sku.Id ASC LIMIT 1")
                .param(excludedSkuCode == null ? "" : excludedSkuCode)
                .query().listOfRows());
    }

    /** 演练账号：一个**未封禁**的客户（业务号 + 登录名一起取，见类注释第 2 条）。 */
    public Map<String, Object> pickCustomer() {
        return first(jdbcClient.sql(
                        "SELECT u.customerid AS CustomerId, u.username AS Account "
                                + "FROM mall_management_sql.`user` u "
                                + "WHERE u.customerid IS NOT NULL AND u.customerid <> '' "
                                + "AND (u.banned IS NULL OR u.banned = 0) "
                                + "ORDER BY u.id ASC LIMIT 1")
                .query().listOfRows());
    }

    /** 演练用敏感词（取最长的一个，理由见类注释第 3 条）。挑不到 ⇒ {@code null}。 */
    public String pickSensitiveWord() {
        List<String> words = jdbcClient.sql(
                        "SELECT `Word` FROM mall_management_sql.sensitive_word "
                                + "WHERE `Word` <> '' ORDER BY CHAR_LENGTH(`Word`) DESC LIMIT 1")
                .query(String.class).list();
        return words.isEmpty() ? null : words.get(0);
    }

    /** 某一刻的库存读数（演练前 / 演练后各一次 —— 防超卖看的就是这两个数的差）。 */
    public Integer stockOf(String skuCode) {
        List<Integer> stock = jdbcClient.sql(
                        "SELECT Stock FROM mall_management_sql.goods_sku WHERE SkuCode = ? LIMIT 1")
                .param(skuCode == null ? "" : skuCode).query(Integer.class).list();
        return stock.isEmpty() ? null : stock.get(0);
    }

    /**
     * 人工设的**演示基线**（Migration ⑰-2 的 {@code BaselineStock}；{@code NULL} = 未处于演示中）。
     *
     * <p>⚠️ 本类**只读不写**：基线是「人工四步」的载体（演示前拍、演示后还原、结束清空），
     * 由演示人自己执行 —— 引擎自动还原会把「库存被扣到 0」这条当场证据抹掉，而那正是
     * 秒杀场景要看的东西。这里读它只是为了让 {@code Result} 里能同时给出「跑前实际值」
     * 与「本轮演示的基线」。
     */
    public Integer baselineStockOf(String skuCode) {
        List<Integer> rows = jdbcClient.sql(
                        "SELECT BaselineStock FROM mall_management_sql.goods_sku "
                                + "WHERE SkuCode = ? LIMIT 1")
                .param(skuCode == null ? "" : skuCode).query(Integer.class).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }
}
