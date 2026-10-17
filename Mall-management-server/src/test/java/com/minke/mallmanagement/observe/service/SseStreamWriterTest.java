package com.minke.mallmanagement.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SSE 出口层（{@link SseStreamWriter}）的**线格式**实测 —— 只测字节，不测业务。
 *
 * <h2>【为什么这层必须有个测】</h2>
 * 它写出的东西**不是给 Java 读的**，是给浏览器端的 {@code ReadableStream} 解析的：
 * 少一个空行 = 前端永远等不到完整帧（页面看着像死机）；多一个裸换行 = 一帧被劈成两帧
 * （前端解析当场变形）。这两种错都不会在别处报出来 —— 只能在这里钉住字节。
 *
 * <p>用 {@code interval-ms = 0}（不 sleep ⇒ 测试不慢）+ 有限的 {@code max-ticks}
 * （不挂住）。本文件顺带钉住这两个「测试专用口子」本身：它们**真的能把流停下来**。
 */
class SseStreamWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("线格式：每帧 `data: <单行 JSON>` + 空行；帧内无裸换行")
    void writesOneDataFramePerTick() throws IOException {
        String body = run(new SseStreamWriter(MAPPER, 0, 3), tagFrame());

        assertThat(body).as("逐字节：三帧、每帧一个 data 行 + 一个空行")
                .isEqualTo("data: {\"seq\":0,\"tag\":\"a\"}\n\n"
                        + "data: {\"seq\":1,\"tag\":\"a\"}\n\n"
                        + "data: {\"seq\":2,\"tag\":\"a\"}\n\n");
        assertFraming(body, 3);
    }

    @Test
    @DisplayName("max-ticks=1（smoke profile 钉的值）⇒ 恰好一帧后正常收工，流不挂住")
    void oneTickStopsTheStream() throws IOException {
        String body = run(new SseStreamWriter(MAPPER, 0, 1), tagFrame());

        assertThat(body).as("只写了一帧：第 1 拍从未发生").isEqualTo("data: {\"seq\":0,\"tag\":\"a\"}\n\n");
        assertFraming(body, 1);
    }

    @Test
    @DisplayName("值里带换行也劈不开帧：Jackson 逃逸 ⇒ 帧内仍无裸换行")
    void newlineInsideAValueCannotSplitAFrame() throws IOException {
        String body = run(new SseStreamWriter(MAPPER, 0, 2), tick -> {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("seq", tick);
            frame.put("text", "第一行\n第二行\r\n第三行");
            return frame;
        });

        assertFraming(body, 2);
        assertThat(body).as("内容仍在（被逃逸成 \\n / \\r\\n 这些**两字符**），只是不再有裸换行")
                .contains("第一行\\n第二行").contains("\\r\\n");
    }

    @Test
    @DisplayName("帧源抛异常 = 该条流就此结束：异常不外抛，**已写出的帧仍在**")
    void frameSourceFailureEndsTheStreamQuietly() {
        // max-ticks = 0（无限）⇒ 这条流只能靠帧源抛异常停下来，正是本用例要测的那条路。
        SseStreamWriter writer = new SseStreamWriter(MAPPER, 0, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatCode(() -> writer.stream("test", () -> tick -> {
            if (tick >= 2) {
                throw new IllegalStateException("故意：第 2 拍起炸");
            }
            return Map.of("seq", tick);
        }).writeTo(out)).as("流线程上的异常被吃掉（断流 = 页面看得见的终态，不往上抛）")
                .doesNotThrowAnyException();

        assertThat(out.toString(StandardCharsets.UTF_8)).as("炸之前的两帧照常写了出来")
                .isEqualTo("data: {\"seq\":0}\n\ndata: {\"seq\":1}\n\n");
    }

    @Test
    @DisplayName("负数被夹成 0：max-ticks<0 ⇒ 无限（不是「一帧不发」），interval<0 ⇒ 不抛")
    void negativeValuesAreClampedToZero() {
        // ⚠️ max-ticks = -1 夹成 0 = **无限**：若哪天真变成「一帧不写」，本用例会红
        //    （帧源在第 2 拍抛异常 ⇒ 断言它拿到了 2 帧，而不是 0 帧）。
        SseStreamWriter writer = new SseStreamWriter(MAPPER, -1, -1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatCode(() -> writer.stream("test", () -> tick -> {
            if (tick >= 2) {
                throw new IllegalStateException("停");
            }
            return Map.of("seq", tick);
        }).writeTo(out)).doesNotThrowAnyException();

        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("负数 max-ticks 是「无限」而不是「零帧」").isEqualTo(
                        "data: {\"seq\":0}\n\ndata: {\"seq\":1}\n\n");
    }

    @Test
    @DisplayName("丙（块 7 · commit 10）：帧源工厂**每条连接各调一次** —— 同一个响应体写两遍，第二次仍从第 0 拍起")
    void frameSourceIsCreatedPerConnection() throws IOException {
        SseStreamWriter writer = new SseStreamWriter(MAPPER, 0, 2);
        List<String> factoryCalls = new ArrayList<>();

        StreamingResponseBody body = writer.stream("test", () -> {
            factoryCalls.add("get()");
            // ⚠️ 这一份帧源**自己的**状态：每连接一份。若工厂只被调一次、帧源被复用，
            //    第二遍写出来的 `own` 会接着第一遍的数往下走（0,1 → 2,3）—— 那就是状态串了。
            long[] mine = {0L};
            return tick -> {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("seq", tick);
                frame.put("own", mine[0]++);
                return frame;
            };
        });

        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        body.writeTo(first);      // 第 1 条连接
        body.writeTo(second);     // 第 2 条连接

        assertThat(factoryCalls).as("写了两遍 = 两条连接 ⇒ 工厂被调两次").hasSize(2);
        assertThat(second.toString(StandardCharsets.UTF_8)).as("两次的字节完全一样（各从 0 起）")
                .isEqualTo(first.toString(StandardCharsets.UTF_8))
                .isEqualTo("data: {\"seq\":0,\"own\":0}\n\ndata: {\"seq\":1,\"own\":1}\n\n");
    }

    // ================= 工具 =================

    private static SseStreamWriter.FrameSource tagFrame() {
        return tick -> {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("seq", tick);
            frame.put("tag", "a");
            return frame;
        };
    }

    private static String run(SseStreamWriter writer, SseStreamWriter.FrameSource source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ⚠️ 传的是**工厂**（块 7 · commit 10 的「丙」）：这里给一份「每次现同一个人」的工厂，
        //    够本文件用 —— 它测的是线格式，不测「每连接一份」（那条在下面的独立用例里）。
        writer.stream("test", () -> source).writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * 两条不变量：① 帧数 = 空行个数（每个空行结束一帧，不多不少，且流以空行收尾）；
     * ② 每帧的 payload 段里**没有**裸换行（有的话它当场被劈成两帧）。
     */
    private static void assertFraming(String body, int expectedFrames) {
        String[] parts = body.split("\n\n", -1);
        assertThat(parts).as("拆出的段数 = 帧数 + 收尾的那个空串").hasSize(expectedFrames + 1);
        assertThat(parts[expectedFrames]).as("流以空行收尾").isEmpty();
        for (int i = 0; i < expectedFrames; i++) {
            assertThat(parts[i]).as("第 %d 帧以 `data: ` 开头", i).startsWith("data: ");
            assertThat(parts[i]).as("第 %d 帧的 payload 里不得有裸换行", i)
                    .doesNotContain("\n").doesNotContain("\r");
        }
    }
}
