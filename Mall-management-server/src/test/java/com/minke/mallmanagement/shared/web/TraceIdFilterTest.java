package com.minke.mallmanagement.shared.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TraceIdFilter} 的**纯单测**：不起 Spring 上下文，直接 new + 手工喂
 * {@code MockHttpServletRequest}。理由：本类的全部价值就在「零依赖、能在任何上下文里跑」，
 * 用 {@code @WebMvcTest} 去测它反而是在验证框架、且会（讽刺地）制造它本来要避免的切片负担。
 *
 * <p>⚠️ 本类**不是**在验「日志里真的出现了 traceId」—— 那一条由 {@code logback-spring.xml} 的
 * pattern 与 {@code %X{traceId}} 的连接决定，单测看不到。本类验的是它的**前提**：
 * 链中 MDC 里确实有值、响应头确实带值、值确实合法。pattern 那一半在真跑一次时肉眼可见。
 */
class TraceIdFilterTest {

    /** 自生成 id 的形状：标准 UUID（36 字符）。⚠️ 与 8b 的 {@code trace_id varchar(36)} 对齐。 */
    private static final Pattern UUID_36 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final TraceIdFilter filter = new TraceIdFilter();

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest("GET", "/goods/goodsList");
    }

    /**
     * 跑一遍完整的过滤链，返回「链执行**期间**从 MDC 看到的那个值」。
     * ⚠️ 必须是链中取、不是链后取：链后 MDC 已被 finally 清空（那正是要断言的另一件事）。
     */
    private String mdcDuringChain(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        String[] seen = new String[1];
        filter.doFilter(request, response, (rq, rs) -> seen[0] = MDC.get(TraceIdFilter.MDC_KEY));
        return seen[0];
    }

    @Test
    @DisplayName("无入参头 → 自生成 UUID；响应头与链中 MDC 是同一个值；链后 MDC 被清空")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletRequest request = req();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String during = mdcDuringChain(request, response);

        assertThat(during).matches(UUID_36);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(during);
        // ⚠️ 这条是「不会串号」的判据：Tomcat 线程会复用，不清就会把上一个请求的号带进下一个请求的日志。
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("合法入参头（8 字符）→ 原样沿用，不另生成")
    void acceptsIncoming() throws Exception {
        MockHttpServletRequest request = req();
        request.addHeader(TraceIdFilter.HEADER, "abc12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(mdcDuringChain(request, response)).isEqualTo("abc12345");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc12345");
    }

    @Test
    @DisplayName("合法入参头上界（恰好 64 字符，含 '-' 与 '_'）→ 原样沿用")
    void acceptsIncomingAtUpperBound() throws Exception {
        String incoming = "a".repeat(61) + "-_b";   // 61 + 3 = 64
        assertThat(incoming).hasSize(64);

        MockHttpServletRequest request = req();
        request.addHeader(TraceIdFilter.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(mdcDuringChain(request, response)).isEqualTo(incoming);
    }

    @Test
    @DisplayName("入参头太短 / 含空格 / 含换行（日志注入）/ 超 64 字符 → 一律丢弃并自生成")
    void rejectsIllegalIncoming() throws Exception {
        // 换行那条是本类**唯一**的安全判据：不卡格式的话，一个含 \n 的头能在日志里伪造出一整行。
        String[] illegal = {
                "abc1234",                              // 7 字符，短于下界
                "bad id with space",                    // 含空格
                "abc12345\n2026-01-01 ERROR 伪造的一行",   // 换行 ⇒ 日志注入
                "a".repeat(65),                         // 超上界
                "abc12345; rm -rf",                     // 含非白名单字符
        };
        for (String bad : illegal) {
            MockHttpServletRequest request = req();
            request.addHeader(TraceIdFilter.HEADER, bad);
            MockHttpServletResponse response = new MockHttpServletResponse();

            String during = mdcDuringChain(request, response);

            assertThat(during).as("入参 %s 应被拒", bad).matches(UUID_36);
            assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(during);
            assertThat(during).isNotEqualTo(bad);
        }
    }

    @Test
    @DisplayName("链里抛异常 → 异常照常上抛，但 MDC 仍被清空（finally 而不是 try 尾部）")
    void clearsMdcEvenOnException() {
        MockHttpServletRequest request = req();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response,
                (rq, rs) -> { throw new ServletException("炸了"); }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("响应头名逐字是 X-Trace-Id（前端与 commit 8 的 CORS 放开都认这个名字）")
    void headerNameIsExact() throws Exception {
        assertThat(TraceIdFilter.HEADER).isEqualTo("X-Trace-Id");
        assertThat(TraceIdFilter.MDC_KEY).isEqualTo("traceId");

        MockHttpServletResponse response = new MockHttpServletResponse();
        mdcDuringChain(req(), response);
        assertThat(response.getHeaderNames()).containsExactly("X-Trace-Id");
    }
}
