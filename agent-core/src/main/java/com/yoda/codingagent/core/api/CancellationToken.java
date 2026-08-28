package com.yoda.codingagent.core.api;

@FunctionalInterface
public interface CancellationToken {

    CancellationToken NONE = () -> false;

    boolean isCancelled();
}
