package com.yoda.codingagent.core.api;

@FunctionalInterface
public interface AgentEventSink {

    AgentEventSink NOOP = event -> { };

    void publish(AgentEvent event);
}
