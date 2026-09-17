package com.videoagent.agent.memory;

import com.videoagent.agent.config.ConversationCompactionExecutorProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class ConversationCompactionConfiguration {

    @Bean(name = "conversationMemoryCompactionExecutor")
    public ThreadPoolTaskExecutor conversationMemoryCompactionExecutor(
        ConversationCompactionExecutorProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.coreSize());
        executor.setMaxPoolSize(properties.maxSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setKeepAliveSeconds(Math.toIntExact(properties.keepAlive().toSeconds()));
        executor.setThreadNamePrefix("conversation-compact-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
