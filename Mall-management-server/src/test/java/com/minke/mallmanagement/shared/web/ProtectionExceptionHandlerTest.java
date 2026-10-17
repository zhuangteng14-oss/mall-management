package com.minke.mallmanagement.shared.web;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 块 7 收尾 · P0 全局兜底的三类改写（HTTP 500 → 200 + {@code code}）。
 *
 * <h2>【为什么每类都要断言 code + msg + 200 三件套】</h2>
 * 只断言 200 时，把三个 handler 写反（订单冲突回 DB_BUSY）也会全绿；
 * 只断言 code 时，文案回退成通用「操作失败」不会红 —— 而前端/用户看到的就是这一句。
 *
 * <h2>【判据的另一半：不该接的异常必须冒泡】</h2>
 * 本类刻意**不**挂通用 {@code Exception} 兜底（否则 404 与真缺陷都会被改写）。
 * 故补一条反向断言：{@code IllegalStateException} 在本 Advice 里没有映射。
 * 少了它，「把 Exception 加进 @ExceptionHandler」这种退化不会被任何用例拦住。
 *
 * <h2>【指标记录的口径】</h2>
 * 重复提交与死锁各记一条 {@code blocked}；熔断**不在这里记**（已在
 * {@code ProtectionMetricsBinder#onCallNotPermitted} 记过，再记会翻倍）。
 * 测试用一个只记录调用的子类证明这条差异，而不是只看「没抛异常」。
 */
class ProtectionExceptionHandlerTest {

    /** 只记录 blocked 调用，不走真实 MeterRegistry 的环形缓冲。 */
    private static final class RecordingMetrics extends ProtectionMetrics {
        final List<String> calls = new ArrayList<>();

        RecordingMetrics() {
            super(new SimpleMeterRegistry());
        }

        @Override
        public void blocked(String key, String scope, String detail, Long durationNanos, String reason) {
            calls.add(key + "|" + scope + "|" + reason);
        }
    }

    /** 最小 ObjectProvider：只服务 getIfAvailable()。 */
    private static ObjectProvider<ProtectionMetrics> providerOf(ProtectionMetrics metrics) {
        return new ObjectProvider<>() {
            @Override
            public ProtectionMetrics getObject() {
                return metrics;
            }

            @Override
            public ProtectionMetrics getObject(Object... args) {
                return metrics;
            }

            @Override
            public ProtectionMetrics getIfAvailable() {
                return metrics;
            }

            @Override
            public ProtectionMetrics getIfUnique() {
                return metrics;
            }
        };
    }

    private static ProtectionExceptionHandler handler(ProtectionMetrics metrics) {
        return new ProtectionExceptionHandler(providerOf(metrics));
    }

    private static Map<String, Object> body(ResponseEntity<Result<Map<String, Object>>> res) {
        assertThat(res.getStatusCode().value()).as("防护类失败必须改写为 HTTP 200").isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private static void assertFlatFail(Map<String, Object> body, String code, String msg) {
        assertThat(body.get("success")).as("平铺契约：顶层 success=false").isEqualTo(false);
        assertThat(body.get("code")).isEqualTo(code);
        assertThat(body.get("msg")).isEqualTo(msg);
    }

    @Test
    @DisplayName("订单号唯一键（真实 MySQL 文案 uk_order_orderId）⇒ 200 DUPLICATE_ORDER/订单已存在")
    void duplicateOrderIsRewrittenTo200() {
        // rootMessage() 取的是**根因**文案 ⇒ 索引名必须放进最内层，顺带覆盖“嵌套 cause”这条路径。
        SQLIntegrityConstraintViolationException root =
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'SIM-1' for key 'order.uk_order_orderId'");
        DuplicateKeyException e = new DuplicateKeyException("order insert failed", root);
        RecordingMetrics metrics = new RecordingMetrics();

        Map<String, Object> body = body(handler(metrics).onDuplicateKey(e));

        assertFlatFail(body, "DUPLICATE_ORDER", "订单已存在");
        assertThat(metrics.calls).as("重复提交记一条 blocked（订单冲突也记）")
                .containsExactly("duplicateSubmit|db|订单已存在");
    }

    @Test
    @DisplayName("非订单唯一键 ⇒ 200 DUPLICATE_KEY/数据已存在（不把“订单已存在”套到注册上）")
    void duplicateNonOrderKeyGetsTheGenericMessage() {
        DuplicateKeyException e = new DuplicateKeyException("dup",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'alice' for key 'login.uk_login_username'"));

        Map<String, Object> body = body(handler(new RecordingMetrics()).onDuplicateKey(e));

        assertFlatFail(body, "DUPLICATE_KEY", "数据已存在");
    }

    @Test
    @DisplayName("熔断打开 ⇒ 200 CIRCUIT_OPEN/当前系统繁忙，请稍后再试；且此处**不**记指标")
    void circuitOpenIsRewrittenTo200WithoutDoubleCounting() {
        RecordingMetrics metrics = new RecordingMetrics();
        CallNotPermittedException e = CallNotPermittedException.createCallNotPermittedException(
                CircuitBreakerRegistry.ofDefaults().circuitBreaker("test"));

        Map<String, Object> body = body(handler(metrics).onCircuitOpen(e));

        assertFlatFail(body, "CIRCUIT_OPEN", "当前系统繁忙，请稍后再试");
        assertThat(metrics.calls)
                .as("熔断事件已由 ProtectionMetricsBinder 记过 ⇒ 这里再记就翻倍")
                .isEmpty();
    }

    @Test
    @DisplayName("锁等待超时（CannotAcquireLock）⇒ 200 DB_BUSY/系统处理中，请重试")
    void cannotAcquireLockIsRewrittenTo200() {
        RecordingMetrics metrics = new RecordingMetrics();
        CannotAcquireLockException e =
                new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction");

        Map<String, Object> body = body(handler(metrics).onDatabaseBusy(e));

        assertFlatFail(body, "DB_BUSY", "系统处理中，请重试");
        assertThat(metrics.calls).containsExactly("dbBusy|mysql|系统处理中，请重试");
    }

    @Test
    @DisplayName("死锁受害者（DeadlockLoser，与 CannotAcquireLock 是兄弟类型）⇒ 同样 200 DB_BUSY")
    void deadlockLoserIsRewrittenTo200() {
        DeadlockLoserDataAccessException e =
                new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", null);

        assertFlatFail(body(handler(new RecordingMetrics()).onDatabaseBusy(e)),
                "DB_BUSY", "系统处理中，请重试");
    }

    @Test
    @DisplayName("反向：未预期异常（IllegalStateException）在本 Advice 里没有映射，必须仍按 500 冒泡")
    void unexpectedExceptionsAreNotSwallowed() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(ProtectionExceptionHandler.class);

        assertThat(resolver.resolveMethod(new IllegalStateException("boom")))
                .as("本类只接三类可预期拒绝；加了通用 Exception 兜底会让真缺陷失去 500 信号")
                .isNull();
        assertThat(resolver.resolveMethod(new DuplicateKeyException("d")))
                .isNotNull();
        assertThat(resolver.resolveMethod(new CannotAcquireLockException("l")))
                .isNotNull();
    }

    @Test
    @DisplayName("没有 ProtectionMetrics bean（切片）⇒ 响应改写照旧生效")
    void worksWithoutMetricsBean() {
        ProtectionExceptionHandler sliceHandler = new ProtectionExceptionHandler(
                new DefaultListableBeanFactory().getBeanProvider(ProtectionMetrics.class));

        assertFlatFail(body(sliceHandler.onDatabaseBusy(new CannotAcquireLockException("x"))),
                "DB_BUSY", "系统处理中，请重试");
    }
}
