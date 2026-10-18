package com.minke.mallmanagement.observe.config;

import com.minke.mallmanagement.observe.dao.ObservedEventDao;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 2026-10-19〈应用层性能隔离〉：把**业务主池**与**MQ 消费者台账池**拆成两个 Hikari 实例。
 *
 * <h2>【为什么要拆】</h2>
 * 500 并发诊断（{@code _work/diag-500}）的实测：业务池 10/10 占满、{@code pending} 峰值 160，
 * jstack 里 91–122 条 Tomcat 线程停在 {@code HikariPool.getConnection}；
 * 而 {@code ObservedEventConsumer} 的批量 INSERT 与业务请求**抢的是同一个池**
 * —— 台账是后台日志，不该在演练时把业务请求挡在池外。
 *
 * <h2>【两个池各自多大】</h2>
 * <ul>
 *   <li>{@code business-pool}：沿用 Boot 缺省的 10（{@code spring.datasource.hikari.maximum-pool-size=10}）。</li>
 *   <li>{@code observe-consumer-pool}：**4**（{@code mall.observe.consumer.hikari.maximum-pool-size=4}），
 *       与监听器并发 4 对齐 —— 4 条消费者线程最多同时占 4 条连接，池里不会排长队。</li>
 * </ul>
 *
 * <h2>【为什么把 {@code JdbcTemplate} 也自己声明】</h2>
 * {@code JdbcTemplateAutoConfiguration} 的创建条件是「容器里没有 {@code JdbcOperations} 且
 * {@code DataSource} 只有一个候选」。本类一旦声明了消费者的 {@code JdbcTemplate}，Boot 会
 * **整体退让**（它看到 `JdbcOperations` 已存在）⇒ 若不显式声明业务那个，全仓注入会落到
 * 唯一存在的消费者模板上，正好把隔离反着做。故两个都显式声明，业务那个标 {@code @Primary}。
 *
 * <h2>【两个 DAO 实例】</h2>
 * {@link ObservedEventDao} 的 SQL 只有一份，但实例有两份：业务读（回放）走主池，
 * 消费者写走小池。消费者用 {@code @Qualifier("observeConsumerObservedEventDao")} 拿后者；
 * 其余按类型注入的调用方（{@code ReplayService}）拿到 {@code @Primary} 的那份。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class ObserveDataSourceConfig {

    /** 业务主池：URL / 账号 / 密码沿用 {@code spring.datasource.*}。 */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        ds.setPoolName("business-pool");
        return ds;
    }

    /** 业务主模板（{@code @Primary}：全仓既有 DAO 按类型注入拿到的都是它）。 */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 消费者台账池：**同一个库、同一套凭据**，只是池独立。
     * 显式先给 4 条再让 {@code mall.observe.consumer.hikari.*} 覆盖 —— 漏配时行为仍确定。
     */
    @Bean
    @ConfigurationProperties("mall.observe.consumer.hikari")
    public HikariDataSource observeConsumerDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        ds.setPoolName("observe-consumer-pool");
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(1);
        return ds;
    }

    /** 消费者专用模板（非 {@code @Primary}：只有显式 {@code @Qualifier} 的调用方拿得到）。 */
    @Bean
    public JdbcTemplate observeConsumerJdbcTemplate(
            @Qualifier("observeConsumerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 业务读路径（回放）使用的 DAO —— 走主池。 */
    @Bean
    @Primary
    public ObservedEventDao observedEventDao(
            @Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new ObservedEventDao(jdbcTemplate);
    }

    /** MQ 消费者使用的 DAO —— 走独立小池。 */
    @Bean
    public ObservedEventDao observeConsumerObservedEventDao(
            @Qualifier("observeConsumerJdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new ObservedEventDao(jdbcTemplate);
    }
}
