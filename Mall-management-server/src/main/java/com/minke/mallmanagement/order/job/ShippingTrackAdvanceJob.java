package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.order.dao.OrderDao;
import com.minke.mallmanagement.order.dao.ShippingTrackDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * <h2>【偏移：{@code ShippedAt} + 阈值 —— 今日**四个节点全在本类**】</h2>
 * 阈值走 properties，**演示默认秒级**（15s / 30s / 45s / 60s，2026-10-10 由 30/60/120/240 秒收紧），
 * 生产口径（15min / 30min / 2h / 4h）只需改配置。四个节点**同一套机制**，逐条偏移：
 * <table border="1">
 *   <tr><th>时序</th><th>节点码</th><th>文案</th><th>偏移</th><th>阈值 key（默认）</th></tr>
 *   <tr><td>1</td><td>{@code in_transit}</td><td>运输中</td><td>+15 秒</td><td>{@code transit-ms}（15000）</td></tr>
 *   <tr><td>2</td><td>{@code picked_up}</td><td>驿站已揽收</td><td>+30 秒</td><td>{@code picked-up-ms}（30000）</td></tr>
 *   <tr><td>3</td><td>{@code out_for_delivery}</td><td>驿站派送中</td><td>+45 秒</td><td>{@code delivery-ms}（45000）</td></tr>
 *   <tr><td>4</td><td>{@code delivered}</td><td>已签收</td><td>+60 秒</td><td>{@code signed-ms}（60000）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>2026-10-11（用户手验后改文案 + 调前两步顺序）：时序现在是「运输中 → 驿站已揽收 → 驿站派送中 → 已签收」</b>。
 * 用户口径逐字：「只改文案字符串 + 偏移阈值的顺序；不涉及新状态；{@code @Scheduled} 的时间偏移映射跟着换」。
 * 叙事是**干线在前、到站入库在后**：先「运输中」（干线在途），再「驿站已揽收」（到达目的驿站入库），
 * 再到「驿站派送中」，最后「已签收」。⇒ <b>改的是「节点码 ↔ 阈值 ↔ 文案」的配对，不是只换字符串</b>：
 * {@code in_transit} 拿到 15 秒、{@code picked_up} 拿到 30 秒（两者的<b>默认阈值对调</b>）。
 * <b>为什么整对调而不是只换文案</b>：{@code order_shipping_track.Status} 是**会显示**的
 * （管理端订单详情 `admin/OrderPage.vue` 直接打这个码）⇒ 只换文案会造出
 * 「{@code picked_up} | 运输中」这种自相矛盾的行。
 * ⚠️ <b>存量行不改写</b>（同本类其余口径：轨迹是过程记录）—— 手验看**新发的单**。
 *
 * <p>⚠️ <b>2026-10-10（用户手验报）改判两处，别改回去</b>：
 * <ol>
 *   <li><b>首行 {@code picked_up} 从发货路径**搬进本类**</b>。用户原话：「揽收不该在发货时立即生成，
 *       应等发货后 30 秒由 {@code @Scheduled} 生成」。判据是**语义**：揽收是**物流方（驿站）**的动作、
 *       不是商家的 —— 商家点「发货」那一刻能确定的事实只有「交给快递了」，揽收要再等一等。
 *       改前它写在 {@code OrderService.deliveryIfUp} 里（同锁内、偏移 0）⇒ 轨迹第一行与发货时刻
 *       **一秒不差**，看着就像商家自己盖的章。</li>
 *   <li><b>文案「商家已揽收」→「驿站已揽收」</b>。理由同上一条：动作主体是驿站。文案是**落库文本**
 *       （{@code Description} 列），前端只负责显示 ⇒ 改这里即可，前端一个字不用动。
 *       ⚠️ 存量行（2026-10-10 之前发的单）里的「商家已揽收」**不会**被改写 —— 轨迹是过程记录，
 *       不回溯改历史（同「逻辑废弃、不 UPDATE」的口径）。手验看**新发的单**。</li>
 * </ol>
 *
 * <h2>【⚠️ 时刻：本类不构造任何时间点，全部由库时钟算】</h2>
 * 2026-10-10 用户报「轨迹 22:27:51 vs 订单列表 14:29:16，差 8 小时」之后的统一口径。
 * {@code OrderDao.selectShippableForTrack} 交回的是 {@code ShippedAt} 的**文本**与
 * {@code TIMESTAMPDIFF} 的**整数秒**；{@code ShippingTrackDao.writeAtShippedOffset} 用
 * {@code DATE_ADD(文本, INTERVAL 秒 SECOND)} 算出 {@code TrackedAt}。
 * <b>整条路上没有任何 Java 时间对象</b>（改前是 {@code toMillis(ShippedAt)} + {@code System.currentTimeMillis()}
 * + {@code new Timestamp}，那条路上 {@code serverTimezone=Asia/Shanghai} 会掺进一次折算 ⇒ 落库 +8 小时）。
 * 详见 {@code ShippingTrackDao} 类注释。⚠️ 别图省事把 {@code toMillis} 加回来。
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
 *       ⇒ 本类即便 flag 忘了关也**不会跑**（两道闸，今天第二道是空转的）。
 *       ⚠️ <b>2026-10-10 起这条更要紧</b>：首行 {@code picked_up} 也归本类 ⇒ <b>a3 下新发的单
 *       <em>一条轨迹都不会有</em></b>（发货时不再写、本类又不跑）。看轨迹必须用默认 profile。</li>
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

    /** 运输中阈值（发货后多久记 {@code in_transit}）。**演示默认 15 秒**；生产口径 30min 改这里。 */
    @Value("${mall.order.shipping.track.transit-ms:15000}")
    private long transitMs;

    /** 驿站已揽收阈值（发货后多久记 {@code picked_up}）。**演示默认 30 秒**；生产口径 15min 改这里。 */
    @Value("${mall.order.shipping.track.picked-up-ms:30000}")
    private long pickedUpMs;

    /** 驿站派送中阈值。**演示默认 45 秒**；生产口径 2h。 */
    @Value("${mall.order.shipping.track.delivery-ms:45000}")
    private long deliveryMs;

    /** 已签收阈值。**演示默认 60 秒**；生产口径 4h。 */
    @Value("${mall.order.shipping.track.signed-ms:60000}")
    private long signedMs;

    public ShippingTrackAdvanceJob(OrderDao orderDao, ShippingTrackDao shippingTrackDao) {
        this.orderDao = orderDao;
        this.shippingTrackDao = shippingTrackDao;
    }

    /**
     * 扫描 + 补轨迹。整方法 catch 一切（同 {@code MerchantDowngradeJob.scanAndApply}：
     * 一个定时任务抛异常会让 Spring 把后续调度一起停掉）。单条失败只跳过该条，不拖垮整轮。
     */
    @Scheduled(fixedDelayString = "${mall.order.shipping.track.fixed-delay-ms:15000}")
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
                advanceOne(String.valueOf(one.get("OrderId")), one.get("ShippedAtText"), one.get("ElapsedSec"));
            } catch (Exception e) {
                log.warn("物流轨迹推进：订单 {} 失败（跳过，下一轮重试）：{}", one.get("OrderId"), e.toString());
            }
        }
    }

    /** 单条：按「发货时刻 + 偏移」补 4 个节点（各带 exists 幂等判据）。 */
    private void advanceOne(String orderId, Object shippedAtTextRaw, Object elapsedSecRaw) {
        String shippedAtText = shippedAtTextRaw == null ? null : String.valueOf(shippedAtTextRaw);
        Long elapsedSec = toLong(elapsedSecRaw);
        if (shippedAtText == null || shippedAtText.isBlank() || elapsedSec == null) {
            return;     // 基准时刻缺失（本不该发生，SQL 已过滤 IS NOT NULL）——静默跳过
        }
        long elapsedMs = elapsedSec * 1000L;   // 阈值仍是毫秒口径（properties 名不变）⇒ 在这里换算一次
        // ⚠️ 2026-10-11 起：这里按**时序**排列（运输中 → 驿站已揽收 → 驿站派送中 → 已签收）。
        //    前两步的「节点码 ↔ 阈值」已对调（transit 15s / picked-up 30s），别按旧印象调回来。
        if (elapsedMs >= transitMs) {
            step(orderId, ShippingTrackDao.IN_TRANSIT, "运输中", shippedAtText, transitMs);
        }
        if (elapsedMs >= pickedUpMs) {
            step(orderId, ShippingTrackDao.PICKED_UP, "驿站已揽收", shippedAtText, pickedUpMs);
        }
        if (elapsedMs >= deliveryMs) {
            step(orderId, ShippingTrackDao.OUT_FOR_DELIVERY, "驿站派送中", shippedAtText, deliveryMs);
        }
        if (elapsedMs >= signedMs) {
            step(orderId, ShippingTrackDao.DELIVERED, "已签收", shippedAtText, signedMs);
        }
    }

    /**
     * 补一个节点：**已存在则跳过**（幂等）。{@code TrackedAt} 由 {@code DATE_ADD(发货文本, 偏移)} 出
     * —— 不是 {@code NOW()}：同一轮里越过的节点会一次补好几条，各条必须落在**各自**的偏移时刻上。
     */
    private void step(String orderId, String status, String description,
                      String shippedAtText, long offsetMs) {
        if (!shippingTrackDao.exists(orderId, status)) {
            shippingTrackDao.writeAtShippedOffset(orderId, status, description, shippedAtText, offsetMs / 1000L);
        }
    }

    /**
     * JDBC 交回的整数（{@code TIMESTAMPDIFF} 可能是 {@code Integer} 或 {@code Long}）→ {@code Long}；
     * 认不出来 ⇒ {@code null}（同改前 {@code toMillis} 的 fail-soft 口径：不猜、静默跳过）。
     */
    private static Long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
