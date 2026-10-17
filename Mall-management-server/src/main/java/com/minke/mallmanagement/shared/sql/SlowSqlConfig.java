package com.minke.mallmanagement.shared.sql;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 慢 SQL 的装配点（C3 块 7 · commit 6b · 批文 Q4 的「丁」）：
 * 把容器里那个 {@code DataSource} 包一层 datasource-proxy，挂上 {@link SlowSqlListener}。
 *
 * <h2>【为什么用 {@code BeanPostProcessor} 包，而不是自己 {@code @Bean DataSource}】</h2>
 * 后者（{@code DataSourceProperties.initializeDataSourceBuilder()…}）是网上教程的标准写法，但它在这里
 * 会**悄悄丢掉一件事**：Boot 的 {@code DataSourceConfiguration.Hikari} 会把 {@code spring.datasource.hikari.*}
 * 绑到那个 Hikari 实例上，而手工 new 一个 DataSource 就绕过了这层绑定。今日本仓没写任何
 * {@code spring.datasource.hikari.*}，所以<b>现在</b>两者等价 —— 而「现在等价」正是最危险的说辞：
 * 将来有人加了 {@code spring.datasource.hikari.maximum-pool-size=20}，那一刻它会**不报错也不生效**。
 * 包一层则完全不碰 Boot 的装配，那个绑定照旧落在真实的 Hikari 上。
 *
 * <p>代价（如实记）：包装发生在**初始化之后**，所以任何在容器里缓存了 DataSource 原始引用的 bean
 * 都拿不到代理。本仓没有这样的 bean（已核：源码里唯一的 {@code DataSource} 出现处全是注释），
 * 但这是一条依赖「现状」的前提，不是依赖语言的保证。
 *
 * <h2>【为什么要 {@code static} @Bean】</h2>
 * {@code BeanPostProcessor} 必须比它要处理的 bean **更早**注册，而普通 {@code @Bean} 方法需要先实例化
 * 配置类本身 —— 那会让本类提前构造并引发 Boot 的「not eligible for getting processed by all
 * BeanPostProcessors」告警。{@code static} 方法不依赖实例，正是 Spring 给出的解法。
 *
 * <h2>【逃生阀：{@code app.sql.slow-ms} ≤ 0】</h2>
 * 置 0 或负数 ⇒ **完全不包装**，DataSource 保持 Boot 原样。这不是「阈值设成 0 所以每次都报」，
 * 而是「这条链路整个不装」—— 两者的区别在压测上是「一层动态代理」的差别。<b>a3（CI 门禁专用）
 * 就是这么关的</b>：A3 跑的是高并发真 SQL，代理的每调用开销会落在 QPS 门禁数字上，而慢 SQL 是
 * 运维观测、不是验收项。默认 profile 与 dev 保持开启（200 ms）。
 */
@Configuration
public class SlowSqlConfig {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlConfig.class);

    /** 阈值键。⚠️ 默认值 200 ms 与 {@code application.properties} 末尾登记的值**必须一致**（那里写了就是给「不知道去哪改」的人看的）。 */
    private static final String SLOW_MS_KEY = "${app.sql.slow-ms:200}";

    @Bean
    static BeanPostProcessor slowSqlDataSourceWrapper(@Value(SLOW_MS_KEY) long slowMs) {
        if (slowMs <= 0) {
            log.info("慢 SQL 告警已装配（SLOW-SQL）：已关闭（app.sql.slow-ms={}）—— 数据源不做包装，连接池保持 Boot 原样", slowMs);
            return new BeanPostProcessor() {
                // 关闭态：什么都不做（刻意**不留**注解式开关，那样仍会创建 BPP，只是不做事的差别看不出来）
            };
        }
        log.info("慢 SQL 告警已装配（SLOW-SQL）：阈值 {} ms —— 超阈值的语句按 WARN 打印（含 traceId，因回调就在请求线程上）", slowMs);
        return new SlowSqlDataSourceWrapper(slowMs);
    }

    /** 只包一层，且只包一次。独立成类（而不是匿名）是为了错误栈里能看见它叫什么。 */
    static final class SlowSqlDataSourceWrapper implements BeanPostProcessor {

        private final long slowMs;

        SlowSqlDataSourceWrapper(long slowMs) {
            this.slowMs = slowMs;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            // `!(bean instanceof ProxyDataSource)` 是幂等闸：万一将来有第二个 BPP 也做了同样的事，
            // 不会出现「代理套代理」（那会让每条语句被计时两次、告警两遍）。
            if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
                return ProxyDataSourceBuilder.create(dataSource)
                        .name("mall")   // 出现在 ExecutionInfo.getDataSourceName() ⇒ 告警行里能看出是哪个源
                        .listener(new SlowSqlListener(slowMs))
                        .build();
            }
            return bean;
        }
    }
}
