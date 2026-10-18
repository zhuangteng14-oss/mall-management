package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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
     *
     * <p>⚠️ <b>实现里可以、也应该放「这条连接自己的状态」</b>（游标 / 差分的上一拍读数）：
     * {@link #stream} 拿到的是**工厂**，每条连接现建一份 ⇒ 实例内的字段天然是「每连接一份」。
     * 三个产帧服务的写法见各自类注释的「每连接一份」一节。
     */
    @FunctionalInterface
    public interface FrameSource {
        Map<String, Object> frame(long tick);
    }

    private final ObjectMapper mapper;
    private final long intervalMs;
    private final int maxTicks;
    private final long idleTimeoutMs;

    /**
     * 空闲看门狗（A7 · 块 7 收尾）。⚠️ 必须 daemon：本仓自建线程一律守护，否则非守护线程会把 JVM 拖住
     * （见 {@code ObservePoolConfig} 的同款理由）。单线程足够 —— 每个任务只做一次纳秒比较，
     * 真正阻塞的是 {@code out.close()}，而它通常立刻返回。
     */
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-idle-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** 测试 / 手工构造用（默认空闲超时 30 秒，与生产一致）。 */
    public SseStreamWriter(ObjectMapper mapper, long intervalMs, int maxTicks) {
        this(mapper, intervalMs, maxTicks, 30_000L);
    }

    @Autowired
    public SseStreamWriter(ObjectMapper mapper,
                           @Value("${mall.observe.sse.interval-ms:1000}") long intervalMs,
                           @Value("${mall.observe.sse.max-ticks:0}") int maxTicks,
                           @Value("${mall.observe.sse.idle-timeout-ms:30000}") long idleTimeoutMs) {
        this.mapper = mapper;
        this.intervalMs = Math.max(0L, intervalMs);
        this.maxTicks = Math.max(0, maxTicks);
        this.idleTimeoutMs = Math.max(0L, idleTimeoutMs);
    }

    /**
     * 把 {@code frames} 包成一条可持续写出的响应体。
     *
     * <p>⚠️ <b>入参是<u>工厂</u>而不是帧源本身</b>（块 7 · commit 10 的「丙」）：本方法在
     * **这条连接自己的异步线程上**现调一次工厂、现建一份帧源。所以「帧源里存着游标 / 上一拍读数」
     * 这种写法在这里是**对的** —— 状态天然按连接隔离。
     *
     * <p>⚠️ <b>为什么不能传一份共享的帧源</b>（改前的样子，那时还配了一个 {@code FramePacer}
     * 把产帧压成「每半拍至多一次」）：差分量的读法**有副作用**（把计数器的读数读走、把环游标推着走），
     * 两条连接共用一份帧源时，**先算的那条会把后算的那条的数据吃掉** ⇒ 后开的那条看着像死机。
     * 实测（2026-10-14，同一轮演练 + 两条连接）：事件 A 独有 7168 条 · B 独有 6657 条 ·
     * **交集只有 420 条** —— 两条流各看了一半。
     *
     * @param tag    仅用于日志（哪条流断了），不要放业务数据
     * @param frames 逐拍产帧的**工厂**：每条连接各调一次
     */
    public StreamingResponseBody stream(String tag, Supplier<FrameSource> frames) {
        return stream(tag, frames, null);
    }

    /**
     * 同上，外加一个**关流钩子**（D3-6 新增）。
     *
     * <p>⚠️ {@code onClose} 在 lambda 的 {@code finally} 里跑：正常结束、客户端断开、
     * 产帧抛异常、空闲看门狗断开 —— 四条路径都会经过。本仓唯一的调用方是
     * {@link SseConnectionRegistry.Permit#release()}，漏调一次就永久少一个名额。
     * ⚠️ 钩子自身抛异常只记 debug，不外抛：流已经在收尾，再抛只会掩盖真正的结束原因。
     */
    public StreamingResponseBody stream(String tag, Supplier<FrameSource> frames, Runnable onClose) {
        return out -> {
            long tick = 0;
            // A7（块 7 收尾）：空闲超时在**本层**实现，不改 `spring.mvc.async.request-timeout=-1`
            // （那是 commit 10b 的刻意选择：容器在 30s 掐断会把正常长连接一起杀掉）。
            // 判据是「连续 idleTimeoutMs 没有一次**成功写出**」——不是「没有新事件」：
            // 每秒一帧本身是心跳，写不出去（客户端半死 / 网络黑洞）才是需要断开的状态。
            AtomicLong lastWriteNanos = new AtomicLong(System.nanoTime());
            AtomicBoolean idleFired = new AtomicBoolean(false);
            ScheduledFuture<?> idleGuard = null;
            if (idleTimeoutMs > 0) {
                long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs);
                long period = Math.max(50L, Math.min(intervalMs <= 0 ? 50L : intervalMs, 1_000L));
                idleGuard = WATCHDOG.scheduleAtFixedRate(() -> {
                    if (System.nanoTime() - lastWriteNanos.get() <= idleNanos) {
                        return;
                    }
                    if (idleFired.compareAndSet(false, true)) {
                        log.info("[observe] {} 流空闲超过 {} ms 未成功写出 ⇒ 主动断开", tag, idleTimeoutMs);
                    }
                    try {
                        // ⚠️ 从看门狗线程 close：目的是让阻塞中的 write/flush 立刻抛异常退出。
                        //    正常收工时本任务已被 cancel，不会走到这里。
                        out.close();
                    } catch (IOException ignored) {
                        // 已经断了：什么都不用做。
                    }
                }, period, period, TimeUnit.MILLISECONDS);
            }
            try {
                // ⚠️ 建在这里（lambda 内、每条连接各一次），不是建在 `stream(...)` 那一行上：
                //    调用方若把本方法返回的响应体缓存起来复用，状态也不会跨连接串。
                //    D3-6 起也放进 try：帧源工厂抛异常时 finally 仍要还名额。
                FrameSource source = frames.get();
                while (maxTicks <= 0 || tick < maxTicks) {
                    if (tick > 0) {
                        Thread.sleep(intervalMs);
                    }
                    writeFrame(out, source.frame(tick));
                    lastWriteNanos.set(System.nanoTime());
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
            } finally {
                if (idleGuard != null) {
                    idleGuard.cancel(false);
                }
                if (onClose != null) {
                    try {
                        onClose.run();
                    } catch (Exception e) {
                        log.debug("[observe] {} 流关闭钩子失败（忽略）：{}", tag, e.toString());
                    }
                }
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
