package com.minke.mallmanagement.observe.service;

import com.minke.mallmanagement.shared.web.AuthSession;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 长连接的**名额表**（D3-6）—— 单用户上限 + 全局上限，超限时让端点回 429。
 *
 * <h2>【为什么必须有这张表】</h2>
 * 每条 {@code StreamingResponseBody} 连接占一条**异步线程**（见 {@link SseStreamWriter} 类注释）。
 * 演示现场很容易连开十几个标签页（指标 / 事件 / 订单三个页面各开几条，再加几个残留的旧标签），
 * 每条连接每秒产一帧、读一次库存水位 —— 到某个点后端线程与连接池被吃满，页面**白屏**，
 * 而现场能看到的症状是「系统挂了」，不是「开太多页了」。
 *
 * <p>用户 2026-10-18 裁定：**单用户 50 / 全局 200**，且数值写在配置文件里（现场可改，不改代码）。
 *
 * <h2>【身份口径】</h2>
 * 取 {@link AuthSession#identify} 的 {@code userId}；认不出来（dev / a3 的
 * {@code app.auth.enabled=false}，或请求没带票）时按**客户端 IP** 计
 * （{@code ip:127.0.0.1}）。⚠️ 不按连接随机分组 —— 那样等于没有单用户上限。
 *
 * <h2>【名额一定会还】</h2>
 * 释放点只有一个：{@link SseStreamWriter#stream(String, java.util.function.Supplier, Runnable)}
 * 的 {@code finally}（正常结束 / 客户端断开 / 产帧抛异常 / 空闲看门狗断开，四条路径都经过它）。
 * ⚠️ 超限的那条请求**没有占用名额**，也就没有对应释放 —— 控制器在拿到 {@code null} 时直接返回。
 */
@Component
public class SseConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);

    /** 单用户上限（可配）。用户裁定默认 50。 */
    private final int maxPerUser;

    /** 全局上限（可配）。用户裁定默认 200。 */
    private final int maxTotal;

    /** 身份 → 当前占用连接数。 */
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();

    /** 全局当前占用连接数（与 {@link #perUser} 由同一把段内锁维护，见 {@link #tryAcquire}）。 */
    private final AtomicInteger total = new AtomicInteger();

    /** 累计被拒次数（面板/排障用；不是连接数）。 */
    private final AtomicLong rejected = new AtomicLong();

    public SseConnectionRegistry() {
        this(50, 200);
    }

    @Autowired
    public SseConnectionRegistry(
            @Value("${mall.observe.sse.max-per-user:50}") int maxPerUser,
            @Value("${mall.observe.sse.max-total:200}") int maxTotal,
            MeterRegistry registry) {
        this(Math.max(1, maxPerUser), Math.max(1, maxTotal));
        Gauge.builder("mall.observe.sse.connections", this, SseConnectionRegistry::connections)
                .description("当前打开的 SSE 长连接数（D3，全局上限见 mall.observe.sse.max-total）")
                .register(registry);
        Gauge.builder("mall.observe.sse.users", this, SseConnectionRegistry::identities)
                .description("当前持有 SSE 连接的身份数（用户或未登录时的客户端 IP）")
                .register(registry);
        Gauge.builder("mall.observe.sse.rejected", this, SseConnectionRegistry::rejectedCount)
                .description("本进程累计因超过 SSE 连接上限被拒的请求数")
                .register(registry);
    }

    /** 测试 / 手工构造用（不注册 gauge）。 */
    public SseConnectionRegistry(int maxPerUser, int maxTotal) {
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxTotal = Math.max(1, maxTotal);
    }

    /**
     * 尝试为一个 SSE 请求占一个名额。
     *
     * @return 占到了 ⇒ 返回**释放句柄**（必须在流结束时调用一次，见类注释）；超限 ⇒ {@code null}
     */
    public Permit tryAcquire(HttpServletRequest request) {
        String identity = identityOf(request);
        synchronized (this) {
            int nowTotal = total.get();
            if (nowTotal >= maxTotal) {
                reject(identity, "全局 " + nowTotal + "/" + maxTotal);
                return null;
            }
            AtomicInteger per = perUser.computeIfAbsent(identity, k -> new AtomicInteger());
            int nowPer = per.get();
            if (nowPer >= maxPerUser) {
                // ⚠️ 只拒、不先加全局：否则被拒的请求会占着一个永远不还的总数名额。
                reject(identity, "单用户 " + nowPer + "/" + maxPerUser);
                return null;
            }
            per.incrementAndGet();
            total.incrementAndGet();
        }
        return new Permit(identity);
    }

    private void reject(String identity, String detail) {
        long n = rejected.incrementAndGet();
        if (n == 1L || n % 100L == 0L) {
            log.warn("[observe] SSE 连接被拒（累计 {} 次）：{} 已达上限（{}；全局上限 {}）—— "
                            + "关掉不用的标签页即可，或在 application.properties 调 "
                            + "mall.observe.sse.max-per-user / max-total",
                    n, identity, detail, maxTotal);
        }
    }

    /** 当前全局连接数（gauge）。 */
    public int connections() {
        return total.get();
    }

    /** 当前持有连接的身份数（gauge）。 */
    public int identities() {
        return perUser.size();
    }

    /** 累计被拒次数（gauge）。 */
    public double rejectedCount() {
        return rejected.get();
    }

    public int maxPerUser() {
        return maxPerUser;
    }

    public int maxTotal() {
        return maxTotal;
    }

    /**
     * 身份：登录 userId 优先，否则客户端 IP。
     *
     * <p>⚠️ 只认 {@code request.getRemoteAddr()}，**不读** {@code X-Forwarded-For} —— 本仓没有
     * 受信任的反向代理层，读那个头等于让客户端自己决定“我是谁”，一个脚本就能绕开单用户上限。
     */
    static String identityOf(HttpServletRequest request) {
        AuthSession.Identity who = AuthSession.identify(request);
        if (who != null && who.userId() != null && !who.userId().isBlank()) {
            return "user:" + who.userId();
        }
        String ip = request == null ? null : request.getRemoteAddr();
        return "ip:" + (ip == null || ip.isBlank() ? "unknown" : ip);
    }

    /** 一次占用的释放句柄；{@link #release()} 幂等（重复调用不会把计数减成负数）。 */
    public final class Permit {
        private final String identity;
        private boolean released;

        private Permit(String identity) {
            this.identity = identity;
        }

        public String identity() {
            return identity;
        }

        public synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            synchronized (SseConnectionRegistry.this) {
                total.decrementAndGet();
                AtomicInteger per = perUser.get(identity);
                if (per != null && per.decrementAndGet() <= 0) {
                    // 只有计数归零才移除；并发下另一个同身份连接可能刚 computeIfAbsent 拿到同一个
                    // AtomicInteger，remove(k, v) 的相等判据保证不会误删“已经又被加过”的那个。
                    perUser.remove(identity, per);
                }
            }
        }
    }
}
