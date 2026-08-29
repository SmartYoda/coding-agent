package com.yoda.codingagent.core.tool;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record ToolContext(WorkspaceId workspaceId, Path workspaceRoot, TurnId turnId,
                          String callId, Instant turnDeadline, RunLimits runLimits,
                          CancellationToken cancellationToken) {

    public ToolContext {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(turnId, "turnId");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        Objects.requireNonNull(turnDeadline, "turnDeadline");
        Objects.requireNonNull(runLimits, "runLimits");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
