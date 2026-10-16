package com.minke.mallmanagement.observe.metrics;

/**
 * 九个事件环**内嵌 record 的公共面**（块 7 · 阶段 3a.3「消息扩充 + 字段补齐」）。
 *
 * <h2>【它为什么存在】</h2>
 * 九环的 record 各记各的业务字段（缓存环有 {@code cache} 没有 {@code skuCode}，订单环九列，
 * 防护环 {@code key / scope / detail}……），但其中有**一组字段是九个环逐字共有的** ——
 * 它们全都属于「这条事件是被谁、在哪次请求、花了多久」这一族，取值口径也逐字相同：
 * <ul>
 *   <li>{@code epochMilli} / {@code traceId} / {@code batchId} —— commit 8 / 8c 就有的三个；</li>
 *   <li>{@code actorId} / {@code actorRole} —— 本笔新增（「谁」）；</li>
 *   <li>{@code durationUs} —— 本笔新增（「多久」）。</li>
 * </ul>
 * 在本接口之前，{@link com.minke.mallmanagement.observe.service.EventBroadcaster} 的九个
 * {@code ingestXxx} 各自把 {@code epochMilli / traceId / batchId} 三个**逐个手抄**进
 * {@code add(...)} 的位置参数里 —— 本笔要再给每处加三个，那样会变成
 * 「9 处 × 12 个位置参数」，且里面**有八个是 String**（传错顺序编译器不会报错）。
 * ⇒ 把这六个字段收成一条公共面：{@code ingestXxx} 只需把**那条 record 自己**传下去，
 * 既不再手抄、也不可能抄错。
 *
 * <h2>【⚠️ 本笔引入它，是「收口」不是「重写」】</h2>
 * {@code ingestXxx} 的九段结构、{@code poll()} 的环序、各环自己的业务字段**一个都不动**
 * —— 本接口只声明「这六个 accessor 一定在」，不规定任何行为。
 * ⚠️ <b>刻意不声明 {@code key()}</b>：九个环里 {@code key} 的**名字与语义都不一样**
 * （缓存/锁/通知是 {@code key}，订单是业务键，库存是 {@code skuCode}，审计是 {@code actor}，
 * 敏感词是 {@code scene}，身份是 {@code subject}，防护是开关名）—— 那是**各环自己的业务字段**，
 * 归 {@code ingestXxx} 在调用点逐环给定，不属于这条公共面。把它收进来只会诱使读的人
 * 以为九个 {@code key()} 是同一个东西。
 *
 * <h2>【新增第十环时】</h2>
 * 新环的内嵌 record 直接 {@code implements Sourced} —— 少一个 accessor 就编译不过，
 * 不会出现「新环忘了带 traceId / actor」这种**只有跑到页面上才看得见**的缺口
 * （那正是本仓 TD-20「静默 / 断言落空」那一族的形状）。
 */
public interface Sourced {

    /** 源事件的墙钟时刻（各环记的就是墙钟，原样透传）。 */
    long epochMilli();

    /** 产生本条事件的那次请求的号；不在请求线程上（定时任务 / SSE 流线程 / 异步池）时为 {@code null}。 */
    String traceId();

    /** 产生本条事件的请求**所属的演练批次**；{@code null} = 不属于任何演练批次（日常流量）。 */
    String batchId();

    /** 发起那次请求的业务号（块 7 · 3a.3 新增）；解析不出身份时为 {@code null}。 */
    String actorId();

    /** 发起者的角色号（1 客户 / 2 商家 / 3 管理员，块 7 · 3a.3 新增）；同上为 {@code null}。 */
    Integer actorRole();

    /**
     * 这条事件本身做了多久（**微秒**，块 7 · 3a.3 新增）；**测不出来时为 {@code null}**。
     *
     * <p>⚠️ 单位是微秒不是毫秒：本环里最便宜的一档（L1 缓存命中）是纳秒级的
     * 本地 map 命中，整数毫秒对它**恒为 0** —— 那会把「快到量不出来」显示成「耗时 0 ms」，
     * 是个假事实。微秒能把这一档与「一次 Redis 往返（几百微秒）」分开。
     *
     * <p>⚠️ {@code null} 与 {@code 0} 在页面上的意思**完全不同**：前者是「这一档量不出来」
     * （如 L1 命中、或该事件根本没有起止），后者是「量出来了，就是 0」。
     * 别为了省一个判空把它们折成同一个值。
     */
    Long durationUs();
}
