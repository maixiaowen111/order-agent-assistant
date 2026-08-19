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