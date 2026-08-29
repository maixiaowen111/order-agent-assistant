package com.example.order.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson（分布式锁）客户端。
 * 注意：地址不能写死——本地开发是 localhost，进 docker 容器要用服务名 redis。
 * 所以走 @Value，默认值保留本地开发配置，容器里用环境变量 REDISSON_ADDRESS 覆盖。
 *
 * 当前状态：库存并发已改为 MySQL 原子 UPDATE + 影响行数闸门（ProductMapper.deductStock /
 * restoreStock），本客户端暂无注入方，保留作为后续「缓存击穿互斥重建」等场景的锁实现。
 */
@Configuration
public class RedissonConfig {

    @Value("${redisson.address:redis://localhost:6379}")
    private String address;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address);
        return Redisson.create(config);
    }
}