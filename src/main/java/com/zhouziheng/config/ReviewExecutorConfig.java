package com.zhouziheng.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 评审专用线程池。
 * <p>
 * 和 Tomcat 的请求线程隔离：模型调用再慢也不会把 Web 线程占满，
 * 导致整个服务（包括健康检查）一起卡住。
 * <p>
 * 并发固定 2 是刻意的——评审是花钱的外部调用，排队比并发更划算，
 * 也能避开服务端的限流和"并发一高就超时"的坑。
 */
@Configuration
public class ReviewExecutorConfig {

    @Bean
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("review-");
        executor.initialize();
        return executor;
    }
}
