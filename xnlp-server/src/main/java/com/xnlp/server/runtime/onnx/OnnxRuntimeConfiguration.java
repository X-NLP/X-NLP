package com.xnlp.server.runtime.onnx;

import com.xnlp.core.runtime.NlpRuntime;
import com.xnlp.core.runtime.NlpRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OnnxRuntimeProperties.class, NlpRuntimeRoutingProperties.class})
public class OnnxRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OnnxRuntimeConfiguration.class);

    @Bean(name = "onnxNlpRuntime", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "xnlp.nlp.onnx", name = "enabled", havingValue = "true")
    public NlpRuntime onnxNlpRuntime(OnnxRuntimeProperties properties) {
        return new OnnxNlpRuntime(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "xnlp.nlp.onnx", name = "enabled", havingValue = "true")
    public ApplicationRunner onnxRuntimeLoader(
            @Qualifier("onnxNlpRuntime") NlpRuntime runtime) {
        return arguments -> {
            try {
                runtime.load();
            } catch (NlpRuntimeException e) {
                log.warn("NLP runtime {} did not start: {}",
                        runtime.descriptor().name(), e.getErrorCode().code());
            }
        };
    }

    @Bean(name = "onnxNlpRuntimeHealthIndicator")
    @ConditionalOnProperty(prefix = "xnlp.nlp.onnx", name = "enabled", havingValue = "true")
    public HealthIndicator onnxNlpRuntimeHealthIndicator(
            @Qualifier("onnxNlpRuntime") NlpRuntime runtime) {
        return () -> {
            var status = runtime.status();
            Health.Builder builder = switch (status.state()) {
                case READY -> Health.up();
                case FAILED -> Health.down();
                case CLOSED -> Health.outOfService();
                case NEW, LOADING -> Health.unknown();
            };
            return builder
                    .withDetail("state", status.state().name())
                    .withDetail("runtime", runtime.descriptor().name())
                    .withDetail("provider", runtime.descriptor().provider())
                    .withDetail("modelVersion", runtime.descriptor().modelVersion())
                    .withDetail("message", status.message())
                    .build();
        };
    }
}
