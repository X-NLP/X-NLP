package com.xnlp.server.waste;

/**
 * Signals a valid request that cannot be applied because the waste workflow is
 * in an incompatible state, for example weighing out before weighing in.
 */
public class WasteWorkflowException extends RuntimeException {

    public WasteWorkflowException(String message) {
        super(message);
    }
}
