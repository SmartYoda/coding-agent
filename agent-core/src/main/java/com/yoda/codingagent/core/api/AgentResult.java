package com.yoda.codingagent.core.api;

import java.time.Duration;
import java.util.Objects;

public record AgentResult(
        WorkspaceId workspaceId,
        SessionId sessionId,
        TurnId turnId,
        TurnStatus status,
        String finalText,
        ErrorCode errorCode,
        String errorMessage,
        int stepCount,
        int toolCallCount,
        Duration duration) {

    public AgentResult {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        if (status != TurnStatus.COMPLETED && status != TurnStatus.FAILED
                && status != TurnStatus.CANCELLED && status != TurnStatus.LIMIT_REACHED
                && status != TurnStatus.INTERRUPTED) {
            throw new IllegalArgumentException("AgentResult requires a terminal turn status");
        }
        if (stepCount < 0 || toolCallCount < 0 || duration.isNegative()) {
            throw new IllegalArgumentException("result counters and duration must not be negative");
        }
        boolean successful = status == TurnStatus.COMPLETED;
        if (successful && (finalText == null || finalText.isBlank()
                || errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("a completed result requires text and no error");
        }
        if (!successful && (errorCode == null || errorMessage == null || finalText != null)) {
            throw new IllegalArgumentException("a failed result requires an error and no final text");
        }
        if (!successful) {
            validateTerminalClassification(status, errorCode);
        }
    }

    public static AgentResult completed(
            WorkspaceId workspaceId, SessionId sessionId, TurnId turnId, String finalText,
            int stepCount, int toolCallCount, Duration duration) {
        return new AgentResult(workspaceId, sessionId, turnId, TurnStatus.COMPLETED,
                Objects.requireNonNull(finalText, "finalText"), null, null,
                stepCount, toolCallCount, duration);
    }

    public static AgentResult failed(
            WorkspaceId workspaceId, SessionId sessionId, TurnId turnId, TurnStatus status,
            ErrorCode errorCode, String safeMessage, int stepCount, int toolCallCount,
            Duration duration) {
        if (status == TurnStatus.COMPLETED || status == TurnStatus.CREATED
                || status == TurnStatus.RUNNING || status == TurnStatus.STREAMING_MODEL
                || status == TurnStatus.EXECUTING_TOOL) {
            throw new IllegalArgumentException("status must be terminal and unsuccessful");
        }
        return new AgentResult(workspaceId, sessionId, turnId, status, null,
                Objects.requireNonNull(errorCode, "errorCode"),
                Objects.requireNonNull(safeMessage, "safeMessage"),
                stepCount, toolCallCount, duration);
    }

    private static void validateTerminalClassification(TurnStatus status, ErrorCode errorCode) {
        boolean limitError = errorCode == ErrorCode.TURN_LIMIT
                || errorCode == ErrorCode.CONTEXT_LIMIT;
        if (status == TurnStatus.CANCELLED && errorCode != ErrorCode.CANCELLED
                || status == TurnStatus.LIMIT_REACHED && !limitError
                || status == TurnStatus.FAILED
                && (errorCode == ErrorCode.CANCELLED || limitError)
                || status == TurnStatus.INTERRUPTED && errorCode != ErrorCode.INTERNAL_ERROR) {
            throw new IllegalArgumentException("terminal status and error code do not match");
        }
    }
}
