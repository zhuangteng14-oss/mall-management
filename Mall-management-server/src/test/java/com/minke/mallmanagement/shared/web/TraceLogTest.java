package com.minke.mallmanagement.shared.web;

import com.minke.mallmanagement.shared.web.TraceLog.RequestTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link TraceLog} 的**纯单测**（块 7 · commit 8b 补，用户 2026-10-14 批文点名）——
 * 记什么、取什么、游标怎么算、覆盖怎么淘汰。**不起 Spring、不起 MySQL**（同
 * {@code TraceIdFilterTest} 的姿势：本类的全部价值就在「零依赖」，用切片去测它是在验框架）。
 *
 * <h2>【本笔为什么非补不可】</h2>
 * commit 8 的实时日志页点开一条事件卡片要显示「**这一次请求的完整链路 + 总耗时**」，
 * 而总耗时的**唯一**数据来源就是本类的 {@code startMs} / {@code endMs} 两列（{@code ms} 是它俩的差）。
 * ⇒ 这两列一旦记错（比如把 {@code endMs} 也写成开始时刻），前端那行会显示「0ms」而且
 * **没有任何报错**——这正是 TD-19/TD-20 那一族「静默错」的形态。故两列**各有用例单独断言**，
 * 不靠「ms 对得上」间接推（{@code ms = end - start} 在 start == end 时也等于 0，推不出来）。
 *
 * <h2>【⚠️ 台账是 {@code static} 的：每个用例只断言自己的增量】</h2>
 * 同一个 JVM 里，其它用例（{@code TraceIdFilterTest} 等）也会往这个静态台账里写。⇒
 * 每个用例先取一次 {@link TraceLog#latestSeq()} 当**基准游标**，再用
 * {@link TraceLog#since(long)} 只取基准之后的部分 —— 口径与 {@code EventStreamServiceTest}
 * 那条「先空跑一拍把存量送掉」同源（那边不能空跑，只能取增量）。**别**写
 * {@code since(-1)} 就断言「恰好 N 条」的用例（本文件里唯一用得上全量的地方是覆盖淘汰那条，
 * 它自己写满了 300 条 ⇒ 窗口里全是它写的）。
 */
class TraceLogTest {

    /** 本类 {@code CAPACITY} 的值（私有常量，改它就要同改这里与覆盖淘汰那条用例）。 */
    private static final int CAPACITY = 256;

    /**
     * 记一条「刚发生完」的请求，返回**记之前**的游标。
     *
     * <p>⚠️ 基准必须在 {@code record} **之前**取：取在之后会漏掉这条（{@code since} 的入参是
     * 「我上次读到哪」）。
     */
    private static long recordAt(String traceId, String path, long startMs, long endMs) {
        long base = TraceLog.latestSeq();
        TraceLog.record(traceId, "POST", path, 200, startMs, endMs);
        return base;
    }

    // ================= 起止两列（本笔的判据） =================

    @Test
    @DisplayName("record 把 startMs / endMs 两列**原样**存下来；ms = end - start")
    void recordKeepsBothEndpointColumns() {
        long base = recordAt("trace-start-end", "/order/orderAdd", 1_700_000_000_000L, 1_700_000_000_042L);

        List<RequestTrace> rows = TraceLog.since(base);

        assertThat(rows).as("本用例只该多出这一条").hasSize(1);
        RequestTrace t = rows.get(0);
        assertThat(t.startMs()).as("起始列：墙钟毫秒，原样（不是 nanoTime、不被替换成结束时刻）")
                .isEqualTo(1_700_000_000_000L);
        assertThat(t.endMs()).as("结束列：墙钟毫秒，原样（不是开始时刻、不是 now()）")
                .isEqualTo(1_700_000_000_042L);
        assertThat(t.ms()).as("总耗时 = 两列之差，前端不必再减").isEqualTo(42L);
        assertThat(t.traceId()).isEqualTo("trace-start-end");
        assertThat(t.method()).isEqualTo("POST");
        assertThat(t.path()).as("记的是 URI，不含 query（query 里可能有敏感值）")
                .isEqualTo("/order/orderAdd");
        assertThat(t.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("同一拍里的多条：**每一条**的起止两列都各归各（不串号、不共用一个值）")
    void sinceKeepsEndpointColumnsPerRow() {
        long base = TraceLog.latestSeq();
        TraceLog.record("tr-a", "POST", "/goods/goodsUp", 0, 10L, 25L);
        TraceLog.record("tr-b", "POST", "/cart/cartAdd", 0, 100L, 100L);
        TraceLog.record("tr-c", "GET", "/goods/goodsList", 0, 500L, 1_500L);

        assertThat(TraceLog.since(base))
                .extracting(RequestTrace::traceId, RequestTrace::startMs, RequestTrace::endMs, RequestTrace::ms)
                .as("三列的对应关系逐条核：串一个号，前端抽屉那条链路的总耗时就是别人的")
                .containsExactly(
                        tuple("tr-a", 10L, 25L, 15L),
                        tuple("tr-b", 100L, 100L, 0L),      // 起止相同 ⇒ 0ms（合法：极快的一次请求）
                        tuple("tr-c", 500L, 1_500L, 1_000L));
    }

    @Test
    @DisplayName("endMs 早于 startMs（时钟回拨）⇒ ms 归 0，不出现负数")
    void msNeverNegative() {
        long base = recordAt("trace-clock-back", "/goods/goodsUp", 2_000L, 1_000L);

        assertThat(TraceLog.since(base)).extracting(RequestTrace::ms)
                .as("负的总耗时会让前端显示成 -1000ms；这里兜底成 0（与事件环的 ms 兜底同一口径）")
                .containsExactly(0L);
        assertThat(TraceLog.since(base)).extracting(RequestTrace::startMs, RequestTrace::endMs)
                .as("⚠️ 兜底只动 ms，两列仍原样 —— 否则「时钟回拨」这件事在数据里就查不出来了")
                .containsExactly(tuple(2_000L, 1_000L));
    }

    // ================= 游标口径 =================

    @Test
    @DisplayName("since(afterSeq) 是**开区间**：入参那条自己不回，它之后的才回")
    void sinceIsExclusive() {
        long base = TraceLog.latestSeq();
        TraceLog.record("tr-1", "POST", "/a", 0, 0L, 1L);
        TraceLog.record("tr-2", "POST", "/b", 0, 0L, 2L);
        TraceLog.record("tr-3", "POST", "/c", 0, 0L, 3L);

        // ⚠️ 坐标对齐先钉住：`latestSeq` 是「**最后一条**的 seq」，故新记的三条依次是 base+1 / +2 / +3。
        //    （本用例第一版把 base 当成 tr-1 的 seq，于是三条断言全错 —— 记在这里免得下次再踩。）
        assertThat(TraceLog.latestSeq()).as("latestSeq 与 since 同一坐标系：最后一条的 seq").isEqualTo(base + 3);

        assertThat(TraceLog.since(base)).extracting(RequestTrace::traceId)
                .as("基准之后再读 ⇒ 三条全回").containsExactly("tr-1", "tr-2", "tr-3");
        assertThat(TraceLog.since(base + 2)).extracting(RequestTrace::traceId)
                .as("读到 tr-2（seq = base+2）之后再读 ⇒ 只回 tr-3（含边界：base+2 那条自己不回）")
                .containsExactly("tr-3");
        assertThat(TraceLog.since(base + 3)).as("读到最新 ⇒ 空（不重复送）").isEmpty();
    }

    @Test
    @DisplayName("since 入参 < 0（含首次读）⇒ 当作「要一屏全量」：右端是最新一条，左端是窗口内最早的一条")
    void sinceNegativeMeansFullWindow() {
        TraceLog.record("tr-in-window", "POST", "/goods/goodsUp", 200, 0L, 7L);
        long newest = TraceLog.latestSeq();

        // ⚠️ 本用例**不能**断言「恰好一条」—— 台账是静态的，同 JVM 里别的用例也往里写（见类注释）。
        List<RequestTrace> all = TraceLog.since(-1);
        assertThat(all).extracting(RequestTrace::traceId).contains("tr-in-window");
        assertThat(all.get(all.size() - 1).seq()).as("全量的右端 = 最新一条").isEqualTo(newest);
        assertThat(all.get(0).seq()).as("左端 ≥ 0（-1 不是「全历史」：被覆盖掉的取不回来）")
                .isGreaterThanOrEqualTo(0L)
                .isLessThanOrEqualTo(newest);
        assertThat(all.get(all.size() - 1).seq() - all.get(0).seq() + 1)
                .as("窗口内的 seq 是连续的（淘汰只丢最早的一批，不在中间挖洞）")
                .isEqualTo(all.size());
    }

    // ================= 不记的情形 =================

    @Test
    @DisplayName("traceId 为 null ⇒ 整条丢弃（无号的记录进台账只会占位）")
    void nullTraceIdIsDropped() {
        long before = TraceLog.latestSeq();
        TraceLog.record(null, "POST", "/goods/goodsUp", 200, 1L, 2L);

        assertThat(TraceLog.latestSeq()).as("游标都不该动（不是「记了一条空记录」）").isEqualTo(before);
    }

    @Test
    @DisplayName("/stream 结尾的长连接**不记**：SSE 要等客户端断开才算「结束」，记进去是一条不断变长的假记录")
    void streamPathIsSkipped() {
        long before = TraceLog.latestSeq();
        TraceLog.record("tr-stream", "GET", "/admin/observe/events/stream", 200, 1L, 90_000L);
        assertThat(TraceLog.latestSeq()).as("三条 SSE 端点（events / orders / metrics）都落这个判据")
                .isEqualTo(before);

        // ⚠️ 边界：判的是**后缀**，不是「路径里含 stream」——「/streams」或「?stream=1」不在此列。
        long base = TraceLog.latestSeq();
        TraceLog.record("tr-not-stream", "POST", "/admin/observe/events/stream-status", 200, 1L, 2L);
        assertThat(TraceLog.since(base)).extracting(RequestTrace::traceId)
                .as("只有恰以 /stream 结尾的才跳过").containsExactly("tr-not-stream");
    }

    // ================= 覆盖边界 =================

    @Test
    @DisplayName("写满超过 CAPACITY ⇒ 丢最早的，窗口里永远是最新的 256 条（覆盖即淘汰，不报错）")
    void overwriteDropsOldest() {
        int total = CAPACITY + 44;
        long base = TraceLog.latestSeq();
        for (int i = 0; i < total; i++) {
            TraceLog.record(String.format("ov-%03d", i), "POST", "/goods/goodsUp", 200, i, i + 1);
        }

        List<RequestTrace> window = TraceLog.since(-1);

        assertThat(window).as("窗口大小 = CAPACITY（本用例自己写满了，窗口里全是它写的）").hasSize(CAPACITY);
        assertThat(window.get(0).traceId()).as("最早的一条已被覆盖（写了 300 条 ⇒ 前 44 条丢）")
                .isEqualTo("ov-044");
        assertThat(window.get(window.size() - 1).traceId()).as("最新的一条在（ov-299）").isEqualTo("ov-299");
        // ⚠️ 这里**不**硬算范围（第一版写成 base + total - CAPACITY，差一个 —— 因为 `base` 是
        //    「记之前最后一条的 seq」，新记的 300 条是 base+1 .. base+total）。
        //    改成结构性判据：升序 + 不重复 + 两端差 = 条数 - 1 ⇒ 恰好连续、恰好是最后 256 个号。
        assertThat(window).extracting(RequestTrace::seq)
                .as("seq 仍是连续的、升序的 —— 淘汰只丢最早的，不挖洞")
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(window.get(window.size() - 1).seq() - window.get(0).seq() + 1)
                .as("两端之差 = 条数 ⇒ 中间没有洞（被覆盖的那 44 条只可能是最早的一批）")
                .isEqualTo(window.size());
        assertThat(window.get(0).seq()).as("剩下的第一批恰好接着窗口起点")
                .isEqualTo(base + total - CAPACITY + 1);
    }
}
