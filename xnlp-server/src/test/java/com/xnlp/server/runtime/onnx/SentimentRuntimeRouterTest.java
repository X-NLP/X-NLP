package com.xnlp.server.runtime.onnx;

import com.xnlp.core.api.ComponentResult;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.runtime.NlpRuntime;
import com.xnlp.core.runtime.NlpRuntimeDescriptor;
import com.xnlp.core.runtime.NlpRuntimeErrorCode;
import com.xnlp.core.runtime.NlpRuntimeException;
import com.xnlp.core.runtime.NlpRuntimeResult;
import com.xnlp.core.runtime.NlpRuntimeState;
import com.xnlp.core.runtime.NlpRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentimentRuntimeRouterTest {

    @Test
    void builtinMode_preservesExistingLexiconBehavior() {
        NlpRuntimeRoutingProperties properties = mode(SentimentRuntimeMode.BUILTIN);
        SentimentRuntimeRouter router = new SentimentRuntimeRouter(List.of(new FakeRuntime()), properties);

        var execution = router.execute(NlpContext.builder().text("这个结果很好，我很满意").build());

        assertThat(execution.result().getData()).containsEntry("label", "positive")
                .containsEntry("score", 0.75);
        assertThat(execution.runtime()).containsEntry("mode", "builtin-demo")
                .doesNotContainKey("fallbackFrom");
    }

    @Test
    void autoMode_readyRuntime_delegatesToOnnx() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.state = NlpRuntimeState.READY;
        SentimentRuntimeRouter router = new SentimentRuntimeRouter(
                List.of(runtime), mode(SentimentRuntimeMode.AUTO));

        var execution = router.execute(NlpContext.builder().text("text").build());

        assertThat(runtime.executions).isEqualTo(1);
        assertThat(execution.result().getData()).containsEntry("label", "negative");
        assertThat(execution.runtime()).containsEntry("mode", "onnx");
    }

    @Test
    void autoMode_unavailableRuntime_fallsBackWithStableReason() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.state = NlpRuntimeState.FAILED;
        SentimentRuntimeRouter router = new SentimentRuntimeRouter(
                List.of(runtime), mode(SentimentRuntimeMode.AUTO));

        var execution = router.execute(NlpContext.builder().text("good").build());

        assertThat(execution.result().getData()).containsEntry("label", "positive");
        assertThat(execution.runtime()).containsEntry("mode", "builtin-demo")
                .containsEntry("fallbackFrom", "onnx")
                .containsEntry("fallbackReason", "nlp_runtime_not_ready");
    }

    @Test
    void onnxMode_missingRuntime_failsClearlyInsteadOfFallingBack() {
        SentimentRuntimeRouter router = new SentimentRuntimeRouter(
                List.of(), mode(SentimentRuntimeMode.ONNX));

        assertThatThrownBy(() -> router.execute(NlpContext.builder().text("text").build()))
                .isInstanceOf(com.xnlp.core.runtime.NlpRuntimeException.class)
                .hasMessage("ONNX sentiment runtime is not configured");
    }

    @Test
    void onnxMode_executionFailure_doesNotFallBackToBuiltin() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.state = NlpRuntimeState.READY;
        runtime.failure = new NlpRuntimeException(
                NlpRuntimeErrorCode.EXECUTION_FAILED, "NLP runtime execution failed");
        SentimentRuntimeRouter router = new SentimentRuntimeRouter(
                List.of(runtime), mode(SentimentRuntimeMode.ONNX));

        assertThatThrownBy(() -> router.execute(NlpContext.builder().text("good").build()))
                .isSameAs(runtime.failure);
        assertThat(runtime.executions).isEqualTo(1);
    }

    @Test
    void autoMode_newRuntime_loadsLazilyThenExecutes() {
        FakeRuntime runtime = new FakeRuntime();
        SentimentRuntimeRouter router = new SentimentRuntimeRouter(
                List.of(runtime), mode(SentimentRuntimeMode.AUTO));

        var execution = router.execute(NlpContext.builder().text("text").build());

        assertThat(runtime.loads).isEqualTo(1);
        assertThat(runtime.executions).isEqualTo(1);
        assertThat(execution.runtime()).containsEntry("mode", "onnx");
    }

    private static NlpRuntimeRoutingProperties mode(SentimentRuntimeMode mode) {
        NlpRuntimeRoutingProperties properties = new NlpRuntimeRoutingProperties();
        properties.setSentimentMode(mode);
        return properties;
    }

    private static final class FakeRuntime implements NlpRuntime {
        private final NlpRuntimeDescriptor descriptor = new NlpRuntimeDescriptor(
                "fake-onnx", "onnxruntime", "v1", "a".repeat(64), Set.of("SENTIMENT"));
        private NlpRuntimeState state = NlpRuntimeState.NEW;
        private int loads;
        private int executions;
        private NlpRuntimeException failure;

        @Override
        public NlpRuntimeDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public NlpRuntimeStatus status() {
            return NlpRuntimeStatus.of(state, state.name());
        }

        @Override
        public void load() {
            loads++;
            state = NlpRuntimeState.READY;
        }

        @Override
        public NlpRuntimeResult execute(String capability, NlpContext context) {
            executions++;
            if (failure != null) {
                throw failure;
            }
            return new NlpRuntimeResult(
                    ComponentResult.of("SENTIMENT", Map.of("label", "negative", "score", 0.9)),
                    Map.of("mode", "onnx", "runtime", descriptor.name()));
        }

        @Override
        public void close() {
            state = NlpRuntimeState.CLOSED;
        }
    }
}
