package com.minke.mallmanagement.merchant.job;

import com.minke.mallmanagement.merchant.service.MerchantDowngradeService;
import com.minke.mallmanagement.notification.NotificationEvents;
import com.minke.mallmanagement.notification.service.NotificationService;
import com.minke.mallmanagement.observe.service.ScheduledJobGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 降级到期扫描任务（C3 块 2 用户块）—— 五阶段的**触发器**（阶段 ②→③ 的时钟）。
 *
 * <h2>【定时框架：Spring {@code @Scheduled}，零新依赖（A 档裁决 5「甲」）】</h2>
 * 用户裁定不引 Quartz / XXL-Job：本项目的定时需求只有「每 N 秒扫一张小表」这一条，
 * 引一个调度中间件换来的分布式协调、持久化触发器等能力**一条都用不上**，
 * 却要多一套运维面。故用 Boot 自带的 {@code @Scheduled}。
 *
 * <p>⚠️ {@code @EnableScheduling} **不在本类上**（它需要一个 {@code @Configuration} 载体，
 * 见 {@code MerchantDowngradeSchedulingConfig}）—— 这是 Spring 的规定，不是风格问题。
 *
 * <h2>【⚠️ 为什么整类挂 {@code @ConditionalOnProperty}，且 a3/smoke 两个 profile 置 false】</h2>
 * 定时器一旦跑起来就会**自己去连库**。A3 的并发门禁要在**毫秒级**度量下单链路的竞争行为，
 * 一个每 60 秒醒来跑几条 UPDATE 的后台线程会往被测路径里注入噪声（且那是**无法从门禁输出里
 * 分辨**的噪声）；smoke 切片则根本没有 DataSource。故：
 * <ul>
 *   <li>{@code application-a3.properties} 显式 {@code mall.downgrade.job.enabled=false}；</li>
 *   <li>{@code src/test/resources/application-smoke.properties} 同上；</li>
 *   <li><b>默认 profile（开发机 8088）与 CI 的完整上下文测试保持开启</b>（{@code matchIfMissing=true}）
 *       —— 开发时要能真看到降级执行，这是本块的验收路径之一。</li>
 * </ul>
 *
 * <h2>【{@code fixedDelay} 而不是 {@code fixedRate}】</h2>
 * {@code fixedDelay} = 上一轮**跑完**再等 60 秒；{@code fixedRate} = 每 60 秒**发一次**、
 * 不论上轮是否跑完。前者天然不会堆积执行（库慢时拉长间隔，而不是并发跑两轮同一条 UPDATE）。
 */
@Component
@ConditionalOnProperty(name = "mall.downgrade.job.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantDowngradeJob {

    private static final Logger log = LoggerFactory.getLogger(MerchantDowngradeJob.class);

    /** 通知收件人类型：商家（与 {@code notification.ToType} 的取值域一致）。 */
    private static final String TO_TYPE_MERCHANT = "merchant";

    private final MerchantDowngradeService downgradeService;
    private final NotificationService notificationService;

    /** 调度停摆注入（D3）。{@code null} 只出现在手工 new 的测试里 ⇒ 视为未注入。 */
    private ScheduledJobGate jobGate;

    public MerchantDowngradeJob(MerchantDowngradeService downgradeService,
                                NotificationService notificationService) {
        this.downgradeService = downgradeService;
        this.notificationService = notificationService;
    }

    @Autowired(required = false)
    public void setJobGate(ScheduledJobGate jobGate) {
        this.jobGate = jobGate;
    }

    /**
     * 扫描 + 执行 + 通知。整方法 catch 一切：**一个定时任务抛异常会让 Spring 把后续调度也一起停掉**
     * （默认的 {@code TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER} 其实会吞，但显式兜住更稳，
     * 且能把「这一轮整体失败」记成一条可检索的 WARN）。
     */
    @Scheduled(fixedDelayString = "${mall.downgrade.job.fixed-delay-ms:60000}")
    public void scanAndApply() {
        if (jobGate != null && jobGate.paused("merchantDowngrade")) {
            return;     // D3：jobDown 注入 ⇒ 整轮跳过（不扫描、不降级、不通知）
        }
        try {
            List<Map<String, Object>> done = downgradeService.runDue();
            if (done.isEmpty()) {
                return;   // 静默：绝大多数轮次都是空扫，不该刷日志
            }
            log.info("降级执行（DOWNGRADE）：本轮处理 {} 个商家", done.size());
            for (Map<String, Object> one : done) {
                notifyMerchant(one);
            }
        } catch (Exception e) {
            log.warn("降级扫描整轮失败（DOWNGRADE）—— 下一轮重试", e);
        }
    }

    /** 阶段 ③ 的通知：告诉商家「你已被降级，原因是 X，30 天内可申诉」。原因列**是给商家看的**。 */
    private void notifyMerchant(Map<String, Object> one) {
        Object merchantId = one.get("merchantId");
        if (merchantId == null) {
            return;
        }
        String reason = one.get("reason") == null ? "（未填写）" : one.get("reason").toString();
        String title = "店铺已被降级";
        String content = "您的店铺已进入冻结状态，商品已全部下架，暂时无法接收新订单。原因：" + reason
                + "。您可在 30 天内重新提交入驻申请，审核通过后将自动恢复。";
        notificationService.send(UUID.randomUUID().toString(),
                merchantId.toString(), TO_TYPE_MERCHANT,
                NotificationEvents.MERCHANT_DOWNGRADE_APPLIED,
                title, content, "user", one.get("username") == null ? null : one.get("username").toString());
    }
}
