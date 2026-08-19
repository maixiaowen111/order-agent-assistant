package com.example.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步 + 定时任务配置
 *
 * @EnableAsync       — 支持 @Async 注解（异步方法）
 * @EnableScheduling  — 支持 @Scheduled 注解（定时任务，EventRetryScheduler 扫描事件表用）
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 订单事件线程池
     *
     * 为什么自定义？Spring 默认线程池是 SimpleAsyncTaskExecutor，
     * 每来一个任务就 new 一个线程，高并发下直接 OOM
     */
    @Bean("orderEventExecutor")
    public Executor orderEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("order-event-");
        // 拒绝策略：队列满了 + 线程满了 → 交给调用线程执行（不丢任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
