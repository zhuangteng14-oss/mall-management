package com.minke.mallmanagement.observe.mq;

import com.minke.mallmanagement.observe.service.EventBroadcaster.ObservedEvent;

/**
 * 事件台账的**出口**（块 7 · commit 8c）—— 把一条已归一的 {@link ObservedEvent} 送去
 * 「落库 + 进 Redis 列表」的那条路。
 *
 * <h2>【为什么先有接口，而不是直接注入 {@code RabbitTemplate}】</h2>
 * 两个都不是风格问题：
 * <ol>
 *   <li><b>调点在热路径上</b>：唯一调点是 {@code EventBroadcaster#add}（跑在 <b>SSE 流线程</b>上）。
 *       把 MQ 的类型直接写进那一行，意味着「换掉传输方式要改一个已被单测钉死的事件环」——
 *       而那一环的语义（覆盖式环形缓冲、seq 自校验）与「往哪儿发」毫无关系；</li>
 *   <li><b>切片要能起</b>：{@code @WebMvcTest} 把 {@code EventBroadcaster} 当真 bean 拉进切片
 *       （见 {@code AbstractSmokeTest}），而切片里**没有** AMQP 的任何基础设施。有一个接口，
 *       切片只需要补一个 {@code @MockitoBean EventPublisher}（一个，不是一套）。</li>
 * </ol>
 *
 * <h2>【契约：{@link #publish} 承诺「永不抛」】</h2>
 * 调用方（{@code EventBroadcaster#add}）在 SSE 流线程上，抛出去会把**实时日志页整条断流**
 * —— 而「台账落不了库」比「页面看不见任何事件」轻得多（前者是丢了证据，后者是丢了工具本身）。
 * ⇒ 实现类必须把一切异常吞在内部（fail-soft），并只记 WARN。
 *
 * <h2>【本接口只有两个方法，第二个是「上屏」而不是「控制」】</h2>
 * {@link #connected()} 回答的是**页面该画哪个颜色的点**（用户 2026-10-14 批文：
 * 「MQ 缺席上屏…8c 接通」）。它**不**参与任何决策：{@link #publish} 自己要不要跳过
 * 由实现类定（当前实现拿它当「已知连不上就别去撞网络」的闸），调用方不该据此分支。
 */
public interface EventPublisher {

    /**
     * 发布一条事件。**永不抛**（契约见类注释），**不保证送达**（fail-soft）：
     * 发布失败时事件仍留在内存合并环里，页面上照看得到，只是不落库。
     *
     * @param event 已归一的观测事件（{@code EventBroadcaster.ObservedEvent}）；
     *              非 null。⚠️ 它的 {@code detail} / {@code key} 两个字段**不会**落库
     *              （{@code observed_event} 刻意没有这两列，见 ⑲ 的列清单）
     */
    void publish(ObservedEvent event);

    /** 当前「MQ 这条路通不通」——**给页面画点用**（见类注释最后一节），不参与任何业务判定。 */
    boolean connected();
}
