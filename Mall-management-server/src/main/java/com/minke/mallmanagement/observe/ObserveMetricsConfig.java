package com.minke.mallmanagement.observe;

import com.minke.mallmanagement.observe.metrics.IdentityMetrics;
import com.minke.mallmanagement.shared.web.IdentityGuard;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 观测域的**装配点**（两件事）：① 指标面板要读的分位数 —— **只给一个指标**开直方图式分位；
 * ② 身份守卫（静态工具，拿不到构造注入）的观测出口绑定 —— 见 {@link #bindIdentityGuard()}。
 *
 * <h2>【为什么需要这一笔】</h2>
 * 面板的 KPI 卡里有一张「P99 延迟」（《块 7 参考》§2.2 第 2 项）。Micrometer 的
 * {@code Timer} 默认**不保留**分位数（{@code takeSnapshot().percentileValues()} 是空数组）
 * ⇒ 不开关的话，那格只能显示「—」。开关有两条路：
 * <ol>
 *   <li>配置项 {@code management.metrics.distribution.percentiles.http.server.requests=0.95,0.99}
 *       —— 属**部署配置**。块 7 的边界明写「不改部署配置」（pack §约束），故不走这条；</li>
 *   <li>一个 {@link MeterFilter} bean（本类）—— 是**代码**，且能精确限定到**一个**指标。</li>
 * </ol>
 *
 * <h2>【⚠️ 开销与边界，如实记下】</h2>
 * <ul>
 *   <li>开启分位 = 该指标多带一份分位缓冲（每个 {@code method × uri × status} 组合一份），
 *       记录时日志级开销。**只开这一个指标**：本仓的自定义指标（{@code mall.*}）一个都不动
 *       —— 那些指标的读法是「每拍计数器的差」，不需要分位。</li>
 *   <li>⚠️ <b>已经在过滤器注册之前建出来的 meter 不吃新配置</b>。本类在容器启动期就位，
 *       而 {@code http.server.requests} 要到**第一个真实请求**才建 ⇒ 正常路径上不存在
 *       「漏开」的窗口；但**测试里**用 {@code SimpleMeterRegistry} 手工建的 timer 不受影响
 *       （那些测试自己决定配置）。</li>
 *   <li>⚠️ 它**不改变任何既有断言的输入**：只给这一个指标补了分位数，计数 / 计时 / tag 一个字不动。</li>
 * </ul>
 *
 * <h2>【B 档自裁 + 报备】</h2>
 * 「P99 是配置开还是代码开」在《块 7 参考》里**没有写**（B 档只列了 SSE 动词 / 事件环容量 /
 * 推送频率 / SIM- 前缀四件）。本块选**代码开**，理由如上（部署配置不动）；若要求「配置开」，
 * 改成 {@code application.properties} 里那一行 + 删掉本类即可，两处**互不影响**（同一开关的两种形态）。
 */
@Configuration
public class ObserveMetricsConfig {

    /** 与 {@code MetricsSnapshotService.HTTP} 同一个名字（那一处是读取方，本处是开启方）。 */
    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    private final IdentityMetrics identityMetrics;

    public ObserveMetricsConfig(IdentityMetrics identityMetrics) {
        this.identityMetrics = identityMetrics;
    }

    /**
     * 把身份守卫的观测出口接上（块 7 · commit 2b）。
     *
     * <p>{@link IdentityGuard} 是 {@code final} + 私有构造 + 全 {@code static} 的工具类
     * —— 不是 bean、拿不到构造注入，故走一个静态装配口。⚠️ 不装配的后果是**不记**（不是报错）：
     * 切片里（{@code @WebMvcTest} 只 {@code @Import} 被吃到的 bean）本就该没有流量数字，
     * 真起应用时本类在启动期就把线接上。
     */
    @PostConstruct
    void bindIdentityGuard() {
        IdentityGuard.bind(identityMetrics);
    }

    /**
     * 给 HTTP 计时器开 0.5 / 0.95 / 0.99 三档分位。⚠️ <b>本方法必须是 {@code static}</b>：非 static 时，MeterFilter 要在 {@code simpleMeterRegistry} **创建过程中**被收集，Spring 就得先实例化本 {@code @Configuration} ⇒ 本类构造注入了 {@link IdentityMetrics} ⇒ 要 {@code MeterRegistry}（此刻正在创建中）⇒ **循环依赖，整台起不来**（2026-10-14 实测，a3 / dev 同一现象）；静态 {@code @Bean} 方法不需要实例化配置类 ⇒ 环自断（本行的加字是行内补写，未增删行 —— 仓规零漂移）。
     *
     * <p>用 {@code merge} 而不是新建：配置里**已有**的项（如 TTL 的衰减窗口、SLA 边界）
     * 原样保留，本类只补 {@code percentiles} 一项 —— 否则等于把 Boot 的默认分发配置整份覆盖掉。
     */
    @Bean
    static MeterFilter observeHttpPercentiles() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!HTTP_SERVER_REQUESTS.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentiles(0.5, 0.95, 0.99)
                        .build()
                        .merge(config);
            }
        };
    }
}
