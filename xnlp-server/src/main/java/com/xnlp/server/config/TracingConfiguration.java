package com.xnlp.server.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer Tracing configuration for X-NLP Server.
 *
 * <p>Enables the {@code @Observed} annotation on service methods so that
 * {@code predict}, {@code benchmark}, and other operations create child spans
 * automatically.  The {@link ObservationRegistry} is auto-configured by
 * Spring Boot; this class only exposes the AOP aspect and registers
 * custom observation conventions.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingConfiguration.class);

    /**
     * Enable {@code @Observed} interception on any Spring-managed bean.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        log.info("Micrometer Tracing ObservationRegistry active: {}", observationRegistry);
        return new ObservedAspect(observationRegistry);
    }
}
