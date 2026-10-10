package com.minke.mallmanagement.order.job;

import com.minke.mallmanagement.order.dao.OrderDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订单超时未付款自动取消任务（C3 块4 补丁 · 第 2 笔）—— 按「创建时刻 + 阈值」把未付款的单置为已取消。
 *
 * <h2>【唯一职责：给<b>未付款</b>的单写 {@code order.CancelledAt}】</h2>
 * 本类**不做别的**：不改 {@code PayIf}（改了就与「已付款」混同）、不删行、不发通知。
 * {@code CancelledAt} 是「第 4 态」的**唯一**载体 —— 取消的单三位仍是 {@code (0,0,0)}，
 * 与「未付款」**完全相同**，故前端必须**先判 {@code CancelledAt != null}**、再落到三位表上。
 *
 * <h2>【⚠️ 阈值：演示默认 60 秒，生产改配置】</h2>
 * <table border="1">
 *   <tr><th>项</th><th>默认</th><th>阈值 key</th></tr>
 *   <tr><td>扫描间隔</td><td>15 秒</td><td>{@code fixed-delay-ms}（15000）</td></tr>
 *   <tr><td>超时阈值</td><td>60 秒</td><td>{@code timeout-ms}（60000），生产口径 30 分钟改这里</td></tr>
 * </table>
 * ⚠️ 用户 2026-10-10 裁「批全档」时一并把演示节奏定成「间隔 15 秒」：最坏等待 = 阈值 + 一个 tick。
 *
 * <h2>【⚠️ 时刻：本类不构造任何时间点，全部由库时钟算】</h2>
 * 与 {@link ShippingTrackAdvanceJob} 同款口径（2026-10-10 时区统一）。候选判据是
 * {@code CreateTime < DATE_FORMAT(DATE_SUB(NOW(), INTERVAL n SECOND), …)}、写入是 {@code NOW(3)}，
 * **整条路上没有任何 Java 时间对象**（改前那种 {@code System.currentTimeMillis()} +
 * {@code new Timestamp} 会在 {@code serverTimezone=Asia/Shanghai} 下掺进一次折算 ⇒ 落库 ±8 小时）。
 *
 * <h2>【幂等与并发：靠 {@code OrderDao.cancelIfUnpaid} 的 CAS】</h2>
 * 候选查询带 {@code CancelledAt IS NULL}，写入是
 * {@code UPDATE … WHERE OrderId=? AND PayIf=0 AND CancelledAt IS NULL} ⇒
 * 同一张单被反复扫到也只会取消一次（{@code rows == 0} 即跳过）。
 * ⚠️ <b>反向的竞态由付款那条 UPDATE 兜</b>：取消**不改</b> {@code PayIf}，
 * 故「已取消的单被付款」光靠付款守卫拦不住 —— 那一半在
 * {@code OrderStateMachine.updateSql(PAY)} 尾部的 {@code AND CancelledAt IS NULL}。两条各管一边。
 *
 * <h2>【⚠️ 与 {@code MerchantDowngradeJob} / {@code ShippingTrackAdvanceJob} 同族：整类挂 {@code @ConditionalOnProperty}】</h2>
 * A3 是并发压测，要求「同一份输入跑出同一份结果」；一个每 15 秒自己醒来写库的后台线程会注入
 * 与压测无关的变量 ⇒ a3 profile 显式置 false。默认（开发机）保持开启（{@code matchIfMissing=true}）。
 * <p>⚠️ <b>调度器总闸不在本类手里</b>（{@code @EnableScheduling} 挂在
 * {@code MerchantDowngradeSchedulingConfig}、由 {@code mall.downgrade.job.enabled} 把关）
 * ⇒ <b>a3 下调度器根本不存在</b>，本类即便 flag 忘了关也不会跑。要看超时取消必须用**默认 profile**。
 */
@Component
@ConditionalOnProperty(name = "mall.order.cancel.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutCancelJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCancelJob.class);

    private final OrderDao orderDao;

    /** 超时阈值（创建后多久未付款即取消）。**演示默认 60 秒**；生产口径 30 分钟改这里。 */
    @Value("${mall.order.cancel.timeout-ms:60000}")
    private long timeoutMs;

    public OrderTimeoutCancelJob(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    /**
     * 扫描 + 取消。整方法 catch 一切（同 {@code ShippingTrackAdvanceJob.advance}：
     * 一个定时任务抛异常会让 Spring 把后续调度一起停掉）。单条失败只跳过该条，不拖垮整轮。
     */
    @Scheduled(fixedDelayString = "${mall.order.cancel.fixed-delay-ms:15000}")
    public void advance() {
        long timeoutSeconds = timeoutMs / 1000L;
        if (timeoutSeconds <= 0) {
            // 阈值 <= 0 会让「刚下的单立刻被取消」——那是配置事故，不是业务行为。宁可整轮不动。
            log.warn("超时取消：阈值 {} ms 非正，本轮跳过（检查 mall.order.cancel.timeout-ms）", timeoutMs);
            return;
        }
        List<Map<String, Object>> candidates;
        try {
            candidates = orderDao.selectUnpaidExpired(timeoutSeconds);
        } catch (Exception e) {
            log.warn("超时取消：查候选整轮失败 —— 下一轮重试", e);
            return;
        }
        for (Map<String, Object> one : candidates) {
            String orderId = String.valueOf(one.get("OrderId"));
            try {
                if (orderDao.cancelIfUnpaid(orderId) == 1) {
                    log.info("超时取消：订单 {} 超过 {} 秒未付款，已置 CancelledAt", orderId, timeoutSeconds);
                }
            } catch (Exception e) {
                log.warn("超时取消：订单 {} 失败（跳过，下一轮重试）：{}", orderId, e.toString());
            }
        }
    }
}
