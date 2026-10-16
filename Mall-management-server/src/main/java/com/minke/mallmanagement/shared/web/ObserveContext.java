package com.minke.mallmanagement.shared.web;

import java.util.function.Supplier;

/**
 * 一组请求级上下文的**搬运件**（块 7 · commit 8c 建立；阶段 3a.3 从两个扩到四个）——
 * 把 {@code (traceId, batchId, actorId, actorRole)} 从一个线程带到另一个线程。
 *
 * <h2>【它解决什么：MDC 不跨线程】</h2>
 * 两个号都住在 SLF4J 的 {@link org.slf4j.MDC} 里（写入点唯一，见 {@link TraceIdFilter}），
 * 而 MDC 是 {@code ThreadLocal} ⇒ 请求线程上记出的事件带号，**它派生出去的线程上记出的事件不带**。
 * 本仓有三处这样的派生（全部由批文点名，纯乙 = 显式传参，不做 {@code InheritableThreadLocal}
 * 那种「隐式透传」）：
 * <ol>
 *   <li>{@code OrderStateExecutor#submit} —— 时间线落库的池线程（{@code order-state-N}）；</li>
 *   <li>{@code LockSceneService#run} —— 锁场景的池线程（{@code lock-scene-N}）；</li>
 *   <li>{@code AttackEngine#runPhase} —— 演练自己的线程（{@code drill-*}）。</li>
 * </ol>
 *
 * <h2>【为什么不做成 {@code InheritableThreadLocal}】</h2>
 * 三条独立理由，任何一条单独成立就够：① 线程池里的线程是**建一次用多次**的，继承只发生在创建那一刻，
 * 于是「继承到的」往往是**第一个**请求的上下文（比不带还糟：带错号）；② 它会静默 —— 谁在哪儿跨了线程
 * 从代码上看不出来，正是本仓 TD-20「静默 / 断言落空」那一族要防的形状；③ 显式传参只有三处，
 * 成本比「让所有人都不敢改线程代码」低得多。
 *
 * <h2>【用法：捕获在**产生方**线程，绑定在**消费方**线程】</h2>
 * <pre>
 *   // 请求线程（MDC 有值）
 *   ObserveContext ctx = ObserveContext.capture();
 *   executor.execute(() -&gt; ctx.run(() -&gt; { ...记事件的代码... }));
 * </pre>
 * ⚠️ {@link #run} / {@link #call} 跑完会**还原成本线程原来的值**（不是粗暴清空）：同一池线程
 * 被复用、且它本来就属于另一个上下文时，清空会把别人的号抹掉。
 *
 * <h2>【本类不生成任何值】</h2>
 * 四个字段各只有一处来源：{@link TraceIdFilter#pick}（traceId，外来或自生成）・
 * {@code AttackEngine}（batchId，一轮演练一个 UUID）・{@link AuthSession#identify}
 * （actorId / actorRole，请求线程上解析一次）。本类只是把它们搬过去 ——
 * 所以它能被纯单测（{@code ObserveContextTest}：不碰 Spring、不碰 HTTP）。
 */
public record ObserveContext(String traceId, String batchId, String actorId, Integer actorRole) {

    /**
     * 在**当前线程**上取一份快照。不在请求线程上时四个字段都是 {@code null}（快照仍然合法 ——
     * 「没有上下文」也是一种上下文，{@link #run} 照样能用它把子线程还原成「没有」）。
     *
     * <p>⚠️ 后两个（{@code actorId} / {@code actorRole}）是块 7 · 阶段 3a.3 补的：不补的话，
     * 三处派生线程上记出的事件会**有 traceId 却没有 actor**（同一件事的两个属性分家），
     * 页面上表现为「这条事件知道是哪次请求、却不知道是谁」。四件一起搬是有意的。
     */
    public static ObserveContext capture() {
        return new ObserveContext(TraceIdFilter.current(), TraceIdFilter.currentBatch(),
                TraceIdFilter.currentActorId(), TraceIdFilter.currentActorRole());
    }

    /**
     * 在本线程上执行 {@code body}：先把本上下文绑上，跑完**还原成本线程原来的上下文**。
     *
     * <p>⚠️ 必须用 {@code finally} 还原：{@code body} 抛异常时若把绑上的号留在池线程上，
     * 下一个拿到这条线程的**别的**请求就会顶着别人的 traceId/batchId 记事件 —— 这类污染
     * 在页面上表现为「不相干的事件挂进了同一条链路」，且事后无法从数据上分辨。
     */
    public void run(Runnable body) {
        ObserveContext previous = capture();
        bind(this);
        try {
            body.run();
        } finally {
            bind(previous);
        }
    }

    /** 同 {@link #run}，给有返回值的任务。 */
    public <T> T call(Supplier<T> body) {
        ObserveContext previous = capture();
        bind(this);
        try {
            return body.get();
        } finally {
            bind(previous);
        }
    }

    /** 把给定上下文写进本线程的 MDC。⚠️ 转手即走 {@link TraceIdFilter#bind} —— 本类不碰 MDC。 */
    private static void bind(ObserveContext c) {
        TraceIdFilter.bind(c.traceId(), c.batchId(), c.actorId(), c.actorRole());
    }
}
