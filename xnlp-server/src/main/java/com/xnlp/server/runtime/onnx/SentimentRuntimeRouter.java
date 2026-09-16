package com.xnlp.server.runtime.onnx;

import com.xnlp.core.api.ComponentResult;
import com.xnlp.core.api.NlpContext;
import com.xnlp.core.runtime.NlpRuntime;
import com.xnlp.core.runtime.NlpRuntimeErrorCode;
import com.xnlp.core.runtime.NlpRuntimeException;
import com.xnlp.core.runtime.NlpRuntimeResult;
import com.xnlp.core.runtime.NlpRuntimeState;
import com.xnlp.server.nlp.NlpComponentExecution;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies BUILTIN/AUTO/ONNX routing while preserving the existing sentiment response shape. */
@Component
public class SentimentRuntimeRouter {

    private static final String CAPABILITY = "SENTIMENT";
    private static final Set<String> POSITIVE = Set.of(
            "好", "优秀", "喜欢", "满意", "高兴", "成功", "提升",
            "positive", "good", "great", "excellent", "love");
    private static final Set<String> NEGATIVE = Set.of(
            "差", "糟糕", "讨厌", "失败", "问题", "风险", "不好",
            "negative", "bad", "poor", "fail", "risk");

    private final List<NlpRuntime> runtimes;
    private final NlpRuntimeRoutingProperties properties;

    public SentimentRuntimeRouter(List<NlpRuntime> runtimes,
                                  NlpRuntimeRoutingProperties properties) {
        this.runtimes = List.copyOf(runtimes);
        this.properties = properties;
    }

    public NlpComponentExecution execute(NlpContext context) {
        return switch (properties.getSentimentMode()) {
            case BUILTIN -> builtin(context, null);
            case AUTO -> executeAuto(context);
            case ONNX -> executeStrict(context);
        };
    }

    private NlpComponentExecution executeAuto(NlpContext context) {
        NlpRuntime runtime = findRuntime();
        if (runtime == null) {
            return builtin(context, "nlp_runtime_not_configured");
        }
        try {
            return execute(runtime, context);
        } catch (NlpRuntimeException e) {
            return builtin(context, e.getErrorCode().code());
        }
    }

    private NlpComponentExecution executeStrict(NlpContext context) {
        NlpRuntime runtime = findRuntime();
        if (runtime == null) {
            throw new NlpRuntimeException(
                    NlpRuntimeErrorCode.NOT_READY,
                    "ONNX sentiment runtime is not configured",
                    Map.of("capability", CAPABILITY));
        }
        return execute(runtime, context);
    }

    private NlpComponentExecution execute(NlpRuntime runtime, NlpContext context) {
        if (runtime.status().state() == NlpRuntimeState.NEW) {
            runtime.load();
        }
        if (runtime.status().state() != NlpRuntimeState.READY) {
            throw new NlpRuntimeException(
                    NlpRuntimeErrorCode.NOT_READY,
                    "ONNX sentiment runtime is not ready",
                    Map.of("runtime", runtime.descriptor().name(), "capability", CAPABILITY));
        }
        NlpRuntimeResult result = runtime.execute(CAPABILITY, context);
        return new NlpComponentExecution(result.result(), result.metadata());
    }

    private NlpRuntime findRuntime() {
        return runtimes.stream()
                .filter(runtime -> runtime.supports(CAPABILITY))
                .filter(runtime -> "onnxruntime".equalsIgnoreCase(runtime.descriptor().provider()))
                .findFirst()
                .orElse(null);
    }

    private static NlpComponentExecution builtin(NlpContext context, String fallbackReason) {
        String text = context.getText().toLowerCase(java.util.Locale.ROOT);
        long positive = POSITIVE.stream().filter(text::contains).count();
        long negative = NEGATIVE.stream().filter(text::contains).count();
        String label = positive == negative ? "neutral" : positive > negative ? "positive" : "negative";
        double score = "neutral".equals(label) ? 0.5 : 0.75;
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("mode", "builtin-demo");
        runtime.put("runtime", "lexicon-sentiment");
        runtime.put("standard", "hanlp-demo-compatible");
        if (fallbackReason != null) {
            runtime.put("fallbackFrom", "onnx");
            runtime.put("fallbackReason", fallbackReason);
        }
        return new NlpComponentExecution(
                ComponentResult.of(CAPABILITY, Map.of("label", label, "score", score)),
                runtime);
    }
}
