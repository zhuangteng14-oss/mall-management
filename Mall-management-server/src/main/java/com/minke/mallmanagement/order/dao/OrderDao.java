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
     * Price, Quantity, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf}，{@code CreateTime}
     * 由 SQL 里的 {@code NOW()} 填）—— 这两处顺序一旦有一个错位，A3 的 S2b 不会报错，
     * 只会有行数据静默错位。
     *
     * <p>⚠️ <b>C3 块 4（A2 甲）在此加了 {@code Quantity}</b>：列位**紧跟 {@code Price}**
     * （单价旁边就是数量），参数位同理 —— 两处都加在**同一相对位置**，这是「列序 = 参数序」
     * 这条纪律在加列时唯一安全的做法（加在末尾也能跑，但下次谁按语义插列就错位了）。
     */
    public int insertOrder(OrderRow r) {
        String sql = "INSERT INTO mall_management_sql.order " +
                "(OrderId, CustomerId, MerchantId, Price, Quantity, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf, CreateTime) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        return jdbcClient.sql(sql)
                .params(r.orderId(), r.customerId(), r.merchantId(), r.price(), r.quantity(),
                        r.productInformation(), r.images(),
                        r.payIf(), r.deliveryIf(), r.receiveIf())
                .update();
    }

    /**
     * 下单入参的载体（10 个位置参数，用 record 免掉一个 10 参方法签名）。嵌套在此，不另立文件。
     *
     * <p>⚠️ 分量顺序 = {@link #insertOrder} 的列顺序（{@code Quantity} 在 {@code price} 之后）。
     */
    public record OrderRow(String orderId, String customerId, String merchantId, float price, int quantity,
                           String productInformation, String images,
                           int payIf, int deliveryIf, int receiveIf) {
    }

    // ==================== 写：订单明细（可视化段 commit 0） ====================

    /**
     * 一条订单明细的载体。分量顺序 = {@link #insertItems} 的列顺序。
     *
     * <p>⚠️ {@code productCode} 可为**空串**（解析不出商品编码时）。唯一键是
     * {@code (OrderId, ProductCode)} ⇒ **同单多件同码必须由调用方先合并数量**，
     * 否则会撞 {@code uk_order_item_order_product}。
     */
    public record ItemRow(String orderId, String merchantId, String productCode, String goodName,
                          double price, int quantity, double subtotal) {
    }

    /**
     * 写入订单明细（一单多品 ⇒ 多条）。返回**累计影响行数**。
     *
     * <p>⚠️ <b>逐条 INSERT，不用批量 API</b>：本仓既有 DAO 无一处用批量写法，而明细条数有界
     * （购物车行数 / 弹窗选购件数，均为个位数）⇒ 为省几次往返引一套新写法不划算。
     *
     * <p>⚠️ <b>跑在订单锁的临界区内</b>（调用方 = {@code OrderService.addLocked}）⇒ 与
     * {@link #insertOrder} 同一条禁令：<b>不得加 {@code @Transactional}</b>（提交点必须在锁释放
     * 之前，理由见本类注释）；{@code DistributedLockServiceTest.orderChainIsNotTransactional}
     * 有结构断言兜底（它按**类**扫 {@code OrderDao}，本方法无注解即通过）。
     *
     * <p>⚠️ {@code OrderedAt} 由 SQL 的 {@code NOW(3)} 填 —— 与 {@code order.CreateTime} 同一口径的
     * <b>库时钟</b>，Java 侧不构造任何时间点（TD-32）。
     *
     * <p>⚠️ <b>刻意不写 {@code ON DUPLICATE KEY UPDATE}</b>：本方法只在下单**成功后**、用**新生成的**
     * OrderId 调用（{@code addLocked} 临界区内的查重已保证该 OrderId 此前不存在）⇒ 没有重复的可能，
     * 也就没有「重放」语义要照顾。真撞上说明调用方没合并同码，该让它报错，而不是静默改写。
     */
    public int insertItems(List<ItemRow> items) {
        String sql = "INSERT INTO mall_management_sql.`order_item` "
                + "(OrderId, MerchantId, ProductCode, GoodName, Price, Quantity, Subtotal, OrderedAt) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))";
        int n = 0;
        for (ItemRow it : items) {
            n += jdbcClient.sql(sql)
                    .params(it.orderId(), it.merchantId(), it.productCode(), it.goodName(),
                            it.price(), it.quantity(), it.subtotal())
                    .update();
        }
        return n;
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
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate, Quantity, CancelledAt " +
                "FROM mall_management_sql.`order` WHERE CustomerId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }

    /** 订单中心：某商家的未收货订单。与 {@link #selectByMerchant} 的 {@code active} 分支**逐字相同** —— 两条语句各有调用方，本笔不合并（合并会改变调用面）。 */
    public List<Map<String, Object>> selectActiveByMerchant(String merchantId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate, Quantity, CancelledAt " +
                "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        return jdbcClient.sql(sql).param(merchantId).query().listOfRows();
    }

    /**
     * 商家端订单中心。{@code completed = true} → {@code ReceiveIf = 1}；否则 {@code ReceiveIf != 1}。
     *
     * <p>⚠️ 两条 SELECT **都不读 {@code CreateTime}**，而 {@code completed} 分支的调用方
     * （前端 OrderCenter.vue 的「已完成」tab）会渲染它 ⇒ 该列整列空白。这是**既有设计**
     * （{@code db-schema.md#db-4-1} 有记），本笔不趁手改 —— 改它等于改响应字段集，属行为变更。
     */
    public List<Map<String, Object>> selectByMerchant(String merchantId, boolean completed) {
        String sql;
        if (completed) {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate, Quantity " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf = 1 ORDER BY CreateTime DESC";
        } else {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate, Quantity, CancelledAt " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        }
        return jdbcClient.sql(sql).param(merchantId).query().listOfRows();
    }

    /** 历史订单：某客户的已收货订单，按 {@code SettlementDate}（字符串列）倒序。 */
    public List<Map<String, Object>> selectHistoryByCustomer(String customerId) {
        String sql = "SELECT OrderId, MerchantId, ProductInformation, Images, SettlementDate, Quantity " +
                "FROM mall_management_sql.`order` " +
                "WHERE CustomerId = ? AND ReceiveIf = 1 " +
                "ORDER BY SettlementDate DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }

    // ==================== 读：归属（C3 块 4 · A3 连带） ====================

    /**
     * 按 {@code OrderId} 取该单的**归属双方**；单不存在 ⇒ {@code null}。
     *
     * <p>块 4 加它只有一个用途：三个 CAS 端点（{@code orderPayIfUp} / {@code orderReceiveIfUp} /
     * {@code orderDeliveryIfUp}）在此之前**只收 {@code orderId}、谁都能改别人的单**
     * （缺口登记 {@code progress.md §5-AF}）—— 判「这张单是不是你的」必须**回查资源归属**，
     * 故 A3 连带在此补一条**只读**回查。
     *
     * <p>⚠️ <b>用 {@code optional()} 而不是 {@code single()}</b>：单不存在是**正常输入**
     * （乱传 / 已被清理的 orderId），不是异常。
     * <p>⚠️ <b>返回 {@code null} 时调用方必须「放行到 CAS」，不许改判成 403</b>：
     * 让 CAS 的 {@code rows == 0} 去出既有的那条文案 —— 否则 A3 的 {@code A-S3-2}
     * （20 并发支付里恰好 19 个返回逐字「订单不存在或已付款」）会当场变红。
     * 归属校验**只管「别人的单」**，不管「不存在的单」。
     */
    public OrderOwner selectOwner(String orderId) {
        String sql = "SELECT CustomerId, MerchantId FROM mall_management_sql.`order` WHERE OrderId = ?";
        return jdbcClient.sql(sql).param(orderId)
                .query((rs, rowNum) -> new OrderOwner(rs.getString("CustomerId"), rs.getString("MerchantId")))
                .optional()
                .orElse(null);
    }

    /** 订单归属：{@code customerId} = 买家，{@code merchantId} = 卖家。 */
    public record OrderOwner(String customerId, String merchantId) {
    }

    // ==================== 补丁（C3 块4 补丁）：明细写 + 买家详情 ====================
    // ⚠️ 加在**类尾**（读区之后）：本类中段被 progress.md（:133 / :138）/ slot-registry.md（:133）/
    //    db-schema.md（:141）/ OrderCenter.vue（:146）/ context-pack（:125）按行号引用，
    //    插在中间会让其后集体漂移；加在尾 = 这些引用一个都不漂。

    /**
     * 执行一条**明细列** UPDATE（{@code OrderStateMachine.detailUpdateSql} 生成），返回影响行数。
     *
     * <p>与 {@link #casUpdate} 同款「SQL 不进 DAO」：语句由状态机出。调用点在**同一把订单锁内**、
     * CAS 命中 1 行**之后** —— 那条「位已置、明细未写」的非原子性由订单锁消除竞态
     * （见 {@code OrderStateMachine} 类尾补丁段的理由）。
     */
    public int applyDetails(String sql, Object... params) {
        return jdbcClient.sql(sql).params(params).update();
    }

    /**
     * 单条订单（买家详情端点 {@code /order/orderDetail} 的数据源）。不存在 ⇒ {@code null}。
     *
     * <p>⚠️ <b>显式列清单</b>（含块4 补丁的 7 个明细列），**不是 {@code SELECT *}** ——
     * 加列不会自动漏进响应，这里多列的列举是**有意**的（买家要看支付方式 / 快递 / 时间）。
     * 轨迹**不在**本方法里出：另由 {@code ShippingTrackDao} 查（本方法只回订单行）。
     */
    public Map<String, Object> selectByOrderId(String orderId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, Quantity, ProductInformation, Images, "
                + "PayIf, DeliveryIf, ReceiveIf, CreateTime, SettlementDate, "
                + "TradeNo, PaymentMethod, PaidAt, ExpressCompany, TrackingNo, ShippedAt, ReceivedAt "
                + "FROM mall_management_sql.`order` WHERE OrderId = ? LIMIT 1";
        return jdbcClient.sql(sql).param(orderId).query().listOfRows().stream().findFirst().orElse(null);
    }

    /**
     * 待补物流轨迹的订单（调度器 {@code ShippingTrackAdvanceJob} 的数据源）：
     * **已发货（{@code DeliveryIf = 1}）且未收货（{@code ReceiveIf = 0}）且有发货时刻**。
     *
     * <p>谓词三支缺一不可，各自理由：
     * <ul>
     *   <li>{@code DeliveryIf = 1} —— 没发货就没有 {@code ShippedAt} 可偏移，谈不上轨迹；</li>
     *   <li>{@code ReceiveIf = 0} —— 已收货的单不再补节点（用户已点了「确认收货」，
     *       再往里插「派送中」是**时序倒错**）；</li>
     *   <li>{@code ShippedAt IS NOT NULL} —— 老单（本补丁之前发的）{@code DeliveryIf = 1}
     *       但明细列全 NULL ⇒ 没有基准时刻。**刻意排掉它们**，而不是拿 {@code CreateTime} 顶替
     *       （那会凭空造出一条与发货无关的时间线）。</li>
     * </ul>
     *
     * <p>⚠️ <b>只读</b>，且**不加时间谓词** —— 「这一轮该补哪些节点」是**应用层判**的
     * （拿已过秒数比阈值，各节点各判一次），不是 SQL 判的。把阈值写进 SQL 会让
     * 「改阈值要改两条语句」，且无法表达「同一单这一轮补 2 个节点、下轮补第 3 个」。
     *
     * <p>⚠️ <b>2026-10-10（用户报时区差 8 小时）改判：两列都改成「不带时区」的形态，Java 侧不再接时间值</b>
     * —— 原先是 {@code ShippedAt} 原列（驱动回 {@code LocalDateTime}）+ 应用层 {@code System.currentTimeMillis()}
     * 相减，那条路上 {@code serverTimezone=Asia/Shanghai} 会掺进一次折算。现在是：
     * <ul>
     *   <li>{@code ShippedAtText} = {@code DATE_FORMAT(...)} 的**文本**，库时钟（UTC），原样透传给
     *       {@code ShippingTrackDao.writeAtShippedOffset} 当 {@code DATE_ADD} 的基准；</li>
     *   <li>{@code ElapsedSec} = {@code TIMESTAMPDIFF(SECOND, ShippedAt, NOW())}，**整数秒**，
     *       两列都是库时钟 ⇒ 差值天然正确，与 JVM 时区无关（{@code NOW()} 与 {@code ShippedAt} 同为 UTC，
     *       相减把两端一起消掉）。</li>
     * </ul>
     */
    public List<Map<String, Object>> selectShippableForTrack() {
        String sql = "SELECT OrderId, DATE_FORMAT(ShippedAt, '%Y-%m-%d %H:%i:%s') AS ShippedAtText, "
                + "TIMESTAMPDIFF(SECOND, ShippedAt, NOW()) AS ElapsedSec FROM mall_management_sql.`order` "
                + "WHERE DeliveryIf = 1 AND ReceiveIf = 0 AND ShippedAt IS NOT NULL";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    // ==================== 超时未付款取消（C3 块4 补丁 · 第 2 笔） ====================

    /**
     * 扫「**未付款且未取消**、且创建时刻已超阈值」的单 —— {@code OrderTimeoutCancelJob} 每轮唯一的读。
     *
     * <p>⚠️ <b>{@code CreateTime} 是 {@code varchar(50)}（{@code mall_management_sql.sql:152}），
     * 不是 datetime</b> ⇒ 比对必须走「字符串 &lt; {@code DATE_FORMAT(…)}」这个形状，
     * 与 {@code AdminOrderDao} 的 {@code unpaidTimeout} 指标**同一写法**（那边是 24 小时、只数不动，
     * 这里是可配阈值、真的动手）。{@code CreateTime} 由 SQL {@code NOW()} 填
     * （{@link #insertOrder}）⇒ 与 {@code NOW()} **同一条库时钟轴**，字符串比较不会错位。
     *
     * <p>⚠️ <b>阈值以字符串拼接进 SQL，不走 {@code ?} 占位</b>：这是本仓既有做法
     * （{@code AdminOrderDao:164} 拼 {@code UNPAID_HOURS}）。值来自 properties（{@code long}），
     * 不是用户输入。调用方须保证 {@code timeoutSeconds > 0}（见 job 的前置判）。
     *
     * <p>{@code CancelledAt IS NULL} 这条不只是「跳过已取消的」—— 它同时是**幂等**的来源：
     * 取消过的单不会再进候选集。
     */
    public List<Map<String, Object>> selectUnpaidExpired(long timeoutSeconds) {
        String sql = "SELECT OrderId FROM mall_management_sql.`order` "
                + "WHERE PayIf = 0 AND CancelledAt IS NULL "
                + "AND CreateTime < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL " + timeoutSeconds + " SECOND), '%Y-%m-%d %H:%i:%s')";
        return jdbcClient.sql(sql).query().listOfRows();
    }

    /**
     * 取消一张「仍未付款」的单 —— **CAS**，返回影响行数（调用方判 {@code rows == 1}）。
     *
     * <p>⚠️ <b>为什么必须是 CAS 而不是「先查再写」</b>：本方法要在**没有任何锁**的前提下
     * 与付款路径竞争（付款走 {@code ORDER_PAY} 锁，取消任务不取锁 —— 取锁反而会让每单多一次
     * Redis 往返，而正确的保证本来就不该押在 fail-open 的锁上）。
     * 单条 UPDATE 把「仍是未付款」「仍未取消」两个前提与写入绑成一步 ⇒ 原子，无 TOCTOU 窗口。
     *
     * <p>⚠️ <b>与付款的互斥靠 {@code PayIf = 0}</b>：取消不改 {@code PayIf}，故**反向**不成立
     * （付款的守卫拦不住取消）—— 那半边由 {@code OrderStateMachine.updateSql(PAY)} 尾部的
     * {@code AND CancelledAt IS NULL} 补上。两条 UPDATE 各管一个方向，合起来才闭合。
     *
     * <p>{@code NOW(3)} 而非 {@code NOW()}：与 {@code CancelledAt datetime(3)} 的精度对齐
     * （同 {@code order_shipping_track.TrackedAt} 的口径）。
     */
    public int cancelIfUnpaid(String orderId) {
        String sql = "UPDATE mall_management_sql.`order` SET CancelledAt = NOW(3) "
                + "WHERE OrderId = ? AND PayIf = 0 AND CancelledAt IS NULL";
        return jdbcClient.sql(sql).param(orderId).update();
    }
}
