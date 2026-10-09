package com.minke.mallmanagement.goods.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 「商品上/下架（货架态）」的内联 SQL 出口（C3 块 2 用户块新增）—— 只读写
 * {@code goods.ShelfState} / {@code goods.DelistedAt} 两列（块 2 为商家降级新加）。
 *
 * <h2>【⚠️ 为什么另开一个 DAO，而不是往 {@code GoodsDao} 里加方法】</h2>
 * 两条理由，都是「不制造漂移」：
 * <ol>
 *   <li>{@link GoodsDao} 的类 javadoc 把「本类 8 条语句 / 6 处 schema 前缀」当成**逐字复核过的
 *       计数**写死（那是三层重构第二笔的验收判据）。往里加 2 条带前缀的语句会让那段注释当场失真。</li>
 *   <li>{@code GoodsDao} 被多处文档按行号互引；新开文件 ⇒ 它**一行不动**。</li>
 * </ol>
 * 与块 2 里 {@code UserDao} / {@code UserGovernanceDao} 的分工是同一手法。
 *
 * <h2>【⚠️ 下架 = 改 {@code ShelfState}，**绝不 DELETE】**</h2>
 * 这是块 2 的一条 A 档裁决：商家降级时「商品全下架」是**逻辑下架**。
 * <p>反例记在这里以免有人「优化」回去：既有的 {@code GoodsController} 下架端点走的是
 * <b>两条真 DELETE</b>（{@code goods} + {@code productsales}，见 {@code GoodsDao.deleteGoods} /
 * {@code deleteProductsales}）—— 那条路径与此处**语义不同**，别混用：前者是「商家主动删除
 * 一件商品」，此处是「平台冻结一个商家的全部在售」。用 DELETE 会让「30 天内重新申请通过
 * 自动恢复」变成**不可恢复**（数据已经没了）。
 *
 * <h2>【无 schema 前缀 —— 见 {@code UserGovernanceDao} 类注释的同一条理由】</h2>
 */
@Repository
public class GoodsShelfDao {

    private final JdbcClient jdbcClient;

    public GoodsShelfDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 把某商家的**全部**商品逻辑下架（{@code ShelfState=0} + 记下架时刻）。
     *
     * <p>⚠️ 把**已经在 0** 的行也一并再写一次（不写 {@code AND ShelfState=1}）—— 返回的
     * 「影响行数」因此等于该商家的商品总数，是个更好读的审计数字（「下架了 N 件」）；
     * 加上条件会让这个数变成「这次新下架的件数」，语义更细但不稳定。
     *
     * @return 受影响行数（= 该商家商品总数）
     */
    public int delistAllByMerchant(String merchantId, LocalDateTime at) {
        String sql = "UPDATE goods SET ShelfState = 0, DelistedAt = ? WHERE MerchantId = ?";
        return jdbcClient.sql(sql).params(at, merchantId).update();
    }

    /** 把某商家的全部商品恢复上架（{@code ShelfState=1} 且清空 {@code DelistedAt}）。 */
    public int relistAllByMerchant(String merchantId) {
        String sql = "UPDATE goods SET ShelfState = 1, DelistedAt = NULL WHERE MerchantId = ?";
        return jdbcClient.sql(sql).param(merchantId).update();
    }
}
