package com.xnlp.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;


/**
 * Bounded executor for evaluation jobs. Evaluation requests should not occupy
 * servlet request threads while the model processes a whole dataset.
 */
@Configuration
@EnableAsync
public class EvaluationAsyncConfiguration {

    @Bean(name = "evaluationTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor evaluationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("xnlp-eval-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
