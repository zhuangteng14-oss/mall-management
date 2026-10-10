package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.dao.ShippingTrackDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 物流轨迹推进任务（C3 块4 补丁 A2）—— 按 {@code ShippedAt} 偏移补 {@code order_shipping_track}。
 *
 * <h2>【⚠️ 唯一职责：往 {@code order_shipping_track} **追加行** —— 绝不改 {@code order} 的状态位】</h2>
 * 用户 2026-10-10 批准的红线。本类**没有任何** {@code UPDATE order} —— 状态位仍只由三条 CAS 边推进。
 * ⇒ 由此必然产生一个**语义后果**：轨迹末条 {@code delivered}（物流「已签收」）**不代表**
 * {@code order.ReceiveIf = 1}（订单「收货」仍由买家点）。两者含义不同，前端**分两词**表述
 * （见 {@code ShippingTrackDao} 类注释 + {@code tech-debt.md}）。
 *
 * <h2>【偏移：{@code ShippedAt} + 阈值】</h2>
 * 发货时已由 {@code OrderService.deliveryIfUp} 写下首行 {@code picked_up}（偏移 0）；
 * 本任务补 {@code in_transit / out_for_delivery / delivered} 三行，{@code TrackedAt = ShippedAt + 阈值}。
 * 阈值走 properties：**演示默认分钟级**（1 / 2 / 4 分钟），生产口径（30min / 2h / 4h）只需改配置。
 *
 * <h2>【幂等：靠 {@code ShippingTrackDao.exists(orderId, status)}】</h2>
 * 每 tick 全表扫「已发货未收货」单，同一阈值会被**反复越过** ⇒ 落库前先查该节点是否已存在，
 * 有则跳过。缺这一条，每 tick 都会把同一节点重插一遍。
 *
 * <h2>【⚠️ 与 {@code MerchantDowngradeJob} 同款：整类挂 {@code @ConditionalOnProperty}】</h2>
 * A3 的并发门禁要在**毫秒级**度量下单链路，一个每 60 秒醒来的后台线程会注入**无法从门禁输出分辨**的噪声；
 * smoke 切片没有 DataSource。故 a3 / smoke 两个 profile 置 false；默认（开发机 8088）保持开启
 * （{@code matchIfMissing=true}）—— 手验调度推进要在**默认 profile**（或临时翻 flag）下做。
 *
 * <h2>【⚠️ 一条隐藏耦合：调度器总闸不在本类手里】</h2>
 * {@code @EnableScheduling} **不在启动类上**，而是挂在
 * {@code MerchantDowngradeSchedulingConfig}（那个类自身 {@code @ConditionalOnProperty(name =
 * "mall.downgrade.job.enabled", havingValue = "true", matchIfMissing = true)}）。⇒ <b>本类的
 * {@code @Scheduled} 能不能被注册，先决条件是「商家降级任务」那个名字的开关也是开的</b>：
 * <ul>
 *   <li><b>a3</b>：{@code mall.downgrade.job.enabled=false} ⇒ 调度器**根本不存在**
 *       ⇒ 本类即便 flag 忘了关也**不会跑**（两道闸，今天第二道是空转的）；</li>
 *   <li><b>默认 profile</b>：那一道开 ⇒ 本类**真的有在跑** —— 所以本类的 flag 不是装饰，
 *       是想单独关它时唯一能用的那道（改那个名字的开关会**连降级任务一起关掉**，语义不对）。</li>
 * </ul>
 * ⚠️ 反过来说：**别为了关本任务去改 {@code mall.downgrade.job.enabled}** —— 那会顺带停掉降级执行。
 */
@Component
@ConditionalOnProperty(name = "mall.order.shipping.track.enabled", havingValue = "true", matchIfMissing = true)
public class ShippingTrackAdvanceJob {

    private static final Logger log = LoggerFactory.getLogger(ShippingTrackAdvanceJob.class);

    private final OrderDao orderDao;
    private final ShippingTrackDao shippingTrackDao;

    /** 运输中阈值（发货后多久记 {@code in_transit}）。**演示默认 1 分钟**；生产口径 30min 改这里。 */
    @Value("${mall.order.shipping.track.transit-ms:60000}")
    private long transitMs;

    /** 派送中阈值。**演示默认 2 分钟**；生产口径 2h。 */
    @Value("${mall.order.shipping.track.delivery-ms:120000}")
    private long deliveryMs;

    /** 已签收阈值。**演示默认 4 分钟**；生产口径 4h。 */
    @Value("${mall.order.shipping.track.signed-ms:240000}")
    private long signedMs;

    public ShippingTrackAdvanceJob(OrderDao orderDao, ShippingTrackDao shippingTrackDao) {
        this.orderDao = orderDao;
        this.shippingTrackDao = shippingTrackDao;
    }

    /**
     * 扫描 + 补轨迹。整方法 catch 一切（同 {@code MerchantDowngradeJob.scanAndApply}：
     * 一个定时任务抛异常会让 Spring 把后续调度一起停掉）。单条失败只跳过该条，不拖垮整轮。
     */
    @Scheduled(fixedDelayString = "${mall.order.shipping.track.fixed-delay-ms:60000}")
    public void advance() {
        List<Map<String, Object>> orders;
        try {
            orders = orderDao.selectShippableForTrack();
        } catch (Exception e) {
            log.warn("物流轨迹推进：查待推进订单整轮失败 —— 下一轮重试", e);
            return;
        }
        for (Map<String, Object> one : orders) {
            try {
                advanceOne(String.valueOf(one.get("OrderId")), one.get("ShippedAt"));
            } catch (Exception e) {
                log.warn("物流轨迹推进：订单 {} 失败（跳过，下一轮重试）：{}", one.get("OrderId"), e.toString());
            }
        }
    }

    /** 单条：按 {@code ShippedAt} 偏移补 3 个节点（各带 exists 幂等判据）。 */
    private void advanceOne(String orderId, Object shippedAtRaw) {
        Long shippedMillis = toMillis(shippedAtRaw);
        if (shippedMillis == null) {
            return;     // ShippedAt 缺失（本不该发生，SQL 已过滤 IS NOT NULL）——静默跳过
        }
        long elapsed = System.currentTimeMillis() - shippedMillis;
        if (elapsed >= transitMs) {
            step(orderId, ShippingTrackDao.IN_TRANSIT, "运输中", shippedMillis + transitMs);
        }
        if (elapsed >= deliveryMs) {
            step(orderId, ShippingTrackDao.OUT_FOR_DELIVERY, "派送中", shippedMillis + deliveryMs);
        }
        if (elapsed >= signedMs) {
            step(orderId, ShippingTrackDao.DELIVERED, "已签收", shippedMillis + signedMs);
        }
    }

    /** 补一个节点：**已存在则跳过**（幂等）。{@code TrackedAt} 用算出来的偏移时刻，不是 now。 */
    private void step(String orderId, String status, String description, long trackedAtMillis) {
        if (!shippingTrackDao.exists(orderId, status)) {
            shippingTrackDao.write(orderId, status, description, trackedAtMillis);
        }
    }

    /**
     * {@code ShippedAt}（JDBC 列的取值形态随驱动不同）→ epoch 毫秒；无法识别 ⇒ {@code null}。
     * MySQL Connector/J 对 {@code datetime} 的 {@code getObject()} 多回 {@code LocalDateTime}，
     * 但保留 {@code Timestamp} / {@code Date} 分支以防换驱动。
     */
    private static Long toMillis(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp ts) {
            return ts.getTime();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (v instanceof Date d) {
            return d.getTime();
        }
        return null;
    }
}
