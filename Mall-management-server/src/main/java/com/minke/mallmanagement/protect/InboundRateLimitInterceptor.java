package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * <b>入站限流闸</b>（D 段 · commit 2 · 裁定「限流 = 入站闸」）—— 全站第一个、也是唯一一个
 * 「不看业务、只看 QPS」的关口。
 *
 * <h2>【为什么是拦截器，不是 Filter —— 这一步是有代价的，写在这里】</h2>
 * 裁定给的三条路是「统一拦截器 / Filter / 网关层」，本笔取<b>拦截器</b>，理由与代价并列：
 * <ul>
 *   <li><b>理由一（决定性的）：{@code Filter} 在这个仓里是切片地雷。</b>
 *       {@code @WebMvcTest} 的切片类型过滤<b>会扫到 {@code Filter}</b>（{@code TraceIdFilter}
 *       的类注释逐字记过这条），而它<b>零构造依赖</b>正是为了绕开它。本闸要吃
 *       {@code RateLimiter} / {@code ProtectionGate} / {@code ProtectionMetrics} 三个 bean
 *       ⇒ 做成 {@code @Component Filter} 会让<b>全部 10 个切片一起启动失败</b>
 *       （本仓被这种形态咬过两次：237 条测试 / 77 条错误；commit 4：132 errors）。</li>
 *   <li><b>理由二：白名单的判定形状与 {@code AuthInterceptor} 逐字同款</b>
 *       （同一个 {@code AntPathMatcher}、同一张「路径 → 是否豁免」的常量表）——
 *       两处读法是同一个习惯，不需要第二套心智模型。</li>
 *   <li><b>代价（如实记）：拦截器不覆盖「没有匹配到任何 handler」的请求</b>
 *       （404 那一路在 {@code DispatcherServlet} 找 handler 时就结束了，进不了 {@code preHandle}）。
 *       ⇒ 拿不存在的路径刷量时，本闸<b>不计数、不拦</b>。这是本笔选拦截器就认下的缺口 ——
 *       那一路的开销是「一次 handler 查找」，不是「一次业务处理」，本段要保护的正是后者。</li>
 *   <li><b>另一条代价：{@code preHandle} 在异步分发上是重入的。</b> 三条 SSE 端点走
 *       {@code StreamingResponseBody}，异步分派时 {@code preHandle} 会被<b>再调一次</b>
 *       ⇒ 一条流占两个令牌。本笔<b>不去消掉它</b>：那需要按 {@code request.isAsyncDispatch()}
 *       判分支，而这里的取舍是「令牌多花一个、语义少一个特例」。速率上限是 200/s，
 *       观测面三条流页均 3 条 ⇒ 这个差异在数量级上不可见。</li>
 * </ul>
 *
 * <h2>【白名单：六条，每一条都写明理由】</h2>
 * <pre>
 *   /health/**        运维探针（本仓 {@code HealthController}，a3/dev 才有）
 *   /actuator/**      Boot 的 health / metrics（{@code application.properties:124} 暴露的两个）
 *   /error            容器未处理异常的 ERROR dispatch 落点 —— 拦它等于让「出错」再叠一层 429
 *   /img/**           商品图与头像（本仓唯一显式注册的静态资源前缀，{@code ImgPath.java:109}）
 *   /favicon.ico      浏览器自动请求，且它不对应任何业务动作
 *   /admin/observe/** **观测与控制面** —— 见下，这条是本笔唯一超出裁定枚举的一条
 * </pre>
 * ⚠️ <b>为什么必须加上第六条</b>：面板上那枚〔暂停〕按钮打的是
 * {@code /admin/observe/attack/stop}。闸一开、演练一满档，这条控制请求<b>会和演练自己的流量
 * 抢同一桶令牌</b> —— 结果就是「演示现场按不动停止按钮」。防护面掐断控制面是本段最不该出的
 * 事故，故观测域整段豁免。⚠️ 这条**不放宽任何业务面**：八条业务端点、以及演练真正要打的那些
 * 目标路径，一条都没进白名单。
 *
 * <h2>【超限怎么答：429 + 与 {@code Result.fail} 同形的体】</h2>
 * 形状逐字与 {@code Result.fail(msg)} 一致（{@code {"success":false,"msg":…}}），因为全仓前端
 * {@code http.js} 的失败分支读的就是这两个键。⚠️ 这里<b>手写 JSON 而不是注入 ObjectMapper</b>：
 * 本闸在切片里必须能安全地「不生效」，多一个构造依赖就多一处切片起不来的可能；而形状只有两个字面键，
 * 手写的风险面比多一个 bean 小。⚠️ 中文带上 {@code charset=UTF-8} —— 少了它就是 GBK 乱码。
 *
 * <h2>【「不可用」是一种合法状态，不是缺失】</h2>
 * 构造参数允许为 {@code null}（装配类用 {@code ObjectProvider} 取），任一为空则本闸
 * <b>整体空转</b>。两个场合会走到：
 * <ol>
 *   <li><b>{@code @WebMvcTest} 切片</b>：切片不加载 resilience4j 的自动装配，只有本拦截器被
 *       {@code WebMvcConfigurer} 拉进来 ⇒ 这里空转，498 条既有断言一条都不受影响
 *       （⚠️ 与 {@code WebAuthConfig} 第 4 个参数用 {@code ObjectProvider} 是**同一条处置**）；</li>
 *   <li><b>运维显式关掉</b>：{@code mall.protection.inbound.enabled=false}（见装配类）。</li>
 * </ol>
 */
public class InboundRateLimitInterceptor implements HandlerInterceptor {

    /** 白名单 —— 唯一出处（判定与文案都读它；六条的理由写在类注释里，改这里必须同时改那段）。 */
    public static final List<String> EXEMPT_PATHS = List.of(
            "/health/**",
            "/actuator/**",
            "/error",
            "/img/**",
            "/favicon.ico",
            "/admin/observe/**");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimiter limiter;
    private final ProtectionGate gate;
    private final ProtectionMetrics metrics;

    public InboundRateLimitInterceptor(RateLimiter limiter, ProtectionGate gate, ProtectionMetrics metrics) {
        this.limiter = limiter;
        this.gate = gate;
        this.metrics = metrics;
    }

    /** 本闸是不是真的接着闸（三个依赖齐备）。给启动日志与测试用。 */
    public boolean armed() {
        return limiter != null && gate != null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!armed()) {
            return true;   // 切片 / 显式关闭：整体空转（理由见类注释末节）
        }
        String path = request.getRequestURI();
        if (exempt(path)) {
            return true;
        }
        if (!gate.isOn("rateLimit")) {
            return true;   // 演练把限流这枚开关关掉了 —— 这正是「关防护 ⇒ 后端吃满」那一半
        }
        // ⚠️ 取的是 `acquirePermission()` 而不是某个 `tryXxx`：resilience4j 2.3.0 的
        //    `RateLimiter` 接口**没有** `tryAcquirePermission()`（`javap` 实测：只有
        //    `acquirePermission()` / `acquirePermission(int)` / `reservePermission()` / `reservePermission(int)`）。
        //    `acquirePermission()` 的语义是「要一个令牌，最多等 timeoutDuration」——
        //    本仓把它配成 **0**（见 `ProtectConfig`）⇒ 等价于「不等、当场答」，正是本闸要的。
        //    ⚠️ 换句话说：**「不排队」这件事由 timeoutDuration=0 保证，不由方法名保证**。
        //    谁要是哪天把那个 0 改成非 0，这里就会开始让 Tomcat 工作线程睡在闸上。
        // 块 7 · 阶段 3a.3：耗时起 = **闸口处**（不是 preHandle 入口）。⚠️ 方案写的是「拦截入口」，
        // 这里收窄到闸口，两条理由：① 通过路径**不发事件** ⇒ 把 t0 提到方法入口等于给全量请求
        // 白付一次 nanoTime；② 方法入口那几行含 exempt(path) 的 AntPathMatcher 匹配（微秒级、
        // 与「闸拦了多久」无关），包进来只会稀释这个数。量的是**取令牌这一步**（timeoutDuration=0 ⇒ 当场答）
        long startedNanos = System.nanoTime();
        if (limiter.acquirePermission()) {
            return true;
        }
        if (metrics != null) {
            metrics.blocked("rateLimit", "inbound",
                    "method=" + request.getMethod() + " path=" + path
                            + " limiter=" + limiter.getName() + " limitForPeriod=" + limiter.getRateLimiterConfig().getLimitForPeriod(),
                    System.nanoTime() - startedNanos);
        }
        writeTooManyRequests(response, limiter.getRateLimiterConfig().getLimitForPeriod(),
                limiter.getRateLimiterConfig().getLimitRefreshPeriod().toSeconds());
        return false;
    }

    /**
     * 白名单判定 —— 包级可见是为了可单测（不必起 Web 上下文），与 {@code TraceIdFilter.pick} 同一处置。
     *
     * <p>⚠️ {@code AntPathMatcher} 的 {@code /**} 匹配**零个或多个**路径段 ⇒ {@code /health/**}
     * 也匹配裸的 {@code /health}（{@code /admin/observe/**} 同理匹配 {@code /admin/observe}）。
     * 这是**要**的效果（探针常打不带尾斜杠的那条），但它是 Ant 的语义、不是本表的写法 ——
     * 别按「{@code /**} 至少要一段」去推白名单边界。实测钉在
     * {@code InboundRateLimitInterceptorTest#businessPathsAreNotExempt}。
     */
    static boolean exempt(String path) {
        if (path == null) {
            return false;
        }
        for (String pattern : EXEMPT_PATHS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写 429。
     *
     * <p>⚠️ <b>阈值从 limiter 现读，不写死成字面量</b>：配置一改，这句话要跟着变 ——
     * 文案里印着一个和实际生效值不同的数字，比不印数字更容易误导排查的人。
     * ⚠️ 刷新周期按秒取（{@code limitForPeriod} 说的就是「每个周期几条」）。
     */
    private static void writeTooManyRequests(HttpServletResponse response, int limitForPeriod, long refreshSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"msg\":\"请求过于频繁：入站限流每 "
                + refreshSeconds + " 秒放行 " + limitForPeriod + " 条，这一拍没有余量，请稍后重试\"}");
    }
}
