package com.xnlp.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded executor for document chunking and embedding jobs. */
@Configuration
@EnableConfigurationProperties(KnowledgeIngestionProperties.class)
public class IngestionAsyncConfiguration {

    @Bean(name = "ingestionTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor ingestionTaskExecutor(KnowledgeIngestionProperties properties) {
        if (properties.getMaxPoolSize() < properties.getCorePoolSize()) {
            throw new IllegalArgumentException("ingestion maxPoolSize must be at least corePoolSize");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("xnlp-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
