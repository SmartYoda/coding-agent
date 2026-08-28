package com.yoda.codingagent.core.api;

import java.time.Instant;
import java.util.Objects;

public record SessionDescriptor(
        SessionId sessionId,
        WorkspaceId workspaceId,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public SessionDescriptor {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }
}
