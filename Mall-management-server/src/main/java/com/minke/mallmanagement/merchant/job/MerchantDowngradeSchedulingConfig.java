package com.minke.mallmanagement.merchant.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 调度器的**载体配置**（C3 块 2 用户块）。
 *
 * <h2>【为什么单独一个类，而不是把 {@code @EnableScheduling} 挂到启动类上】</h2>
 * {@code @EnableScheduling} 只能挂在 {@code @Configuration} 上；而启动类
 * {@code com.minke.MallManagementApplication} 一旦被挂上，就**没有任何条件能关掉调度器**——
 * 本块需要「开发/CI 完整上下文开、a3 与 smoke 关」这条开关（理由见
 * {@link MerchantDowngradeJob} 类注释）。把注解挪进这个带 {@code @ConditionalOnProperty}
 * 的类里，开关就生效了。
 *
 * <p>⚠️ 这个类**刻意不实现 {@code WebMvcConfigurer}**、也不是 {@code @Controller} 相关类型
 * ⇒ {@code @WebMvcTest} 切片不会扫到它（切片的类型过滤器只收 web 相关组件），
 * 故 smoke 切片里既没有调度器、也不需要 {@code NotificationService} 之外的任何额外 bean。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mall.downgrade.job.enabled", havingValue = "true", matchIfMissing = true)
public class MerchantDowngradeSchedulingConfig {
}
