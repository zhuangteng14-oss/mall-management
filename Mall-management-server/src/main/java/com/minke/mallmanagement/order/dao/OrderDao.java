package com.minke.mallmanagement.order.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 订单域**唯一的数据访问出口**（三层重构第一笔：订单域）。
 *
 * <h2>【本类是"数据出口收成一处"的落点】</h2>
 * 三层前的订单域 SQL 散在 {@code MallManagementGoodsController} 的 8 个方法体里
 * （{@code orderAdd} / 三个 CAS / 四个读），跨 bean 走 {@code GoodjdbcTemplate} 裸调。
 * 本类把其中**除状态机 SQL 以外**的全部语句收在一处 —— 状态机那三条 UPDATE 仍由
 * {@link OrderStateMachine} 生成（B3 的收敛，本笔**不动**），本类只负责执行。
 *
 * <h2>【SQL 字面量逐字未变 —— 这是本笔的验收判据，不是修辞】</h2>
 * 每条语句都是**从原方法体整段搬来**的，包括：
 * <ul>
 *   <li>{@code mall_management_sql.} 前缀 —— ⚠️ 本笔**不消灭**它（那是 F#1，跨四域 39 处，
 *       单独一笔；本笔做只会得到"一半有前缀、一半没有"的中间态）；</li>
 *   <li>{@code `order`} 的反引号 —— 保留字必须带引号；</li>
 *   <li>⚠️ INSERT 那条写的是 {@code mall_management_sql.order}（**无**反引号），与 SELECT 的
 *       `` mall_management_sql.`order` `` **不一致** —— 这是既有形态，本笔**照抄不改**。
 *       MySQL 规则：限定名里跟在点号后面的词按标识符解析，保留字也无需引号，故它能跑。</li>
 * </ul>
 * 保留原字面量的收益是可复核：逐条与 {@code MallManagementGoodsController} 三层前的版本
 * 做字符串比对即可，不必重推语义。
 *
 * <h2>【为什么是 {@code JdbcClient}】</h2>
 * D#4 已定：短期 {@code JdbcClient}（{@code spring-boot-starter-jdbc} 已在，**零新依赖**），
 * 长期 jOOQ。构造器**收 {@code JdbcTemplate} 而不是 {@code JdbcClient}**，是为了让
 * {@code @WebMvcTest} 切片里既有的 {@code @MockitoBean JdbcTemplate} 继续喂给本类
 * —— 切片不加载 Boot 的 {@code JdbcClient} 自动配置，若直接注入 {@code JdbcClient}
 * 就得再多一个替身（那个坑 {@code AbstractSmokeTest} 的注释里已记了六次）。
 *
 * <h2>⚠️ 【本类不得加 {@code @Transactional}】</h2>
 * 本类的方法**跑在订单锁的临界区内**（调用链 {@code OrderService.addLocked} →
 * {@code OrderDao.insertOrder}）。加事务会让提交点从"语句结束"挪到"方法返回"，而
 * {@code DistributedLockService.runLocked} 的锁在 {@code finally} 里、即**返回值传回之前**
 * 已经释放 ⇒ 提交晚于释放 ⇒ 后进者查重读不到这一行 ⇒ 同一 OrderId 仍插 2 行。
 * 详见 {@link OrderService} 的类注释与 {@code three-layers.md §D.1}。
 * 本条有结构性断言兜底：{@code DistributedLockServiceTest} 里的
 * {@code orderChainIsNotTransactional}。
 */
@Repository
public class OrderDao {

    private final JdbcClient jdbcClient;

    public OrderDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    // ==================== 写：下单 ====================

    /**
     * 同一 OrderId 的落库行数。**只在临界区内调用** —— 查重是「锁 + 临界区内先查后插」
     * 两条里的第二条（第一条是锁），缺一条都仍会插重复行。
     *
     * <p>用 {@code single()}（= {@code DataAccessUtils.requiredSingleResult}）而非
     * {@code optional()}：{@code COUNT(*)} 恒返 1 行，查不回就是真异常，该让调用方看见
     * —— 调用方（{@code OrderService.addLocked}）对它的处理是**刻意 fail-open**（不阻断下单）。
     */
    public int countByOrderId(String orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.`order` WHERE OrderId = ?")
                .param(orderId)
                .query(Integer.class)
                .single();
    }

    /**
     * 下单落库。返回影响行数（原样透传给调用方判 {@code rows > 0} 分支）。
     *
     * <p>⚠️ 参数顺序与 INSERT 的列顺序**逐字对应**（{@code OrderId, CustomerId, MerchantId,
     * Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf}，{@code CreateTime}
     * 由 SQL 里的 {@code NOW()} 填）—— 这两处顺序一旦有一个错位，A3 的 S2b 不会报错，
     * 只会有行数据静默错位。
     */
    public int insertOrder(OrderRow r) {
        String sql = "INSERT INTO mall_management_sql.order " +
                "(OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf, CreateTime) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        return jdbcClient.sql(sql)
                .params(r.orderId(), r.customerId(), r.merchantId(), r.price(),
                        r.productInformation(), r.images(),
                        r.payIf(), r.deliveryIf(), r.receiveIf())
                .update();
    }

    /** 下单入参的载体（9 个位置参数，用 record 免掉一个 9 参方法签名）。嵌套在此，不另立文件。 */
    public record OrderRow(String orderId, String customerId, String merchantId, float price,
                           String productInformation, String images,
                           int payIf, int deliveryIf, int receiveIf) {
    }

    // ==================== 写：状态转移 ====================

    /**
     * 执行一条条件 UPDATE（CAS），返回影响行数。SQL **由 {@link OrderStateMachine} 生成**、
     * 不从本类出去 —— 三条语句被 golden copy 钉死（{@code OrderStateMachineTest
     * .updateSqlIsByteForByteTheLegacyLiteral}），搬进 DAO 会让那条钉子的落点变远。
     *
     * <p>{@code rows == 0} 是**正常分支**（守卫没命中：重复流转 / 单不存在），不是错误。
     */
    public int casUpdate(String sql, String orderId) {
        return jdbcClient.sql(sql).param(orderId).update();
    }

    // ==================== 读 ====================

    /** 购物车：某客户的未收货订单（{@code ReceiveIf != 1}），按创建时间倒序。 */
    public List<Map<String, Object>> selectActiveByCustomer(String customerId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate " +
                "FROM mall_management_sql.`order` WHERE CustomerId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }

    /** 订单中心：某商家的未收货订单。与 {@link #selectByMerchant} 的 {@code active} 分支**逐字相同** —— 两条语句各有调用方，本笔不合并（合并会改变调用面）。 */
    public List<Map<String, Object>> selectActiveByMerchant(String merchantId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate " +
                "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        return jdbcClient.sql(sql).param(merchantId).query().listOfRows();
    }

    /**
     * 商家端订单中心。{@code completed = true} → {@code ReceiveIf = 1}；否则 {@code ReceiveIf != 1}。
     *
     * <p>⚠️ 两条 SELECT **都不读 {@code CreateTime}**，而 {@code completed} 分支的调用方
     * （前端 OrderCenter.vue 的「已完成」tab）会渲染它 ⇒ 该列整列空白。这是**既有设计**
     * （{@code db-schema.md:91} 有记），本笔不趁手改 —— 改它等于改响应字段集，属行为变更。
     */
    public List<Map<String, Object>> selectByMerchant(String merchantId, boolean completed) {
        String sql;
        if (completed) {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf = 1 ORDER BY CreateTime DESC";
        } else {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        }
        return jdbcClient.sql(sql).param(merchantId).query().listOfRows();
    }

    /** 历史订单：某客户的已收货订单，按 {@code SettlementDate}（字符串列）倒序。 */
    public List<Map<String, Object>> selectHistoryByCustomer(String customerId) {
        String sql = "SELECT OrderId, MerchantId, ProductInformation, Images, SettlementDate " +
                "FROM mall_management_sql.`order` " +
                "WHERE CustomerId = ? AND ReceiveIf = 1 " +
                "ORDER BY SettlementDate DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }
}
