package com.minke.mallmanagement.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求级 traceId 的**唯一注入点**（C3 块 7 · commit 6b）：给每个请求生成（或接受）一个 id，
 * 放进 SLF4J 的 {@link MDC}，并在响应头上回带。
 *
 * <h2>【它解决什么】</h2>
 * 块 7 的实时日志页（commit 8 的 {@code EventLog.vue}）要能把「页面上看到的一条事件」和
 * 「后端日志里那几行」对上。在那之前，日志里没有任何东西能把同一个请求的多行串起来 ——
 * 一次下单会打十几行 INFO/WARN，分属 Dao / Service / Job，互相之间没有共同标识。
 * 本类把 {@code traceId} 塞进 MDC，{@code logback-spring.xml} 的 pattern 里带 {@code %X{traceId}}，
 * 于是**同一个请求的所有日志行天然同号**，且响应头能把它交回前端。
 *
 * <h2>【为什么是 {@code @Component} + 零构造依赖 —— 这条不是风格，是硬约束】</h2>
 * Spring Boot 的 {@code @WebMvcTest} 切片默认扫 {@code Filter} 类型（{@code WebMvcTypeExcludeFilter}
 * 的名单里有它，与 {@code WebMvcConfigurer} / {@code HandlerInterceptor} 同一批）⇒ 本类会被
 * **全部 10 个切片**一起加载，并真的挂进 MockMvc 的过滤器链。
 * 因此构造函数<b>必须零参数</b>：任何依赖（哪怕是一个 {@code @Value} 之外的 bean）都会在切片里
 * 找不到而让**所有切片一起红**——本仓已经被这种形态咬过两次（块 1：237 条测试 / 77 条错误；
 * commit 4：132 errors）。{@code WebAuthConfig} 第 4 个构造参数刻意用 {@code ObjectProvider} 绕开同一件事。
 * 本类不需要任何依赖（UUID 是 JDK 的、MDC 是 SLF4J 的），故这条天然满足 —— 但写在这里，
 * 是为了让下一次想给它加构造参数的人先看到这段话。
 *
 * <h2>【白名单校验：为什么要收外来 id，又要卡格式】</h2>
 * 收 {@code X-Trace-Id} 是为了支持**跨服务串联**（上游网关/前端若能带上，就不再另起一个号）。
 * 但外来值会被原样写进日志 ⇒ 必须卡格式，否则一个含 {@code \n} 的头就能在日志里**伪造出整行**
 * （日志注入）。白名单 {@code [A-Za-z0-9_-]{8,64}} 同时满足：够长不撞号、够短不进爆日志、
 * 且天然排除控制字符。不合法就**静默丢弃并自生成**——不报错，因为一个脏头不该让请求失败。
 *
 * <h2>【已知边界，如实记】</h2>
 * ① 只覆盖**请求线程**：SSE 那条流（{@code StreamingResponseBody}）在**另一个线程**上跑，
 *    MDC 不跨线程传递 ⇒ 流内打的日志**没有** traceId。跨线程透传按批文归 8c（纯乙：显式传参），
 *    本笔不做，也不假装做了。
 * ② 异步分派不会让本 filter 再跑一次（{@code OncePerRequestFilter.shouldNotFilterAsyncDispatch()}
 *    默认 true），故一次请求**恰好**一个 id，不会因为 async 又被换掉。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头名。⚠️ 前端若要读它，CORS 还得放开 {@code Access-Control-Expose-Headers} —— 归 commit 8（跨域补 2 处）。 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 键名。⚠️ 必须与 {@code logback-spring.xml} pattern 里的 {@code %X{traceId}} **逐字一致**。 */
    public static final String MDC_KEY = "traceId";

    /**
     * 可接受的外来 id 形状。⚠️ 上限 64 与 {@code UUID} 的 36 不同：36 是**我们生成**的长度
     * （与 8b 的 {@code trace_id varchar(36)} 对齐），64 是**接受外来**的宽容度（别家的 id 可能更长）。
     * 两者不必相等——写短了会拒掉合法上游，写长了才需要担心列宽。
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = pick(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        // 先设头再放行：即使后面被 AuthInterceptor 拒了（401），响应上也带着号 —— 这正是排查时最需要的那个场景。
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // ⚠️ 必须清：Tomcat 的工作线程是**复用**的，不清就会把上一个请求的号带进下一个请求的日志。
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 选一个 traceId：外来值合法就用它，否则自生成。
     *
     * <p>包级可见（不是 private）是**为了可单测** —— 本类是零依赖的，测试不必起 Spring 上下文；
     * 把这条判据单独暴露出来，就能对一个纯函数断言边界，而不是绕一大圈去验一个请求。<b>不是</b>为了让别处调用。
     */
    static String pick(String incoming) {
        if (incoming != null && ACCEPTED.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
