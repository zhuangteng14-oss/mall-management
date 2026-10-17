package com.minke.mallmanagement.shared.web;

import com.minke.mallmanagement.observe.metrics.ProtectionMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 「防护动作导致的失败」全局兜底（块 7 收尾 · P0）：把三类**可预期**的拒绝从 HTTP 500
 * 改写成 HTTP 200 + {@code {"success":false,"code":…,"msg":…}}。
 *
 * <h2>【为什么必须单独一个 Advice，而不是塞进 {@link GlobalExceptionHandler}】</h2>
 * 那个类刻意用 {@code assignableTypes} 把作用域收到两个 Controller（理由见其类注释：裸 Advice
 * 会接管 404）。本类只注册三个**类型明确**的 handler，不挂任何通用 {@code Exception} 兜底，
 * 因此 404 / 其余异常仍按 Spring 默认路径走 —— 既拿到覆盖面，又不改写既有契约。
 *
 * <h2>【为什么这三类要返回 200】</h2>
 * 它们都不是「服务端写错了」，而是**防护层按设计把请求挡在门外**：
 * <ul>
 *   <li>{@link DuplicateKeyException}：唯一键兜住了并发重复提交（订单号 / 唯一业务键）；</li>
 *   <li>{@link CallNotPermittedException}：熔断已打开，下游根本没被调用；</li>
 *   <li>{@link CannotAcquireLockException} / {@link DeadlockLoserDataAccessException}：死锁受害者在
 *       重试耗尽后退出，调用方应稍后重试。</li>
 * </ul>
 * 返回 500 会让监控面板把它们算进「服务端错误率」，而它们恰恰是防护**生效**的证据
 * （块 7 的判据是「5xx 归零、PROTECT 上升」）。⚠️ 只捕获这三类：真实未预期异常仍按 500 冒泡，
 * 不在这里被静默改写成 200 —— 那会让缺陷失去信号（TD-20 的反面）。
 *
 * <h2>【与指标面板的关系】</h2>
 * 每拦一次都写一条 {@link ProtectionMetrics} 事件（{@code blocked} 档 ⇒ 严重度 PROTECT）。
 * 熔断那一类**不在这里重复记**：{@code ProtectionMetricsBinder.onCallNotPermitted} 已经在
 * 熔断器回调里记过一次，再记会让「熔断挡了多少」翻倍。
 *
 * <p>⚠️ 依赖用 {@link ObjectProvider} 而不是构造参数注入：本类是 {@code @RestControllerAdvice}，
 * 会被所有 {@code @WebMvcTest} 切片加载，而切片里没有 {@code ProtectionMetrics} bean。
 * 取不到时退化为「不记事件」，但**响应改写照旧生效**（与 {@code WebAuthConfig} 同款的切片自保）。
 */
@RestControllerAdvice
public class ProtectionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProtectionExceptionHandler.class);

    /** 订单号唯一索引名（{@code mall_management_sql.sql} 的 {@code uk_order_orderId}）。 */
    private static final String ORDER_UNIQUE_KEY = "uk_order_orderid";

    private final ObjectProvider<ProtectionMetrics> metricsProvider;

    public ProtectionExceptionHandler(ObjectProvider<ProtectionMetrics> metricsProvider) {
        this.metricsProvider = metricsProvider;
    }

    /**
     * 唯一键冲突：并发重复提交被数据库兜住。
     *
     * <p>订单号冲突按批文返回 {@code DUPLICATE_ORDER}；其余唯一键（账号 / 商品编码…）返回
     * {@code DUPLICATE_KEY} 与「数据已存在」—— <b>不把「订单已存在」这句套到注册接口上</b>，
     * 那会让用户看到一句与他操作无关的话。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Map<String, Object>>> onDuplicateKey(DuplicateKeyException e) {
        String raw = rootMessage(e);
        boolean order = raw != null && raw.toLowerCase().contains(ORDER_UNIQUE_KEY);
        String code = order ? "DUPLICATE_ORDER" : "DUPLICATE_KEY";
        String msg = order ? "订单已存在" : "数据已存在";
        if (!order) {
            // 非订单的唯一键冲突不是「防护成功」的常态路径，留一行 WARN 便于定位是哪个唯一键。
            log.warn("[PROTECT] 唯一键冲突 ⇒ 200 {}：cause={}", code, raw);
        }
        record("duplicateSubmit", "db", "唯一键冲突：" + abbreviate(raw), msg);
        return ok(code, msg);
    }

    /**
     * 熔断已打开：下游没有被调用。
     *
     * <p>⚠️ 不在这里写 {@code ProtectionMetrics}：{@code ProtectionMetricsBinder#onCallNotPermitted}
     * 已经记过 {@code blocked(circuitBreak)}。这里只负责把 500 改写成可读的 200。
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Result<Map<String, Object>>> onCircuitOpen(CallNotPermittedException e) {
        log.warn("[PROTECT] 熔断拒绝 ⇒ 200 CIRCUIT_OPEN：{}", e.getMessage());
        return ok("CIRCUIT_OPEN", "当前系统繁忙，请稍后再试");
    }

    /**
     * 死锁 / 锁等待超时（重试层已耗尽或未覆盖该写路径）。
     *
     * <p>{@link DeadlockLoserDataAccessException} 与 {@link CannotAcquireLockException} 是兄弟类型
     * （都继承 {@code PessimisticLockingFailureException}），显式列出是为了让判据可读。
     */
    @ExceptionHandler({CannotAcquireLockException.class, DeadlockLoserDataAccessException.class})
    public ResponseEntity<Result<Map<String, Object>>> onDatabaseBusy(RuntimeException e) {
        log.warn("[PROTECT] 数据库死锁/锁等待 ⇒ 200 DB_BUSY：{}", rootMessage(e));
        record("dbBusy", "mysql", "死锁/锁等待：" + abbreviate(rootMessage(e)), "系统处理中，请重试");
        return ok("DB_BUSY", "系统处理中，请重试");
    }

    /** 平铺契约：{@code success=false} + {@code code} + {@code msg}，HTTP 仍为 200。 */
    private static ResponseEntity<Result<Map<String, Object>>> ok(String code, String msg) {
        Result<Map<String, Object>> body = Result.fail(msg);
        body.put("code", code);
        return ResponseEntity.ok(body);
    }

    /**
     * 记一条 PROTECT 事件。取不到 {@link ProtectionMetrics}（切片 / 单测）时静默跳过。
     *
     * <p>⚠️ 取值必须在这里现取：{@code blocked(...)} 内部读请求线程的 MDC，晚一拍就丢了。
     */
    private void record(String key, String scope, String detail, String reason) {
        ProtectionMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics == null) {
            return;
        }
        try {
            metrics.blocked(key, scope, detail, null, reason);
        } catch (RuntimeException ignore) {
            // 记事件失败不能反过来把「已兜底的 200」变成 500 —— 兜底本身必须 fail-soft。
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "-";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }
}
