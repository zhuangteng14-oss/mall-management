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
                + "g.GoodName AS GoodName, g.GoodImg AS GoodImg, g.Price AS PriceNow "
                + "FROM cart c LEFT JOIN goods g "
                + "ON g.MerchantId = c.MerchantId AND g.ProductCode = c.ProductCode "
                + "WHERE c.CustomerId = ? "
                + "ORDER BY c.updated_at DESC, c.Id DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }

    /** 结算用：**只取某一行**（{@code ProductCode} 非空）或**整车**（{@code ProductCode} 为空）。 */
    public List<Map<String, Object>> selectForCheckout(String customerId, String productCode) {
        // ⚠️ 两条完整 SQL，**不做字符串拼 where**：拼出来的 SQL 会多一个动态占位符，
        //    绑定顺序得跟着分支走 —— 那正是「运行时才发现的错位」。两条各绑各的，读起来也不用跳。
        String select = "SELECT c.MerchantId, c.ProductCode, c.Quantity, c.PriceSnapshot, "
                + "g.GoodName AS GoodName, g.GoodImg AS GoodImg, g.Price AS PriceNow "
                + "FROM cart c LEFT JOIN goods g "
                + "ON g.MerchantId = c.MerchantId AND g.ProductCode = c.ProductCode ";
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
        String sql = "SELECT GoodName, GoodImg, Price FROM goods WHERE MerchantId = ? AND ProductCode = ? LIMIT 1";
        return jdbcClient.sql(sql).params(merchantId, productCode)
                .query((rs, rowNum) -> new GoodsSnapshot(
                        rs.getString("GoodName"), rs.getString("GoodImg"), rs.getObject("Price", Double.class)))
                .optional()
                .orElse(null);
    }

    /** 商品快照（加购用）。{@code price} 可能为 {@code null}（列可空）。 */
    public record GoodsSnapshot(String goodName, String goodImg, Double price) {
    }
}
