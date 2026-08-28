package com.yoda.codingagent.core.tool;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import java.util.Objects;

public record ToolContext(WorkspaceId workspaceId, TurnId turnId,
                          CancellationToken cancellationToken) {

    public ToolContext {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
