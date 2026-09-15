package com.xnlp.server.runtime.onnx;

import com.xnlp.core.api.NlpContext;
import com.xnlp.core.runtime.NlpRuntime;
import com.xnlp.core.runtime.NlpRuntimeErrorCode;
import com.xnlp.core.runtime.NlpRuntimeException;
import com.xnlp.core.runtime.NlpRuntimeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxNlpRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void observedRuntime_supportsClassBasedSpringProxy() throws Exception {
        Path model = writeModel("proxyable-model");
        OnnxNlpRuntime target = new OnnxNlpRuntime(properties(model), new FakeFactory(4, 0.5f));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);

        Object proxy = proxyFactory.getProxy();

        assertThat(proxy).isInstanceOf(NlpRuntime.class);
        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
        ((NlpRuntime) proxy).close();
    }

    @Test
    void loadAndExecute_validModel_returnsSentimentAndRuntimeMetadata() throws Exception {
        Path model = writeModel("valid-model");
        FakeFactory factory = new FakeFactory(8, 0.82f);
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model), factory);

        runtime.load();
        var result = runtime.execute("sentiment", NlpContext.builder().text("great work").build());

        assertThat(runtime.status().state()).isEqualTo(NlpRuntimeState.READY);
        assertThat(result.result().getData()).containsEntry("label", "positive")
                .containsEntry("score", (double) 0.82f);
        assertThat(result.metadata()).containsEntry("mode", "onnx")
                .containsEntry("runtime", "test-onnx")
                .containsEntry("provider", "onnxruntime")
                .containsEntry("modelVersion", "fixture-v1")
                .containsKey("elapsedMillis");
        assertThat(factory.lastInput).hasSize(8);

        runtime.close();
    }

    @Test
    void load_checksumMismatch_failsBeforeOpeningNativeSession() throws Exception {
        Path model = writeModel("checksum-model");
        OnnxRuntimeProperties properties = properties(model);
        properties.setModelSha256("0".repeat(64));
        FakeFactory factory = new FakeFactory(4, 0.5f);
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties, factory);

        assertThatThrownBy(runtime::load)
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.CHECKSUM_MISMATCH));

        assertThat(runtime.status().state()).isEqualTo(NlpRuntimeState.FAILED);
        assertThat(factory.openCount).hasValue(0);
        runtime.close();
    }

    @Test
    void load_missingAndOversizedModels_returnStableErrors() throws Exception {
        Path missing = tempDir.resolve("missing.onnx");
        OnnxNlpRuntime missingRuntime = new OnnxNlpRuntime(properties(missing),
                new FakeFactory(4, 0.5f));
        assertThatThrownBy(missingRuntime::load)
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.MODEL_NOT_FOUND));
        missingRuntime.close();

        Path oversized = writeModel("too-large");
        OnnxRuntimeProperties oversizedProperties = properties(oversized);
        oversizedProperties.setMaxModelBytes(2);
        OnnxNlpRuntime oversizedRuntime = new OnnxNlpRuntime(oversizedProperties,
                new FakeFactory(4, 0.5f));
        assertThatThrownBy(oversizedRuntime::load)
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.MODEL_INVALID));
        oversizedRuntime.close();
    }

    @Test
    void loadAndClose_areIdempotentAndReleaseSessionOnce() throws Exception {
        Path model = writeModel("idempotent-model");
        FakeFactory factory = new FakeFactory(4, 0.5f);
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model), factory);

        runtime.load();
        runtime.load();
        runtime.close();
        runtime.close();

        assertThat(factory.openCount).hasValue(1);
        assertThat(factory.closeCount).hasValue(1);
        assertThat(runtime.status().state()).isEqualTo(NlpRuntimeState.CLOSED);
    }

    @Test
    void execute_beforeLoadAndAfterClose_isRejected() throws Exception {
        Path model = writeModel("lifecycle-model");
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model),
                new FakeFactory(4, 0.5f));

        assertThatThrownBy(() -> runtime.execute("SENTIMENT", NlpContext.builder().text("x").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.NOT_READY));
        runtime.load();
        runtime.close();
        assertThatThrownBy(() -> runtime.execute("SENTIMENT", NlpContext.builder().text("x").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.CLOSED));
    }

    @Test
    void load_afterClose_preservesTerminalClosedState() throws Exception {
        Path model = writeModel("closed-load-model");
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model),
                new FakeFactory(4, 0.5f));
        runtime.close();

        assertThatThrownBy(runtime::load)
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.CLOSED));
        assertThat(runtime.status().state()).isEqualTo(NlpRuntimeState.CLOSED);
    }

    @Test
    void execute_unsupportedCapability_returnsStableError() throws Exception {
        Path model = writeModel("unsupported-capability-model");
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model),
                new FakeFactory(4, 0.5f));
        runtime.load();

        assertThatThrownBy(() -> runtime.execute("TOK", NlpContext.builder().text("x").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(NlpRuntimeErrorCode.UNSUPPORTED_CAPABILITY));
        runtime.close();
    }

    @Test
    void execute_invalidProbability_isSanitizedAsExecutionFailure() throws Exception {
        Path model = writeModel("invalid-probability-model");
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model),
                new FakeFactory(4, Float.NaN));
        runtime.load();

        assertThatThrownBy(() -> runtime.execute("SENTIMENT", NlpContext.builder().text("x").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.EXECUTION_FAILED);
                    assertThat(error.getMessage()).isEqualTo("NLP runtime execution failed");
                });
        runtime.close();
    }

    @Test
    void execute_zeroCapacityWhileBusy_returnsSaturated() throws Exception {
        Path model = writeModel("saturated-model");
        BlockingFactory factory = new BlockingFactory();
        OnnxRuntimeProperties properties = properties(model);
        properties.setTimeout(Duration.ofSeconds(2));
        properties.setQueueCapacity(0);
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties, factory);
        runtime.load();

        CompletableFuture<Void> active = CompletableFuture.runAsync(() ->
                runtime.execute("SENTIMENT", NlpContext.builder().text("first").build()));
        assertThat(factory.started.await(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> runtime.execute(
                "SENTIMENT", NlpContext.builder().text("second").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.SATURATED));

        factory.release.countDown();
        active.join();
        runtime.close();
    }

    @Test
    void execute_timeout_terminatesNativeCallAndCancelsFuture() throws Exception {
        Path model = writeModel("timeout-model");
        BlockingFactory factory = new BlockingFactory();
        OnnxRuntimeProperties properties = properties(model);
        properties.setTimeout(Duration.ofMillis(40));
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties, factory);
        runtime.load();

        assertThatThrownBy(() -> runtime.execute("SENTIMENT", NlpContext.builder().text("slow").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.EXECUTION_TIMEOUT));

        assertThat(factory.terminated.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(factory.terminateCount).hasValue(1);
        runtime.close();
        assertThat(factory.callClosed).isTrue();
    }

    @Test
    void execute_nativeFailure_doesNotExposeProviderMessageOrCause() throws Exception {
        Path model = writeModel("failure-model");
        FakeFactory factory = new FakeFactory(4, 0.5f);
        factory.failure = new IllegalStateException("/private/models/secret.onnx api-key=secret");
        OnnxNlpRuntime runtime = new OnnxNlpRuntime(properties(model), factory);
        runtime.load();

        assertThatThrownBy(() -> runtime.execute("SENTIMENT", NlpContext.builder().text("x").build()))
                .isInstanceOfSatisfying(NlpRuntimeException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(NlpRuntimeErrorCode.EXECUTION_FAILED);
                    assertThat(error.getMessage()).isEqualTo("NLP runtime execution failed");
                    assertThat(error.getMessage()).doesNotContain("secret", "/private");
                    assertThat(error.getCause()).isNull();
                });
        runtime.close();
    }

    private Path writeModel(String content) throws Exception {
        Path model = tempDir.resolve(content + ".onnx");
        Files.writeString(model, content);
        return model;
    }

    private static OnnxRuntimeProperties properties(Path model) throws Exception {
        OnnxRuntimeProperties properties = new OnnxRuntimeProperties();
        properties.setEnabled(true);
        properties.setRuntimeName("test-onnx");
        properties.setModelPath(model.toString());
        properties.setModelVersion("fixture-v1");
        properties.setModelSha256(Files.exists(model) ? sha256(model) : "a".repeat(64));
        properties.setTimeout(Duration.ofSeconds(1));
        properties.setMaxConcurrency(1);
        properties.setQueueCapacity(1);
        return properties;
    }

    private static String sha256(Path model) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(model)));
    }

    private static class FakeFactory implements OnnxSessionFactory {
        private final int elements;
        private final float probability;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile float[] lastInput;
        private volatile RuntimeException failure;

        private FakeFactory(int elements, float probability) {
            this.elements = elements;
            this.probability = probability;
        }

        @Override
        public OnnxSession open(Path modelPath, String inputName, String outputName,
                                int maxTensorElements) {
            openCount.incrementAndGet();
            return new OnnxSession() {
                @Override
                public int inputElementCount() {
                    return elements;
                }

                @Override
                public OnnxCall newCall(float[] input) {
                    lastInput = input.clone();
                    return new OnnxCall() {
                        @Override
                        public void terminate() {
                        }

                        @Override
                        public Float call() {
                            if (failure != null) {
                                throw failure;
                            }
                            return probability;
                        }

                        @Override
                        public void close() {
                        }
                    };
                }

                @Override
                public void close() {
                    closeCount.incrementAndGet();
                }
            };
        }
    }

    private static final class BlockingFactory implements OnnxSessionFactory {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final AtomicInteger terminateCount = new AtomicInteger();
        private final AtomicBoolean callClosed = new AtomicBoolean();

        @Override
        public OnnxSession open(Path modelPath, String inputName, String outputName,
                                int maxTensorElements) {
            return new OnnxSession() {
                @Override
                public int inputElementCount() {
                    return 4;
                }

                @Override
                public OnnxCall newCall(float[] input) {
                    return new OnnxCall() {
                        @Override
                        public void terminate() {
                            terminateCount.incrementAndGet();
                            terminated.countDown();
                            release.countDown();
                        }

                        @Override
                        public Float call() throws Exception {
                            started.countDown();
                            while (!release.await(1, TimeUnit.SECONDS)) {
                                // Keep waiting until terminate simulates native cancellation.
                            }
                            return 0.5f;
                        }

                        @Override
                        public void close() {
                            callClosed.set(true);
                        }
                    };
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
