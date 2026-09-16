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
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Lifecycle-safe ONNX Runtime adapter for the SENTIMENT capability. */
public class OnnxNlpRuntime implements NlpRuntime {

    private static final Logger log = LoggerFactory.getLogger(OnnxNlpRuntime.class);
    private static final String CAPABILITY = "SENTIMENT";

    private final OnnxRuntimeProperties properties;
    private final NlpRuntimeDescriptor descriptor;
    private final OnnxSessionFactory sessionFactory;
    private final ThreadPoolExecutor executor;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final AtomicReference<NlpRuntimeStatus> status = new AtomicReference<>(
            NlpRuntimeStatus.of(NlpRuntimeState.NEW, "Runtime has not been loaded"));

    private volatile OnnxSessionFactory.OnnxSession session;

    public OnnxNlpRuntime(OnnxRuntimeProperties properties) {
        this(properties, new OrtOnnxSessionFactory());
    }

    OnnxNlpRuntime(OnnxRuntimeProperties properties, OnnxSessionFactory sessionFactory) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.properties.validate();
        this.descriptor = new NlpRuntimeDescriptor(
                properties.getRuntimeName(),
                "onnxruntime",
                properties.getModelVersion(),
                properties.getModelSha256(),
                Set.of(CAPABILITY));
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
        this.executor = new ThreadPoolExecutor(
                properties.getMaxConcurrency(),
                properties.getMaxConcurrency(),
                0L,
                TimeUnit.MILLISECONDS,
                properties.getQueueCapacity() == 0
                        ? new java.util.concurrent.SynchronousQueue<>()
                        : new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                threadFactory(properties.getRuntimeName()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public NlpRuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public NlpRuntimeStatus status() {
        return status.get();
    }

    @Override
    public void load() {
        lifecycleLock.writeLock().lock();
        try {
            NlpRuntimeState current = status.get().state();
            if (current == NlpRuntimeState.READY) {
                return;
            }
            if (current == NlpRuntimeState.CLOSED) {
                throw failure(NlpRuntimeErrorCode.CLOSED, "NLP runtime is closed");
            }
            status.set(NlpRuntimeStatus.of(NlpRuntimeState.LOADING, "Validating external ONNX model"));
            Path modelPath = validateModelFile();
            OnnxSessionFactory.OnnxSession candidate = null;
            try {
                candidate = sessionFactory.open(modelPath, properties.getInputName(),
                        properties.getOutputName(), properties.getMaxTensorElements());
                session = candidate;
                candidate = null;
                status.set(NlpRuntimeStatus.of(NlpRuntimeState.READY, "ONNX model is ready"));
                log.info("Loaded NLP runtime {} model version {}",
                        descriptor.name(), descriptor.modelVersion());
            } finally {
                if (candidate != null) {
                    candidate.close();
                }
            }
        } catch (NlpRuntimeException e) {
            if (status.get().state() != NlpRuntimeState.CLOSED) {
                status.set(NlpRuntimeStatus.of(NlpRuntimeState.FAILED, e.getMessage()));
            }
            throw e;
        } catch (Exception e) {
            status.set(NlpRuntimeStatus.of(NlpRuntimeState.FAILED, "ONNX model validation failed"));
            log.warn("ONNX model load failed for runtime {} ({})",
                    descriptor.name(), e.getClass().getSimpleName());
            throw failure(NlpRuntimeErrorCode.MODEL_INVALID, "ONNX model validation failed");
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    @Override
    @Observed(name = "xnlp.nlp.runtime.onnx.execute")
    public NlpRuntimeResult execute(String capability, NlpContext context) {
        if (!supports(capability)) {
            throw failure(NlpRuntimeErrorCode.UNSUPPORTED_CAPABILITY,
                    "NLP runtime does not support the requested capability");
        }
        Objects.requireNonNull(context, "context must not be null");
        requireReady();

        OnnxSessionFactory.OnnxSession currentSession = session;
        if (currentSession == null) {
            throw failure(NlpRuntimeErrorCode.NOT_READY, "NLP runtime is not ready");
        }
        float[] features = HashedTextFeaturizer.encode(
                context.getText(), currentSession.inputElementCount());
        long started = System.nanoTime();
        float probability = validateProbability(executeBounded(features, properties.getTimeout()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        String label = probability <= properties.getNegativeThreshold()
                ? "negative"
                : probability >= properties.getPositiveThreshold() ? "positive" : "neutral";
        ComponentResult result = ComponentResult.of(CAPABILITY,
                Map.of("label", label, "score", (double) probability));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "onnx");
        metadata.put("runtime", descriptor.name());
        metadata.put("provider", descriptor.provider());
        metadata.put("modelVersion", descriptor.modelVersion());
        metadata.put("modelSha256", descriptor.modelSha256());
        metadata.put("elapsedMillis", elapsedMillis);
        return new NlpRuntimeResult(result, metadata);
    }

    private float executeBounded(float[] features, Duration timeout) {
        AtomicReference<OnnxSessionFactory.OnnxCall> activeCall = new AtomicReference<>();
        Future<Float> future;
        try {
            future = executor.submit(() -> {
                lifecycleLock.readLock().lock();
                try {
                    requireReady();
                    OnnxSessionFactory.OnnxSession current = session;
                    if (current == null) {
                        throw failure(NlpRuntimeErrorCode.NOT_READY, "NLP runtime is not ready");
                    }
                    OnnxSessionFactory.OnnxCall call = current.newCall(features);
                    activeCall.set(call);
                    try (call) {
                        return call.call();
                    } finally {
                        activeCall.compareAndSet(call, null);
                    }
                } finally {
                    lifecycleLock.readLock().unlock();
                }
            });
        } catch (RejectedExecutionException e) {
            throw failure(NlpRuntimeErrorCode.SATURATED, "NLP runtime execution queue is full");
        }

        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            terminate(activeCall.get(), future);
            throw failure(NlpRuntimeErrorCode.EXECUTION_TIMEOUT, "NLP runtime execution timed out");
        } catch (InterruptedException e) {
            terminate(activeCall.get(), future);
            Thread.currentThread().interrupt();
            throw failure(NlpRuntimeErrorCode.EXECUTION_FAILED, "NLP runtime execution was interrupted");
        } catch (CancellationException e) {
            throw failure(NlpRuntimeErrorCode.CLOSED, "NLP runtime execution was cancelled");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof NlpRuntimeException runtimeException) {
                throw runtimeException;
            }
            log.warn("ONNX execution failed for runtime {} ({})",
                    descriptor.name(), e.getCause() == null
                            ? "unknown" : e.getCause().getClass().getSimpleName());
            throw failure(NlpRuntimeErrorCode.EXECUTION_FAILED, "NLP runtime execution failed");
        }
    }

    private float validateProbability(float probability) {
        if (!Float.isFinite(probability) || probability < 0.0f || probability > 1.0f) {
            log.warn("ONNX execution returned an invalid probability for runtime {}", descriptor.name());
            throw failure(NlpRuntimeErrorCode.EXECUTION_FAILED, "NLP runtime execution failed");
        }
        return probability;
    }

    private Path validateModelFile() {
        Path modelPath;
        try {
            modelPath = Path.of(properties.getModelPath()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw failure(NlpRuntimeErrorCode.CONFIGURATION, "ONNX model path is invalid");
        }
        if (!Files.isRegularFile(modelPath)) {
            throw failure(NlpRuntimeErrorCode.MODEL_NOT_FOUND, "Configured ONNX model was not found");
        }
        try {
            long size = Files.size(modelPath);
            if (size < 1 || size > properties.getMaxModelBytes()) {
                throw failure(NlpRuntimeErrorCode.MODEL_INVALID,
                        "Configured ONNX model exceeds the allowed size");
            }
            String actualChecksum = sha256(modelPath);
            if (!MessageDigest.isEqual(
                    actualChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    descriptor.modelSha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw failure(NlpRuntimeErrorCode.CHECKSUM_MISMATCH,
                        "Configured ONNX model checksum does not match");
            }
            return modelPath;
        } catch (NlpRuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw failure(NlpRuntimeErrorCode.MODEL_INVALID, "Configured ONNX model could not be read");
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void requireReady() {
        NlpRuntimeState current = status.get().state();
        if (current == NlpRuntimeState.CLOSED) {
            throw failure(NlpRuntimeErrorCode.CLOSED, "NLP runtime is closed");
        }
        if (current != NlpRuntimeState.READY) {
            throw failure(NlpRuntimeErrorCode.NOT_READY, "NLP runtime is not ready");
        }
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (status.get().state() == NlpRuntimeState.CLOSED) {
                return;
            }
            OnnxSessionFactory.OnnxSession current = session;
            session = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException e) {
                    log.warn("ONNX session close failed for runtime {} ({})",
                            descriptor.name(), e.getClass().getSimpleName());
                }
            }
            executor.shutdownNow();
            status.set(NlpRuntimeStatus.of(NlpRuntimeState.CLOSED, "Runtime is closed"));
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private NlpRuntimeException failure(NlpRuntimeErrorCode code, String message) {
        return new NlpRuntimeException(code, message,
                Map.of("runtime", descriptor.name(), "capability", CAPABILITY));
    }

    private static void terminate(OnnxSessionFactory.OnnxCall call, Future<?> future) {
        if (call != null) {
            call.terminate();
        }
        future.cancel(true);
    }

    private static ThreadFactory threadFactory(String runtimeName) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "xnlp-" + runtimeName + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
