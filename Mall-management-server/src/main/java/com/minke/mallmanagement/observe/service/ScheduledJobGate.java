package com.minke.mallmanagement.observe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度停摆注入的唯一判据（D3）—— 七个 {@code @Scheduled} 入口在方法第一行问它一次。
 *
 * <h2>【为什么是一个共享 bean，而不是每个 job 自己读配置】</h2>
 * 七个 job 若各自写一遍 {@code switchConfig.injections().contains("jobDown")}：
 * <ol>
 *   <li>漏一个不会有任何测试变红，表现是「面板说停摆了，还有一个 job 在跑」——
 *       演示现场最难解释的那一类；</li>
 *   <li>「本轮跳过」的日志会各写一套（有的 INFO、有的 WARN、有的不记），
 *       日志页看起来像七种不同的故障。</li>
 * </ol>
 * ⇒ 判据与日志都收在这里，job 侧只剩一行 {@code if (gate.paused("...")) return;}。
 *
 * <h2>【日志口径：翻转才记，不逐轮刷】</h2>
 * 停摆期间每个 job 仍会按自己的 {@code fixedDelay} 醒来（Spring 的 {@code @Scheduled} 不会
 * 因为我们跳过而停表）。若每轮都记一行，{@code OrderTimeoutCancelJob} 会 15 秒一条。
 * 故只在「第一次跳过」与「恢复后第一次真跑」各记一行 INFO —— 与 {@code RabbitEventPublisher}
 * 的翻转日志同款。
 *
 * <h2>【⚠️ 它只在调度器存在时才有意义】</h2>
 * {@code @EnableScheduling} 的载体是 {@code MerchantDowngradeSchedulingConfig}，由
 * {@code mall.downgrade.job.enabled} 把守（a3=false）。a3 下七个 job 根本不注册 ⇒
 * {@code jobDown} 只在 dev / 默认 profile 演示得出来；a3 不会因为打开它而「少跑」（本来就没跑）。
 */
@Component
public class ScheduledJobGate {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobGate.class);

    private final AttackSwitchConfig switchConfig;

    /**
     * 事件出口（A5）。⚠️ 用 {@link ObjectProvider} 而不是直接注入：本类被七个 job 与
     * {@code ScheduledJobGateTest} 直接 new 出来，直接注入会让纯单测无法构造；且事件是
     * **附加观测**，不该成为调度闸可用性的前提（与 {@code AttackController} 的处置同款）。
     */
    private final ObjectProvider<EventBroadcaster> eventBroadcaster;

    /** 已记过「停摆中」的 job 名。集合的增删就是翻转判据（不另存布尔位）。 */
    private final Set<String> pausedLogged = ConcurrentHashMap.newKeySet();

    /** 纯单测 / 切片用：不发事件。 */
    public ScheduledJobGate(AttackSwitchConfig switchConfig) {
        this(switchConfig, null);
    }

    @Autowired
    public ScheduledJobGate(AttackSwitchConfig switchConfig,
                            ObjectProvider<EventBroadcaster> eventBroadcaster) {
        this.switchConfig = switchConfig;
        this.eventBroadcaster = eventBroadcaster;
    }

    /**
     * 这一轮该不该跳过。
     *
     * @param job 人话 job 名（日志用，如 {@code orderTimeoutCancel}）
     * @return {@code true} = 注入开着，调用方应**整轮跳过**（不查库、不写库、不发通知）
     */
    public boolean paused(String job) {
        if (!switchConfig.jobsPaused()) {
            if (pausedLogged.remove(job)) {
                log.info("[JOB-INJECTION] 调度恢复：{} 下一轮起照常执行（jobDown 已关）", job);
                emitScheduler(job, false, "jobDown 已关闭，下一轮起照常执行");
            }
            return false;
        }
        if (pausedLogged.add(job)) {
            log.info("[JOB-INJECTION] 演练注入 jobDown 开启：{} 本轮及后续轮次整轮跳过"
                    + "（后台调度没有停表，只是每个入口在方法第一行返回）", job);
            emitScheduler(job, true, "演练注入 jobDown 开启，本轮及后续轮次整轮跳过");
        }
        return true;
    }

    /** 只在翻转时发一条设施事件（与上面的日志同一处判定，不逐轮刷）。 */
    private void emitScheduler(String job, boolean paused, String detail) {
        EventBroadcaster broadcaster = eventBroadcaster == null ? null : eventBroadcaster.getIfAvailable();
        if (broadcaster == null) {
            return;
        }
        try {
            broadcaster.emitScheduler(job, paused, detail);
        } catch (RuntimeException e) {
            // 事件出口坏掉不能反过来让调度入口抛出（观测是附加链路）。
            log.debug("[JOB-INJECTION] 调度事件出口失败（已忽略）：{}", e.toString());
        }
    }
}
