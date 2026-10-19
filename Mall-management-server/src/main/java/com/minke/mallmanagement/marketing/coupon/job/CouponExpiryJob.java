package com.minke.mallmanagement.marketing.coupon.job;

import com.minke.mallmanagement.marketing.coupon.dao.CouponDao;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.dao.NotificationDao;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.observe.service.ScheduledJobGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「**未领 + 快过期**」优惠券的到期提醒任务（C3 块 5 · commit 1 · 用户 2026-10-11 裁定 #4 ①）。
 *
 * <h2>【只发「未领的」—— 已领的不发，这是裁定 #4 的核心分界】</h2>
 * 用户原话「一刀切『不发』是错的」：<b>未领的</b>要被提醒（他们可能根本不知道有这张券），
 * <b>已领的</b>**不发系统通知**（券就在他自己的券包里，发通知是打扰）——
 * 已领的那一侧改由**券包红点 + 排序**呈现（{@code CouponDao.selectMyGrants} 的
 * {@code Bucket='expiring'}，前端据此点亮）。
 *
 * <h2>【受众 = 全部客户 − 已领这张券的人】</h2>
 * 受众解析走 {@link NotificationService#resolveAudience}（**不自己拼 {@code user} 表查询**：
 * 客户/商家的魔法串与 SQL 只该在 notification 域出现一次，见 {@code NotificationBroadcastService}
 * 类注释）。
 *
 * <h2>【⚠️ 幂等：靠通知表自己判「这轮该不该发」】</h2>
 * 任务每 {@code fixedDelay} 醒一次，而「快过期」窗口长达 24h ⇒ 不幂等就会**重复提醒上千次**。
 * 判据是 {@link NotificationDao#existsDelivery}：同一 ({@code ToId}, {@code EventType},
 * {@code TargetType}, {@code TargetId}) 已经有行 ⇒ 跳过。
 * ⚠️ 这条判据**没有用 {@code coupon} 表加列**（如 {@code NotifiedAt}）来实现 ——
 * 加列是 A 档（改契约），而通知表本来就记着「谁、因为哪张券、收过什么」。
 * <p>⚠️ 频率因此是「**这张券对这个用户，整个生命周期只提醒一次**」，而不是「每天一次」。
 * 裁定原文是「可配（如券过期前 24h 发一次）」—— 「一次」我方按**一张券一个人**读。
 * 若您要的是「每天一次」，改的只是这条判据的键（加一个日期段）。
 *
 * <h2>【⚠️ 为什么整类挂 {@code @ConditionalOnProperty}，且 a3/smoke 两个 profile 置 false】</h2>
 * 与 {@code MerchantDowngradeJob} / {@code StatsAggregateJob} **逐字同款**：定时器一旦跑起来
 * 就会自己去连库。A3 的并发门禁要在**毫秒级**度量，一个自己醒来的后台线程会往被测路径里注入
 * **无法从门禁输出里分辨**的噪声；smoke 切片则根本没有 DataSource。
 * ⇒ 本任务在 a3 / smoke 显式关闭，默认 profile（开发机 8088）保持开启（{@code matchIfMissing=true}）。
 *
 * <h2>【{@code fixedDelay} 而不是 {@code fixedRate}】</h2>
 * 同 {@code MerchantDowngradeJob}：上一轮**跑完**再等（库慢时拉长间隔，而不是并发跑两轮）。
 */
@Component
@ConditionalOnProperty(name = "mall.coupon.expiry.job.enabled", havingValue = "true", matchIfMissing = true)
public class CouponExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryJob.class);

    /**
     * 每轮最多处理多少张券。**不是性能调优，是爆炸半径的上界**：一次提醒的落库行数 ≈
     * 券数 × 客户数，给券数封顶即给这一轮的写量封顶（下一轮接着扫剩下的，
     * {@code ORDER BY EndAt ASC} 保证最急的先处理）。
     */
    private static final int MAX_COUPONS_PER_ROUND = 20;

    private final CouponDao couponDao;
    private final NotificationService notificationService;
    private final NotificationDao notificationDao;

    /** 调度停摆注入（D3）。{@code null} 只出现在手工 new 的测试里 ⇒ 视为未注入。 */
    private ScheduledJobGate jobGate;

    public CouponExpiryJob(CouponDao couponDao, NotificationService notificationService,
                           NotificationDao notificationDao) {
        this.couponDao = couponDao;
        this.notificationService = notificationService;
        this.notificationDao = notificationDao;
    }

    @Autowired(required = false)
    public void setJobGate(ScheduledJobGate jobGate) {
        this.jobGate = jobGate;
    }

    /**
     * 扫描 + 提醒。整方法 catch 一切：**一个定时任务抛异常会让 Spring 把后续调度也一起停掉**
     * （同 {@code MerchantDowngradeJob} 的处置）。
     */
    @Scheduled(fixedDelayString = "${mall.coupon.expiry.job.fixed-delay-ms:3600000}")
    public void scanExpiringUnclaimed() {
        if (jobGate != null && jobGate.paused("couponExpiry")) {
            return;     // D3：jobDown 注入 ⇒ 整轮跳过（不查券、不发提醒）
        }
        try {
            List<Map<String, Object>> soon = couponDao.selectExpiringUnclaimed(MAX_COUPONS_PER_ROUND);
            if (soon.isEmpty()) {
                return;   // 静默：绝大多数轮次都是空扫，不该刷日志
            }
            List<String> audience = customerIds();
            if (audience.isEmpty()) {
                return;   // 库里一个客户都没有 ⇒ 这一轮没什么可发的（正常状态，不是错误）
            }
            int sent = 0;
            for (Map<String, Object> coupon : soon) {
                sent += remindOne(coupon, audience);
            }
            if (sent > 0) {
                log.info("券到期提醒（NOTIFY）：本轮命中 {} 张券，落库 {} 条", soon.size(), sent);
            }
        } catch (Exception e) {
            log.warn("券到期提醒整轮失败（NOTIFY）—— 下一轮重试", e);
        }
    }

    /** 一张券的扇出：把「已领的人」剔掉、把「已经提醒过的人」剔掉，剩下的各发一条。 */
    private int remindOne(Map<String, Object> coupon, List<String> audience) {
        String couponId = str(coupon.get("CouponId"));
        if (couponId == null) {
            return 0;
        }
        Set<String> claimed = couponDao.selectClaimantAccounts(couponId);
        String title = "优惠券即将过期";
        String content = "「" + (coupon.get("Name") == null ? "优惠券" : coupon.get("Name"))
                + "」将在 " + (coupon.get("EndAt") == null ? "近期" : coupon.get("EndAt"))
                + " 过期，还没领取的记得去领券中心领取。";
        // ⚠️ 一个 BatchId 覆盖「这一张券这一轮的全部收件人」—— 同 NotificationBroadcastService
        //    的「一次投递 = 一个 BatchId + N 行」，管理端「通知历史」据此把这 N 行收成一行。
        String batchId = UUID.randomUUID().toString();
        int sent = 0;
        for (String toId : audience) {
            if (claimed.contains(toId)) {
                continue;   // 已领的**不发**（裁定 #4 ②）—— 他有券包红点
            }
            if (notificationDao.existsDelivery(toId, NotificationEvents.COUPON_EXPIRING_UNCLAIMED,
                    "coupon", couponId)) {
                continue;   // 幂等：这张券已经提醒过这个人了
            }
            if (notificationService.send(batchId, toId, NotificationService.TO_TYPE_CUSTOMER,
                    NotificationEvents.COUPON_EXPIRING_UNCLAIMED, title, content, "coupon", couponId)) {
                sent++;
            }
        }
        return sent;
    }

    /** 全部客户业务号（经通知域的受众解析 —— 不自己读 {@code user} 表）。 */
    private List<String> customerIds() {
        List<String> ids = new ArrayList<>();
        for (NotificationService.Audience target : notificationService.resolveAudience(
                NotificationService.AUDIENCE_CUSTOMER)) {
            ids.addAll(target.ids());
        }
        return ids;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
