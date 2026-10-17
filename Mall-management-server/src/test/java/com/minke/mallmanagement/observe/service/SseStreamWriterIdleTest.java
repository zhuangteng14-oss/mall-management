package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A7（块 7 收尾）空闲看门狗的**确定性**单测 —— 单独成文件，因为它是本类唯一
 * 「靠时间判定」的行为，塞进常规用例里会让每次跑都多等几百毫秒。
 *
 * <h2>【判据是「连续 N 毫秒没有一次成功写出去」，不是「没有新事件」】</h2>
 * 本流每秒都写一帧（心跳），健康连接上永远有写出 ⇒ 默认 30 秒阈值**不会**断开正常页面。
 * 真正该断的是「写不出去」：客户端半死 / 网络黑洞 ⇒ {@code out.write} 阻塞 ⇒
 * 看门狗从另一条线程 {@code close()} 这条输出流，把它逼出异常并结束循环。
 *
 * <h2>【两条用例，缺一不可】</h2>
 * <ul>
 *   <li><b>负向</b>（写不出去 ⇒ 必须断）：光测这条时，把实现写成「永远 close」也会绿；</li>
 *   <li><b>正向</b>（写得出去 ⇒ 不许断）：证明看门狗只掐「写不出去」，不误杀正常流。</li>
 * </ul>
 */
class SseStreamWriterIdleTest {

    /** 极小阈值 + 极小节拍：两条用例合计不到 1 秒。 */
    private static final long INTERVAL_MS = 10L;
    private static final long IDLE_MS = 100L;

    private static SseStreamWriter writer(int maxTicks) {
        return new SseStreamWriter(new ObjectMapper(), INTERVAL_MS, maxTicks, IDLE_MS);
    }

    private static Map<String, Object> frame(long tick) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tick", tick);
        return m;
    }

    @Test
    @DisplayName("写不出去（客户端半死）⇒ 看门狗在阈值后主动 close，流线程当场收工")
    void idleWatchdogClosesAStalledStream() throws Exception {
        BlockingOutputStream out = new BlockingOutputStream(1);   // 第 1 帧写成功，之后全阻塞
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();

        Thread streamThread = new Thread(() -> {
            try {
                writer(0).stream("test-stalled", () -> SseStreamWriterIdleTest::frame)
                        .writeTo(out);
            } catch (Exception ignored) {
                // 正常终态：看门狗 close 之后，阻塞中的 write 抛 IOException，本层 catch 掉。
            } finally {
                done.countDown();
            }
        }, "sse-idle-test-stalled");
        streamThread.setDaemon(true);
        streamThread.start();

        assertThat(done.await(5, TimeUnit.SECONDS))
                .as("连续 %d ms 没有成功写出 ⇒ 看门狗应主动 close 并结束流（≤5s 内）", IDLE_MS)
                .isTrue();
        assertThat(out.closed.get()).as("结束的原因是看门狗 close 了输出流，不是别的东西").isTrue();
        assertThat(out.writes.get()).as("第 1 帧成功，第 2 帧阻塞 —— 阻塞点被 close 打断")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("正常写出（每拍都成功）⇒ 按 max-ticks 自然结束，看门狗不误杀")
    void healthyStreamIsNotKilledByTheWatchdog() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writer(3).stream("test-healthy", () -> SseStreamWriterIdleTest::frame).writeTo(out);

        String text = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).as("三帧都写出来了（若有误杀，这里会少帧）").contains("\"tick\":0")
                .contains("\"tick\":1").contains("\"tick\":2");
        assertThat(text).as("每帧都以 SSE 的空行收尾").contains("\n\n");
    }

    /**
     * 前 {@code allowedWrites} 次写正常返回，之后**阻塞**直到 {@link #close()} —— 模拟
     * 「TCP 缓冲区吃满、对端不再读」。{@code close()} 之后阻塞的写抛 {@link IOException}，
     * 与真实 Tomcat 输出流被关闭时的形态一致（流循环靠它退出）。
     */
    private static final class BlockingOutputStream extends OutputStream {

        private final int allowedWrites;
        private final AtomicInteger writes = new AtomicInteger();
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private BlockingOutputStream(int allowedWrites) {
            this.allowedWrites = allowedWrites;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int n = writes.incrementAndGet();
            if (n > allowedWrites) {
                try {
                    // ⚠️ 带超时等待：实现若坏了（看门狗没 close），测试必须**失败**而不是挂住。
                    if (!closedLatch.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("blocking stream was never closed");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                throw new IOException("stream closed by idle watchdog");
            }
        }

        @Override
        public void flush() {
            // 无缓冲。
        }

        @Override
        public void close() {
            closed.set(true);
            closedLatch.countDown();
        }
    }
}
