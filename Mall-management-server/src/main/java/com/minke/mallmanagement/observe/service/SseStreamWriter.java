package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SSE 推流出口 —— 块 7 的三个观测端点（{@code /admin/observe/{metrics,orders,events}/stream}）共用的
 * 「按节拍一帧一帧往外写」的那一层。**只做传输，不认识任何业务字段**。
 *
 * <h2>【为什么是 {@code StreamingResponseBody}，不是 {@code SseEmitter}，也不是 {@code EventSource}】</h2>
 * <ul>
 *   <li><b>不用 {@code EventSource}（浏览器原生 SSE 客户端）</b>：它**不能带自定义请求头**，
 *       而本仓的鉴权票走 {@code Authorization: Bearer}（{@code AuthInterceptor.java:178-180}：
 *       role=3 的管理员 cookie 会话被折成未登录）⇒ 默认 profile 上 {@code EventSource} 必然 401。
 *       前端改用 {@code fetch} + {@code ReadableStream} 手工解析 {@code text/event-stream}
 *       （读法见 {@code api/observe.js}），故本类写出的就是**最朴素的 SSE 线格式**。</li>
 *   <li><b>不用 {@code SseEmitter}</b>：它把「何时发下一帧」交给调用方自己排（要么自建定时器、
 *       要么另起线程），而本块要的只是一条「每 N 毫秒算一帧」的循环 ——
 *       循环写在 {@link StreamingResponseBody} 的 lambda 里是最短的一条路，且**断连即停**是免费的
 *       （写已关闭的输出流当场抛 {@link IOException}，见下面的 catch）。</li>
 *   <li><b>代价（如实记下）</b>：{@code StreamingResponseBody} 每连接占**一条异步线程**
 *       （{@code WebMvcConfigurer} 的默认异步执行器是每次请求新建线程，**不占** Tomcat 的
 *       请求线程池）。块 7 至多 4 个页面各开 1 条 ⇒ 4 条线程；换成 {@code SseEmitter} 省不下
 *       多少，却要多管一套超时与并发容器。</li>
 * </ul>
 *
 * <h2>【节拍与终止】</h2>
 * <ul>
 *   <li><b>首帧立即发</b>（进循环前不 sleep）：页面一挂载就有数据，不空等一个节拍；</li>
 *   <li>此后每 {@code mall.observe.sse.interval-ms}（默认 1000）发一帧；</li>
 *   <li>{@code mall.observe.sse.max-ticks}（默认 0 = 无限）大于 0 时，推满这么多帧**主动结束**
 *       —— 它是给**自动化测试与排查**用的「抓一帧就够」口子（smoke profile 钉 1）。
 *       ⚠️ 它**不是**限流也不是降级：生产默认值 0 才是本端点的语义（长连接一直推）。</li>
 * </ul>
 *
 * <h2>【帧格式】</h2>
 * <pre>
 *   data: {"seq":0,"ts":1760000000000, ...}\n
 *   \n
 * </pre>
 * 即每帧一行 {@code data:} + 一个空行 —— 满足 SSE 规范的最小形态，**不写** {@code event:} /
 * {@code id:} / {@code retry:} 三个字段：本块的前端是自己解析的（不是 {@code EventSource}），
 * 多写字段等于给将来读代码的人三条「它靠这个工作吗」的误导。
 *
 * <p>⚠️ <b>帧内绝不出现裸换行</b>：SSE 以 {@code \n} 分隔帧，JSON 里混进裸 {@code \n}
 * 会把一帧劈成两帧（前端解析当场变形）。JSON 由 Jackson 序列化（默认紧凑输出，字符串里的换行
 * 逃逸成 {@code \n} 两字符），{@link #writeFrame} 另做一次剥除作为**兜底不变量**。
 */
@Component
public class SseStreamWriter {

    private static final Logger log = LoggerFactory.getLogger(SseStreamWriter.class);

    /** 帧里的换行兜底（理由见类注释最后一节）。 */
    private static final Pattern NEWLINE = Pattern.compile("[\\r\\n]");

    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FRAME_END = "\n\n".getBytes(StandardCharsets.UTF_8);

    /**
     * 一帧的产地：给定第几拍（从 0 起），答一组要写成 JSON 的字段。
     *
     * <p>⚠️ <b>在流线程上被调用、且每秒一次</b> ⇒ 实现里**不要做慢 I/O 之外的等待**
     * （本块三个实现的读法都是「读内存里的环 + 至多一次短查询」，见各自类注释）。
     * 实现抛异常 = 这条流就此结束（不重试、不吞成空帧）—— 与「出错也要让页面看见」
     * 相比，本块的取舍是**宁可断流**：半死的流会让页面显示一份看起来正常的旧数据。
     */
    @FunctionalInterface
    public interface FrameSource {
        Map<String, Object> frame(long tick);
    }

    private final ObjectMapper mapper;
    private final long intervalMs;
    private final int maxTicks;

    public SseStreamWriter(ObjectMapper mapper,
                           @Value("${mall.observe.sse.interval-ms:1000}") long intervalMs,
                           @Value("${mall.observe.sse.max-ticks:0}") int maxTicks) {
        this.mapper = mapper;
        this.intervalMs = Math.max(0L, intervalMs);
        this.maxTicks = Math.max(0, maxTicks);
    }

    /**
     * 把 {@code source} 包成一条可持续写出的响应体。
     *
     * @param tag    仅用于日志（哪条流断了），不要放业务数据
     * @param source 逐拍产帧
     */
    public StreamingResponseBody stream(String tag, FrameSource source) {
        return out -> {
            long tick = 0;
            try {
                while (maxTicks <= 0 || tick < maxTicks) {
                    if (tick > 0) {
                        Thread.sleep(intervalMs);
                    }
                    writeFrame(out, source.frame(tick));
                    tick++;
                }
                log.debug("[observe] {} 流按 max-ticks={} 正常结束", tag, maxTicks);
            } catch (InterruptedException e) {
                // 容器关停 / 池回收：恢复中断位后收工，**不**把它当业务失败。
                Thread.currentThread().interrupt();
                log.debug("[observe] {} 流被中断，收工", tag);
            } catch (Exception e) {
                // ⚠️ 客户端断开在容器里就长这样（Tomcat 抛 ClientAbortException : IOException）
                //    ⇒ 这是**正常的终态**，按 debug 记一行即可，不 WARN、不重试。
                //    其余（含 FrameSource 自己抛）同样只记不吞：流结束 = 页面看得见（前端 onerror/close）。
                log.debug("[observe] {} 流结束：{}", tag, e.toString());
            }
        };
    }

    /** 写一帧：{@code data: <json>\n\n}，写完立刻 flush（不 flush = 攒在缓冲里，页面看起来「不动」）。 */
    private void writeFrame(OutputStream out, Map<String, Object> frame) throws IOException {
        String payload = NEWLINE.matcher(mapper.writeValueAsString(frame)).replaceAll("");
        out.write(DATA_PREFIX);
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.write(FRAME_END);
        out.flush();
    }
}
