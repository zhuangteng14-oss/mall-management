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
 *    MDC 不跨线程传递 ⇒ 流内打的日志**没有** traceId。
 *    <b>块 7 · commit 8c 已补上跨线程那一半</b>：搬运件是 {@link ObserveContext}
 *    （纯乙 = 三处**显式传参**：{@code OrderStateExecutor#submit} / {@code LockSceneService#run}
 *    / {@code AttackEngine#runPhase}），它只管「把本线程的 (traceId, batchId) 带到那个线程」，
 *    MDC 的写入点**仍然只有本类**（走 {@link #bind}）。⇒ 流线程自己那几行日志照旧没有 traceId
 *    —— 那属于「谁产生的事件谁带号」，不是本 filter 的职责，别把两件事混为一谈。
 * ② 异步分派不会让本 filter 再跑一次（{@code OncePerRequestFilter.shouldNotFilterAsyncDispatch()}
 *    默认 true），故一次请求**恰好**一个 id，不会因为 async 又被换掉。
 *
 * <h2>【块 7 · commit 8c 新增：{@code X-Drill-Batch} —— 第二个请求级上下文】</h2>
 * 与 traceId 同款（同一个 filter、同一套白名单、同一个「不合法就静默丢弃」），差别只有三点：
 * ① 它是**外来才有**的：日常请求不带这个头 ⇒ {@link #currentBatch()} 返回 {@code null}
 *    （「不属于任何演练批次」是个合法状态，不是缺失）；② 它**不自生成**（自造一个批次号没有意义，
 *    会把「日常流量」错标成「某次演练」）；③ 它会被写进 {@code observed_event.BatchId}
 *    （8b 建表时该列注释逐字写的就是「8c 由 X-Drill-Batch 头注入」）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 响应头名。⚠️ 前端若要读它，CORS 还得放开 {@code Access-Control-Expose-Headers} —— 归 commit 8（跨域补 2 处）。 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 键名。⚠️ 必须与 {@code logback-spring.xml} pattern 里的 {@code %X{traceId}} **逐字一致**。 */
    public static final String MDC_KEY = "traceId";

    /**
     * 演练批次头（块 7 · commit 8c）。与 {@link #HEADER} 同款，但只由**攻击模拟器**带出
     * （见 {@code AttackEngine#post}）—— 日常请求不带它，于是 {@link #currentBatch()} 为 {@code null}。
     *
     * <p>⚠️ 它**不是**给前端读的响应头（本类不回带它）：批次的产生者是 {@code AttackEngine}
     * （一轮 = 一个 UUID），前端拿到它没有任何用处。响应头只回 traceId。
     */
    public static final String BATCH_HEADER = "X-Drill-Batch";

    /** 批次号的 MDC 键名。⚠️ 与 {@link #MDC_KEY} 一样，是「本线程当前上下文」的一部分。 */
    public static final String MDC_BATCH_KEY = "batchId";

    /**
     * 「谁」的 MDC 键名（块 7 · 阶段 3a.3 新增）—— 发起这次请求的**业务号**。
     *
     * <p>与 traceId / batchId 同款：写在本 filter 里，各事件环在**记录那一刻**现读。
     * ⚠️ 本键**不进 {@code logback-spring.xml} 的 pattern**（那是 traceId 的专属位置）——
     * 它只服务事件环的归属，不是给人读日志行用的。
     */
    public static final String MDC_ACTOR_ID_KEY = "actorId";

    /**
     * 「谁」的**角色号** MDC 键名（块 7 · 阶段 3a.3 新增）。
     *
     * <p>⚠️ MDC 的值只能是 {@code String}（它是 {@code Map<String, String>}）⇒ 角色存的是
     * **十进制串**（{@code "1"} / {@code "2"} / {@code "3"}），读回时由 {@link #currentActorRole()}
     * 解析成 {@code Integer}。别在别处直接 {@code MDC.get} 这个键然后当整数用 —— 那会得到一个
     * 「看着像数字的字符串」，在 {@code ==} 比较里静默走错分支。
     */
    public static final String MDC_ACTOR_ROLE_KEY = "actorRole";

    /**
     * 可接受的外来 id 形状。⚠️ 上限 64 与 {@code UUID} 的 36 不同：36 是**我们生成**的长度
     * （与 8b 的 {@code trace_id varchar(36)} 对齐），64 是**接受外来**的宽容度（别家的 id 可能更长）。
     * 两者不必相等——写短了会拒掉合法上游，写长了才需要担心列宽。
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * 当前线程正在服务的请求号；不在请求线程上（定时任务 / SSE 流线程 / 异步池）返回 {@code null}。
     *
     * <p>存在的理由是块 7 · commit 8 的<b>归属</b>：八个事件环的记录点都在请求线程上，它们要给自己
     * 记出的每条事件标一个号（{@code ObservedEvent.traceId}），前端才可能把「页面上那张卡片」
     * 归到「哪一次请求」并画出链路。取值只能走 MDC —— 那是本类唯一的写入点，别处不许再设。
     *
     * <p>⚠️ <b>这是有意的静态口，不是漏了注入</b>：调用方（{@code observe/metrics/*} 与
     * {@code shared/{cache,lock}} 的指标类）全都是 {@code @Component}，本可以注入 —— 但那样每个
     * 记录点都要多带一个字段、多一份切片里可能取不到的依赖，而 MDC 本身就是个线程局部静态表，
     * 包一层注入只是把同一个静态读打扮成实例调用。读一次是 {@code ThreadLocal} 的 map 查表，热路径上可忽略。
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 当前线程正在服务的**演练批次号**；不在演练请求里（或不在请求线程上）返回 {@code null}。
     *
     * <p>取值口径与 {@link #current()} **完全同款**（同一个 MDC、同一批调用点、同样必须在**记录那一刻**
     * 读）：八环的记录点在请求线程上，而并入合并环跑在 SSE 流线程上 —— 那时 MDC 是空的。
     * ⇒ 「本条事件属不属于某次演练」这件事，只能在记录点回答。
     *
     * <p>⚠️ {@code null} 是**正常值**：日常流量（含 A3 的压测、含种子数据）本来就不属于任何批次，
     * 它在 {@code observed_event.BatchId} 里就是 {@code NULL}（8b 的列注释逐字如此）。
     * 别为了「省一个判空」把它换成空串 —— 那会让「日常」与「批次号为空串」两种行混为一谈。
     */
    public static String currentBatch() {
        return MDC.get(MDC_BATCH_KEY);
    }

    /**
     * 当前请求的**发起者业务号**（块 7 · 阶段 3a.3 新增）；不在请求线程上、或这次请求没有身份
     * （未登录 / 闸关着 / 白名单面）时返回 {@code null}。
     *
     * <p>取值口径与 {@link #current()} / {@link #currentBatch()} **完全同款**（同一个 MDC、
     * 同一个「必须在记录那一刻读」的纪律）：九个事件环在请求线程上记事件，而并入合并环跑在
     * SSE 流线程上 —— 那时 MDC 是空的。
     *
     * <p>⚠️ {@code null} 是**正常值**，且比 traceId 的 null 更常见：它是「这条事件没有可归属的操作人」
     * （定时任务 / 演练线程 / 匿名请求），不是缺失。现在没有列依赖它，但页面要靠它显示「谁做的」。
     */
    public static String currentActorId() {
        return MDC.get(MDC_ACTOR_ID_KEY);
    }

    /**
     * 当前请求发起者的**角色号**（块 7 · 阶段 3a.3 新增）；无身份时返回 {@code null}。
     *
     * <p>⚠️ 解析失败一律答 {@code null}（**不抛**）：这个值只用来在页面上着色，
     * 一个读不懂的串不值得让一条事件记录失败 —— 与 traceId / batchId 的
     * 「不合法就静默丢弃」同一条纪律。
     */
    public static Integer currentActorRole() {
        String raw = MDC.get(MDC_ACTOR_ROLE_KEY);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把一组请求级上下文 {@code (traceId, batchId, actorId, actorRole)} 绑到**当前线程**的 MDC 上
     * （块 7 · commit 8c 建立；阶段 3a.3 补上后两个）。
     *
     * <p>存在的理由是跨线程：{@link com.minke.mallmanagement.shared.web.ObserveContext}
     * 在**请求线程**上捕获这一组，到**另一个线程**上再绑一次，于是那条线程上记出的事件照样带号。
     * ⚠️ 本类仍是 MDC 的**唯一写入点** —— 搬运件只持有值，不碰 MDC。
     *
     * <p>值为 {@code null} 的键会被 **移除**（不是写成空串）：这样「还原成一个没有上下文的线程」
     * 与「本来就没有上下文」是同一种状态，池线程被复用时的残留也就无从谈起。
     *
     * @param actorRole 角色号；{@code null} ⇒ 移除该键。⚠️ 内部按十进制串写入（见
     *                  {@link #MDC_ACTOR_ROLE_KEY}）
     */
    public static void bind(String traceId, String batchId, String actorId, Integer actorRole) {
        put(MDC_KEY, traceId);
        put(MDC_BATCH_KEY, batchId);
        put(MDC_ACTOR_ID_KEY, actorId);
        put(MDC_ACTOR_ROLE_KEY, actorRole == null ? null : String.valueOf(actorRole));
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = pick(request.getHeader(HEADER));
        String batchId = pickBatch(request.getHeader(BATCH_HEADER));
        long startMs = System.currentTimeMillis();
        // ⚠️ 清理必须覆盖「本方法做的每一件事」——因此 try 从第一处 MDC 写入之前开始：
        // 旧写法把 pick/pickBatch/MDC.put/AuthSession.identify/setHeader 全留在 try 之外，
        // 其中任何一步抛异常，finally 里的四个 remove 都不会执行，Tomcat 复用该工作线程的
        // **下一个请求**就会继承 batchId / actorId / actorRole（traceId 会被覆盖，另外三个不会）。
        // 实证：3a 压测期间 dispatcherServlet 打出过 traceId 为空、却带着上一个请求残留上下文的日志行。
        try {
            // 先清三个「按需写入」的键：即便上一个请求因为异常漏清，这里也不允许它跨请求存活。
            // traceId 紧接着无条件覆盖；三个可选键没值时必须移除，而不是「保持原样」。
            MDC.remove(MDC_BATCH_KEY);
            MDC.remove(MDC_ACTOR_ID_KEY);
            MDC.remove(MDC_ACTOR_ROLE_KEY);
            MDC.put(MDC_KEY, traceId);
            if (batchId != null) {
                MDC.put(MDC_BATCH_KEY, batchId);
            }
            // 块 7 · 阶段 3a.3：「谁」与 traceId / batchId **同一处、同一刻**写进 MDC ——
            // 九个事件环在**记录那一刻**现读（理由见 currentActorId 的 javadoc）。
            // ⚠️ 取**识别**口径（AuthSession.identify）：它是纯静态、AuthTokenStore 是内存表（不查库）
            //    ⇒ 每个请求多这一次解析的代价可忽略。⚠️ 闸关着时本类**照样**要解身份（这是刻意的）：
            //    若改由 AuthInterceptor 记，app.auth.enabled=false（本机 dev / a3 两个 profile 都是 false）
            //    时那个闸直接放行、根本不解身份 ⇒ actor 会恒为 null。
            AuthSession.Identity actor = AuthSession.identify(request);
            if (actor != null) {
                MDC.put(MDC_ACTOR_ID_KEY, actor.userId());
                if (actor.role() != null) {
                    MDC.put(MDC_ACTOR_ROLE_KEY, String.valueOf(actor.role()));
                }
            }
            // 先设头再放行：即使后面被 AuthInterceptor 拒了（401），响应上也带着号 —— 这正是排查时最需要的那个场景。
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            // 清理本身也要防「记台账抛异常」把 remove 跳过：record 包一层 try/finally。
            try {
                // 块 7 · commit 8：把「这一趟跑了多久」记进台账。前端抽屉的「总耗时 xx ms」与链路两端
                //（请求开始 / 请求结束）都出自这一行 —— 事件本身推不出「请求何时结束」。
                TraceLog.record(traceId, request.getMethod(), request.getRequestURI(), response.getStatus(),
                        startMs, System.currentTimeMillis());
            } finally {
                // ⚠️ 必须清：Tomcat 的工作线程是**复用**的，不清就会把上一个请求的号带进下一个请求的日志。
                // batchId 同理 —— 且它更危险：漏清会让**一次演练之后的全部日常请求**（复用同一条工作线程的）
                // 被标成那次演练的批次，于是「精准批次」变成「把整个下午都算进去」。
                // actorId / actorRole（3a.3）同款：漏清会让下一个复用该线程的**匿名**请求顶着
                // 上一个用户的身份记事件 —— 页面上表现为「别人的操作挂到了他名下」，且事后分不出来。
                MDC.remove(MDC_KEY);
                MDC.remove(MDC_BATCH_KEY);
                MDC.remove(MDC_ACTOR_ID_KEY);
                MDC.remove(MDC_ACTOR_ROLE_KEY);
            }
        }
    }

    /**
     * 选一个批次号：外来值合法就用它，**否则返回 {@code null}**（丢弃，不是自生成 —— 理由见类注释）。
     *
     * <p>白名单与 traceId **共用同一个** {@link #ACCEPTED}：两个值都进 MDC、都会被打进日志，
     * 注入面完全相同，没有理由给批次开一个更宽的口子。
     * <p>包级可见的理由与 {@link #pick} 逐字相同（为了可单测）。
     */
    static String pickBatch(String incoming) {
        if (incoming != null && ACCEPTED.matcher(incoming).matches()) {
            return incoming;
        }
        return null;
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
