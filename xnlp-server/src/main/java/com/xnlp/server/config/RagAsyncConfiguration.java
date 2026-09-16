package com.xnlp.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded executor used to enforce request-level chat timeouts and cancellation. */
@Configuration
@Profile("!memory")
@EnableConfigurationProperties(RagChatProperties.class)
public class RagAsyncConfiguration {

    @Bean(name = "ragTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService ragTaskExecutor(RagChatProperties properties) {
        properties.validate();
        ThreadFactory threadFactory = namedThreadFactory();
        if (properties.getQueueCapacity() == 0) {
            return new ThreadPoolExecutor(
                    properties.getCorePoolSize(), properties.getMaxPoolSize(), 60, TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(), threadFactory,
                    new ThreadPoolExecutor.AbortPolicy());
        }
        return new ThreadPoolExecutor(
                properties.getCorePoolSize(), properties.getMaxPoolSize(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "xnlp-rag-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
