package com.yoda.codingagent.core.model;

@FunctionalInterface
public interface ModelStreamSink {

    void onEvent(ModelStreamEvent event);
}
