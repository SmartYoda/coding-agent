package com.yoda.codingagent.core.api;

import java.util.Objects;

public record AgentResult(
        TurnId turnId,
        TurnStatus status,
        String finalText,
        ErrorCode errorCode,
        String errorMessage) {

    public AgentResult {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(status, "status");
        boolean successful = status == TurnStatus.COMPLETED;
        if (successful && (finalText == null || finalText.isBlank()
                || errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("a completed result requires text and no error");
        }
        if (!successful && (errorCode == null || errorMessage == null || finalText != null)) {
            throw new IllegalArgumentException("a failed result requires an error and no final text");
        }
    }

    public static AgentResult completed(TurnId turnId, String finalText) {
        return new AgentResult(turnId, TurnStatus.COMPLETED,
                Objects.requireNonNull(finalText, "finalText"), null, null);
    }

    public static AgentResult failed(
            TurnId turnId, TurnStatus status, ErrorCode errorCode, String safeMessage) {
        if (status == TurnStatus.COMPLETED || status == TurnStatus.RUNNING) {
            throw new IllegalArgumentException("status must be terminal and unsuccessful");
        }
        return new AgentResult(turnId, status, null,
                Objects.requireNonNull(errorCode, "errorCode"),
                Objects.requireNonNull(safeMessage, "safeMessage"));
    }
}
