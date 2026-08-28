package com.yoda.codingagent.core.persistence;

public enum ToolExecutionStatus {
    PENDING,
    EXECUTING,
    SUCCESS,
    FAILURE,
    DENIED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN
}
