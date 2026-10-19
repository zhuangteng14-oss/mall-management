package com.minke.mallmanagement.shared.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import com.minke.mallmanagement.observe.service.AttackSwitchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;
import com.minke.mallmanagement.shared.cache.MallCacheMetrics;

/**
 * L2 探活相关的装配：探测专用通道 + 健康状态 bean + 启动期校验。
 *
 * 【为什么单独一个类，而不是塞进 CacheConfig】
 * 与 ImgConfig / ImgPath 一致：一个关注点一个配置类。CacheConfig 的职责是「把 Boot 的
 * 单级 CacheManager 覆盖成两级」；L2 健康状态与探活是**另一件事** —— 它不参与
 * CacheManager 的构造语义，只是被 TwoLevelCache 用来做降级判定。
 * 混在一起会让「动探活」必须去改「CacheManager 装配」这个高危文件。
 *
 * 【探测通道为什么不是 bean —— 这是本类最要紧的一条】
 * ⚠️ 绝不能把探测通道声明成 @Bean，**哪怕返回类型写成 LettuceConnectionFactory**：
 * Boot 的主工厂（LettuceConnectionConfiguration#redisConnectionFactory）带
 * `@ConditionalOnMissingBean(RedisConnectionFactory.class)`，而用户 @Configuration 的
 * bean 定义先于自动装配注册 —— 于是这个「200ms 超时、无连接池」的探测通道会把主工厂
 * 顶掉，CacheConfig.cacheManager(RedisConnectionFactory) 拿到的是探测通道，
 * 整个 L2 静默跑在探测通道上（a3 的连接池配置、spring.data.redis.timeout=3s 一并失效）。
 * 这个后果在 A3 上是**看不出来的**（缓存照样能读能写），所以只能靠结构上不给它
 * 注册进容器的机会。探测通道因此是普通对象，生命周期由 L2HealthProbe 负责。
 *
 * 【为什么探测超时这么短】见 L2HealthProbe 类注释第 2 条：主通道冷读在故障端点上实测
 * 3.38s（accept 后不回字节）/ 3.40s（SYN 被丢弃，192.0.2.1 TEST-NET）—— 探测超时必须远短于此，
 * 否则「每 cooldown 秒挑一个请求慢 3 秒」。而探测本身实测稳态 233ms、进程内首次 0.5~0.8s：
 * **200ms 是超时上限，不是实测耗时**。
 *
 * 【启动期两道校验 + 一次自检】都只 WARN，不阻断启动 —— Redis 不可用本来就是被允许的
 * 降级场景（cache-layer.md §9 有专门的降级断言），配置疑点不该让应用起不来。
 */
@Configuration
public class L2ProbeConfig {

    private static final Logger log = LoggerFactory.getLogger(L2ProbeConfig.class);

    /**
     * 探测专用通道 + 健康状态。
     *
     * 参数里的 redisConnectionFactory 是**主工厂**，只用于一致性校验（下面第 2 项），
     * 不参与探测通道的构造 —— 探测通道从 RedisProperties 派生（同源，不重抄字面量）。
     */
    @Bean
    public L2HealthProbe l2HealthProbe(RedisConnectionFactory redisConnectionFactory,
                                       RedisProperties redisProperties,
                                       MallCacheMetrics cacheMetrics,
                                       ObjectProvider<AttackSwitchConfig> switchConfigProvider,
                                       @Value("${app.cache.l2-probe.timeout:200ms}") Duration probeTimeout,
                                       @Value("${app.cache.l2-probe.cooldown:30s}") Duration cooldown) {
        LettuceConnectionFactory probeChannel = createProbeChannel(redisProperties, probeTimeout);

        warnIfInflightNotLongerThanProbeTimeout(probeTimeout);
        warnIfProbeTargetDiffers(redisConnectionFactory, probeChannel);

        // 把生效值打进启动日志（同 CacheConfig 的「两级缓存已装配」行）：属性名拼错时
        // @Value 会静默退化成默认值，而默认值恰好与 properties 同值 —— 有这一行，
        // 「属性被读到」就是启动即可取证的事实。ASCII 片段 "L2Probe" 供 GBK 日志下检索。
        log.info("L2 探活通道已装配（L2Probe）：timeout={} cooldown={} inflight={} -> {}:{}/db{}",
                probeTimeout, cooldown, L2HealthProbe.PROBE_INFLIGHT,
                probeChannel.getHostName(), probeChannel.getPort(), probeChannel.getDatabase());

        selfCheckProbeChannel(probeChannel);
        // D3：redisDown 注入立即生效（理由见 L2HealthProbe#injectedDown 的 javadoc）。
        // 与 SlowSqlConfig 同一条处置：ObjectProvider 每次现取，避免在 @Bean 构造期把配置类提前拉起。
        java.util.function.BooleanSupplier injectedDown = () -> {
            AttackSwitchConfig cfg = switchConfigProvider.getIfAvailable();
            return cfg != null && cfg.injections().contains("redisDown");
        };
        L2HealthProbe probe = new L2HealthProbe(probeChannel, cooldown, cacheMetrics, injectedDown);
        // L2 健康状态出口：gauge mall.cache.l2.up（1=可用 0=处于冷却窗口内）。
        // 传 lambda 而不是 probe 本身：把 probe 交给 MallCacheMetrics 会构成 bean 循环引用
        // （probe ← metrics ← probe）。代价是取值对象没有别的强引用，必须由
        // MallCacheMetrics 自己存一份 —— 否则一次 GC 后 gauge 静默变 NaN（见 bindL2Up）。
        // isHealthy() 是纯读取：scrape 本指标**不会**触发探测（isL2UsableNow() 才会）。
        cacheMetrics.bindL2Up(probe::isHealthy);
        return probe;
    }

    /**
     * 建探测专用通道（普通对象，不注册进容器 —— 理由见类注释）。
     *
     * host/port/password/db 全部从 RedisProperties 派生：与主工厂同一个来源，
     * 结构上排除「改了配置只改一处」的漂移。不配连接池（shareNativeConnection 默认 true
     * ⇒ 共享一条物理连接）：探活天生是低频单飞，池只会多一层排队。
     */
    private static LettuceConnectionFactory createProbeChannel(RedisProperties redisProperties,
                                                               Duration probeTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                redisProperties.getHost(), redisProperties.getPort());
        standalone.setDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getUsername())) {
            standalone.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            standalone.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }

        // 三层超时都设成 probeTimeout —— 缺任一层，「探测在 200ms 超时内返回」就是空话
        // （200ms 是超时**上限**：实测稳态约 233ms、进程内首次 0.5~0.8s，见 L2HealthProbe 类注释第 2 条）：
        //   RedisURI.timeout（由下面的 commandTimeout 带入）—— Lettuce 的默认命令/建连超时
        //   TimeoutOptions.fixedTimeout —— 命令超时；
        //     connectionTimeout() 让建连阶段也受它约束
        //   SocketOptions.connectTimeout —— TCP 连接超时。黑洞端点上这条是决定性的：
        //     SYN 被丢弃期间根本没有命令可超时，只设命令超时救不了。
        ClientOptions clientOptions = ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.builder()
                        .timeoutCommands()
                        .fixedTimeout(probeTimeout)
                        .connectionTimeout()
                        .build())
                .socketOptions(SocketOptions.builder().connectTimeout(probeTimeout).build())
                .build();

        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(probeTimeout)
                .build();

        LettuceConnectionFactory channel = new LettuceConnectionFactory(standalone, clientConfiguration);
        // 非 bean ⇒ 容器不会替我们调 afterPropertiesSet()。必须手动调：它把状态推到
        // STARTED（earlyStartup 默认 true → start() 建 RedisClient）。漏掉它，
        // getConnection() 会抛 IllegalStateException("... Use start() to initialize it")，
        // 而探测把所有异常都当「还没恢复」—— 于是探测永远失败、窗口永远清不了零，
        // L2 被永久绕过且毫无报错。
        // 注意 start() 不会在启动期真连 Redis（eagerInitialization 默认 false）：
        // Redis 挂着时应用照样能启动。
        channel.afterPropertiesSet();
        return channel;
    }

    /**
     * 不变量「探测占位窗口 > 探测超时」的启动期校验。
     *
     * 违反的后果：探测还没回来占位窗口就到期了 → 第二个线程可以抢到探测权 → 并发探测，
     * 与 L2HealthProbe 里「同一时刻只有一个探测者」的前提相矛盾。
     * 与 CacheConfig 的 L1 ≤ L2 同类：这是正确性前提，不是风格偏好。
     */
    private static void warnIfInflightNotLongerThanProbeTimeout(Duration probeTimeout) {
        if (L2HealthProbe.PROBE_INFLIGHT.compareTo(probeTimeout) <= 0) {
            log.warn("探测占位窗口({}) 不大于探测超时({})：探测期间可能有第二个线程抢到探测权并重复探测。"
                            + "PROBE_INFLIGHT 是内部常量（L2HealthProbe），请改小 app.cache.l2-probe.timeout。",
                    L2HealthProbe.PROBE_INFLIGHT, probeTimeout);
        }
    }

    /**
     * 探测通道 vs 主工厂的一致性校验：host / port / db 三项。
     *
     * 两侧都从 RedisProperties 派生时本该恒等，这条是**防将来**：谁把主工厂换成自定义
     * factory / Sentinel / 集群，探测就会指向别处 —— 那时两侧不再同源，这里有话可说。
     * 只 WARN：目标不一致不必然导致错误行为（探测指向一个恰好可用的端点，后果是真实操作
     * 反复重新触发窗口 —— 降级但可用，且日志里可见），不足以让应用起不来。
     *
     * 不比口令：口令错由下面的自检用一次真实 PING 回答（比字符串更能说明问题），
     * 也就不必把明文口令拖进这条比较路径。主工厂非 Lettuce 时跳过并记录 ——
     * 本项目主通道是 Lettuce，出现别的实现本身就是需要人看一眼的信号。
     */
    private static void warnIfProbeTargetDiffers(RedisConnectionFactory main,
                                                 LettuceConnectionFactory probe) {
        if (!(main instanceof LettuceConnectionFactory mainLettuce)) {
            log.warn("主 Redis 连接工厂不是 LettuceConnectionFactory（{}），跳过探测通道一致性校验",
                    main.getClass().getName());
            return;
        }
        boolean same = Objects.equals(mainLettuce.getHostName(), probe.getHostName())
                && mainLettuce.getPort() == probe.getPort()
                && mainLettuce.getDatabase() == probe.getDatabase();
        if (!same) {
            log.warn("探测通道与主工厂目标不一致：主 {}:{}/db{} vs 探测 {}:{}/db{}；"
                            + "探测结果将不代表主通道。检查是否有自定义 RedisConnectionFactory。",
                    mainLettuce.getHostName(), mainLettuce.getPort(), mainLettuce.getDatabase(),
                    probe.getHostName(), probe.getPort(), probe.getDatabase());
        }
    }

    /**
     * 启动期自检：真打一次 PING，结果进日志，**不阻断**。
     *
     * 为什么不做成「连不上就不启动」：Redis 不可用是允许的降级场景（同下面两道校验）。
     * 它的真正作用是抓**口令漂移**这类最坏的静默失效 —— 若探测通道因口令错而永远连不上，
     * 冷却窗口会永远清不了零，L2 被永久绕过却毫无报错；有这一行，它在启动日志里就是
     * 可见的事实。
     *
     * 自检结果**不**写进健康状态：探测通道不通 ≠ 主通道不通（见 L2HealthProbe 类注释
     * 第 1 条「探测 ≠ 主通道健康」）。启动时没有任何证据说明主通道坏了，
     * 不该凭探测通道就预先降级 —— 让第一次真实 L2 操作去回答。
     */
    private static void selfCheckProbeChannel(LettuceConnectionFactory probeChannel) {
        try (RedisConnection connection = probeChannel.getConnection()) {
            connection.ping();
            log.info("L2 探活通道自检（L2Probe）：PONG");
        } catch (Exception e) {
            log.warn("L2 探活通道自检（L2Probe）失败：{} —— 不阻断启动，按降级运行；"
                            + "若主通道正常而本行持续出现，检查探测通道的端点/口令是否与主配置同源",
                    e.toString());
        }
    }
}
