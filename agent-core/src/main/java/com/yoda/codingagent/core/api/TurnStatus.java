package com.yoda.codingagent.core.api;

public enum TurnStatus {
    CREATED,
    RUNNING,
    STREAMING_MODEL,
    EXECUTING_TOOL,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    LIMIT_REACHED
}
