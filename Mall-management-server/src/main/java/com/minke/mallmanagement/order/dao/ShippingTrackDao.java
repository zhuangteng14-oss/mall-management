package com.minke.mallmanagement.order.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 订单物流轨迹（{@code order_shipping_track}，C3 块4 补丁 A2）的**唯一写入口 + 读出口**。
 *
 * <h2>【它是「过程记录」，不是「真值」】</h2>
 * 真值是 {@code order} 的三个状态位（由三条 CAS 边推进）；本表只是一条**模拟物流**的轨迹。
 * 故写失败**不影响**请求成败 —— 但它的写点在两处、都不是异步线程池：
 * <ul>
 *   <li>发货路径：{@code OrderService.casTransition} 命中后、**在同一把订单锁内**写首条 {@code picked_up}；</li>
 *   <li>调度器：{@code ShippingTrackAdvanceJob} 在调度线程按阈值补 {@code in_transit / out_for_delivery / delivered}。</li>
 * </ul>
 * 两处都**自带 try-catch**（fail-soft 由调用点保证），故本类的方法**会抛**、不做 total catch
 * —— 与 {@code OrderTimelineWriter}（跑在线程池、必须 total catch）分工不同（薄薄一层接住即可）。
 *
 * <h2>【⚠️ 调度器只追加行、绝不改 order 的状态位】</h2>
 * 用户 2026-10-10 批准的红线。本类的 {@link #write} 只 INSERT {@code order_shipping_track}，
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

    /** 落库语句。列序与参数序**必须**一一对应。 */
    private static final String INSERT_SQL = "INSERT INTO order_shipping_track "
            + "(OrderId, Status, Description, TrackedAt) VALUES (?, ?, ?, ?)";

    private final JdbcClient jdbcClient;

    public ShippingTrackDao(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 写一条轨迹。**会抛**（fail-soft 由调用点接住，见类注释）。
     *
     * @param trackedAtMillis 节点**发生**时刻（发货第一行 = 发货时刻；调度行 = ShippedAt + 阈值偏移）
     *                        —— 不是落库时刻（同 {@code order_state_transition.OccurredAt} 的理由）。
     */
    public int write(String orderId, String status, String description, long trackedAtMillis) {
        return jdbcClient.sql(INSERT_SQL)
                .params(orderId, status, description, new Timestamp(trackedAtMillis))
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
