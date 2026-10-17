package com.minke.mallmanagement.shared.text;

/**
 * 事件「失败原因」的统一收口（块 7 · 阶段 3a.3 后续）。
 *
 * <p>背景：九个事件环里只有审计 / 通知 / 身份三环自带 {@code reason}；缓存 / 锁 / 订单 /
 * 库存 / 敏感词 / 防护六环在失败或降级时只把原因写进日志，事件里只剩一个
 * {@code result=error} —— 日志页答不出「为什么」。本类给这六环的 {@code reason}
 * 提供同一条有界口径。
 *
 * <h2>【三条铁律】</h2>
 * <ol>
 *   <li><b>有界</b>：最多 {@value #MAX_LENGTH} 个字符，超长截断并加「…」。原因来自异常消息，
 *       长度不可控；它要进事件环（2048 槽）与 SSE 帧，不能变成第二处无界内存。</li>
 *   <li><b>不进 Micrometer tag</b>：reason 的取值基数无界（异常消息可含任意内容），
 *       只进事件 record → {@code fields.reason}。本类不提供任何 tag 用途的方法。</li>
 *   <li><b>空即无</b>：空白串折叠后视为 {@code null}，调用侧据此决定「不写这个键」——
 *       不要让页面出现一个空的「原因」。</li>
 * </ol>
 *
 * <p>⚠️ 本类不做任何 I/O、不记日志、不参与决策；纯字符串规整。
 */
public final class EventReason {

    /** 原因文本的上界。超过即截断 —— 见类注释第 1 条。 */
    static final int MAX_LENGTH = 160;

    private EventReason() {
    }

    /**
     * 折叠空白并截断。
     *
     * @param raw 原始原因；{@code null} / 空白 ⇒ {@code null}
     * @return 规整后的原因，长度 ≤ {@value #MAX_LENGTH}
     */
    public static String clip(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LENGTH - 1) + "…";
    }

    /**
     * 异常 → 「前缀：异常类名：message」，再 {@link #clip(String) 裁剪}。
     *
     * <p>只取<b>简单类名</b>与 message，不拼堆栈、不拼 SQL：堆栈属于日志，事件里要的是
     * 一句人能读懂的原因。message 为空时只留类名（不写「null」字面量）。
     *
     * @param prefix 人话前缀（如「库存扣减失败」）；空白时只用异常部分
     * @param cause  异常；{@code null} 时只返回裁剪后的前缀
     */
    public static String of(String prefix, Throwable cause) {
        String head = clip(prefix);
        if (cause == null) {
            return head;
        }
        String message = cause.getMessage();
        String body = cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : "：" + message);
        return clip(head == null ? body : head + "：" + body);
    }
}
