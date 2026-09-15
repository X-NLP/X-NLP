package com.xnlp.server.nlp;

import com.xnlp.core.api.NlpComponent;
import com.xnlp.core.api.NlpContext;

/** Optional extension for components that can report the runtime used for each invocation. */
public interface RuntimeAwareNlpComponent extends NlpComponent {

    NlpComponentExecution executeWithRuntime(NlpContext context);

    @Override
    default com.xnlp.core.api.ComponentResult execute(NlpContext context) {
        return executeWithRuntime(context).result();
    }
}
