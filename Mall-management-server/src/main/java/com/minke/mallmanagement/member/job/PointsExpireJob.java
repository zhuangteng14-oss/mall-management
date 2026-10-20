package com.minke.mallmanagement.member.job;

import com.minke.mallmanagement.member.service.MemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 积分过期任务（第三批㉘，2026-10-19）—— 每日 03:00 把 365 天前获得、仍未消费的积分清零。
 *
 * <p>⚠️ 与其余 {@code @Scheduled} 同一条纪律：整台调度器的总闸挂在
 * {@code MerchantDowngradeSchedulingConfig} 上（{@code mall.downgrade.job.enabled}）。
 * 总闸关（a3 / smoke）时本类**不会醒** —— 这与「本类自己的开关」是两件事：
 * 本类的 {@code matchIfMissing=true} 只保证默认 profile 下会被注册。
 *
 * <p>⚠️ 本任务**不在** a3 的并发路径上：它只碰 {@code user_points_log} / {@code user_points_account}，
 * 与订单/库存/券的压测路径无交集。
 */
@Component
@ConditionalOnProperty(name = "mall.member.points.expire.job.enabled", havingValue = "true", matchIfMissing = true)
public class PointsExpireJob {

    private static final Logger log = LoggerFactory.getLogger(PointsExpireJob.class);

    private final MemberService memberService;

    public PointsExpireJob(MemberService memberService) {
        this.memberService = memberService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void expire() {
        try {
            memberService.expireAll();
        } catch (Exception e) {
            // 任务失败不冒泡：调度器没有调用方能接住它，记一行即可（下一轮继续）。
            log.warn("积分过期任务失败（MEMBER）：{}", e.toString(), e);
        }
    }
}
