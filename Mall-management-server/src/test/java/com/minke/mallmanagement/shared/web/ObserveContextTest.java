package com.minke.mallmanagement.shared.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ObserveContext} 的语义实测 —— <b>不碰 Spring、不碰 HTTP</b>（本类 javadoc 末句承诺的就是它）。
 *
 * <p>⚠️ <b>这里测的是「搬」的三条性质</b>，不是「号从哪来」：号的两个来源（
 * {@link TraceIdFilter#pick} 与 {@code AttackEngine} 的 UUID）各自有测试，本类只用字面量当号。
 * 三条性质分别是 —— ① 捕获的是**那一刻的快照**（不是引用）；② 在别的线程上绑得住；
 * ③ 跑完**还原成本线程原来的值**（而不是清空）。第 ③ 条是这三条里唯一会**污染别人数据**的一条
 * （池线程复用 ⇒ 带着别人的号记事件），故它有三个用例（正常 / 原来是空的 / body 抛异常）。
 *
 * <p>⚠️ 线程一律用**新建的** {@code Thread} 而不是池：本类要验的是「另一个线程上的 MDC 与请求线程无关」
 * 这条**基础设施事实**，用池反而会把「池线程复用」这层噪声引进来（那一层由 ③ 的三个用例单独覆盖）。
 */
class ObserveContextTest {

    private static final String TRACE = "trace-aaaa1111";
    private static final String BATCH = "batch-bbbb2222";

    @AfterEach
    void clearMdc() {
        // ⚠️ 必须清：JUnit 在**同一个线程**上跑完本类全部用例 ⇒ 上一个用例残留的号会顶掉下一个的
        //    `captureOffRequestThreadYieldsNulls…`（它会读到上一个用例的值而红）。
        MDC.clear();
    }

    // ==================== 一、快照 + 跨线程 ====================

    @Test
    @DisplayName("请求线程捕获 → 另一个线程 run：那个线程上能读到同一对号")
    void carriesBothIdsToAnotherThread() {
        MDC.put(TraceIdFilter.MDC_KEY, TRACE);
        MDC.put(TraceIdFilter.MDC_BATCH_KEY, BATCH);

        ObserveContext ctx = ObserveContext.capture();
        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenBatch = new AtomicReference<>();

        MDC.clear();   // 请求线程这边**先清掉**：下面读到的东西只可能来自 ctx（否则用例自欺）
        runOnAnotherThread(() -> ctx.run(() -> {
            seenTrace.set(TraceIdFilter.current());
            seenBatch.set(TraceIdFilter.currentBatch());
        }));

        assertThat(seenTrace.get()).as("traceId 搬过去了").isEqualTo(TRACE);
        assertThat(seenBatch.get()).as("batchId 同时搬过去（两个号共用同一套上下文）").isEqualTo(BATCH);
    }

    @Test
    @DisplayName("捕获是**快照**：捕获之后再改源线程的 MDC，子线程拿到的仍是捕获那一刻的值")
    void capturedValuesAreASnapshotNotAReference() {
        MDC.put(TraceIdFilter.MDC_KEY, TRACE);

        ObserveContext ctx = ObserveContext.capture();
        MDC.put(TraceIdFilter.MDC_KEY, "trace-cccc3333");   // 捕获之后才改

        AtomicReference<String> seen = new AtomicReference<>();
        runOnAnotherThread(() -> ctx.run(() -> seen.set(TraceIdFilter.current())));

        assertThat(seen.get()).as("跑的是捕获那一刻的值，不是改完的值").isEqualTo(TRACE);
    }

    @Test
    @DisplayName("call(T)：带上上下文求值，返回值与普通调用一致")
    void callCarriesContextAndReturnsValue() {
        MDC.put(TraceIdFilter.MDC_KEY, TRACE);

        AtomicReference<String> seen = new AtomicReference<>();
        Integer out = ObserveContext.capture().call(() -> {
            seen.set(TraceIdFilter.current());
            return 42;
        });

        assertThat(out).isEqualTo(42);
        assertThat(seen.get()).as("call 与 run 是同一套搬运（只是带返回值）").isEqualTo(TRACE);
    }

    // ==================== 二、还原（本类唯一会污染别人的性质） ====================

    @Test
    @DisplayName("⚠️ 跑完**还原成原来那个上下文**（不是清空）：池线程本来就属于另一个请求时，不能把它的号抹掉")
    void restoresThePreviousContextOfThatThread() {
        MDC.put(TraceIdFilter.MDC_KEY, TRACE);
        MDC.put(TraceIdFilter.MDC_BATCH_KEY, BATCH);
        ObserveContext ctx = ObserveContext.capture();
        MDC.clear();

        AtomicReference<String> afterTrace = new AtomicReference<>();
        AtomicReference<String> afterBatch = new AtomicReference<>();
        runOnAnotherThread(() -> {
            // 这条线程「本来就是别人的」：带着另一个请求的号
            TraceIdFilter.bind("trace-previous", "batch-previous", null, null);
            ctx.run(() -> { /* 干点活；这一段的上下文由 ctx 负责 */ });
            afterTrace.set(TraceIdFilter.current());
            afterBatch.set(TraceIdFilter.currentBatch());
        });

        assertThat(afterTrace.get()).as("跑完回到 prev，而不是 null").isEqualTo("trace-previous");
        assertThat(afterBatch.get()).isEqualTo("batch-previous");
    }

    @Test
    @DisplayName("原来是**空的**线程：跑完还是空的（还原 = 把键移除，不是留个空串）")
    void restoresToNothingWhenTheThreadHadNoContext() {
        MDC.put(TraceIdFilter.MDC_KEY, TRACE);
        ObserveContext ctx = ObserveContext.capture();   // 捕获点仍在**请求线程**上

        AtomicReference<String> afterTrace = new AtomicReference<>("(没跑到)");
        runOnAnotherThread(() -> {
            ctx.run(() -> { /* 干点活：这一段是「有上下文」，跑完该回到「没有」 */ });
            afterTrace.set(TraceIdFilter.current());
        });

        assertThat(afterTrace.get()).as("空 ↔ 空 是同一种状态（MDC.remove 而不是 put(\"\")）").isNull();
    }

    @Test
    @DisplayName("⚠️ body 抛异常时**照样还原**，异常原样传播 —— 漏还原会把号污染给下一条复用该线程的请求")
    void restoresEvenWhenTheBodyThrows() {
        MDC.put(TraceIdFilter.MDC_KEY, TRACE);
        ObserveContext ctx = ObserveContext.capture();
        MDC.clear();

        AtomicReference<String> afterTrace = new AtomicReference<>("(没跑到)");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        runOnAnotherThread(() -> {
            TraceIdFilter.bind("trace-previous", null, null, null);
            try {
                ctx.run(() -> {
                    throw new IllegalStateException("业务体自己炸了");
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
            afterTrace.set(TraceIdFilter.current());
        });

        assertThat(thrown.get()).as("异常不被吞：本类不负责兜底，只负责还原").isInstanceOf(IllegalStateException.class)
                .hasMessage("业务体自己炸了");
        assertThat(afterTrace.get()).as("炸了也要还原（finally 那一句就是为这个用例写的）")
                .isEqualTo("trace-previous");
    }

    @Test
    @DisplayName("⚠️ 空快照（capture 在非请求线程上）**不是「什么都不做」**：body 里那个线程的上下文要被真的抹掉，跑完再还原")
    void captureOffRequestThreadYieldsNulls() {
        MDC.clear();   // 本用例的前提：捕获点上确实没有上下文（不依赖上一个用例的收尾）
        ObserveContext ctx = ObserveContext.capture();
        assertThat(ctx.traceId()).as("不在请求线程上 ⇒ null（「没有上下文」是合法快照，不是缺失）").isNull();
        assertThat(ctx.batchId()).isNull();

        AtomicReference<String> insideTrace = new AtomicReference<>("(没跑到)");
        AtomicReference<String> afterTrace = new AtomicReference<>("(没跑到)");
        runOnAnotherThread(() -> {
            TraceIdFilter.bind("trace-someone-else", "batch-someone-else", null, null);
            ctx.run(() -> insideTrace.set(TraceIdFilter.current()));
            afterTrace.set(TraceIdFilter.current());
        });

        // ⚠️ 这条断言是本用例的**唯一价值**：`(null, null)` 绑上去等于「这条线程此刻没有上下文」，
        //    body 里若还读得到别人（`trace-someone-else`）的号，说明空快照被跳过了 —— 那会让
        //    「不属于任何演练的日常事件」错挂进某次演练的批次（`bind` 里 `null ⇒ remove` 就是为它写的）。
        assertThat(insideTrace.get()).as("body 里读到的是「没有」，不是「别人的」").isNull();
        assertThat(afterTrace.get()).as("跑完还原成这条线程原来的（不是继续空着）").isEqualTo("trace-someone-else");
    }

    // ==================== 工具 ====================

    /**
     * 起一条**新线程**跑 {@code body} 并等它结束。
     *
     * <p>⚠️ 异常在这里被原样重抛（包装成 RuntimeException）：上面 {@code body} 里自己 catch 的
     * 那些是**被测对象**的行为，而这个包装是**测试脚手架自己的失败** —— 两者必须能分开看，
     * 否则一个「探针线程里写错一行」会伪装成「被测对象没还原」。
     */
    private static void runOnAnotherThread(Runnable body) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "probe-thread");
        t.start();
        try {
            t.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等探测线程被中断", e);
        }
        if (t.isAlive()) {
            throw new IllegalStateException("探测线程 5 秒没结束（本类里没有会阻塞的东西，这一定是死循环）");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("探测线程自己失败了（不是被测对象的断言）", failure.get());
        }
    }
}
