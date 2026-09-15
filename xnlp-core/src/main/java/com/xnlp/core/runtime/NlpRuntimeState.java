package com.xnlp.core.runtime;

/** Lifecycle states shared by pluggable NLP runtimes. */
public enum NlpRuntimeState {
    NEW,
    LOADING,
    READY,
    FAILED,
    CLOSED
}
