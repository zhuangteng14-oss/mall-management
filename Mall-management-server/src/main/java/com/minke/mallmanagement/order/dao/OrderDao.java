package com.minke.mallmanagement.order.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
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
     *
     * <p>⚠️ <b>C3 块 7 · 边界补笔（2026-10-14）又加了 {@code `Source`}</b>：位置上它排在
     * {@code ReceiveIf} 之后、{@code CreateTime} **之前**（不是文件尾）—— 因为 {@code CreateTime}
     * 是 VALUES 里唯一写死 {@code NOW()} 的列、本就不占参数位，把 {@code Source} 排在它前面，
     * 「列序 = 参数序 = {@link OrderRow} 分量序」这条线性对应才不被一个非参列打断。⚠️ C3 块 7 · commit 10（2026-10-15）再加 {@code BatchId}：同款排在 {@code Source} 之后、{@code CreateTime} 之前；⚠️ 它的口径与 {@code Source} **相反**（列是 {@code varchar(36) DEFAULT NULL} ⇒ 不归一成空串、日常单落 {@code null}），归一路径与列宽截断见 {@link #clipBatchId}。
     * <p>⚠️ <b>落库前**归一**：{@code null} / 纯空白 ⇒ {@code ""}</b>（列是
     * {@code NOT NULL DEFAULT ''}）—— 口径与 {@code CommentDao.insertComment} 的 {@code Source}
     * **逐字同源**（那里也先 trim、空则落空串）。⚠️ 归一放在**这里**而不是调用方：本列是
     * 「写入方自己声明的标记」，任何调用方（含将来的）都不该因为传了个 null 就撞 NOT NULL。
     * <p>⚠️ 反引号是**刻意**加的（本仓既有 INSERT 通篇不加引号）：{@code SOURCE} 在 MySQL 8 里
     * 是非保留关键字，加引号「无论如何都安全」，且与 {@link #selectUnpaidExpired} 的投影、
     * 以及全仓 DDL / 探针 SQL 的写法一致。⚠️ 表名 {@code mall_management_sql.order} 仍**不加**引号
     * —— 那是既有形态，理由见类注释（跟随点号的词按标识符解析，保留字也无需引号），本笔不动。
     */
    public int insertOrder(OrderRow r) {
        // 第三批㉙：老签名委托到全列版本（支付/积分为默认值）—— SQL 只有一份。
        return insertOrder(r, "", java.math.BigDecimal.ZERO, 0L, java.math.BigDecimal.ZERO);
    }

    /**
     * 第三批㉙：带支付通道 / 余额支付 / 积分抵扣的完整写入。
     *
     * <p>{@code PointsSettled} 恒写 0（积分在**收货**时发放，见 {@code MemberService.earnForOrder}）；
     * 其余快照列是「这一单实际用了什么」的不可变证据，退款 / 对账都读它。
     */
    public int insertOrder(OrderRow r, String payChannel, java.math.BigDecimal walletPaidAmount,
                           long pointsUsed, java.math.BigDecimal pointsDiscount) {
        String sql = "INSERT INTO mall_management_sql.order " +
                "(OrderId, CustomerId, MerchantId, Price, Quantity, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf, `Source`, BatchId, " +
                " PayChannel, WalletPaidAmount, PointsUsed, PointsDiscount, PointsSettled, CreateTime) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())";
        return jdbcClient.sql(sql)
                .params(r.orderId(), r.customerId(), r.merchantId(), r.price(), r.quantity(),
                        r.productInformation(), r.images(),
                        r.payIf(), r.deliveryIf(), r.receiveIf(), normaliseSource(r.source()), clipBatchId(r.batchId()),
                        payChannel == null ? "" : payChannel,
                        walletPaidAmount == null ? java.math.BigDecimal.ZERO : walletPaidAmount,
                        pointsUsed,
                        pointsDiscount == null ? java.math.BigDecimal.ZERO : pointsDiscount)
                .update();
    }

    /**
     * {@code Source} 的归一：{@code null} / 纯空白 ⇒ {@code ""}，其余 {@code trim()} 后原样。
     *
     * <p>⚠️ 与 {@code CommentDao.insertComment} 里那句三元是**同一口径的两份实现**（两个域各自持有，
     * 刻意不抽公共工具）：列都是 {@code varchar(16) NOT NULL DEFAULT ''}，都不截断（超长由
     * 各自的 Service 层**拒**，见 {@code OrderService.MAX_SOURCE}）。
     */
    private static String normaliseSource(String source) {
        return source == null || source.isBlank() ? "" : source.trim();
    }

    /**
     * 下单入参的载体（12 个位置参数，用 record 免掉一个 12 参方法签名）。嵌套在此，不另立文件。
     *
     * <p>⚠️ 分量顺序 = {@link #insertOrder} 的列顺序（{@code Quantity} 在 {@code price} 之后、
     * {@code source} 在 {@code receiveIf} 之后、{@code batchId} 在 {@code source} 之后）。
     */
    public record OrderRow(String orderId, String customerId, String merchantId, float price, int quantity,
                           String productInformation, String images,
                           int payIf, int deliveryIf, int receiveIf, String source, String batchId) {
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
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate, Quantity, CancelledAt, RefundStatus, RefundRetryCount " +
                "FROM mall_management_sql.`order` WHERE CustomerId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        return jdbcClient.sql(sql).param(customerId).query().listOfRows();
    }

    /** 订单中心：某商家的未收货订单。与 {@link #selectByMerchant} 的 {@code active} 分支**逐字相同** —— 两条语句各有调用方，本笔不合并（合并会改变调用面）。 */
    public List<Map<String, Object>> selectActiveByMerchant(String merchantId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,CreateTime,SettlementDate, Quantity, CancelledAt, RefundStatus, RefundRetryCount " +
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
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate, Quantity, RefundStatus, RefundRetryCount " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf = 1 ORDER BY CreateTime DESC";
        } else {
            sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, PayIf, DeliveryIf, ReceiveIf,SettlementDate, Quantity, CancelledAt, RefundStatus, RefundRetryCount " +
                    "FROM mall_management_sql.`order` WHERE MerchantId = ? AND ReceiveIf != 1 ORDER BY CreateTime DESC";
        }
        return jdbcClient.sql(sql).param(merchantId).query().listOfRows();
    }

    /** 历史订单：某客户的已收货订单，按 {@code SettlementDate}（字符串列）倒序。 */
    public List<Map<String, Object>> selectHistoryByCustomer(String customerId) {
        String sql = "SELECT OrderId, MerchantId, ProductInformation, Images, SettlementDate, Quantity, Price, RefundStatus, RefundRetryCount, RefundReason, RefundAmount, RefundHandledAt " +
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
                + "TradeNo, PaymentMethod, PaidAt, ExpressCompany, TrackingNo, ShippedAt, ReceivedAt, "
                + "StockDeducted, RefundStatus, RefundRetryCount, RefundReason, RefundAmount, RefundApplyAt, RefundHandledAt, RefundHandler "
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
     *
     * <p>⚠️ <b>投影从「只有 {@code OrderId}」扩到「{@code OrderId} + {@code Source}」</b>
     * （C3 块 7 · commit B，2026-10-14）：{@code Source} 是「演练数据标记」（块 5 可视化段给 11 张表
     * 加的那一列，{@code mall_management_sql.sql:1167}）⇒ 它是 {@code skip-attack-data} 那条谓词
     * 两个判据之一。
     * <p>⚠️ <b>该列的写入通道已由「边界补笔」（2026-10-14）补齐</b>：{@code insertOrder} 现在写它、
     * {@code /order/orderAdd} 有可选 {@code Source} 入参、{@code AttackEngine} 下演练单时传
     * {@code 'attack'} ⇒ 本投影取到的 {@code Source} **不再恒为空串**（commit B 时它还没有写入方，
     * 谓词里 {@code Source='attack'} 那一半匹配不到行）。
     * <p>⚠️ <b>筛子**刻意留在 Java 侧**（{@link com.minke.mallmanagement.order.job.OrderTimeoutCancelJob}
     * 的 {@code isAttackData}），不在本 SQL 里</b>：那个开关是**运行时配置**（{@code @Value} 读
     * properties），进 WHERE 就得两条 SQL 各写一遍阈值比较 —— 而阈值比较是本方法唯一容易写错的东西
     * （见上一段 ⚠️）。多带一列几乎不要钱（{@code varchar(16)} × 候选条数），一条谓词只写一处。
     * <p>⚠️ 本方法**不认识**演练业务串前缀 —— 「{@code SIM-}」这个字面量在
     * {@code AttackEngine}（演练域）与 {@code OrderTimeoutCancelJob}（本域）各一份，理由写在后者。
     */
    public List<Map<String, Object>> selectUnpaidExpired(long timeoutSeconds) {
        String sql = "SELECT OrderId, `Source` FROM mall_management_sql.`order` "
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

    // ==================== 折扣商品判定（C3 块 5〈折扣商品用券规则〉） ====================

    /**
     * 本单的码里，**哪些是折扣商品**（{@code goods.OriginalPrice > 0}）。只读、只判「在不在」。
     *
     * <p>给 {@code OrderService.addLocked} 的用券段用：本单含任意一件折扣商品 ⇒ 满减 / 折扣券
     * 整单拒，{@code none}（无门槛）放行 —— 规则与理由见 {@code CouponService.redeem} 的 javadoc。
     *
     * <p>⚠️ <b>为什么要两条 SQL</b>：本单的码（{@code order_item.ProductCode} / 前端上报的
     * {@code ProductCode}）对**无型号商品**是 {@code goods.ProductCode}、对**多型号商品**是
     * {@code goods_sku.SkuCode}（建表注释 {@code mall_management_sql.sql:1071} 明文：
     * 「SKU 编码（商家填；详情页加入购物车时作为 {@code ProductCode} 上报）」）。
     * 而划线原价 {@code OriginalPrice} 加在 **{@code goods}** 上（型号件共享父商品那一份）
     * ⇒ 后一条必须经 {@code goods_sku.GoodsId} 回联父商品才读得到它。
     * 两条**不合并成 UNION**：各绑各的参数，读起来不用来回数占位符（同 {@code CartDao} 那条
     * 「两条完整 SQL、不做字符串拼 where」的理由）。
     *
     * <p>⚠️ <b>占位符是拼的、值不是</b>：{@code ?} 的**个数**按入参码数生成（{@code IN} 列表长度
     * 没法用单个 {@code ?} 表达），而每一个码都走 {@code .params(...)} **绑定** —— 与
     * {@link #selectUnpaidExpired} 那种「把数值本身拼进 SQL」不是一回事（那里拼的是来自
     * properties 的阈值）。码里含逗号也安全（不像 {@code FIND_IN_SET} 会被逗号切碎）。
     *
     * <p>⚠️ <b>不要读成「本单商品都属于该商家」</b>：这里只按 {@code MerchantId} 收窄
     * （同 {@code CartDao.selectGoodsSnapshot} 的口径 —— {@code ProductCode} 跨商家不保证唯一），
     * 「这单是不是他自己的商品」由下单链路自己管，不在本方法职责内。
     *
     * @param merchantId 本单商家业务号
     * @param codes      本单的码（可空 / 可含空串；空串与 {@code null} 一律跳过）
     * @return 命中的**码**（不是布尔）—— 调用方要拿它写日志 / 组文案；一个都没命中回空表
     */
    public List<String> selectDiscountCodes(String merchantId, Collection<String> codes) {
        List<String> marks = new ArrayList<>();
        StringBuilder in = new StringBuilder();
        if (codes != null) {
            for (String c : codes) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                if (in.length() > 0) {
                    in.append(',');
                }
                in.append('?');
                marks.add(c);
            }
        }
        if (merchantId == null || merchantId.isBlank() || marks.isEmpty()) {
            return List.of();
        }
        List<Object> byGoodsParams = new ArrayList<>();
        byGoodsParams.add(merchantId);
        byGoodsParams.addAll(marks);
        List<String> hit = new ArrayList<>(jdbcClient
                .sql("SELECT ProductCode FROM mall_management_sql.goods "
                        + "WHERE MerchantId = ? AND OriginalPrice > 0 AND ProductCode IN (" + in + ")")
                .params(byGoodsParams).query(String.class).list());
        hit.addAll(jdbcClient
                .sql("SELECT s.SkuCode FROM mall_management_sql.goods_sku s "
                        + "JOIN mall_management_sql.goods g ON g.Id = s.GoodsId "
                        + "WHERE s.MerchantId = ? AND g.OriginalPrice > 0 AND s.SkuCode IN (" + in + ")")
                .params(byGoodsParams).query(String.class).list());
        return hit;
    }

    // ==================== 读：某单的「商品码 + 数量」（C3 块 7 · 超时释放库存的输入）====================

    /**
     * 读一张单的「{@code ProductCode} + {@code Quantity}」两列 —— 订单**超时取消**时把库存按码
     * 加回去的**唯一输入**（C3 块 7 · TD-36 的释放腿，调用方 = {@code OrderTimeoutCancelJob}）。
     *
     * <p>⚠️ <b>只取这两列</b>：释放的判据是 {@code goods_sku.SkuCode}、只关心数量；单价 / 名 /
     * 小计 / 时刻都不参与 —— 多取一列就多一处将来会漂的快照（同 {@code writeItems} 只记成交事实）。
     *
     * <p>⚠️ <b>两种「空结果」都不必调用方另外兜</b>：
     * <ul>
     *   <li>该单**没有明细行**（存量单 / {@code resolveItems} 解析为空）⇒ 返回**空表**
     *       ⇒ 调用方一行不动（同 {@code writeItems} 的 fail-open）；</li>
     *   <li>明细的 {@code ProductCode} 是**空串**（解析不出商品编码时）⇒ 行照常返回，
     *       但拿它去查 {@code goods_sku} 必然 0 行 ⇒ 天然 no-op。调用方**不需要**先滤空串。</li>
     * </ul>
     */
    public List<Map<String, Object>> selectItemStocksByOrder(String orderId) {
        return jdbcClient.sql("SELECT ProductCode, Quantity FROM mall_management_sql.`order_item` "
                        + "WHERE OrderId = ?")
                .param(orderId).query().listOfRows();
    }

    // ==================== 读：五态计数 + 最近订单（C3 块 7 · 状态流转页的两条读）====================

    /**
     * 全表按**五态**计数 —— 状态流转页顶部那排计数卡的唯一输入（C3 块 7 · commit 3）。
     *
     * <p>判据链**逐条**与 {@code OrderStreamService.stateOf} 对齐（那里也写了一份，两处必须一起改）：
     * <pre>
     *   CancelledAt 非空                                    → cancelled
     *   CancelledAt IS NULL AND ReceiveIf = 1               → completed
     *   CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 1 → waitReceive
     *   CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 1 → waitShip
     *   其余（三位全 0 且未取消）                              → waitPay
     * </pre>
     * ⚠️ <b>{@code CancelledAt} 必须最先判</b>：取消的单三位仍是 {@code (0,0,0)}，
     * 与「未付款」**完全相同**（见 {@code OrderTimeoutCancelJob} 类注释）——
     * 先判三位就会把取消的单算进「待付款」。
     *
     * <p>⚠️ 数值型列（{@code PayIf} 等）统一用 {@code = 1} 判「是」：与
     * {@code AdminOrderDao.selectMonitor} 的既有写法一致（不写 {@code COALESCE}，
     * 也不假设它们非空 —— {@code NULL} 落进「否则」分支，与 Java 侧 {@code isOne()} 同）。
     *
     * <p>外层的 {@code COALESCE} 是给**空表**兜底：没有行的表只应回 5 个 0。
     */
    public Map<String, Object> countOrderStates() {
        return countOrderStates(false);
    }

    /**
     * 五态计数的**可选口径版**（2026-10-17，状态流转页「剔除已取消 / 仅看真实数据」开关）。
     *
     * <p>{@code healthyOnly=true} ⇒ 扫描范围先按 {@link OrderDataScope#HEALTHY_SCOPE_SQL}
     * 收窄：排除演练数据（{@code SIM-%} 前缀 **OR** {@code Source='attack'}）**且**排除已取消单。
     * 判据链本身**一字不动**：过滤发生在扫描范围，不参与五态判定 —— 两件事不能混
     * （把过滤写进 CASE 会让每条分支都多一个条件，三处镜像更难对齐）。
     *
     * <p>⚠️ 谓词只有 {@link OrderDataScope} 一处定义：超时取消 job、状态流转页、清场端点
     * 用**同一条**口径。前端不给这个开关传值时用无参重载（全量）。
     */
    public Map<String, Object> countOrderStates(boolean healthyOnly) {
        String sql = "SELECT "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS cancelled, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 1 THEN 1 ELSE 0 END), 0) AS completed, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 1 THEN 1 ELSE 0 END), 0) AS waitReceive, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 1 THEN 1 ELSE 0 END), 0) AS waitShip, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 0 THEN 1 ELSE 0 END), 0) AS waitPay, "
                + "COUNT(*) AS total "
                + "FROM mall_management_sql.`order`"
                + (healthyOnly ? " WHERE " + OrderDataScope.HEALTHY_SCOPE_SQL : "");
        return jdbcClient.sql(sql).query().listOfRows().stream().findFirst().orElse(Map.of());
    }

    /**
     * 最近 {@code limit} 张单 —— 状态流转页「实时订单列表」的唯一输入（C3 块 7 · commit 3）。
     *
     * <p>排序用 {@code CreateTime DESC, OrderId DESC}：{@code CreateTime} 是字符串列
     * （ISO 式零填充，同 {@code selectOrders} 的既有口径），同秒下单时靠 OrderId 保证**顺序稳定**
     * （否则同一拍两次读可能给出不同的行序，页面上的列表会无理由地抖）。
     *
     * <p>只取页面要展示的列：{@code ProductInformation} 是列表上的商品名，
     * {@code Price} / {@code Quantity} 是金额与件数；状态由 {@code PayIf/DeliveryIf/ReceiveIf}
     * + {@code CancelledAt} 现算（**不取** {@code SettlementDate} 等本页用不到的列）。
     *
     * <p>⚠️ {@code limit} 由调用方给（{@code OrderStreamService.RECENT_LIMIT} = 20），
     * 本方法**不再夹**一个上限：这里是「页面要几行就给几行」的读，与
     * {@code AdminOrderService} 那套分页截断（{@code clampLimit}）不是一回事。
     */
    public List<Map<String, Object>> selectRecentOrders(int limit) {
        return selectRecentOrders(limit, false);
    }

    /**
     * 最近 {@code limit} 张单的**可选口径版**（2026-10-17，状态流转页「剔除已取消 / 仅看真实数据」）。
     *
     * <p>{@code healthyOnly=true} ⇒ 与 {@link #countOrderStates(boolean)} 用**同一条**
     * {@link OrderDataScope#HEALTHY_SCOPE_SQL}：趋势、耗时、账龄、健康度、明细若各走一套过滤，
     * 同一张图上的分母与点会互相打架。
     */
    public List<Map<String, Object>> selectRecentOrders(int limit, boolean healthyOnly) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, Quantity, ProductInformation, "
                + "PayIf, DeliveryIf, ReceiveIf, CancelledAt, CreateTime, PaidAt, ShippedAt, ReceivedAt "
                + "FROM mall_management_sql.`order`"
                + (healthyOnly ? " WHERE " + OrderDataScope.HEALTHY_SCOPE_SQL : "")
                + " ORDER BY CreateTime DESC, OrderId DESC LIMIT ?";
        return jdbcClient.sql(sql).param(limit).query().listOfRows();
    }

    // ==================== 写 / 读：本单库存「真扣成过」标记（C3 块 7 · commit 2d · Migration ⑱）====================

    /**
     * 标一条单「**本单的 {@code goods_sku} 库存真扣成过**」（{@code StockDeducted = 1}）——
     * 订单**超时取消**时决定要不要回补库存的**唯一判据**（C3 块 7 · TD-36 的第四条腿）。
     *
     * <p>⚠️ <b>为什么不是 {@code insertOrder} 列清单里的第 13 列、而是建单之后再 UPDATE 一句</b>：
     * INSERT 发生在 {@code addLocked} 里「订单落库」那一刻，而「扣成没扣成」要等
     * {@code deductStocks} 跑完才知道 ⇒ <b>INSERT 那一刻这个值还不存在</b>。两条路：
     * （a）先扣后建单（值就能随 INSERT 一起落库）；（b）先建单后扣、再补这一句。
     * 取 (b)：块 7 commit 1 已把「下单即扣」**排在落库与明细之后**（那条顺序有自己的理由 ——
     * 明细与扣减吃同一批 specs、且都不许改订单结论），本笔**不动那个顺序**，
     * 代价是每张真扣过库存的单多一句单行 UPDATE（{@code PRIMARY} 之外无索引，按 OrderId 走全表扫，
     * 演示库量级可忽略）。
     *
     * <p>⚠️ {@code AND StockDeducted = 0} 让本方法**幂等**（同 {@code cancelIfUnpaid} 的写法）：
     * 已经是 1 就回 {@code 0}，调用方不必先读、也不必判重。
     */
    public int markStockDeducted(String orderId) {
        String sql = "UPDATE mall_management_sql.`order` SET StockDeducted = 1 "
                + "WHERE OrderId = ? AND StockDeducted = 0";
        return jdbcClient.sql(sql).param(orderId).update();
    }

    /**
     * 读一条单的 {@code StockDeducted}（{@code 0} / {@code 1}）—— 超时释放的前置判据
     * （唯一调用方 {@code OrderTimeoutCancelJob#releaseStock}）。
     *
     * <p>⚠️ <b>查无此行 ⇒ 回 {@code 0}</b>（=「没扣过」，与列默认值同义）：那单要么不存在、
     * 要么是本列加列之前的**存量单** —— 两种都**不该回补**（存量单的库存本来就没动过，
     * 回补就是凭空加库存，正是 2d 要堵的那个偏账）。故这里**不抛**「找不到订单」：
     * 调用方要的就是一个「要不要回补」的判据。
     */
    public int selectStockDeducted(String orderId) {
        return jdbcClient.sql("SELECT StockDeducted FROM mall_management_sql.`order` "
                        + "WHERE OrderId = ? LIMIT 1")
                .param(orderId).query(Integer.class).optional().orElse(0);
    }

    // ==================== 写 / 读：演练批次号（C3 块 7 · commit 10 · Q3 路线 i）====================
    //
    // ⚠️ 两个新成员**刻意追加在文件尾**（不是插在 insertOrder 旁边）：全仓大量
    // 「文件:行号」互引，插在中间会让其后每一处引用集体偏移。本仓既有纪律是
    // 「新增代码追加在最大被引用行之后」——这两个成员在上面的 `selectStockDeducted` 之后。

    /** 列宽上限 —— 与 ⑲-3 的 DDL（{@code varchar(36)}）以及 {@code ObservedEventDao.BATCH_ID_MAX} 对齐，两处一起改。 */
    private static final int BATCH_ID_MAX = 36;

    /**
     * {@code BatchId} 的归一：**只截断、不补空**（与 {@link #normaliseSource} **相反**）。
     *
     * <h2>【为什么不像 {@code Source} 那样「空 ⇒ 空串」】</h2>
     * 列是 {@code varchar(36) DEFAULT NULL} ⇒ 「没有批次」在语义上就是 {@code null}，
     * 与「批次是个空串」不是一回事。日常请求（不带 {@code X-Drill-Batch} 头）正是前者
     * （{@code TraceIdFilter.currentBatch()} 此时回 {@code null}）⇒ 本方法对 {@code null} 原样放行。
     *
     * <h2>【为什么要截断】</h2>
     * 值的来源是请求头 {@code X-Drill-Batch}，它的白名单是 {@code [A-Za-z0-9_-]{8,64}}
     * （{@code TraceIdFilter.ACCEPTED}）—— <b>最长 64，比列宽 36 长</b>。
     * MySQL 严格模式下超长是**报错**（不是静默截断）⇒ 会把整条下单打死。故此处按列宽截断。
     * <p>⚠️ 与 {@code ObservedEventDao.clip} 是**同一手法**（那里也用 36）：
     * 「列宽是本层的责任，不是调用方的」这条口径同源。截断会悄无声息丢尾巴，这是取舍。
     */
    private static String clipBatchId(String batchId) {
        return batchId == null || batchId.length() <= BATCH_ID_MAX
                ? batchId : batchId.substring(0, BATCH_ID_MAX);
    }

    /**
     * 按**演练批次**的五态计数 —— 状态流转页「本批次」口径的唯一输入（C3 块 7 · commit 10。
     * 设计出处见 {@code 块7-commit10-阶段一续-后端改动面.txt} §3.4「路线 i」）。
     *
     * <p>与 {@link #countOrderStates} **同形**（判据链逐条相同），只多一句 {@code WHERE BatchId = ?}
     * ⇒ 那两个方法里「{@code CancelledAt} 必须最先判」的理由在这里**同样成立**，三处必须一起改。
     *
     * <p>⚠️ <b>只能数到 commit 10 之后落的单</b>：{@code order.BatchId} 这列在 Migration ⑲-3
     * 就建好了，但**写入链路是 commit 10 才接的** —— 此前它恒为 {@code NULL}
     * （见 {@code db-schema.md §16.2}）⇒ 存量单永远不进任何批次，这是**预期**不是缺陷。
     *
     * <p>⚠️ <b>批次号必须来自服务端</b>（{@code TraceIdFilter.currentBatch()} 读的 MDC），
     * 不得由任何调用方或客户端显式传入 —— 否则谁都能按别人的批次号查库。
     */
    public Map<String, Object> countOrderStatesByBatch(String batchId) {
        String sql = "SELECT "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS cancelled, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 1 THEN 1 ELSE 0 END), 0) AS completed, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 1 THEN 1 ELSE 0 END), 0) AS waitReceive, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 1 THEN 1 ELSE 0 END), 0) AS waitShip, "
                + "COALESCE(SUM(CASE WHEN CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 0 THEN 1 ELSE 0 END), 0) AS waitPay, "
                + "COUNT(*) AS total "
                + "FROM mall_management_sql.`order` WHERE BatchId = ?";
        return jdbcClient.sql(sql).param(batchId).query().listOfRows().stream().findFirst().orElse(Map.of());
    }

    // ==================== 读：全局流转明细的服务端分页（2026-10-17 · 用户 P0）====================
    //
    // 背景：此前状态流转页的明细表吃的是 insight 端点的「最近 1000 行」，前端再按状态**本地过滤**
    // ⇒ 顶部 Tab 是全库真实 COUNT（几万），表却只有 1000 行的子集；点「待发货 2271」自然空表。
    // 用户明令：「绝对不能在前端拿全部最新 1000 条去自己过滤」。故计数与取行都下沉到这里，
    // 且**两处共用同一个 Where 片段**（pageWhere）—— 分页脚的 total 与行集不可能各说各话。

    /** 待发货超时阈值（小时）。**与 {@code OrderStreamService.timeoutHoursOf} 共用这一份值**，防两处漂移。 */
    public static final int WAIT_SHIP_TIMEOUT_HOURS = 24;

    /** 待收货超时阈值（小时）。同上。 */
    public static final int WAIT_RECEIVE_TIMEOUT_HOURS = 48;

    /**
     * 某筛选下的**真实总行数**（分页脚与「全部 N」的唯一来源）。
     *
     * <p>⚠️ {@code status} 为空白 = 全部；未知状态 ⇒ {@code 1 = 0}（返回 0，不抛错）。
     * {@code timeout=true} 时只数「待发货 &gt; 24h / 待收货 &gt; 48h」的预警行。
     */
    public int countOrderPage(boolean healthyOnly, String status, boolean timeout) {
        String sql = "SELECT COUNT(*) FROM mall_management_sql.`order`" + pageWhere(healthyOnly, status, timeout);
        Integer n = jdbcClient.sql(sql).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /**
     * 某筛选下的一页明细。排序与 {@link #selectRecentOrders(int, boolean)} **逐字相同**
     * （{@code CreateTime DESC, OrderId DESC}）⇒ 翻页时同一行不会在页间漂移。
     *
     * <p>⚠️ 只接受**已消毒**的 {@code offset >= 0} / {@code limit >= 1}（服务的职责在
     * {@code OrderStreamService.detail}，这里不再夹一遍 —— 夹两次会让两层的上限各说各话）。
     */
    public List<Map<String, Object>> selectOrderPage(boolean healthyOnly, String status, boolean timeout,
                                                     int offset, int limit) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, Quantity, ProductInformation, "
                + "PayIf, DeliveryIf, ReceiveIf, CancelledAt, CreateTime, PaidAt, ShippedAt, ReceivedAt "
                + "FROM mall_management_sql.`order`" + pageWhere(healthyOnly, status, timeout)
                + " ORDER BY CreateTime DESC, OrderId DESC LIMIT ? OFFSET ?";
        return jdbcClient.sql(sql).param(limit).param(offset).query().listOfRows();
    }

    /**
     * 三个过滤维度的**唯一**拼装点：健康口径 → 状态 → 超时。
     *
     * <p>⚠️ 顺序不是随意的：先收窄扫描范围（健康口径），再叠状态与超时；
     * 每段都加括号，避免 {@code OR} 把健康口径短路掉。
     *
     * <p>⚠️ 状态未知或「超时口径不适用于该状态」（待付款 / 已完成 / 已取消）⇒ 直接
     * {@code 1 = 0}。这不是保守，是**如实**：那几态本来就没有 24/48h 预警的定义。
     */
    private static String pageWhere(boolean healthyOnly, String status, boolean timeout) {
        String safeStatus = status == null ? "" : status.trim();
        String state = stateCondition(safeStatus);
        if (state != null && "1 = 0".equals(state)) {
            return " WHERE 1 = 0";
        }
        String timeoutCond = null;
        if (timeout) {
            timeoutCond = timeoutCondition(safeStatus);
            if (timeoutCond == null) {
                return " WHERE 1 = 0";
            }
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (healthyOnly) {
            where.append(" AND (").append(OrderDataScope.HEALTHY_SCOPE_SQL).append(")");
        }
        if (state != null) {
            where.append(" AND (").append(state).append(")");
        }
        if (timeoutCond != null) {
            where.append(" AND (").append(timeoutCond).append(")");
        }
        return where.toString();
    }

    /**
     * 五态判据的 **SQL 镜像** —— 与 {@link #countOrderStates(boolean)} 的 CASE 链逐条对齐，
     * 也与 {@code OrderStreamService.stateOf} 对齐（三处一起改）。
     *
     * @return {@code null} = 不加状态过滤（空串）；{@code 1 = 0} = 未知状态
     */
    private static String stateCondition(String status) {
        if (status.isEmpty()) {
            return null;
        }
        switch (status) {
            case "waitPay":
                return "CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 0";
            case "waitShip":
                return "CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 0 AND PayIf = 1";
            case "waitReceive":
                return "CancelledAt IS NULL AND ReceiveIf = 0 AND DeliveryIf = 1";
            case "completed":
                return "CancelledAt IS NULL AND ReceiveIf = 1";
            case "cancelled":
                return "CancelledAt IS NOT NULL";
            default:
                return "1 = 0";
        }
    }

    /**
     * 超时预警判据（只有待发货 / 待收货两态有定义）。
     *
     * <p>⚠️ 时刻全部由**库时钟**算（与 {@code OrderTimeoutCancelJob} 同一纪律）：
     * {@code DATE_FORMAT} 把时间列压成 {@code yyyy-MM-dd HH:mm:ss} 文本再比，避免
     * {@code serverTimezone} 在 JDBC 层掺进一次折算。
     *
     * <p>停留起点与 {@code OrderStreamService.stateSince} 对齐：待发货从 {@code PaidAt}（缺失回
     * {@code CreateTime}），待收货从 {@code ShippedAt}（缺失依次回 {@code PaidAt} / {@code CreateTime}）。
     *
     * @return {@code null} = 该状态下没有超时口径
     */
    private static String timeoutCondition(String status) {
        String shipSince = "COALESCE(DATE_FORMAT(PaidAt, '%Y-%m-%d %H:%i:%s'), CreateTime)";
        String shipTimeout = shipSince + " < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL "
                + WAIT_SHIP_TIMEOUT_HOURS + " HOUR), '%Y-%m-%d %H:%i:%s')";
        String receiveSince = "COALESCE(DATE_FORMAT(ShippedAt, '%Y-%m-%d %H:%i:%s'), "
                + "DATE_FORMAT(PaidAt, '%Y-%m-%d %H:%i:%s'), CreateTime)";
        String receiveTimeout = receiveSince + " < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL "
                + WAIT_RECEIVE_TIMEOUT_HOURS + " HOUR), '%Y-%m-%d %H:%i:%s')";
        switch (status) {
            case "":
                // 全部：两态各自的「状态判据 AND 自己的超时判据」再取并 —— 不能只取超时半句，
                // 否则待收货的 48h 判据会把待发货的行也捞进来（反之亦然）。
                return "((" + stateCondition("waitShip") + ") AND (" + shipTimeout + "))"
                        + " OR ((" + stateCondition("waitReceive") + ") AND (" + receiveTimeout + "))";
            case "waitShip":
                return shipTimeout;
            case "waitReceive":
                return receiveTimeout;
            default:
                return null;
        }
    }

    // ==================== 读：智能客服（chat 域，只读） ====================

    /**
     * 客服：某客户最近的订单（含已完成 / 已取消），按下单时间倒序。
     *
     * <p>⚠️ {@code CreateTime} 是 {@code varchar(50)}（{@code db-schema.md#db-6-8}），排序按字典序；
     * 种子/生产格式统一为 {@code yyyy-MM-dd HH:mm:ss} 时与时间序等价，再用自增 {@code Id} 兜同秒。
     * 本方法**只读**，不参与任何状态流转。
     */
    public List<Map<String, Object>> selectRecentByCustomer(String customerId, int limit) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, "
                + "PayIf, DeliveryIf, ReceiveIf, CreateTime, SettlementDate, Quantity, CancelledAt, "
                + "RefundStatus, RefundRetryCount, RefundReason, RefundAmount, RefundApplyAt, "
                + "RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` WHERE CustomerId = ? "
                + "ORDER BY CreateTime DESC, Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(customerId, limit).query().listOfRows();
    }

    /** 客服：某客户的订单总数（用于「你最近有 N 笔订单」）。 */
    public long countByCustomer(String customerId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.`order` WHERE CustomerId = ?")
                .param(customerId)
                .query(Long.class)
                .single();
    }

    /** 客服：按单号查该客户名下的订单；不存在 / 不是他的单 ⇒ {@code null}。 */
    public Map<String, Object> selectByOrderIdAndCustomer(String customerId, String orderId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, "
                + "PayIf, DeliveryIf, ReceiveIf, CreateTime, SettlementDate, Quantity, CancelledAt, "
                + "RefundStatus, RefundRetryCount, RefundReason, RefundAmount, RefundApplyAt, "
                + "RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` WHERE CustomerId = ? AND OrderId = ? LIMIT 1";
        return jdbcClient.sql(sql).params(customerId, orderId).query().listOfRows()
                .stream().findFirst().orElse(null);
    }

    /** 客服：某商家最近的订单（角色 2 的「查订单」走这里）。 */
    public List<Map<String, Object>> selectRecentByMerchant(String merchantId, int limit) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, "
                + "PayIf, DeliveryIf, ReceiveIf, CreateTime, SettlementDate, Quantity, CancelledAt, "
                + "RefundStatus, RefundRetryCount, RefundReason, RefundAmount, RefundApplyAt, "
                + "RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` WHERE MerchantId = ? "
                + "ORDER BY CreateTime DESC, Id DESC LIMIT ?";
        return jdbcClient.sql(sql).params(merchantId, limit).query().listOfRows();
    }

    /** 客服：某商家的订单总数。 */
    public long countByMerchant(String merchantId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM mall_management_sql.`order` WHERE MerchantId = ?")
                .param(merchantId)
                .query(Long.class)
                .single();
    }

    /** 客服：按单号查该商家名下的订单。 */
    public Map<String, Object> selectByOrderIdAndMerchant(String merchantId, String orderId) {
        String sql = "SELECT OrderId, CustomerId, MerchantId, Price, ProductInformation, Images, "
                + "PayIf, DeliveryIf, ReceiveIf, CreateTime, SettlementDate, Quantity, CancelledAt, "
                + "RefundStatus, RefundRetryCount, RefundReason, RefundAmount, RefundApplyAt, "
                + "RefundHandledAt, RefundHandler "
                + "FROM mall_management_sql.`order` WHERE MerchantId = ? AND OrderId = ? LIMIT 1";
        return jdbcClient.sql(sql).params(merchantId, orderId).query().listOfRows()
                .stream().findFirst().orElse(null);
    }
}
