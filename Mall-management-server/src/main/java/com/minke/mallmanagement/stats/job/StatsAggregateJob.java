package com.minke.mallmanagement.stats.job;

import com.minke.mallmanagement.observe.service.ScheduledJobGate;
import com.minke.mallmanagement.stats.dao.StatsDao;
import com.minke.mallmanagement.stats.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 经营数据**滚动**预聚合任务（可视化段 commit 2）—— 每轮重算最近 N 天，upsert 进 {@code stats_daily}。
 *
 * <h2>【⚠️ 本类只做「滚动」，不做「全量」】</h2>
 * 全量重建在 {@link StatsService#rebuild()}（HTTP 端点 {@code POST /admin/stats/rebuild}）。
 * 分工的理由不是「谁干净」，是**代价**：滚动窗口每轮碰几天、是全表扫描的零头；
 * 全量重建要重扫整张 {@code order}。让一个每 60 秒醒来的线程去干全量，等于把一份
 * 「只在换数据后需要一次」的开销变成周期开销。
 * <p>⚠️ 反过来说清楚**滚动窗口做不到的事**：它**不清表**。订单若被全量重导（DROP + 重插，
 * 日期集合变了），窗口外的旧日期行会**留在** {@code stats_daily} 里，看板上就会出现
 * 「数据源里已经没有的一天」。那种场景**必须**手工打一次 {@code rebuild}。
 *
 * <h2>【窗口按**库时钟**，不是 Java 的今天】</h2>
 * {@code [今天-(N-1), 今天]} 两端都由 {@code StatsDao.currentDate()} / {@code dateMinus()}
 * 出（SQL {@code CURDATE()}）。理由见那两个方法的注释（TD-32 的时区口径）。
 *
 * <h2>【⚠️ 与 {@code ShippingTrackAdvanceJob} / {@code OrderTimeoutCancelJob} 同族同款】</h2>
 * 整类挂 {@code @ConditionalOnProperty(matchIfMissing = true)}：默认（开发机 8088）开、
 * a3 与 smoke 关。A3 是并发压测，要求「同一份输入跑出同一份结果」，一个按墙上时钟自己醒来、
 * 扫库写库的后台线程会注入**无法从门禁输出分辨**的噪声（理由与既有三处逐字相同）。
 * <p>⚠️ 调度器总闸（{@code @EnableScheduling}）挂在 {@code MerchantDowngradeSchedulingConfig} 上，
 * 由 {@code mall.downgrade.job.enabled} 把守 ⇒ 本类的 flag 是**第二道独立闸**。
 * **别为了关本任务去改 {@code mall.downgrade.job.enabled}** —— 那会连商家降级的到期执行一起关掉。
 *
 * <h2>【⚠️ 演示数据下它大概率「算不到东西」，那是正常的】</h2>
 * 本仓库的种子订单日期在 2026-05 … 2026-10，而窗口是「最近 N 天」⇒ 库里那几张历史单
 * **落在窗口之外**，本类每轮扫到 0 行。这不是缺陷：滚动窗口服务的是「每天有新单」的真实形态。
 * 看板要显示历史数据，打一次 {@code rebuild}（前端看板页上有那枚按钮）。
 */
@Component
@ConditionalOnProperty(name = "mall.stats.job.enabled", havingValue = "true", matchIfMissing = true)
public class StatsAggregateJob {

    private static final Logger log = LoggerFactory.getLogger(StatsAggregateJob.class);

    private final StatsDao statsDao;
    private final StatsService statsService;

    /** 滚动窗口天数（含今天）。默认 3：够覆盖「跨零点前刚下的单」与「昨天的补单」。 */
    @Value("${mall.stats.job.window-days:3}")
    private int windowDays;

    /** 调度停摆注入（D3）。{@code null} 只出现在手工 new 的测试里 ⇒ 视为未注入。 */
    private ScheduledJobGate jobGate;

    public StatsAggregateJob(StatsDao statsDao, StatsService statsService) {
        this.statsDao = statsDao;
        this.statsService = statsService;
    }

    @Autowired(required = false)
    public void setJobGate(ScheduledJobGate jobGate) {
        this.jobGate = jobGate;
    }

    /**
     * 每轮重算最近 {@link #windowDays} 天。
     *
     * <p>整方法 catch 一切：一个定时任务抛异常会让 Spring **把后续调度一起停掉**
     * （同 {@code MerchantDowngradeJob} / {@code ShippingTrackAdvanceJob} 的既有做法）——
     * 一次数据库抖动不该让经营数据**从此不再更新**，而且那种「停了」在日志之外没有任何征兆。
     */
    @Scheduled(fixedDelayString = "${mall.stats.job.fixed-delay-ms:60000}")
    public void refresh() {
        if (jobGate != null && jobGate.paused("statsAggregate")) {
            return;     // D3：jobDown 注入 ⇒ 整轮跳过（不重算 stats_daily）
        }
        try {
            int days = windowDays <= 0 ? 1 : windowDays;
            String to = statsDao.currentDate();
            String from = statsDao.dateMinus(days - 1);
            int written = statsService.refreshWindow(from, to);
            if (written > 0) {
                log.info("经营数据滚动聚合：窗口 {} … {} 写入 {} 行", from, to, written);
            }
        } catch (Exception e) {
            log.warn("经营数据滚动聚合：本轮失败 —— 下一轮重试", e);
        }
    }
}
