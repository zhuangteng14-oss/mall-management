package com.minke.mallmanagement.protect;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import com.minke.mallmanagement.observe.metrics.ProtectionMetrics.Result;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 把熔断器的**状态机翻转**接到第 9 类事件环上（D 段 · commit 2）—— 「半开恢复要有记录」这条
 * 裁定的落点。
 *
 * <h2>【为什么不是「在业务代码里记一笔」】</h2>
 * 熔断的三档翻转（打开 / 半开 / 恢复关闭）**不是业务代码触发的**：它是 Resilience4j 在自己
 * 的度量窗口里算出来的，可能发生在<b>任何一次被注解的方法调用之后</b>，也可能由
 * {@code automatic-transition-from-open-to-half-open-enabled=true} 在**没有任何请求**时
 * 由它自己的线程完成。⇒ 唯一可靠的记录点是它公开的事件流（{@code getEventPublisher()}），
 * 而不是散在调用点上的日志。这也是本节「半开恢复要有记录」能被**保证**的原因 ——
 * 挂在事件流上就不会漏。
 *
 * <h2>【两个订阅，各记一类】</h2>
 * <ul>
 *   <li>{@code onStateTransition} ⇒ {@link ProtectionMetrics.Result#OPENED} /
 *       {@link ProtectionMetrics.Result#HALF_OPEN} / {@link ProtectionMetrics.Result#CLOSED}
 *       —— 「闸自己变了」。⚠️ 只记这三档：{@code DISABLED} / {@code FORCED_OPEN} 等本段
 *       一条都没用，真出现了也<b>不编造一个语义去记它</b>（认不出就不记，与各标签表的兜底口径同源）。</li>
 *   <li>{@code onCallNotPermitted} ⇒ {@link ProtectionMetrics.Result#BLOCKED}
 *       —— 「闸把请求挡在门外」。⚠️ 这个才是「熔断拦了多少」那个数；没有它，页面只能看到
 *       「打开过」而看不到「挡下了多少」。</li>
 * </ul>
 *
 * <h2>【⚠️ 本类在 {@code @WebMvcTest} 切片里不会被加载】</h2>
 * 切片只扫 web 组件（{@code @Controller} / 过滤器 / {@code WebMvcConfigurer}……），
 * 普通 {@code @Configuration} 不在名单里 —— 与 {@code RabbitObserveConfig} 同款
 * （那句「本类在切片里不会被加载」是既有事实，见 {@code application-smoke.properties} 的坦白）。
 * ⇒ 切片里既没有 {@code CircuitBreakerRegistry} 也不会有本类，不会因为取不到 bean 起不来。
 */
@Configuration
public class ProtectionMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(ProtectionMetricsBinder.class);

    private final ProtectionMetrics metrics;

    public ProtectionMetricsBinder(CircuitBreakerRegistry circuitBreakerRegistry, ProtectionMetrics metrics) {
        this.metrics = metrics;
        // 已登记的实例：本类构造时上面两个实例（mallMysqlRead / mallMysqlWrite）已经由
        // `@CircuitBreaker(name=…)` 的注解在**第一次调用**时惰性建出来 —— 也可能一个都还没建。
        // ⇒ 两条路都接上（这里 + onEntryAdded），谁先谁后都不会漏。
        for (CircuitBreaker breaker : circuitBreakerRegistry.getAllCircuitBreakers()) {
            bind(breaker);
        }
        circuitBreakerRegistry.getEventPublisher().onEntryAdded(this::onEntryAdded);
        log.info("[PROTECT] 熔断事件已接到第 9 类事件环（PROTECT）：已登记实例 {} 个",
                circuitBreakerRegistry.getAllCircuitBreakers().size());
    }

    private void onEntryAdded(EntryAddedEvent<CircuitBreaker> event) {
        bind(event.getAddedEntry());
    }

    /** 把一个熔断器的事件流接到本环上（幂等与否不重要：一个实例只会被 bind 一次）。 */
    private void bind(CircuitBreaker breaker) {
        String scope = scopeOf(breaker.getName());
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State to = event.getStateTransition().getToState();
            Result result = resultOf(to);
            if (result == null) {
                return;   // DISABLED / FORCED_OPEN 等本段不用的档：认不出就不记（理由见类注释）
            }
            metrics.state(result, "circuitBreak", scope, "circuitBreaker=" + breaker.getName()
                    + " " + event.getStateTransition().getFromState() + " → " + to
                    + " 失败率=" + breaker.getMetrics().getFailureRate() + "%"
                    + " 慢调用率=" + breaker.getMetrics().getSlowCallRate() + "%");
        });
        breaker.getEventPublisher().onCallNotPermitted(event -> metrics.blocked("circuitBreak", scope,
                "circuitBreaker=" + breaker.getName() + " 已打开：本次调用未执行（不占连接、不开事务）", null));   // 块 7 · 3a.3：末参 = 耗时，熔断这一路是瞬时回调、没有起止 ⇒ null
    }

    /** 熔断的三档 → 本环的 result 三档；其余档返回 {@code null} = 不记。 */
    private static Result resultOf(CircuitBreaker.State state) {
        switch (state) {
            case OPEN:
                return Result.OPENED;
            case HALF_OPEN:
                return Result.HALF_OPEN;
            case CLOSED:
                return Result.CLOSED;
            default:
                return null;
        }
    }

    /**
     * 实例名 → **下游名**（事件里的 {@code scope}）。它回答「这道闸守的是哪条下游」，
     * 而实例名回答「哪个实例」。⚠️ 认不出就原样用实例名 —— 不猜、不归到某个默认下游上
     * （猜错的 scope 会把「MQ 挡下的」画成「MySQL 挡下的」，而两者要查的方向完全不同）。
     */
    static String scopeOf(String breakerName) {
        if (breakerName == null) {
            return "unknown";
        }
        if (breakerName.startsWith("mallMysql")) {
            return "mysql";
        }
        return breakerName;
    }
}
