package com.minke.mallmanagement_server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L2（Redis）健康状态与冷却窗口 —— 全应用唯一持有者。
 *
 * 【为什么必须是单例，而不能留在 TwoLevelCache 里】
 * 这个状态原本是 {@link TwoLevelCache} 的实例字段，而 TwoLevelCache 由
 * {@link TwoLevelCacheManager} **按缓存名各建一个** —— 于是「Redis 挂了」这个
 * **全局事实**被拆成了 N 份互不相干的判定：新增一个缓存名就多一份状态、多一条 WARN，
 * 还会各自独立地付一次探测代价。现在只有 1 个缓存名（goodstype），看不出问题；
 * 等 cache-layer.md §9 列出的几个接口都上了缓存，一次 Redis 故障会变成 N 条告警。
 * 故上移到单例 —— 现在改便宜，多接口上缓存后再改贵。
 *
 * 【状态机】
 * <pre>
 *   健康(0) ──真实 L2 操作失败 / 探测失败──▶ 冷却(now + cooldown)
 *   冷却     ──任意 L2 操作────────────────▶ 跳过 L2（零网络代价，只读一个 volatile）
 *   冷却到期 ──第一个来碰 L2 的线程 CAS 成功─▶ 探测占位(now + PROBE_INFLIGHT) ──▶ PING
 *   冷却到期 ──其余线程 CAS 失败───────────▶ 跳过 L2
 *   PING 成功 ─▶ 健康(0)          PING 失败 ─▶ 冷却(now + cooldown)
 * </pre>
 * 抢探测权用「CAS 前推截止时间」而不是「AtomicBoolean probing」：时间戳会自己流逝，
 * 探测线程万一异常退出也能自愈；布尔标记一旦没被清掉，L2 就被永久绕过了。
 *
 * 【三件必须写清的事】
 * 1. **探测 ≠ 主通道健康**。本类持有的是**探测专用连接**（见 L2ProbeConfig），与主工厂
 *    的连接是两条。故 PING 成功只证明「端点可达」，不证明「主通道可用」。误判的后果有界
 *    且自纠正：清标记后真实操作若仍失败，会立刻 markFailed 重设窗口（最坏多付一次代价）。
 *    反向（端点通、主通道坏）是本类识别的盲区 —— 不假装能识别。
 * 2. **探测者要付探测代价，且拿不到好处**。窗口到期时，唯一抢到探测权的那个线程同步
 *    执行 PING，然后**仍然**按降级路径返回（只清标记，不重试当前操作）。即每 cooldown 秒，
 *    恰有一个请求多付这次探测的耗时。
 *    ⚠️ **200ms 是超时上限，不是实测耗时**（2026-09-28 实测，三种故障端点）：
 *      - 不可达时：稳态 **233ms**（= 200ms 超时 + 开销）；**进程内首次 0.5~0.8s**
 *        （489ms 连接被拒 / 612ms 黑洞 / 772ms SYN 被丢弃，含 Lettuce 客户端首建与重试）
 *      - 可达时：一次 PING 往返（量级同普通 Redis 命令，未单独计时）
 *    这是刻意取舍：换掉的是「故障期间**每个**冷读都付主通道超时」——黑洞端点 3.38s、
 *    SYN 被丢弃 3.40s、真实故障（连接被拒）325ms。
 *    要更快只能拉长 cooldown（恢复延迟变长），不能改成异步探测 —— 异步会把「谁判定成功/
 *    失败」重新变成并发问题。
 * 3. **配置漂移**。探测通道的 host/port/password/db 全部从 RedisProperties 派生，
 *    不重抄字面量；启动期另有「与主工厂比对」与「真实 PING 自检」两道校验（都在
 *    L2ProbeConfig），结果进日志。⚠️ 若将来给主工厂换成自定义工厂 / Sentinel / 集群，
 *    探测通道必须同步改，否则探测会指向一个不存在或不正确的端点。
 */
public class L2HealthProbe implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(L2HealthProbe.class);

    /**
     * 健康哨兵。截止时间一律是 `nanoTime() + 正数`，理论上存在恰好等于 0 的可能：
     * 概率可忽略，且后果只是那一次被当成健康态去碰 L2（失败会立刻重设窗口），不影响正确性。
     */
    private static final long HEALTHY = 0L;

    /**
     * 探测占位窗口 —— **内部常量，不是可调参数**：它是「同一时刻只有一个探测者」这条
     * 不变量的载体（抢到探测权的线程把截止时间前推到 now + PROBE_INFLIGHT，其他人因此
     * 看到「未到期」）。故它必须 > 探测超时，否则两个探测者可能在窗口边界上重叠；
     * L2ProbeConfig 在启动期校验这一点并 WARN。
     */
    static final Duration PROBE_INFLIGHT = Duration.ofSeconds(1);

    /**
     * 探测专用通道。**非 bean**（理由见 L2ProbeConfig 类注释：它绝不能是
     * RedisConnectionFactory bean），故其生命周期由本类负责 —— 本类是 bean，
     * 容器关闭时会调 {@link #destroy()}。
     *
     * 类型取接口而不是 LettuceConnectionFactory：本类只需要「给我一条连接」+「PING」，
     * 不依赖 Lettuce 细节；顺带也让并发逻辑可以用一个接口假实现来实测（见
     * L2HealthProbeConcurrencyTest —— 这段 CAS 不实测等于没写）。
     */
    private final RedisConnectionFactory probeConnectionFactory;

    /** 冷却窗口长度。 */
    private final Duration cooldown;

    /**
     * 冷却截止时间，nanoTime 基准（单调时钟：墙钟被 NTP 校正不会凭空拉长/缩短窗口）。
     * {@link #HEALTHY} = 健康、L2 可用；> now = 冷却中；两个用途共用一个值：
     *   - 限频：非 0 且未到期 → 直接跳过 L2，不探测
     *   - 互斥：到期时用 CAS 把值前推到 now + PROBE_INFLIGHT，只有一个线程能成功
     */
    private final AtomicLong probeAllowedAtNanos = new AtomicLong(HEALTHY);

    /**
     * **只服务于「状态翻转才打日志」的去重**，不参与任何判定 —— 状态一律看
     * {@link #probeAllowedAtNanos}。必须与它分开：探测占位期间的截止时间非 0，
     * 若拿它当「是否已记录过故障」，首次故障（发生在探测占位窗口内）会漏掉日志。
     */
    private volatile boolean down;

    L2HealthProbe(RedisConnectionFactory probeConnectionFactory, Duration cooldown) {
        this.probeConnectionFactory = probeConnectionFactory;
        this.cooldown = cooldown;
    }

    /**
     * 热路径唯一入口：现在能不能碰 L2？false = 不要碰（调用方按「L2 未命中 / 跳过写」处理，
     * 也就是和「Redis 连不上」完全相同的降级路径）。
     *
     * ⚠️ **本方法有副作用，且可能阻塞**（阻塞时长 = 一次探测，见类注释第 2 条：不可达时
     * 稳态约 233ms、进程内首次 0.5~0.8s —— 探测超时 200ms 只是上限）：当冷却窗口刚好到期时，
     * 唯一抢到探测权的线程会在这里同步执行一次 PING，然后**仍然返回 false**（补 3：探测者本人不重试当前
     * 操作）。调用方不必区分「因冷却返回 false」与「因探测返回 false」—— 两种都是降级。
     * 命名取「是否可用」而不是「是否跳过」，是为了让健康路径读起来没有双重否定；
     * 副作用写在这里，别只看名字。
     */
    public boolean isL2UsableNow() {
        long deadline = probeAllowedAtNanos.get();
        if (deadline == HEALTHY) {
            return true;
        }
        long now = System.nanoTime();
        if (now < deadline) {
            return false;   // 冷却中：零网络代价
        }
        if (!probeAllowedAtNanos.compareAndSet(deadline, now + PROBE_INFLIGHT.toNanos())) {
            return false;   // 别的线程已抢到探测权，本线程照旧走降级
        }
        probeNow();
        return false;       // 补 3：探测者本次也不碰 L2
    }

    /** L2 操作成功（真实操作或探测成功）：清标记 + 只在「故障 → 恢复」时打一行。 */
    public void markOk() {
        probeAllowedAtNanos.set(HEALTHY);
        if (down) {
            down = false;
            log.warn("L2(Redis) 已恢复，回到两级模式");
        }
    }

    /**
     * L2 操作失败 / 探测失败：重设窗口（**从失败那一刻起算**，不是从探测开始起算 ——
     * 否则探测耗时会侵吞窗口，探测一旦比窗口慢就会立刻二次探测，「限频」形同虚设）。
     * 只在「健康 → 故障」时打一行。
     */
    public void markFailed(Throwable cause) {
        probeAllowedAtNanos.set(System.nanoTime() + cooldown.toNanos());
        if (!down) {
            down = true;
            log.warn("L2(Redis) 不可达，全部两级缓存降级为「仅 L1 + 直接回落 DB」；"
                    + "故障期间不再逐次记录，每 {}s 才由单个请求探测一次。原因：{}",
                    cooldown.toSeconds(), cause.toString());
        }
    }

    /**
     * 探测通道是非 bean，生命周期由本类负责（与主工厂一样随应用关闭释放）。
     * RedisConnectionFactory 接口本身没有 destroy()（它只继承 PersistenceExceptionTranslator），
     * 故这里按 DisposableBean 判断 —— 真实对象是 LettuceConnectionFactory，它实现了该接口。
     * 关闭失败不在这里处理：DisposableBean.destroy() 本就 throws Exception，
     * 让容器去记（关闭期出问题不该由本类吞掉，那会让「进程退不干净」静默）。
     */
    @Override
    public void destroy() throws Exception {
        if (probeConnectionFactory instanceof DisposableBean disposable) {
            disposable.destroy();
        }
    }

    /**
     * 执行一次探测。任何异常都当作「还没恢复」：重设窗口，而不是清零 ——
     * 否则「探测 → 失败 → 下一请求立刻再探」会形成循环。
     * 这里吞 Exception 而不是只吞 DataAccessException：探测只回答「能不能通」这一个问题，
     * 失败原因全部由 markFailed 记一次，不区分类型（与 TwoLevelCache 的边界不同，
     * 那里是业务读路径，不能被降级掩盖编程错误）。
     */
    private void probeNow() {
        try (RedisConnection connection = probeConnectionFactory.getConnection()) {
            connection.ping();
            markOk();
        } catch (Exception e) {
            markFailed(e);
        }
    }
}
