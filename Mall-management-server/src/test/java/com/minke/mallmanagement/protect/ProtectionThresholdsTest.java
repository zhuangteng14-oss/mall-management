package com.minke.mallmanagement.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D 段三处闸的**阈值**逐条钉住 —— 裁定点名的数字（200 QPS / 50% / 30s 窗口 / 半开 30s 后放 5 个 /
 * 必须设最小请求数）落到配置里之后，是不是**就是**那几个数。
 *
 * <h2>【为什么要为「配置里写了什么」专门写一条测试】</h2>
 * 阈值这种东西**改错了不会报错**：200 改成 20 依然能跑、依然能拦、依然绿；50 改成 5 也一样。
 * 而它偏偏是演示与答辩时会被人**当场读出来**的东西 —— 「你这熔断的错误率阈值是多少」这一问，
 * 答案必须能从代码里**查**到，而不是从某次讲解的记忆里。本文件把它变成红灯。
 *
 * <h2>【为什么读 properties 原文件，而不是 {@code @Value} 注入】</h2>
 * 注入读的是**当前 profile 生效后的值**（还有 {@code application-smoke.properties} 等测试覆盖层），
 * 那验的是「这个上下文里是什么」。本文件要验的是「**仓里登记的就是这几个数**」——
 * 直接读 {@code application.properties} 这一个文件，不受 profile 影响，也不起 Spring。
 *
 * <p>⚠️ 与 {@code ProtectionGateTest#wiringIsThreePlusOnePlusOne} 的关系：那条钉「哪几枚开关真有闸」，
 * 本条钉「真闸的阈值是几」。两条合起来才是本笔对外的完整口径。
 */
class ProtectionThresholdsTest {

    // ================= 限流：200 QPS =================

    @Test
    @DisplayName("入站限流：200 / 1s（= 200 QPS），且**等待 0** —— 「不排队」只由这个 0 保证")
    void inboundRateLimitIsTwoHundredPerSecondAndNeverQueues() {
        assertThat(ProtectConfig.INBOUND_LIMITER).as("日志与实例名里出现的就是它").isEqualTo("inboundGate");
        assertThat(ProtectConfig.LIMIT_FOR_PERIOD).as("批文点名 200").isEqualTo(200);
        assertThat(ProtectConfig.REFRESH_PERIOD).isEqualTo(Duration.ofSeconds(1));
        assertThat(ProtectConfig.LIMIT_FOR_PERIOD / ProtectConfig.REFRESH_PERIOD.toSeconds())
                .as("每周期条数 ÷ 周期秒数 = QPS").isEqualTo(200);
        assertThat(ProtectConfig.TIMEOUT)
                .as("⚠️ 大于 0 就意味着 Tomcat 工作线程会睡在闸上 —— 那正是限流要防的事")
                .isEqualTo(Duration.ZERO);
    }

    // ================= 熔断：三个数 + 两个前提 =================

    @Test
    @DisplayName("熔断：错误率 50% / 30s 窗口 / 半开 30s 后放 5 个 / 最小请求数 20（不为 0）")
    void circuitBreakerThresholds() {
        Properties props = readApplicationProperties();

        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.failure-rate-threshold"))
                .as("批文点名 50%").isEqualTo("50");
        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.sliding-window-type"))
                .isEqualTo("TIME_BASED");
        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.sliding-window-size"))
                .as("TIME_BASED 下这一项的单位是**秒** ⇒ 30 = 批文点名的「30s 窗口」").isEqualTo("30");
        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.permitted-number-of-calls-in-half-open-state"))
                .as("批文点名「半开 30s 后放 5 个」").isEqualTo("5");
        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state"))
                .isEqualTo("30s");

        // ⚠️ 裁定逐字：「必须设最小请求数」。断的是「不为 0 / 存在」，不是某个具体数 ——
        //    具体数取多少是实现取舍，而「有没有这一项」是裁定。
        String minimum = props.getProperty("resilience4j.circuitbreaker.configs.default.minimum-number-of-calls");
        assertThat(minimum).as("不设它 ⇒ 低流量时一次偶发超时就能把熔断打开（误熔断）").isNotNull();
        assertThat(Integer.parseInt(minimum)).as("0 等于没设").isPositive();

        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.automatic-transition-from-open-to-half-open-enabled"))
                .as("半开若只能靠人推，那条「半开恢复要有记录」就成了「没人推就永远不恢复」").isEqualTo("true");
    }

    @Test
    @DisplayName("熔断：慢调用也算失败（1s / 50%）—— 只看异常率的话「被打满、每条 3 秒」等于「一切正常」")
    void slowCallsCountAsFailures() {
        Properties props = readApplicationProperties();

        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.slow-call-duration-threshold"))
                .isEqualTo("1s");
        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.slow-call-rate-threshold"))
                .isEqualTo("50");
        assertThat(props.getProperty("resilience4j.circuitbreaker.configs.default.ignore-exceptions"))
                .as("入参形状错误不是下游故障 —— 别和「MySQL 挂了」记在同一格")
                .isEqualTo("java.lang.IllegalArgumentException");
    }

    // ================= 线程池分池：三个数都是有限的 =================

    @Test
    @DisplayName("观测池：core 8 / max 32 / queue 64 —— 三个数**全部有限**（「有界」就是这件事）")
    void observePoolIsBounded() {
        assertThat(ObservePoolConfig.CORE_POOL_SIZE).isPositive();
        assertThat(ObservePoolConfig.MAX_POOL_SIZE).isGreaterThanOrEqualTo(ObservePoolConfig.CORE_POOL_SIZE);
        assertThat(ObservePoolConfig.QUEUE_CAPACITY)
                .as("⚠️ 队列必须是**有限**的：无界队列会让「池满」永远不成立，挤出策略一辈子不触发 —— "
                        + "那时开关看着接了闸、其实没有一处会被拒").isPositive();
        assertThat(ObservePoolConfig.POOL_NAME).isEqualTo("observe-stream");
    }

    // ================= 工具 =================

    /** 读仓里那份 {@code application.properties}（不起 Spring、不受 profile 影响）。 */
    private static Properties readApplicationProperties() {
        Properties props = new Properties();
        try (InputStream in = ProtectionThresholdsTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertThat(in).as("application.properties 必须在 classpath 上（在的话读不到这事本身就该红）").isNotNull();
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读 application.properties 失败", e);
        }
        return props;
    }
}
