package com.minke.mallmanagement.cart.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 购物车域的**唯一数据访问出口**（C3 块 4 · A1 甲「新建 {@code cart} 表」）。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新域口径】</h2>
 * 与 {@code CategoryDao} / {@code TemplateDao} 一致：新域一律**裸表名**。
 * ⚠️ 老域（{@code OrderDao} / {@code GoodsDao}）里的 {@code mall_management_sql.} 前缀是 F#1 的
 * 存量债，**别照抄** —— 照抄只会让「一半有前缀、一半没有」的中间态扩散（{@code OrderDao} 类注释
 * 已把这条写成「那是 F#1，单独一笔」）。
 *
 * <h2>⚠️ 【本类不得加 {@code @Transactional}】</h2>
 * {@link #insert} / {@link #resurrect} 的调用链在**结算**时与订单链路同源
 * （{@code CartService.checkout} → {@code OrderService.orderAdd} → {@code runLocked}）。
 * 订单链的 {@code @Transactional} 禁令见 {@code OrderService} 类注释；本类虽然不在订单锁的
 * 临界区内，但保持一致 —— 购物车的两条写语句各自是**单条语句**，autocommit 本身就是最小的
 * 正确单位，包事务不增加任何正确性（同 {@code OrderService} 类注释那段论证的结论）。
 *
 * <h2>⚠️ 【本表允许**物理 DELETE**（{@code remove}）—— 对规则 v2 的一处**明文边界**】</h2>
 * 规则 v2 是「**逻辑废弃**，不物理删」（禁止 {@code DROP}/{@code DELETE}）。本类**只在一处**
 * 违反它，且是**有据的**：
 * <ul>
 *   <li><b>购物车行不是业务记录，是瞬时状态</b>：它没有「被谁改过」的审计价值
 *       （{@code admin_audit} 不记它），也没有状态机（{@code order} 那种单调不可逆的语义）。
 *       用户点「删除」的语义**就是**「别再留着」。</li>
 *   <li><b>软删会**功能性**地弄坏「删了再加回来」</b>：唯一键是 {@code (CustomerId, ProductCode)}
 *       ⇒ 软删后重新加购会撞唯一键，得再把 {@code DeletedAt} 清回 NULL；即每一条写路径都要
 *       额外处理「复活」。为一个没有审计价值的瞬时状态付这个复杂度，是**净亏**。</li>
 *   <li><b>范围收得住</b>：{@code DELETE} 只出现在 {@link #remove} 与结算后的清行
 *       （{@link #remove} 是二者**唯一**的出口）。除本表外，全仓仍守规则 v2。</li>
 * </ul>
 * ⚠️ 这条是**我自裁的**（B 档：报备，不静默）—— 若您认为购物车也必须软删，说一声，
 * 我改成加 {@code DeletedAt} 列 + 全部查询加 {@code DeletedAt IS NULL} 的版本。
 */
@Repository
public class CartDao {

    private final JdbcClient jdbcClient;

    public CartDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 「{@code (MerchantId, SkuCode)} → 那一行 SKU」的**至多一行**投影。本类三处查询共用一份。
     *
     * <h2>为什么需要它（这是 C3 块 5 补的一处**真缺陷**）</h2>
     * 卖多型号的商品，详情页加入购物车时上报的 {@code ProductCode} 是
     * {@code goods_sku.SkuCode}，**不是** {@code goods.ProductCode} —— 这条是**建表时就定下的口径**，
     * {@code mall_management_sql.sql:1071} 的列注释逐字写着「SKU 编码（商家填；详情页加入购物车时
     * 作为 {@code ProductCode} 上报）」，{@code :1060} 又写着「⇒ 下单 / 购物车链路**不改**」。
     * 而本类此前三处**只联 {@code goods}**（`WHERE g.ProductCode = c.ProductCode`）⇒ 型号件一律配不上
     * ⇒ 加购回「商品不存在或已下架」、购物车里那一行的名字与价格变成 {@code null}。
     * 修法在**本类**（调用方一个字不改）：{@code goods} 配不上时再拿 {@code SkuCode} 配一次。
     *
     * <p>⚠️ <b>为什么要 {@code MIN(Id)} 这一层</b>：{@code goods_sku} 的唯一键是
     * {@code (GoodsId, AxisName)}（{@code mall_management_sql.sql:1076}）—— <b>{@code SkuCode} 上
     * 没有唯一约束</b>，商家可以在两件商品上填同一个码。直接 JOIN 会让**购物车一行变两行**
     * （{@link #selectByCustomer} 是列表，行数会当场翻倍）。取 {@code MIN(Id)} =「同一个码撞了就
     * 用最早那一行」，是**确定性**的选法（可复核，不是随机的 {@code LIMIT 1}）。
     *
     * <p>⚠️ 也<b>排掉空码</b>：{@code SkuCode} 的默认值是 {@code ''}（列注释「商家填」⇒ 允许留空），
     * 空码是「还没填」不是「一个叫空的型号」；不排掉的话所有没填码的 SKU 会按 {@code (MerchantId, '')}
     * 挤成一行，再被别的商品的购物车行捡去。
     *
     * <p>⚠️ <b>这条 JOIN 的访问路径</b>（2026-10-12 更新）：原先 {@code goods_sku} 上只有
     * {@code (GoodsId, AxisName)} 与 {@code (MerchantId, GoodName)}，**没有 {@code SkuCode} 的**
     * ⇒ 下面那条 {@code GROUP BY} 是全表扫。用户 2026-10-12 裁定 ④-b（A 档）已加**普通**索引
     * {@code idx_goods_sku_sku_code}（{@code mall_management_sql.sql} 尾部 ⑭ 块）。
     * ⚠️ 实测（{@code EXPLAIN}，4 行演示数据）：等值反查 {@code WHERE SkuCode='…'} 走 {@code ref}
     * + {@code Using index}（覆盖索引）；下面这条派生表内层由 {@code ALL} 变 {@code range}。
     * ⚠️ <b>这句不能读成「这条 JOIN 现在全走索引了」</b>：{@code Using temporary} **仍在** ——
     * 分组键是 {@code (MerchantId, SkuCode)}，单列索引消不掉那个临时表；索引改善的是**取数**
     * 那一半，不是**分组**那一半。要连分组一起消掉得建 {@code (MerchantId, SkuCode)} 复合索引，
     * 那是另一次 DDL、另一次裁定。
     */
    private static final String SKU_BY_CODE =
            "SELECT s.MerchantId, s.SkuCode, s.GoodsId, s.GoodName, s.Price, s.AxisName "
            + "FROM goods_sku s JOIN ("
            + "SELECT MerchantId, SkuCode, MIN(Id) AS Id FROM goods_sku "
            + "WHERE SkuCode <> '' GROUP BY MerchantId, SkuCode"
            + ") k ON k.Id = s.Id";

    /**
     * 三处查询共用的「商品列」取值口径：**先认 {@code goods}，配不上再认 SKU**。
     * <ul>
     *   <li>{@code GoodName}：SKU 那一行自带 {@code GoodName} 快照（列注释「免联查 goods」），
     *       但它是**写入时**的快照 —— 商品改名后不会跟着变。故 {@code goods.GoodName} 优先
     *       （与「无型号商品」那条路径逐字同源），SKU 名只做兜底。</li>
     *   <li>{@code GoodImg}：{@code goods_sku} **没有图列**（看建表 `:1063-1078`）⇒ 图必须由
     *       {@code GoodsId} 回联父商品。SKU 自身的 {@code MerchantId} 已冗余，回联只用 {@code Id}。</li>
     *   <li>{@code Price}：SKU 的价是**商家逐格填**的该组合价（`Price` 列注释）⇒ 型号件必须取它，
     *       取父商品的价就是「按错价成交」。</li>
     *   <li>{@code AxisName}：**2026-10-12 新增**（用户五处挂账裁定 ④-a「购物车多型号同名 ——
     *       带上 {@code AxisName}」）。多型号商品的几个 SKU 共享同一个 {@code GoodName} ⇒ 购物车
     *       里几行长得一模一样，看不出买的是哪个型号。{@code goods} 侧**没有**这个字段（只有
     *       {@code goods_sku} 有）⇒ **不 COALESCE**，非型号件就是 {@code null}，前端据此不画。
     *       （只作展示；结算 / 下单链路一个字不改。）</li>
     *   <li>{@code OriginalPrice}：**2026-10-12 新增**（〈折扣商品用券规则〉的前端那一半）。
     *       结算页要按「本单含不含折扣商品」把满减 / 折扣券灰掉（规则见
     *       {@code CouponService.redeem} 的 javadoc），而购物车**整车**的判据只能从这里来
     *       —— 商品页「立即购买」那条走的是 {@code /goods/productCodePrice} 自带的
     *       {@code originalPrice}，不经过本方法。
     *       ⚠️ 这一列**必须 COALESCE 到父商品**（划线原价加在 {@code goods} 上，型号件共享父商品
     *       那一份）—— 形状与 {@code GoodImg} 逐字同款：无型号件走 {@code g}、型号件走 {@code pg}。
     *       只读、不参与任何判据（后端那道闸走 {@code OrderDao.selectDiscountCodes}，
     *       不依赖本列）。</li>
     * </ul>
     */
    private static final String GOODS_COLS =
            "COALESCE(g.GoodName, s.GoodName) AS GoodName, "
            + "COALESCE(g.GoodImg, pg.GoodImg) AS GoodImg, "
            + "COALESCE(g.Price, s.Price) AS PriceNow, "
            + "s.AxisName AS AxisName, "
            + "COALESCE(g.OriginalPrice, pg.OriginalPrice) AS OriginalPrice ";

    /** {@link #GOODS_COLS} 配套的三个 LEFT JOIN（顺序不能换：`pg` 依赖 `s`）。 */
    private static final String GOODS_JOINS =
            "LEFT JOIN goods g ON g.MerchantId = c.MerchantId AND g.ProductCode = c.ProductCode "
            + "LEFT JOIN (" + SKU_BY_CODE + ") s "
            + "ON s.MerchantId = c.MerchantId AND s.SkuCode = c.ProductCode "
            + "LEFT JOIN goods pg ON pg.Id = s.GoodsId ";

    /**
     * 加购**新行**。撞 {@code uk_cart_customer_product} 时抛 {@code DuplicateKeyException}
     * （由 {@link com.minke.mallmanagement.cart.service.CartService#add} 接住并改走
     * {@link #addQuantity}）—— **不用** {@code ON DUPLICATE KEY UPDATE}：
     * 8.0.20 起 {@code VALUES()} 已废弃且刷告警，而 {@code AS new} 别名形态要吃 8.0.19+ 的版本；
     * 「catch 撞键再改 UPDATE」两条语句都只用最老的语法（同 {@code TemplateService} 的既有手法）。
     */
    public int insert(String customerId, String merchantId, String productCode,
                      int quantity, Double priceSnapshot) {
        String sql = "INSERT INTO cart (CustomerId, MerchantId, ProductCode, Quantity, PriceSnapshot) "
                + "VALUES (?, ?, ?, ?, ?)";
        return jdbcClient.sql(sql)
                .params(customerId, merchantId, productCode, quantity, priceSnapshot)
                .update();
    }

    /**
     * 已在车里 ⇒ **合并数量**（不新开行）。{@code LEAST(..., 999)} 是上界的**服务端兜底**：
     * 只靠 Service 判一次不够 —— 并发的两次加购各自读到 900 就都会写 900+50，落库 950 越界。
     * 放在 SQL 里 ⇒ 无论几次并发，落库值恒 ≤ 999。
     */
    public int addQuantity(String customerId, String productCode, int delta,
                           String merchantId, Double priceSnapshot) {
        String sql = "UPDATE cart SET Quantity = LEAST(Quantity + ?, 999), "
                + "MerchantId = ?, PriceSnapshot = ? "
                + "WHERE CustomerId = ? AND ProductCode = ?";
        return jdbcClient.sql(sql)
                .params(delta, merchantId, priceSnapshot, customerId, productCode)
                .update();
    }

    /** 改量（绝对赋值）。数量上界由 Service 卡；SQL 只负责写。 */
    public int updateQuantity(String customerId, String productCode, int quantity) {
        String sql = "UPDATE cart SET Quantity = ? WHERE CustomerId = ? AND ProductCode = ?";
        return jdbcClient.sql(sql).params(quantity, customerId, productCode).update();
    }

    /** 删一行（用户点「删除」/ 减到 0）。**物理 DELETE，理由见类注释的头号警告。** */
    public int remove(String customerId, String productCode) {
        return jdbcClient.sql("DELETE FROM cart WHERE CustomerId = ? AND ProductCode = ?")
                .params(customerId, productCode)
                .update();
    }

    /**
     * 某客户的整车（**展示用**）：联 {@code goods} 取商品名 / 图 / 当前价。
     *
     * <p>联表键是 {@code (MerchantId, ProductCode)} —— 与 {@code GoodsDao.updateGoods} 的既有口径
     * 一致；**不按单独的 {@code ProductCode} 联**（它跨商家不保证唯一，本仓无店铺表）。
     * <p>{@code LEFT JOIN}：商品被删 / 下架后购物车行仍在（否则行数会莫名其妙变少），
     * 此时 {@code GoodName}/{@code GoodImg} 为 {@code null} —— 由前端与结算分别兜（结算会拒）。
     */
    public List<Map<String, Object>> selectByCustomer(String customerId) {
        String sql = "SELECT c.Id, c.CustomerId, c.MerchantId, c.ProductCode, c.Quantity, c.PriceSnapshot, "
                + "c.created_at, c.updated_at, "
                + GOODS_COLS
                + "FROM cart c " + GOODS_JOINS
                + "WHERE c.CustomerId = ? "
                + "ORDER BY c.updated_at DESC, c.Id DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }

    /** 结算用：**只取某一行**（{@code ProductCode} 非空）或**整车**（{@code ProductCode} 为空）。 */
    public List<Map<String, Object>> selectForCheckout(String customerId, String productCode) {
        // ⚠️ 两条完整 SQL，**不做字符串拼 where**：拼出来的 SQL 会多一个动态占位符，
        //    绑定顺序得跟着分支走 —— 那正是「运行时才发现的错位」。两条各绑各的，读起来也不用跳。
        String select = "SELECT c.MerchantId, c.ProductCode, c.Quantity, c.PriceSnapshot, "
                + GOODS_COLS
                + "FROM cart c " + GOODS_JOINS;
        if (productCode != null && !productCode.isBlank()) {
            return jdbcClient.sql(select + "WHERE c.CustomerId = ? AND c.ProductCode = ? ORDER BY c.Id ASC")
                    .params(customerId, productCode)
                    .query().listOfRows();
        }
        return jdbcClient.sql(select + "WHERE c.CustomerId = ? ORDER BY c.Id ASC")
                .param(customerId)
                .query().listOfRows();
    }

    /**
     * 加购时的商品快照：{@code (MerchantId, ProductCode)} → 商品名 / 图 / **当前价**。
     * 商品不存在 ⇒ {@code null}。
     *
     * <p>⚠️ <b>单价必须由服务端取，不能信客户端传来的价</b>：{@code PriceSnapshot} 是**结算成交价**
     * （见类注释与 {@code mall_management_sql.sql} 的列注释）⇒ 让它由客户端直传，
     * 就是把 {@code defect-survey.md:107}「服务端零价格校验」那条病在购物车这层重开一次
     * （「1 元的电视」会变成「1 元的购物车行」）。故本方法是加购链路的**真值源**。
     */
    public GoodsSnapshot selectGoodsSnapshot(String merchantId, String productCode) {
        // ① 先按 `goods.ProductCode` 找 —— **无型号商品走这条**（C3 块 4 起的既有路径）。
        //    ⚠️ 补上 `ShelfState` / `DeletedAt` 两个条件（2026-10-12 续笔）：此前**一条都没有**，
        //    而 `CartService` 的失败文案却是「商品不存在**或已下架**」—— 那句「或已下架」在改前
        //    是**假的**（下架商品照样能加进车，一直要到结算才被 `goodName` 空白那条拦住）。
        //    现在补上，文案才第一次说到做到。两个条件都有 DDL 依据：`ShelfState` 是
        //    `tinyint(1) NOT NULL DEFAULT 1`（`:408`，**不会为 NULL** ⇒ `= 1` 不会漏行），
        //    `DeletedAt` 默认 NULL = 正常（`:466`，逻辑删除）。与 `GoodsCacheService` 首页列表
        //    的 `WHERE g.ShelfState = 1 AND g.DeletedAt IS NULL` **同口径**。
        String byGoods = "SELECT GoodName, GoodImg, Price FROM goods "
                + "WHERE MerchantId = ? AND ProductCode = ? AND ShelfState = 1 AND DeletedAt IS NULL LIMIT 1";
        GoodsSnapshot hit = jdbcClient.sql(byGoods).params(merchantId, productCode)
                .query((rs, rowNum) -> new GoodsSnapshot(
                        rs.getString("GoodName"), rs.getString("GoodImg"), rs.getObject("Price", Double.class)))
                .optional()
                .orElse(null);
        if (hit != null) {
            return hit;
        }
        // ② 没配上 ⇒ 再按 `goods_sku.SkuCode` 找 —— **多型号商品走这条**。
        //    `ProductCode` 就是详情页上报的 SKU 码（理由与形状见 `SKU_BY_CODE` 的方法体注释）；
        //    ⚠️ 三个字段的取值口径（名优先 live 的 `goods`、图只能回联父商品、价必须取该组合价）
        //    与 `GOODS_COLS` **同源**，改一处要一起看。
        //    ⚠️ 父商品的上架 / 未删条件**同样要判** —— 型号是挂在商品下面的，父商品下架了，
        //    它的型号一件都不该能加购（`JOIN` 上加条件 = 父商品不满足时整条落空 ⇒ 走不到这里）。
        String bySku = "SELECT g.GoodName AS GoodName, g.GoodImg AS GoodImg, s.Price AS Price "
                + "FROM (" + SKU_BY_CODE + ") s "
                + "JOIN goods g ON g.Id = s.GoodsId "
                + "WHERE s.MerchantId = ? AND s.SkuCode = ? AND g.ShelfState = 1 AND g.DeletedAt IS NULL";
        return jdbcClient.sql(bySku).params(merchantId, productCode)
                .query((rs, rowNum) -> new GoodsSnapshot(
                        rs.getString("GoodName"), rs.getString("GoodImg"), rs.getObject("Price", Double.class)))
                .optional()
                .orElse(null);
    }

    /** 商品快照（加购用）。{@code price} 可能为 {@code null}（列可空）。 */
    public record GoodsSnapshot(String goodName, String goodImg, Double price) {
    }
}
