package com.minke.mallmanagement_server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L2（Redis）健康状态 —— 全应用唯一持有者。
 *
 * 【为什么必须是单例，而不能留在 TwoLevelCache 里】
 * 这个状态原本是 {@link TwoLevelCache} 的实例字段，而 TwoLevelCache 由
 * {@link TwoLevelCacheManager} **按缓存名各建一个** —— 于是「Redis 挂了」这个
 * **全局事实**被拆成了 N 份互不相干的判定：新增一个缓存名就多一份状态、多一条 WARN。
 * 现在只有 1 个缓存名（goodstype），看不出问题；等 cache-layer.md §9 列出的几个接口
 * 都上了缓存，一次 Redis 故障会变成 N 条告警、N 份可能互相矛盾的状态。
 * 故上移到单例 —— 现在改便宜，多接口上缓存后再改贵。
 *
 * 【职责边界】本类不碰连接、不发命令、不做降级决策，只记录「上一次真实 L2 操作
 * 是成功还是失败」，并把日志压成「状态翻转」。降级本身由 {@link TwoLevelCache} 执行。
 *
 * 【类名里的 "Probe"】本类的目标职责是「持有健康状态 + 冷却窗口，并在窗口到期时由
 * 唯一一个请求线程主动探测 L2」；当前这一版只做了状态上移，探测与冷却窗口是紧随其后
 * 的同一系列改动（探活通道的装配见 L2ProbeConfig）。
 *
 * 【为什么日志只按翻转打】Redis 长时间不可用时，逐次记录会刷爆日志（每个冷读一条），
 * 而那一条给的信息与第一行完全相同。降级是硬约束而非异常情况（cache-layer.md §9/§11），
 * 不需要重复播报。
 */
public class L2HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(L2HealthProbe.class);

    /** true = 上一次 L2 操作成功（尚未发生任何 L2 操作时也视为 true）。 */
    private volatile boolean healthy = true;

    /** L2 操作成功：只在「故障 → 恢复」时打一行。 */
    public void markOk() {
        if (!healthy) {
            healthy = true;
            log.warn("L2(Redis) 已恢复，回到两级模式");
        }
    }

    /** L2 操作失败：只在「健康 → 故障」时打一行。 */
    public void markFailed(Throwable cause) {
        if (healthy) {
            healthy = false;
            log.warn("L2(Redis) 不可达，全部两级缓存降级为「仅 L1 + 直接回落 DB」；"
                    + "本次故障期间不再逐次记录。原因：{}", cause.toString());
        }
    }
}
