package com.xnlp.server.runtime.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class OrtOnnxSessionFactory implements OnnxSessionFactory {

    @Override
    public OnnxSession open(Path modelPath, String inputName, String outputName,
                            int maxTensorElements) throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession session = environment.createSession(modelPath.toString(), options);
            try {
                return validate(environment, session, inputName, outputName, maxTensorElements);
            } catch (Exception e) {
                session.close();
                throw e;
            }
        } finally {
            options.close();
        }
    }

    private static OnnxSession validate(OrtEnvironment environment, OrtSession session,
                                        String inputName, String outputName,
                                        int maxTensorElements) throws OrtException {
        if (!session.getInputNames().contains(inputName)) {
            throw new IllegalArgumentException("Configured ONNX input is not present in the model");
        }
        if (!session.getOutputNames().contains(outputName)) {
            throw new IllegalArgumentException("Configured ONNX output is not present in the model");
        }
        NodeInfo inputNode = session.getInputInfo().get(inputName);
        NodeInfo outputNode = session.getOutputInfo().get(outputName);
        if (!(inputNode.getInfo() instanceof TensorInfo inputInfo)
                || inputInfo.type != OnnxJavaType.FLOAT) {
            throw new IllegalArgumentException("ONNX sentiment input must be a FLOAT tensor");
        }
        if (!(outputNode.getInfo() instanceof TensorInfo outputInfo)
                || outputInfo.type != OnnxJavaType.FLOAT) {
            throw new IllegalArgumentException("ONNX sentiment output must be a FLOAT tensor");
        }

        long[] inputShape = inputInfo.getShape();
        long elements = 1;
        for (long dimension : inputShape) {
            if (dimension < 1) {
                throw new IllegalArgumentException("ONNX sentiment input must have a fixed positive shape");
            }
            try {
                elements = Math.multiplyExact(elements, dimension);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("ONNX sentiment input shape is too large");
            }
        }
        if (elements > maxTensorElements || elements > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ONNX sentiment input exceeds the configured tensor limit");
        }
        long outputElements = outputInfo.getNumElements();
        if (outputElements < 1) {
            throw new IllegalArgumentException("ONNX sentiment output must have a fixed positive shape");
        }
        if (outputElements > maxTensorElements) {
            throw new IllegalArgumentException("ONNX sentiment output exceeds the configured tensor limit");
        }
        return new OrtOnnxSession(environment, session, inputName, outputName,
                inputShape, Math.toIntExact(elements));
    }

    private static final class OrtOnnxSession implements OnnxSession {
        private final OrtEnvironment environment;
        private final OrtSession session;
        private final String inputName;
        private final String outputName;
        private final long[] inputShape;
        private final int inputElementCount;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OrtOnnxSession(OrtEnvironment environment, OrtSession session,
                               String inputName, String outputName,
                               long[] inputShape, int inputElementCount) {
            this.environment = environment;
            this.session = session;
            this.inputName = inputName;
            this.outputName = outputName;
            this.inputShape = inputShape.clone();
            this.inputElementCount = inputElementCount;
        }

        @Override
        public int inputElementCount() {
            return inputElementCount;
        }

        @Override
        public OnnxCall newCall(float[] input) throws OrtException {
            if (closed.get()) {
                throw new IllegalStateException("ONNX session is closed");
            }
            if (input.length != inputElementCount) {
                throw new IllegalArgumentException("ONNX input element count does not match the model");
            }
            return new OrtOnnxCall(environment, session, inputName, outputName, inputShape, input);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    session.close();
                } catch (OrtException e) {
                    throw new IllegalStateException("Could not close ONNX session");
                }
            }
        }
    }

    private static final class OrtOnnxCall implements OnnxCall {
        private final OrtEnvironment environment;
        private final OrtSession session;
        private final String inputName;
        private final String outputName;
        private final long[] inputShape;
        private final float[] input;
        private final OrtSession.RunOptions runOptions;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OrtOnnxCall(OrtEnvironment environment, OrtSession session,
                            String inputName, String outputName,
                            long[] inputShape, float[] input) throws OrtException {
            this.environment = environment;
            this.session = session;
            this.inputName = inputName;
            this.outputName = outputName;
            this.inputShape = inputShape.clone();
            this.input = input.clone();
            this.runOptions = new OrtSession.RunOptions();
            this.runOptions.setRunTag("xnlp-sentiment");
        }

        @Override
        public Float call() throws Exception {
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(input), inputShape);
                 OrtSession.Result result = session.run(
                         Map.of(inputName, inputTensor), Set.of(outputName), runOptions)) {
                OnnxValue value = result.get(outputName)
                        .orElseThrow(() -> new IllegalStateException("Configured ONNX output was not returned"));
                if (!(value instanceof OnnxTensor tensor)) {
                    throw new IllegalStateException("Configured ONNX output is not a tensor");
                }
                FloatBuffer output = tensor.getFloatBuffer();
                if (!output.hasRemaining()) {
                    throw new IllegalStateException("Configured ONNX output is empty");
                }
                float probability = output.get(output.position());
                if (!Float.isFinite(probability) || probability < 0.0f || probability > 1.0f) {
                    throw new IllegalStateException("Configured ONNX output must be a probability");
                }
                return probability;
            }
        }

        @Override
        public void terminate() {
            if (!closed.get()) {
                try {
                    runOptions.setTerminate(true);
                } catch (OrtException ignored) {
                    // Best-effort native cancellation; Future.cancel provides the secondary signal.
                }
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                runOptions.close();
            }
        }
    }
}
