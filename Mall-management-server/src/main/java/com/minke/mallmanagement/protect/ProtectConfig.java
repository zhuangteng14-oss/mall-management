package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 防护面的**装配点**（D 段 · commit 2）—— 只做两件事：<b>造出入站限流闸、把它挂到 {@code /**}</b>。
 * 判定逻辑全在 {@link InboundRateLimitInterceptor}；本类不判任何一条请求（同 {@code WebAuthConfig} 的分工）。
 *
 * <h2>【阈值：200 QPS，出处在代码里而不是 properties，这一条是取舍】</h2>
 * 批文点名的值是 <b>200 QPS</b>。落成 {@link #LIMIT_FOR_PERIOD} / {@link #REFRESH_PERIOD}
 * 两个常量而不是 {@code application.properties} 里的一行，理由与 {@code RabbitObserveConfig}
 * 显式声明容器工厂**逐字同源**：「这三件恰好是批文点名要的」⇒ 让 grep 一次就能核。
 * 代价如实记：改阈值要重新编译，不能靠改配置现场调。⚠️ 若将来需要「按环境不同的阈值」，
 * 改成 properties 一处即可（{@code resilience4j.ratelimiter.instances.inboundGate.*}），
 * 本类把 {@link RateLimiterConfig} 换成 {@code registry.rateLimiter(name)} 就读到了。
 *
 * <h2>【⚠️ 为什么三个依赖都走 {@code ObjectProvider}（本仓第三次同一处置）】</h2>
 * <b>{@code @WebMvcTest} 切片会扫到本类</b>（{@code WebMvcConfigurer} 在切片的类型名单里，
 * {@code WebAuthConfig} 类注释里那条 2026-10-04 实测逐字记过）。而切片<b>不加载</b>
 * resilience4j 的自动装配、也不扫 {@code @Component} ⇒ 裸构造注入会让<b>全部 10 个切片
 * 一起启动失败</b>。{@code ObjectProvider.getIfAvailable()} 在「没有该 bean」时返回
 * {@code null}（不抛），于是切片里本闸整体空转、而默认 profile 里它照常生效。
 * <p>同款处置的前两处：{@code WebAuthConfig} 第 4 个参数（封禁闸）、{@code CommentController}
 * 的 {@code UserStateLookup}。⚠️ <b>别照着把它改成构造器直注入。</b>
 *
 * <h2>【顺序：本闸排在鉴权<b>之前</b>（{@code Ordered.HIGHEST_PRECEDENCE}）】</h2>
 * 取舍写在这里：排在前面 ⇒ <b>不带票的洪水也要占令牌</b>（闸保护了自己和鉴权那一段，
 * 但那一段本来就便宜）；排在后面 ⇒ 401 的洪水不计数，闸只在「已通过鉴权的流量」上生效。
 * 取前者，因为本段要挡的正是「海量无效请求把 Tomcat 工作线程占满」那一类，
 * 而那些请求里**大多数根本过不了鉴权**——排在后面等于把最大的那一股放进来。
 * ⚠️ 与路径 {@code /**} 上另一个拦截器（{@code AuthInterceptor}）的先后<b>只由 order 决定</b>，
 * 不靠「谁先注册」——两个 {@code WebMvcConfigurer} 的注册顺序在本仓是未定义的。
 */
@Configuration
public class ProtectConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ProtectConfig.class);

    /** 入站限流器的**实例名**（{@code mall.protection.*} 与日志里出现的就是它）。 */
    public static final String INBOUND_LIMITER = "inboundGate";

    /** 每个刷新周期放行的条数（批文点名 200）。 */
    public static final int LIMIT_FOR_PERIOD = 200;

    /** 刷新周期（批文点名 1 秒 ⇒ 「200 QPS」）。 */
    public static final Duration REFRESH_PERIOD = Duration.ofSeconds(1);

    /**
     * 等令牌的最长等待 —— <b>0：不等、当场答</b>。
     *
     * <p>⚠️ 这条值立成常量只为一个理由：它是「本闸不排队」的**唯一保证**，而它偏偏不写在方法名上
     * （{@code acquirePermission()} 看着像「会等」）。拦截器那段注释逐字记了这条因果；常量 + 断言
     * （{@code ProtectionThresholdsTest}）是为了让「有人把它从 0 改大」这件事**变红**，
     * 而不是等 Tomcat 工作线程睡在闸上才发现。
     */
    public static final Duration TIMEOUT = Duration.ZERO;

    private final InboundRateLimitInterceptor interceptor;
    private final boolean enabled;

    public ProtectConfig(@Value("${mall.protection.inbound.enabled:true}") boolean enabled,
                         ObjectProvider<RateLimiterRegistry> limiterRegistryProvider,
                         ObjectProvider<ProtectionGate> gateProvider,
                         ObjectProvider<ProtectionMetrics> metricsProvider) {
        this.enabled = enabled;
        RateLimiterRegistry registry = limiterRegistryProvider.getIfAvailable();
        ProtectionGate gate = gateProvider.getIfAvailable();
        ProtectionMetrics metrics = metricsProvider.getIfAvailable();
        // 三条都不满足才空转：关掉开关、切片里没有 registry、切片里没有 gate —— 任一满足即「不接闸」。
        RateLimiter limiter = (enabled && registry != null)
                ? registry.rateLimiter(INBOUND_LIMITER, RateLimiterConfig.custom()
                        .limitForPeriod(LIMIT_FOR_PERIOD)
                        .limitRefreshPeriod(REFRESH_PERIOD)
                        // ⚠️ 等待时长 **0**：等就是排队，排队就是让 Tomcat 工作线程睡在这里 ——
                        //    那正是限流要防的东西。宁可当场 429，也不让人排队。
                        .timeoutDuration(TIMEOUT)
                        .build())
                : null;
        this.interceptor = new InboundRateLimitInterceptor(limiter, gate, metrics);
        // 启动期取证口（惯例同 app.auth.enabled / app.lock.*）：闸开没开、阈值多少、豁免哪几条，
        // 都从这一行读。ASCII 片段 "PROTECT" 供 GBK 检索。
        log.info("[PROTECT] 入站限流闸已装配（PROTECT）：enabled={} 生效={} 阈值={}/{}s 豁免 {} 条 {}",
                enabled, interceptor.armed(), LIMIT_FOR_PERIOD, REFRESH_PERIOD.toSeconds(),
                InboundRateLimitInterceptor.EXEMPT_PATHS.size(), InboundRateLimitInterceptor.EXEMPT_PATHS);
    }

    /** 挂到 {@code /**}（与 {@code AuthInterceptor} 同一形状；白名单在拦截器内部判，不走 exclude）。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
