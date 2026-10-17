package com.minke.mallmanagement.observe.job;

import com.minke.mallmanagement.observe.service.ObservedEventCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件台账的**保留期**清理任务（块 7 · commit 8b）—— 每轮删一次
 * {@code observed_event} 里过了保留期的行（攻击批次 3 天 / 日常事件 30 天，见
 * {@link ObservedEventCleanupService#pruneExpiredEvents()}）。
 *
 * <h2>【⚠️ 本笔它删的**恒为 0 行**，而这是设计好的】</h2>
 * {@code observed_event} 的**写入方**要到 8c（RabbitMQ consumer）才接 —— 用户 2026-10-14 裁决
 * 「8b 只建表 + 清理，写入留 8c」。⇒ 本笔跑起来表是空的，每轮删 0 行。
 * <p>为什么仍要现在建它：**保留期是表结构的一部分**，不是接线以后才想起来的事。
 * 一张**只写不删**的台账，在 8c 接通后几十天里会把磁盘吃满，而那时才发现「当初没写清理」，
 * 就要在别人刚上线的链路上插一个新线程 —— 现在建，成本是「本轮删 0 行」。
 * <p>⚠️ 反过来说清楚它**现在证不了什么**：「保留期真的按天数删对行」这件事，
 * 在本笔**只有单测能证**（{@code ObservedEventCleanupDaoTest} 用替身 JdbcTemplate 钉 SQL 与参数），
 * 真库上的证据要等 8c 之后。别把「本笔跑绿」当成「保留期验过」。
 *
 * <h2>【⚠️ 与 {@code StatsAggregateJob} / {@code OrderTimeoutCancelJob} 同族同款】</h2>
 * 整类挂 {@code @ConditionalOnProperty(matchIfMissing = true)}：默认（开发机 8088）开、
 * a3 与 smoke 关。A3 是并发压测，要求「同一份输入跑出同一份结果」，一个按墙上时钟自己醒来、
 * 扫库删库的后台线程会注入**无法从门禁输出分辨**的噪声（理由与既有几处逐字相同）。
 * <p>⚠️ 调度器总闸（{@code @EnableScheduling}）挂在 {@code MerchantDowngradeSchedulingConfig} 上，
 * 由 {@code mall.downgrade.job.enabled} 把守 ⇒ 本类的 flag 是**第二道独立闸**。
 * **别为了关本任务去改 {@code mall.downgrade.job.enabled}** —— 那会连商家降级的到期执行一起关掉。
 *
 * <h2>【⚠️ {@code fixedDelay} 而不是 {@code fixedRate}】</h2>
 * 与既有**六**处 job 同款（截至本笔，`grep -rn '@Scheduled' …/main/java` 实测 6 处：`QuestionTimeoutJob` /
 * `CouponExpiryJob` / `MerchantDowngradeJob` / `OrderTimeoutCancelJob` / `ShippingTrackAdvanceJob` /
 * `StatsAggregateJob` ⇒ **本类是第 7 个**）：{@code fixedDelay} 从**上一轮结束**开始计时 ⇒ 一轮跑得比间隔长时
 * 不会出现两轮叠着跑（本类的 DELETE 是幂等的，但叠跑仍是无意义的写放大）。
 * 间隔取 1 小时：保留期是以**天**计的，扫得比这更勤没有意义。
 *
 * <h2>【⚠️ 本类**不做**的事：不按龄删业务行】</h2>
 * {@code order} / {@code comment} 里那些带 {@code SIM-} / {@code Source='attack'} 标记的行
 * **不由本 job 删**，只由端点 {@code POST /admin/observe/attack/cleanup} **人工触发**删。
 * 理由：演示数据是白天造出来的，隔夜被后台悄悄删掉，第二次演示时就会「**没东西可清**」——
 * 判据拆得清楚：**job 管表（{@code observed_event}），端点管业务**。
 */
@Component
@ConditionalOnProperty(name = "mall.observe.cleanup.job.enabled", havingValue = "true", matchIfMissing = true)
public class ObservedEventCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ObservedEventCleanupJob.class);

    private final ObservedEventCleanupService cleanupService;

    public ObservedEventCleanupJob(ObservedEventCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /**
     * 一轮保留期清理。⚠️ 全包 {@code try/catch} —— 与既有六处 job 逐字同款：
     * 一个每 15 秒醒来的 job 若把异常抛给调度器，日志里会滚出无穷多条同样的堆栈，
     * 把真正的信号埋掉。吞掉之后**只记一行 error**，下一轮照常跑。
     */
    @Scheduled(fixedDelayString = "${mall.observe.cleanup.fixed-delay-ms:3600000}")
    public void prune() {
        try {
            cleanupService.pruneExpiredEvents();
        } catch (Exception e) {
            log.error("事件台账保留期清理失败（OBSERVE-CLEANUP）—— 本轮跳过，下一轮照常", e);
        }
    }
}
