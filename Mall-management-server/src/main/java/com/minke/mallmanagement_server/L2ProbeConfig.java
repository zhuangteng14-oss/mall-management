package com.minke.mallmanagement_server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L2 探活相关的装配。
 *
 * 【为什么单独一个类，而不是塞进 CacheConfig】
 * 与 ImgConfig / ImgPath 一致：一个关注点一个配置类。CacheConfig 的职责是「把 Boot 的
 * 单级 CacheManager 覆盖成两级」；L2 健康状态与探活是**另一件事** —— 它不参与
 * CacheManager 的构造语义，只是被 TwoLevelCache 用来做降级判定。
 * 混在一起会让「动探活」必须去改「CacheManager 装配」这个高危文件。
 *
 * 【为什么 L2HealthProbe 是 bean 而不是 CacheConfig 里 new 一个】
 * 它必须跨所有 TwoLevelCache 实例共享（TwoLevelCacheManager 按缓存名建 N 个
 * TwoLevelCache，但拿到的必须是同一个 probe —— 见 L2HealthProbe 类注释）。
 * bean 化让「全应用恰好一份」由容器保证，而不是靠调用方自觉。
 */
@Configuration
public class L2ProbeConfig {

    @Bean
    public L2HealthProbe l2HealthProbe() {
        return new L2HealthProbe();
    }
}
