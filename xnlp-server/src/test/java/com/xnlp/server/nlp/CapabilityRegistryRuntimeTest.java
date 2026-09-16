package com.xnlp.server.nlp;

import com.xnlp.core.api.ComponentResult;
import com.xnlp.core.api.NlpContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryRuntimeTest {

    @Test
    void executeWithRuntime_retainsAwareComponentDiagnostics() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new RuntimeAwareNlpComponent() {
            @Override public String id() { return "CUSTOM"; }
            @Override public String displayName() { return "Custom"; }
            @Override public String description() { return "Custom runtime"; }
            @Override public Map<String, String> parameterSchema() { return Map.of(); }
            @Override public NlpComponentExecution executeWithRuntime(NlpContext context) {
                return new NlpComponentExecution(
                        ComponentResult.of("CUSTOM", "value", context.getText()),
                        Map.of("mode", "onnx"));
            }
        });

        NlpComponentExecution execution = registry.executeWithRuntime(
                "custom", NlpContext.builder().text("result").build());

        assertThat(execution.result().get("value")).isEqualTo("result");
        assertThat(execution.runtime()).containsEntry("mode", "onnx");
    }

    @Test
    void executeWithRuntime_addsBuiltinMetadataForLegacyComponents() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new com.xnlp.core.api.NlpComponent() {
            @Override public String id() { return "LEGACY"; }
            @Override public String displayName() { return "Legacy"; }
            @Override public String description() { return "Legacy component"; }
            @Override public Map<String, String> parameterSchema() { return Map.of(); }
            @Override public ComponentResult execute(NlpContext context) {
                return ComponentResult.of("LEGACY", "ok", true);
            }
        });

        NlpComponentExecution execution = registry.executeWithRuntime(
                "LEGACY", NlpContext.builder().build());

        assertThat(execution.runtime()).containsEntry("mode", "builtin-demo")
                .containsEntry("runtime", "builtin-components");
    }
}
