package com.yoda.codingagent.core.api;

import java.util.Objects;

public record SessionContextSummary(
        SessionId sessionId,
        WorkspaceId workspaceId,
        RunLimits runLimits,
        int completedTurnCount,
        int digestCount) {

    public SessionContextSummary {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runLimits, "runLimits");
        if (completedTurnCount < 0 || digestCount < 0) {
            throw new IllegalArgumentException("context summary counts must not be negative");
        }
        if (digestCount > completedTurnCount) {
            throw new IllegalArgumentException("digest count cannot exceed completed turns");
        }
    }
}
