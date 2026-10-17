package com.minke.mallmanagement.shared.lock;

import com.minke.mallmanagement.shared.lock.MallLockMetrics;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.Hold;
import com.minke.mallmanagement.shared.lock.MallLockMetrics.Outcome;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 分布式锁（B2）—— 五个写接口的互斥入口，形态是**模板方法**，不对外暴露锁句柄。
 *
 * <h2>【五个落点与各自锁的角色】</h2>
 * <table>
 *   <tr><td>{@code LockName.ORDER_ADD}</td><td>{@code orderAdd} —— <b>唯一的真修复</b>：
 *       锁 + 临界区内先查后插。锁**单独**拦不住重复行（20 个请求排队进场、每个照样裸 INSERT，
 *       只是把并发变成串行、仍插 20 行）—— 缺一不可。</td></tr>
 *   <tr><td>{@code ORDER_PAY} / {@code ORDER_RECEIVE} / {@code ORDER_DELIVERY}</td>
 *       <td>三个 CAS 状态流转端点 —— 条件 UPDATE 本来就是正确的并发控制，
 *       锁在这里**净正确性收益为 0**，只买到「同单状态流转串行化 + 统一取锁观测口径」。</td></tr>
 *   <tr><td>{@code FRIEND_ADD}</td><td>{@code friendAdd} —— 既有查重已正确（TOCTOU），
 *       锁把「查 + 插」合成原子。⚠️ 键必须规范化成 {@code min:max}，理由见该类调用点。</td></tr>
 * </table>
 *
 * <h2>【形态：模板方法，不暴露锁句柄 —— 三条都不是风格偏好】</h2>
 * <ol>
 *   <li>{@code OrderPayIfUpSmokeTest} 的两个用例**真调**被打锁的端点，而 {@code @MockitoBean}
 *       的默认替身返回 **null** —— 返回句柄的 API 会在 smoke 里 NPE（模板方法 + 替身 stub 成
 *       「直接跑 action」⇒ 那两个用例的 CAS 断言零改动）。</li>
 *   <li>acquire / 埋点 / release 全在一处 ⇒ **结构上不可能漏 unlock**（这是本笔最容易出的 bug）。</li>
 *   <li>action 的返回值与异常**原样透传** ⇒ 五个落点原有的 try/catch 与响应文案一个字节都不变
 *       （{@code A-S3-2} 要求 20 并发支付里恰好 19 个返回逐字「订单不存在或已付款」）。</li>
 * </ol>
 * 也**不用注解 + 切面**：{@code MallManagementApplicationTests} 的
 * {@code goodsControllerIsNotAopProxied} / {@code messageControllerIsNotAopProxied} 在完整上下文里
 * 钉死了两个 Controller 无代理，故锁只能是**方法体内的显式调用**（与 C1 把 {@code evictPair}
 * 收敛成显式调用同一条路）。
 *
 * <h2>【懒建 + 自带冷却窗口 —— 为什么不复用 L2HealthProbe】</h2>
 * 复用探针的健康信号**复核后否决**，两条都是硬伤：{@code markFailed()} 无条件重设窗口且
 * {@code metrics.degraded()} 在 {@code !down} 时 +1 ⇒ 锁的失败会把 {@code mall.cache.l2.up} 翻成 0、
 * 记一条「L2 不可达」WARN，而 L2 探测通道可能完全健康（**指标污染**）；且 {@code isHealthy()} 只在
 * {@code probeAllowedAtNanos == 0} 时为真、清零只发生在 {@code markOk()}（由 L2 探测或**真实的 L2 缓存
 * 操作成功**触发）⇒ **纯写负载下锁会被永久禁用**（恢复路径不成立）。故锁自带窗口（本类，约 40 行，
 * 与 L2 探针同形：抢创建权的 CAS 单飞 + 冷却 + 只在真实翻转时记录 + {@code mall.lock.up}）。
 *
 * <h2>【冷启动：为什么要有 awaitClientCreated】</h2>
 * {@code Redisson.create()} 是**启动期同步建连**（默认 {@code connectTimeout=10s} /
 * {@code retryAttempts=3} / {@code retryInterval=1.5s} ⇒ 不可达时一个请求挂十几秒），故必须懒建，
 * 且本类把四项超时都显式调小（见常量）。懒建带来一个新的并发窗口：**冷启动的首个并发突发**
 * （A3 的 20 并发下单正是这个形状）里，只有抢到 CAS 的线程在建客户端，其余 19 个若直接
 * 判「降级 → fail-open 直行」，那 19 个请求就**同时进入临界区** —— 不只是指标被污染成
 * 「Redis 宕机」，而是 A-S2a-1 会真红。故非获胜者走 {@link #awaitClientCreated(long)} 有界等待，
 * 把「正在建客户端」与「Redis 不通」这两件事分开。
 *
 * <p>⚠️ 冷启动首次锁请求的**失败**代价 ≈ {@link #CREATOR_WAIT_MILLIS}ms 量级
 * （{@code connectTimeout×(retryAttempts+1)+retryInterval} + 500ms 给 DNS/Netty 线程池），
 * **比 L2 探针的 {@code app.cache.l2-probe.timeout=200ms} 大一个数量级**，别混为一谈。
 *
 * <h2>【构造期零 Redis I/O】</h2>
 * 构造器只读属性 + 打一行日志 + 绑定 {@code mall.lock.up}（绑定不是 I/O）；
 * {@code Redisson.create()} 只在**第一次真实取锁**时发生。原因：{@code MallManagementApplicationTests}
 * 是 {@code @SpringBootTest} 全上下文，CI 里 Redis 可能是「连得上但 NOAUTH」或不可达 ——
 * 构造期建连会让 {@code contextLoads} 在**上下文加载期**红。
 *
 * <h2>【关闭】</h2>
 * 实现 {@link DisposableBean}：Redisson 的 Netty 线程非 daemon，不 {@code shutdown()} 会拖住 JVM 退出
 * （{@code mvn test} 不退出）。已实测。
 */
@Service
public class DistributedLockService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    /**
     * Redis key 前缀。与 L2 缓存**同库**（db 由 {@code spring.data.redis.database} 决定），
     * key 空间靠这个前缀隔离。⚠️ 本前缀是 key 格式的**唯一定义处**：Redis 里直接看到的是
     * {@code mall:lock:order-add:ORDER-xxx} 这种明文（见 {@link #CODEC} 的说明）。
     */
    private static final String KEY_PREFIX = "mall:lock:";

    /**
     * 锁不传值，且**必须**用 {@code StringCodec} 而不是 Redisson 默认的 {@code Kryo5Codec}：
     * <ul>
     *   <li>默认编解码器是二进制的 ⇒ Redis 里的 key 会是一串二进制，上面那句「key 是明文
     *       {@code mall:lock:…}」就不成立（运维与 A3 取证都按明文检索）。</li>
     *   <li>{@code Kryo5Codec} 在 Java 17 上有 {@code --add-opens} 风险（本仓不打算加 JVM 参数）。</li>
     * </ul>
     */
    private static final StringCodec CODEC = StringCodec.INSTANCE;

    /** Netty 线程数：取锁是低频阻塞调用，默认（CPU 核数 × 2）在这台机器上会白占线程。 */
    private static final int NETTY_THREADS = 4;

    // ===== Redisson 侧超时（D2）：全部显式调小，默认值对本项目而言过大 =====
    /** 建连超时（Redisson 默认 10000）。不可达端点上的失败代价主要由它决定。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    /** 命令超时（Redisson 默认 3000）。 */
    private static final int COMMAND_TIMEOUT_MILLIS = 1000;
    /** 重试次数（Redisson 默认 3）。1 ⇒ 失败路径 ≈ 2 次建连 + 1 次间隔。 */
    private static final int RETRY_ATTEMPTS = 1;
    /** 重试间隔（Redisson 默认 1500）。 */
    private static final int RETRY_INTERVAL_MILLIS = 200;

    /**
     * 非创建者在「别人正在建客户端」时的等待上限 —— **由上面四项推导，不是可调参数**：
     * {@code connectTimeout×(retryAttempts+1) + retryInterval×retryAttempts} 覆盖创建者一次失败
     * 所需的全部时间（建连 2 次 + 中间间隔），再留 500ms 给 DNS 解析与 Netty 线程池拉起。
     * 比这更长只是空等；更短则会把「创建者正在失败」误判成「还没建完」。
     */
    private static final long CREATOR_WAIT_MILLIS = (long) CONNECT_TIMEOUT_MILLIS * (RETRY_ATTEMPTS + 1)
            + (long) RETRY_INTERVAL_MILLIS * RETRY_ATTEMPTS + 500;

    /** 等待创建者时的轮询间隔（5ms）：创建耗时是几十毫秒量级，不值得用更细的粒度。 */
    private static final long AWAIT_POLL_MILLIS = 5;

    private final MallLockMetrics lockMetrics;
    private final RedisProperties redisProperties;
    private final Duration wait;
    private final Duration lease;
    private final Duration cooldown;

    /** 已建成的客户端。null = 还没建过或从没建成过（**不**代表 Redis 不可达，见「降级」字段）。 */
    private final AtomicReference<RedissonClient> client = new AtomicReference<>();

    /** 创建权单飞：防止冷启动突发里 20 个线程各自 {@code Redisson.create()}（各拉 4 个 Netty 线程）。 */
    private final AtomicBoolean creating = new AtomicBoolean(false);

    /**
     * 降级状态与它的冷却截止时刻（nanoTime 口径）。
     * ⚠️ 用「一个布尔 + 一个截止时刻」而不是「截止时刻为 0 表示可用」：{@code System.nanoTime()}
     * 的起点是任意的、**可能为负**，拿 0 当哨兵会在某些进程里开局即误判降级。
     */
    private volatile boolean degraded = false;
    private final AtomicLong cooldownUntilNanos = new AtomicLong();

    public DistributedLockService(MallLockMetrics lockMetrics,
                                  RedisProperties redisProperties,
                                  @Value("${app.lock.wait:5s}") Duration wait,
                                  @Value("${app.lock.lease:10s}") Duration lease,
                                  @Value("${app.lock.cooldown:30s}") Duration cooldown) {
        this.lockMetrics = lockMetrics;
        this.redisProperties = redisProperties;
        this.wait = wait;
        this.lease = lease;
        this.cooldown = cooldown;

        warnIfWaitNotShorterThanLease(wait, lease);

        // 把生效值打进启动日志（同 CacheConfig 的「两级缓存已装配」行）：属性名拼错时 @Value 会
        // 静默退化成默认值、而默认值恰好与 properties 同值 —— 有这一行，「属性被读到」才是
        // 启动即可取证的事实。ASCII 片段 "B2" 供 GBK 日志下检索。
        // ⚠️ 本行**不代表已连上 Redis**（懒建）：首个锁请求才会建客户端。
        log.info("分布式锁已装配（B2）：wait={} lease={} cooldown={} keyPrefix={} — 客户端懒建，本行不代表已连 Redis",
                wait, lease, cooldown, KEY_PREFIX);

        // 锁可用性出口：gauge mall.lock.up（1=可尝试取锁 0=冷却窗口内）。
        // 绑定不是 I/O、不连 Redis，构造期做掉 ⇒ up 恒存在、初值 1（还没有失败证据时不该预先降级）。
        // 传 lambda 而不是本对象：取值对象没有别的强引用，必须由 MallLockMetrics 自己存一份 ——
        // 否则一次 GC 后 gauge 静默变 NaN（见 bindUp）。isUsableNow() 是纯读取，
        // scrape 本指标**不会**建客户端、不会碰网络。
        lockMetrics.bindUp(this::isUsableNow);
    }

    // ============================== 对外唯一入口 ==============================

    /**
     * 在锁下执行 {@code action}；**拿不到锁也照样执行**（fail-open）。永不抛「取锁失败」类异常。
     *
     * <h3>【四路结果与各自去向】</h3>
     * <ol>
     *   <li>{@code acquired} —— 互斥下执行 {@code action}，返回后释放。</li>
     *   <li>{@code degraded} —— Redis 已知不可达（冷却窗口内，**零网络代价**）⇒ 直行。</li>
     *   <li>{@code error} —— 取锁尝试抛异常 ⇒ 直行，并转入冷却（后续请求零代价降级）。</li>
     *   <li>{@code timeout} —— waitTime 内没抢到 ⇒ 直行，见下面的推演。</li>
     * </ol>
     *
     * <h3>【竞用超时（{@code timeout}）为什么也 fail-open —— 对 orderAdd 的推演】</h3>
     * 竞用超时 = 放弃锁直接 INSERT = 没有临界区查重保护 ⇒ **有可能插出 2 行**。
     * 这是**有意选择**，不是漏考虑：
     * <ol>
     *   <li>查重**存在于 action 内部**（{@code orderAdd} 临界区里的 {@code SELECT COUNT(*)}）——
     *       超时窗口只是一段 TOCTOU 窗口，**不是无条件 INSERT**；能插出 2 行的前提是
     *       「两个请求在同一个 OrderId 上撞进这段窗口」，而 OrderId 由前端每次下单生成
     *       （{@code ProductPage.vue generateOrderId()}）、**不复用** ⇒ 正常路径上不存在这个前提。</li>
     *   <li>一致性：Redis **完全不可达**时已经是 fail-open。若「Redis 活着但争用」反而拒绝，
     *       就形成反常梯度 —— **更差的基础设施**（连不上）**给出更宽松的行为**，更好的基础设施
     *       （连得上、只是忙）**给出更严格的行为**。这条梯度没有业务依据。</li>
     *   <li>代价对比：为拦截一个正常情况不可能发生的重复而**拒绝一笔真实订单**，是坏交易。</li>
     * </ol>
     * 故：**争用超时与 Redis 不可达同级，都走 fail-open；5s wait 相对毫秒级临界区有 20 倍余量，
     * 正常不发生，真发生属基础设施事件。** 40 线程排队量级 ≈ 40 × 3~6ms ≈ 120~240ms，
     * wait 有 20 倍余量（{@code A-S4a-7} 的 40 线程同单场景靠这个余量保持绿）。
     * 真发生时的重复行是**已知代价**，由 {@code mall.lock.acquires{result=timeout}} 可观测；
     * 彻底兜底是给 {@code order.OrderId} 加唯一索引（另一笔，见 worklog 的 non-goal）。
     *
     * <p>⚠️ 争用超时**不新发任何拒绝文案**：拒绝消息只来自各 action 内部既有的分支。
     * 这是 {@code A-S3-2}（20 并发支付里恰好 19 个 {@code success:false} 且 msg 逐字
     * 「订单不存在或已付款」）能保持绿的前提。
     *
     * <h3>【调用点的两条硬要求】</h3>
     * <ul>
     *   <li>⚠️ **被打锁的方法体内不得引入 {@code @Transactional}**（本笔对 {@code orderAdd} 的
     *       明确约束）：事务提交发生在**方法返回之后**，而锁在本方法 {@code finally} 里、
     *       即方法**返回之前**已释放 ⇒ 后进者拿到锁后的 {@code SELECT COUNT(*)} 在 RR 下读的是
     *       自己事务开始时的快照、看不到前一个**未提交**的行 ⇒ 仍插出 2 行。
     *       不加事务时 autocommit 让 INSERT 在释放锁**之前**提交，这正是查重成立的前提。</li>
     *   <li>⚠️ 调用点必须**无条件**调用本方法（不能塞进「成功分支」里）：带写的方法在无事务下
     *       可能「异常但已改库」，条件化调用会漏掉那一路（同 {@code FriendCacheService.evictPair}
     *       的论证方向）。</li>
     * </ul>
     *
     * <p>action 抛出的异常**原样上抛**（本方法不做 catch）—— 释放仍会在 {@code finally} 里发生。
     *
     * @param name   逻辑锁名；其 {@link LockName#tag()} 同时是 {@code mall.lock.*} 的 tag 取值（有界）
     * @param bizKey 业务键，只进 Redis key 与事件流的 key 字段，**绝不进 tag**（基数无界）
     * @param action 临界区（或降级时）要执行的动作
     * @return action 的返回值
     */
    public <T> T runLocked(LockName name, String bizKey, Supplier<T> action) {
        RedissonClient redis = obtain();
        if (redis == null) {
            // 降级放行：与「争用超时」同级，见方法注释的推演。
            lockMetrics.acquire(name.tag(), bizKey, Outcome.DEGRADED, 0L,
                    "锁客户端不可用：降级冷却期内跳过取锁");
            return action.get();
        }

        RLock lock = redis.getLock(keyOf(name, bizKey));
        long startNanos = System.nanoTime();
        boolean held;
        try {
            held = lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断位：本方法把中断当「取锁失败」吞掉，但不该**顺带**吞掉线程的中断语义
            // （调用方或容器可能正靠它收尾）。
            Thread.currentThread().interrupt();
            lockMetrics.acquire(name.tag(), bizKey, Outcome.ERROR, elapsed(startNanos), "取锁被中断");
            return action.get();
        } catch (Exception e) {
            // ⚠️ catch Exception 而不是 RedisException：锁层的异常**必须 total catch**
            // （A-S2b-1）—— 任何取锁异常都不该变成业务请求的 5xx。
            lockMetrics.acquire(name.tag(), bizKey, Outcome.ERROR, elapsed(startNanos),
                    com.minke.mallmanagement.shared.text.EventReason.of("取锁异常", e));
            markDegraded(e);
            return action.get();
        }

        // 走到这里 ⇒ 命令成功往返了（**无论是否拿到锁**）⇒ 这是「Redis 可用」的真凭据，
        // 于是把降级标记清掉。⚠️ 少了这一步，「真实翻转」的 WARN 一辈子只会有第一次：
        // degraded 若只在「新建客户端」时清零，而客户端是长命的 → 第二次故障会被当成
        // 「还在同一段故障里」而不再 WARN、不再记事件（恢复路径不成立）。
        markOk();

        if (!held) {
            lockMetrics.acquire(name.tag(), bizKey, Outcome.TIMEOUT, elapsed(startNanos),
                    "取锁超时：等待 " + wait.toMillis() + " ms 未获得");
            return action.get();
        }

        lockMetrics.acquire(name.tag(), bizKey, Outcome.ACQUIRED, elapsed(startNanos), null);
        try {
            return action.get();
        } finally {
            release(lock, name, bizKey);
        }
    }

    /** 锁是否处于「可尝试取锁」状态 —— {@code mall.lock.up} 的取值来源。**纯读取**：不碰网络、不建客户端。 */
    public boolean isUsableNow() {
        // 降级中但冷却已过 ⇒ 报可用：下一次 runLocked 真的会去尝试（恢复与否由那一次的结果决定）。
        return !degraded || System.nanoTime() - cooldownUntilNanos.get() >= 0;
    }

    /**
     * 锁 key 的构造：{@code mall:lock:<逻辑名>:<业务键>}，如 {@code mall:lock:order-add:ORDER-123}。
     * 公开为 static 是**故意的**：key 格式的唯一定义处，A3/场景测试要断言 Redis 里的键时用它，
     * 不各处重抄字面量（同 L2ProbeConfig 从 RedisProperties 派生端点、不重抄的理由）。
     */
    public static String keyOf(LockName name, String bizKey) {
        return KEY_PREFIX + name.tag() + ":" + bizKey;
    }

    /**
     * **无序对**的业务键：把两个 id 规范化成 {@code min:max}。
     *
     * <p>⚠️ 它的存在不是风格问题。{@code friendAdd} 的查重 SQL 是 **OR 双向**的
     * （{@code (customerid=A AND friendid=B) OR (customerid=B AND friendid=A)}）⇒ A→B 与 B→A 是
     * **同一个逻辑对**，必须落在**同一把锁**上。按入参顺序拼 key 会让两个方向各持一把锁 ⇒
     * 锁形同虚设、TOCTOU 原样保留；而 A3 的 friend 场景只打一个方向，**测试看不出来**
     * （2026-09-29 设计复核时发现）。故这个「只能靠代码做对」的点必须有一个单测钉着，
     * 而单测只能钉公开成员 ⇒ 放在这里（key 格式的唯一出处），而不是留在调用点做私有方法。
     *
     * @param a 关系的一端（例如 customerId）
     * @param b 另一端（例如 friendId）
     * @return 两个 id 按字典序拼接的结果；{@code pairKey(A,B).equals(pairKey(B,A))} 恒成立
     */
    public static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    /**
     * 上下文关闭时释放客户端（{@link DisposableBean}）。
     * Redisson 的 Netty 线程非 daemon —— 不 shutdown 会让 {@code mvn test} 结束后 JVM 不退出。
     * 从未建过客户端时是 no-op（懒建：大多数测试进程走到这里 client 仍是 null）。
     */
    @Override
    public void destroy() {
        RedissonClient existing = client.getAndSet(null);
        if (existing == null) {
            return;
        }
        try {
            existing.shutdown();
            log.info("分布式锁客户端已关闭（B2）");
        } catch (Exception e) {
            log.warn("分布式锁客户端关闭失败（B2）：{} —— 进程即将退出，忽略", e.toString());
        }
    }

    // ============================== 客户端懒建 ==============================

    /**
     * 取一个可用客户端；不可用则返回 null（调用方 fail-open）。
     *
     * <p>判定顺序是**有讲究**的：先看冷却窗口、再看已有客户端。反过来（先 return 已有客户端）
     * 会让一个「活着但已断连」的客户端**永久绕过冷却窗口** —— 每个请求都要白等一次
     * {@code connectTimeout} 量级的失败，冷却就形同虚设。断开后的客户端对象**不丢弃**：
     * Redisson 会在下次命令时自行重连，冷却到期后的第一次尝试成功即恢复（见 {@link #markOk()}）。
     */
    private RedissonClient obtain() {
        if (!isUsableNow()) {
            return null;
        }
        RedissonClient existing = client.get();
        if (existing != null) {
            return existing;
        }
        if (!creating.compareAndSet(false, true)) {
            // 别人正在建（冷启动首个突发）：有界等待，把「正在建」与「Redis 不通」分开。
            return awaitClientCreated(CREATOR_WAIT_MILLIS);
        }
        try {
            RedissonClient created = createClient(buildConfig());
            client.set(created);
            markOk();
            return created;
        } catch (Exception e) {
            markDegraded(e);
            return null;
        } finally {
            creating.set(false);   // 必须在 client.set 之后：见 awaitClientCreated 的提前退出分支
        }
    }

    /**
     * 等创建者把客户端建出来。**这不是为了指标好看，而是为了 A-S2a-1**：
     * 冷启动首个并发突发里，非获胜者若直接判降级直行，那 19 个请求会同时进入临界区 ——
     * 20 并发下单仍可能插出 2 行（锁等于没上）。
     *
     * <p>三条退出路径：①拿到客户端（正常）；②创建者已收工且失败（{@code creating} 转 false
     * 而 {@code client} 仍为 null）⇒ 立刻返回、不空等满上限；③超时或被中断 ⇒ 返回 null（降级）。
     */
    private RedissonClient awaitClientCreated(long waitMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (System.nanoTime() - deadlineNanos < 0) {
            RedissonClient created = client.get();
            if (created != null) {
                return created;
            }
            if (!creating.get()) {
                return null;   // 创建者已结束（失败）：再等也没有意义
            }
            try {
                Thread.sleep(AWAIT_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 建客户端。**这是单元测试的接缝**（{@code protected} 而非 {@code private} 的唯一理由）：
     * 客户端建连是本类里唯一必须真连 Redis 的动作，把它单独拎出来后，冷却/翻转/释放这几条
     * 状态机路径可以用假实现驱动（见 DistributedLockServiceTest），无需 Redis、无需 Spring。
     * ⚠️ 生产路径不覆盖本方法；任何「先建后判」的逻辑改动都不该写在这里。
     */
    protected RedissonClient createClient(Config config) {
        return Redisson.create(config);
    }

    /**
     * 建 Redisson 配置。端点与口令**全部从 {@link RedisProperties} 派生**：与主工厂同一个来源，
     * 结构上排除「改了配置只改一处」的漂移（同 L2ProbeConfig 的探测通道）。
     *
     * <p>只用单机模式（{@code useSingleServer}）：本项目的 Redis 是单实例，哨兵/集群不在范围。
     * 口令不写进日志、不写进异常补充信息。
     */
    private Config buildConfig() {
        Config config = new Config();
        config.setCodec(CODEC).setNettyThreads(NETTY_THREADS);

        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setTimeout(COMMAND_TIMEOUT_MILLIS)
                .setRetryAttempts(RETRY_ATTEMPTS)
                .setRetryInterval(RETRY_INTERVAL_MILLIS);
        if (StringUtils.hasText(redisProperties.getUsername())) {
            server.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        return config;
    }

    // ============================== 降级状态机 ==============================

    /**
     * 转入降级（或延长已有的冷却窗口）。
     *
     * <p>WARN **只在真实翻转（健康 → 降级）时**打一行：冷却窗口内的后续失败会让本方法继续被调到
     * （每个失败请求一次），逐次 WARN 会刷屏，且它们没有带来新信息 —— 「多少」由
     * {@code mall.lock.acquires{result=error}} 回答、「顺序」由事件流回答。
     *
     * <p>⚠️ 并发下两三个线程可能同时判到「翻转」而各打一行 WARN（本类刻意不加锁）——
     * 监控标志上多一行日志无害，比为此引入同步便宜。
     */
    private void markDegraded(Exception e) {
        cooldownUntilNanos.set(System.nanoTime() + cooldown.toNanos());
        if (!degraded) {
            degraded = true;
            lockMetrics.transition(false,
                    com.minke.mallmanagement.shared.text.EventReason.of("锁进入降级", e));
            log.warn("分布式锁转为降级（B2）：{} 秒内不再尝试取锁，写请求按 fail-open 走原路径（结果仍然正确性自洽，"
                            + "但 orderAdd 在此期间退化为裸 INSERT）。原因：{}", cooldown.toSeconds(), e.toString());
        }
    }

    /**
     * 恢复：只在真实翻转（降级 → 健康）时记一行 INFO + 一条 transition 事件。
     *
     * <p>两处调用，都是「Redis 确实能用」的凭据：①客户端建成；②一次取锁命令成功往返
     * （**拿到或没拿到锁都算** —— 「没拿到」也是命令成功执行的结果）。热路径上它只读一个
     * volatile 布尔就返回，代价可忽略。
     */
    private void markOk() {
        if (degraded) {
            degraded = false;
            lockMetrics.transition(true, null);
            log.info("分布式锁已恢复（B2）：下一次取锁将正常尝试");
        }
    }

    // ============================== 释放与启动期校验 ==============================

    /**
     * 释放锁。**必须先判 {@code isHeldByCurrentThread()}**：租期到期后锁已被别人拿走时，
     * 无脑 {@code unlock()} 会误释放**别人的锁**（Redisson 的 unlock 走 Lua 判持有者，
     * 普通实现里这是经典事故）。判为「非本线程持有」即 {@code lease_lost} ——
     * 它意味着本次临界区与他人**可能**重叠，是本笔最值钱的一个信号。
     *
     * <p>释放失败不改变业务结果（action 已经跑完），故只记指标、不抛、不记日志、不进冷却。
     */
    private void release(RLock lock, LockName name, String bizKey) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                lockMetrics.hold(name.tag(), bizKey, Hold.RELEASED, null);
            } else {
                lockMetrics.hold(name.tag(), bizKey, Hold.LEASE_LOST,
                        "租约已丢失：临界区可能与他人重叠");
            }
        } catch (Exception e) {
            lockMetrics.hold(name.tag(), bizKey, Hold.ERROR,
                    com.minke.mallmanagement.shared.text.EventReason.of("释放锁失败", e));
        }
    }

    /**
     * 不变量「wait < lease」的启动期校验。违反的后果：持锁者崩溃（或 Redis 慢到租期不够）时，
     * 等待方会在**等待超时后**走 fail-open 回落路径，而此刻锁**还没**因租期到期释放 ⇒
     * 回落路径与持锁者的临界区重叠得更久。wait < lease 保证「等待方先放弃，而不是等租约到期」。
     * 只 WARN：这是配置疑点，不该让应用起不来（同 L2ProbeConfig 的两道校验）。
     */
    private static void warnIfWaitNotShorterThanLease(Duration wait, Duration lease) {
        if (wait.compareTo(lease) >= 0) {
            log.warn("app.lock.wait({}) 不小于 app.lock.lease({})：持锁者崩溃时等待方会一直等到租约自然到期，"
                    + "回落路径与临界区重叠时间更长。建议 wait 明显小于 lease（默认 5s < 10s）。", wait, lease);
        }
    }

    private static long elapsed(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    /**
     * 逻辑锁名 —— 它的 {@code tag} 同时是 {@code mall.lock.*} 的 tag 取值。
     *
     * <p>用**封闭枚举**而不是调用点各拼字符串：tag 的基数是铁律（见 {@code MallLockMetrics} 类注释），
     * 枚举让「取值只有这 5 个」成为编译期事实，也让 A3/告警侧的取值表有唯一出处。
     * 新增落点必须同时在枚举里加一项 —— 这正是想要的那道摩擦。
     */
    public enum LockName {
        ORDER_ADD("order-add"),
        ORDER_PAY("order-pay"),
        ORDER_RECEIVE("order-receive"),
        ORDER_DELIVERY("order-delivery"),
        FRIEND_ADD("friend-add");

        private final String tag;

        LockName(String tag) {
            this.tag = tag;
        }

        /** 指标 tag 取值（有界）。同时是 Redis key 里的逻辑名片段。 */
        public String tag() {
            return tag;
        }
    }
}
