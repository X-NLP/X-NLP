package com.xnlp.server.runtime.onnx;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for the external single-input ONNX sentiment runtime. */
@ConfigurationProperties("xnlp.nlp.onnx")
public class OnnxRuntimeProperties {

    private boolean enabled;
    private String runtimeName = "onnx-sentiment";
    private String modelPath = "";
    private String modelVersion = "";
    private String modelSha256 = "";
    private String inputName = "x";
    private String outputName = "y";
    private Duration timeout = Duration.ofSeconds(2);
    private long maxModelBytes = 256L * 1024 * 1024;
    private int maxTensorElements = 1_000_000;
    private int maxConcurrency = 2;
    private int queueCapacity = 16;
    private double negativeThreshold = 0.45;
    private double positiveThreshold = 0.55;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRuntimeName() {
        return runtimeName;
    }

    public void setRuntimeName(String runtimeName) {
        this.runtimeName = requireText(runtimeName, "runtimeName");
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath == null ? "" : modelPath.strip();
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion == null ? "" : modelVersion.strip();
    }

    public String getModelSha256() {
        return modelSha256;
    }

    public void setModelSha256(String modelSha256) {
        this.modelSha256 = modelSha256 == null ? "" : modelSha256.strip();
    }

    public String getInputName() {
        return inputName;
    }

    public void setInputName(String inputName) {
        this.inputName = requireText(inputName, "inputName");
    }

    public String getOutputName() {
        return outputName;
    }

    public void setOutputName(String outputName) {
        this.outputName = requireText(outputName, "outputName");
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 millisecond and 5 minutes");
        }
        this.timeout = timeout;
    }

    public long getMaxModelBytes() {
        return maxModelBytes;
    }

    public void setMaxModelBytes(long maxModelBytes) {
        if (maxModelBytes < 1 || maxModelBytes > 4L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("maxModelBytes must be between 1 byte and 4 GiB");
        }
        this.maxModelBytes = maxModelBytes;
    }

    public int getMaxTensorElements() {
        return maxTensorElements;
    }

    public void setMaxTensorElements(int maxTensorElements) {
        if (maxTensorElements < 1 || maxTensorElements > 10_000_000) {
            throw new IllegalArgumentException("maxTensorElements must be between 1 and 10000000");
        }
        this.maxTensorElements = maxTensorElements;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency < 1 || maxConcurrency > 64) {
            throw new IllegalArgumentException("maxConcurrency must be between 1 and 64");
        }
        this.maxConcurrency = maxConcurrency;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 0 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("queueCapacity must be between 0 and 10000");
        }
        this.queueCapacity = queueCapacity;
    }

    public double getNegativeThreshold() {
        return negativeThreshold;
    }

    public void setNegativeThreshold(double negativeThreshold) {
        this.negativeThreshold = requireProbability(negativeThreshold, "negativeThreshold");
    }

    public double getPositiveThreshold() {
        return positiveThreshold;
    }

    public void setPositiveThreshold(double positiveThreshold) {
        this.positiveThreshold = requireProbability(positiveThreshold, "positiveThreshold");
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        requireText(runtimeName, "runtimeName");
        requireText(modelPath, "modelPath");
        requireText(modelVersion, "modelVersion");
        requireText(modelSha256, "modelSha256");
        requireText(inputName, "inputName");
        requireText(outputName, "outputName");
        if (negativeThreshold >= positiveThreshold) {
            throw new IllegalArgumentException("negativeThreshold must be less than positiveThreshold");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static double requireProbability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }
}
