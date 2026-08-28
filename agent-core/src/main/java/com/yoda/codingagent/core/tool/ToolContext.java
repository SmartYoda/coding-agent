package com.yoda.codingagent.core.tool;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import java.nio.file.Path;
import java.util.Objects;

public record ToolContext(WorkspaceId workspaceId, Path workspaceRoot, TurnId turnId,
                          CancellationToken cancellationToken) {

    public ToolContext {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
