package com.xnlp.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Virtual-thread executor keeps node timeouts isolated without unbounded platform threads. */
@Configuration
public class PipelineAsyncConfiguration {
    @Bean(name = "pipelineTaskExecutor", destroyMethod = "close")
    ExecutorService pipelineTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
