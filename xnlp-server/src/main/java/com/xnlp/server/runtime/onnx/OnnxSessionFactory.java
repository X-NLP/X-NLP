package com.xnlp.server.runtime.onnx;

import java.nio.file.Path;
import java.util.concurrent.Callable;

interface OnnxSessionFactory {

    OnnxSession open(Path modelPath, String inputName, String outputName,
                     int maxTensorElements) throws Exception;

    interface OnnxSession extends AutoCloseable {
        int inputElementCount();

        OnnxCall newCall(float[] input) throws Exception;

        @Override
        void close();
    }

    interface OnnxCall extends Callable<Float>, AutoCloseable {
        void terminate();

        @Override
        void close();
    }
}
