package com.minke.mallmanagement.order.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 订单物流轨迹（{@code order_shipping_track}，C3 块4 补丁 A2）的**唯一写入口 + 读出口**。
 *
 * <h2>【它是「过程记录」，不是「真值」】</h2>
 * 真值是 {@code order} 的三个状态位（由三条 CAS 边推进）；本表只是一条**模拟物流**的轨迹。
 * 故写失败**不影响**请求成败 —— 两个写点（都在进程内、都不是异步线程池）：
 * <ul>
 *   <li>收货兜底：{@code OrderService.receiveIfUp} 命中后、**在同一把订单锁内**补末行 {@code delivered}；</li>
 *   <li>调度器：{@code ShippingTrackAdvanceJob} 在调度线程按阈值补**全部 4 个节点**
 *       （{@code picked_up / in_transit / out_for_delivery / delivered}）。</li>
 * </ul>
 * 两处都**自带 try-catch**（fail-soft 由调用点保证），故本类的方法**会抛**、不做 total catch
 * —— 与 {@code OrderTimelineWriter}（跑在线程池、必须 total catch）分工不同（薄薄一层接住即可）。
 *
 * <p>⚠️ <b>2026-10-10 起，发货路径**不再**写轨迹</b>：揽收节点 {@code picked_up} 已移交调度器（发货 + 30 秒；⚠️ 2026-10-11 起它是**第 2 个**节点，首条是 {@code in_transit}「运输中」），
 * 理由与判据见 {@code ShippingTrackAdvanceJob} 类注释。别在 {@code OrderService.deliveryIfUp} 里把它加回来
 * —— 那会让「揽收」重新变成「发货的瞬时副作用」，而揽收是**物流方**（驿站）的动作、不是商家的。
 *
 * <h2>【⚠️ 时刻一律由**库时钟**产生 —— 本类不接收任何 Java 时间值】</h2>
 * 用户 2026-10-10 报「同一条轨迹 22:27:51、订单列表 14:29:16，差 8 小时」之后的统一口径。
 * 两个写口各一条语句，值**全程不离开 MySQL**：
 * <ul>
 *   <li>{@link #write}：{@code TrackedAt = NOW(3)}（落库时刻即发生时刻）；</li>
 *   <li>{@link #writeAtShippedOffset}：{@code TrackedAt = DATE_ADD(?, INTERVAL ? SECOND)}，
 *       基准 {@code ?} 是 {@code OrderDao.selectShippableForTrack} 交回的 {@code ShippedAt} **文本**。</li>
 * </ul>
 * <b>为什么不让 Java 传时间</b>：库时钟是 <b>UTC</b>（VM / a3 实测 {@code @@system_time_zone=UTC}、
 * {@code NOW()} 与机器 UTC 同步），而 JDBC URL 带着 {@code serverTimezone=Asia/Shanghai}；{@code java.sql.Timestamp}
 * 是**瞬时点**、驱动必须按连接时区折成墙钟，于是落库整整 <b>+8 小时</b> —— 实测同一时刻写的两列：
 * {@code TrackedAt = 22:27:51}（走 {@code new Timestamp}）而 {@code created_at = 14:27:51}
 * （走 {@code DEFAULT CURRENT_TIMESTAMP(3)}，库时钟）。三条安全路是 {@code NOW(3)}、{@code DATE_ADD}、
 * 以及**无时区的文本**参数 —— 它们一个都不带时区信息。⚠️ 别把参数换回 {@code Timestamp}/{@code LocalDateTime}。
 *
 * <h2>【⚠️ 调度器只追加行、绝不改 order 的状态位】</h2>
 * 用户 2026-10-10 批准的红线。本类只 INSERT {@code order_shipping_track}，
 * 全类**没有任何 UPDATE {@code order}** 的语句 —— 这是「物流签收 ≠ 订单收货」得以成立的前提。
 *
 * <h2>【SQL 不带 schema 前缀 —— 新 DAO 口径】</h2>
 * 同 {@code AdminOrderDao} / {@code CartDao} / {@code TemplateDao}。
 * ⚠️ 别照抄 {@code OrderDao} 的 {@code mall_management_sql.} 前缀（那是 F#1 存量债）。
 */
@Repository
public class ShippingTrackDao {

    /** 轨迹节点码（有界，4 个）——与建表 `COMMENT` 同步。 */
    public static final String PICKED_UP = "picked_up";
    public static final String IN_TRANSIT = "in_transit";
    public static final String OUT_FOR_DELIVERY = "out_for_delivery";
    public static final String DELIVERED = "delivered";

    /** 落库语句（**发生时刻 = 落库时刻**，由库时钟出）。列序与参数序**必须**一一对应。 */
    private static final String INSERT_SQL = "INSERT INTO order_shipping_track "
            + "(OrderId, Status, Description, TrackedAt) VALUES (?, ?, ?, NOW(3))";

    /**
     * 落库语句（**发生时刻 = 发货时刻 + 偏移**，前两个偏移量、末一个基准时刻，全在 MySQL 里算）。
     *
     * <p>⚠️ 第 1 个 {@code ?} 收的是**文本**（{@code 'YYYY-MM-DD HH:mm:ss'}），MySQL 直接把它当字面量存
     * —— 字符串不带时区，驱动**无从折算**（这正是本类与 {@code Timestamp} 分家的原因，见类注释）。
     * 它必须是 {@code DATE_FORMAT(ShippedAt, '%Y-%m-%d %H:%i:%s')} 的产物（库时钟 UTC），
     * 不是前端那套「已转本地」的文本。
     */
    private static final String INSERT_AT_OFFSET_SQL = "INSERT INTO order_shipping_track "
            + "(OrderId, Status, Description, TrackedAt) VALUES (?, ?, ?, DATE_ADD(?, INTERVAL ? SECOND))";

    private final JdbcClient jdbcClient;

    public ShippingTrackDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 写一条「此刻」发生的轨迹（{@code TrackedAt = NOW(3)}）。**会抛**（fail-soft 由调用点接住）。
     *
     * <p>⚠️ 发生时刻与落库时刻在此**合二为一** —— 与 {@code order_state_transition.OccurredAt}
     * （调用方传瞬时点、刻意区分两者）口径不同。收货兜底那条线用不到这个区分：它就是「买家点收货的此刻」。
     */
    public int write(String orderId, String status, String description) {
        return jdbcClient.sql(INSERT_SQL)
                .params(orderId, status, description)
                .update();
    }

    /**
     * 写一条「发货 + 偏移」发生的轨迹 —— {code picked_up / in_transit / out_for_delivery / delivered}
     * 四个节点共用它（**同一套偏移机制**，用户 2026-10-10 的要求）。
     *
     * <p>时刻在 SQL 里由 {@code DATE_ADD(shippedAtText, INTERVAL offsetSeconds SECOND)} 算出：
     * 基准是**库里的 {@code ShippedAt} 文本**（UTC 墙钟），偏移是整数秒 ⇒ 结果与 {@code ShippedAt}
     * 同处一条时间轴，**不经过 JVM、也不经过驱动**。
     *
     * @param shippedAtText  该单的发货时刻，{@code OrderDao.selectShippableForTrack} 的 {@code ShippedAtText}
     * @param offsetSeconds  相对发货时刻的偏移秒数（0 亦合法 —— 首行 {@code picked_up} 演示口径是 30 秒）
     */
    public int writeAtShippedOffset(String orderId, String status, String description,
                                    String shippedAtText, long offsetSeconds) {
        return jdbcClient.sql(INSERT_AT_OFFSET_SQL)
                .params(orderId, status, description, shippedAtText, offsetSeconds)
                .update();
    }

    /**
     * 该单**是否已有**某节点 —— 调度器的**幂等判据**：已记过的节点不重插
     * （调度器每 tick 全表扫「已发货未收货」单，同一阈值会被反复越过）。
     */
    public boolean exists(String orderId, String status) {
        return jdbcClient.sql("SELECT COUNT(*) FROM order_shipping_track WHERE OrderId = ? AND Status = ?")
                .params(orderId, status).query(Integer.class).single() > 0;
    }

    /**
     * 查某一单的轨迹，按**发生时刻**升序（{@code TrackedAt}，**不是** {@code Id}——同单内并发 INSERT 的
     * {@code Id} 不保证与发生顺序一致，同 {@code order_state_transition} 的排序口径）。
     */
    public List<Map<String, Object>> selectByOrderId(String orderId) {
        return jdbcClient.sql("SELECT Status, Description, TrackedAt FROM order_shipping_track "
                + "WHERE OrderId = ? ORDER BY TrackedAt ASC, Id ASC")
                .param(orderId).query().listOfRows();
    }
}
