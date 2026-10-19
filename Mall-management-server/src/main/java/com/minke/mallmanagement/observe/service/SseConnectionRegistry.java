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
 * SSE 长连接的**名额表**（D3-6）—— 按角色分档的单用户上限 + 全局上限，超限时让端点回 429。
 *
 * <h2>【为什么必须有这张表】</h2>
 * 每条 {@code StreamingResponseBody} 连接占一条**异步线程**（见 {@link SseStreamWriter} 类注释）。
 * 演示现场很容易连开十几个标签页（指标 / 事件 / 订单三个页面各开几条，再加几个残留的旧标签），
 * 每条连接每秒产一帧、读一次库存水位 —— 到某个点后端线程与连接池被吃满，页面**白屏**，
 * 而现场能看到的症状是「系统挂了」，不是「开太多页了」。
 *
 * <p>用户 2026-10-18 裁定（分角色）：
 * <ul>
 *   <li><b>管理员</b>：单账号 <b>500</b>，管理员全局 <b>2000</b>（观测台本身就是多图表多连接）；</li>
 *   <li><b>普通用户 / 商家</b>：单账号 <b>20</b>，普通用户全局 <b>200</b>（严格防挤占）。</li>
 * </ul>
 * 数值全部写在配置文件里（现场可改，不改代码）。⚠️ 两档的**全局名额互相独立**：
 * 普通用户把 200 条占满不会把管理员挤到 429，反之亦然。未登录请求按客户端 IP 归入
 * 普通用户档 —— 把匿名请求算进管理员档等于把放宽的额度送给任何脚本。
 *
 * <h2>【身份口径】</h2>
 * 取 {@link AuthSession#identify} 的 {@code userId}；认不出来（dev / a3 的
 * {@code app.auth.enabled=false}，或请求没带票）时按**客户端 IP** 计
 * （{@code ip:127.0.0.1}）。⚠️ 不按连接随机分组 —— 那样等于没有单用户上限。
 * 角色也取自 {@code identify}：{@code role=3} 为管理员档，其余（含 {@code null}）为普通档。
 *
 * <h2>【名额一定会还】</h2>
 * 释放点只有一个：{@link SseStreamWriter#stream(String, java.util.function.Supplier, Runnable)}
 * 的 {@code finally}（正常结束 / 客户端断开 / 产帧抛异常 / 空闲看门狗断开，四条路径都经过它）。
 * ⚠️ 超限的那条请求**没有占用名额**，也就没有对应释放 —— 控制器在拿到 {@code null} 时直接返回。
 */
@Component
public class SseConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);

    /** 管理员角色号（唯一出处是 {@link AuthSession#ROLE_ADMIN}，这里只做别名便于阅读）。 */
    private static final int ROLE_ADMIN = AuthSession.ROLE_ADMIN;

    /** 一档额度。 */
    public record Limits(int maxPerUser, int maxTotal, String label) {
    }

    /** 额度档：管理员 / 普通用户（含未登录按 IP 计的匿名请求）。 */
    public enum Tier { ADMIN, USER }

    private final Limits adminLimits;
    private final Limits userLimits;

    /** 身份 → 当前占用连接数（两档共用一张表；身份键本身已含 userId / IP，不会串档）。 */
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();

    /** 两档的全局计数**分开**：一档占满不能把另一档挤掉。 */
    private final AtomicInteger adminTotal = new AtomicInteger();
    private final AtomicInteger userTotal = new AtomicInteger();

    /** 累计被拒次数（面板/排障用；不是连接数）。 */
    private final AtomicLong rejected = new AtomicLong();

    public SseConnectionRegistry() {
        this(20, 200, 500, 2000);
    }

    @Autowired
    public SseConnectionRegistry(
            @Value("${mall.observe.sse.max-per-user:20}") int maxPerUser,
            @Value("${mall.observe.sse.max-total:200}") int maxTotal,
            @Value("${mall.observe.sse.admin.max-per-user:500}") int adminMaxPerUser,
            @Value("${mall.observe.sse.admin.max-total:2000}") int adminMaxTotal,
            MeterRegistry registry) {
        this(maxPerUser, maxTotal, adminMaxPerUser, adminMaxTotal);
        Gauge.builder("mall.observe.sse.connections", this, SseConnectionRegistry::connections)
                .description("当前打开的 SSE 长连接数（D3；普通用户全局 / 管理员全局上限分别见 "
                        + "mall.observe.sse.max-total / mall.observe.sse.admin.max-total）")
                .register(registry);
        Gauge.builder("mall.observe.sse.users", this, SseConnectionRegistry::identities)
                .description("当前持有 SSE 连接的身份数（用户或未登录时的客户端 IP）")
                .register(registry);
        Gauge.builder("mall.observe.sse.rejected", this, SseConnectionRegistry::rejectedCount)
                .description("本进程累计因超过 SSE 连接上限被拒的请求数")
                .register(registry);
    }

    /** 测试 / 手工构造用（不注册 gauge）：只给普通档，管理员档沿用裁定默认 500 / 2000。 */
    public SseConnectionRegistry(int maxPerUser, int maxTotal) {
        this(maxPerUser, maxTotal, 500, 2000);
    }

    /** 测试 / 手工构造用：两档额度都可指定。 */
    public SseConnectionRegistry(int userMaxPerUser, int userMaxTotal,
                                 int adminMaxPerUser, int adminMaxTotal) {
        this.userLimits = new Limits(Math.max(1, userMaxPerUser), Math.max(1, userMaxTotal), "普通用户");
        this.adminLimits = new Limits(Math.max(1, adminMaxPerUser), Math.max(1, adminMaxTotal), "管理员");
    }

    /**
     * 尝试为一个 SSE 请求占一个名额。
     *
     * @return 占到了 ⇒ 返回**释放句柄**（必须在流结束时调用一次，见类注释）；超限 ⇒ {@code null}
     */
    public Permit tryAcquire(HttpServletRequest request) {
        String identity = identityOf(request);
        Tier tier = tierOf(request);
        Limits limits = limitsFor(tier);
        synchronized (this) {
            AtomicInteger total = totalOf(tier);
            int nowTotal = total.get();
            if (nowTotal >= limits.maxTotal()) {
                reject(identity, tier, "全局 " + nowTotal + "/" + limits.maxTotal(), limits);
                return null;
            }
            AtomicInteger per = perUser.computeIfAbsent(identity, k -> new AtomicInteger());
            int nowPer = per.get();
            if (nowPer >= limits.maxPerUser()) {
                // ⚠️ 只拒、不先加全局：否则被拒的请求会占着一个永远不还的总数名额。
                reject(identity, tier, "单用户 " + nowPer + "/" + limits.maxPerUser(), limits);
                return null;
            }
            per.incrementAndGet();
            total.incrementAndGet();
        }
        return new Permit(identity, tier);
    }

    private void reject(String identity, Tier tier, String detail, Limits limits) {
        long n = rejected.incrementAndGet();
        if (n == 1L || n % 100L == 0L) {
            log.warn("[observe] SSE 连接被拒（累计 {} 次）：{} [{}] 已达上限（{}；全局上限 {}）—— "
                            + "关掉不用的标签页即可，或在 application.properties 调 {}",
                    n, identity, limits.label(), detail, limits.maxTotal(),
                    tier == Tier.ADMIN
                            ? "mall.observe.sse.admin.max-per-user / mall.observe.sse.admin.max-total"
                            : "mall.observe.sse.max-per-user / mall.observe.sse.max-total");
        }
    }

    private AtomicInteger totalOf(Tier tier) {
        return tier == Tier.ADMIN ? adminTotal : userTotal;
    }

    /** 当前连接的档位。⚠️ 未登录（IP 回退）一律普通档。 */
    public Tier tierOf(HttpServletRequest request) {
        AuthSession.Identity who = AuthSession.identify(request);
        return (who != null && who.role() != null && who.role() == ROLE_ADMIN) ? Tier.ADMIN : Tier.USER;
    }

    /** 这次请求适用的额度（页面的 429 文案据此说清楚「是哪一个上限」）。 */
    public Limits limitsFor(HttpServletRequest request) {
        return limitsFor(tierOf(request));
    }

    public Limits limitsFor(Tier tier) {
        return tier == Tier.ADMIN ? adminLimits : userLimits;
    }

    /** 当前全局连接数（gauge；两档之和）。 */
    public int connections() {
        return adminTotal.get() + userTotal.get();
    }

    /** 当前持有连接的身份数（gauge）。 */
    public int identities() {
        return perUser.size();
    }

    /** 累计被拒次数（gauge）。 */
    public double rejectedCount() {
        return rejected.get();
    }

    /** 普通档单用户上限（保留旧访问器；管理员档请用 {@link #limitsFor}). */
    public int maxPerUser() {
        return userLimits.maxPerUser();
    }

    /** 普通档全局上限（保留旧访问器；管理员档请用 {@link #limitsFor}). */
    public int maxTotal() {
        return userLimits.maxTotal();
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
        private final Tier tier;
        private boolean released;

        private Permit(String identity, Tier tier) {
            this.identity = identity;
            this.tier = tier;
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
                totalOf(tier).decrementAndGet();
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
