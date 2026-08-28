package com.yoda.codingagent.core.workspace;

import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import java.nio.file.Path;
import java.util.Objects;

public record WorkspaceContext(
        WorkspaceId workspaceId,
        Path root,
        WorkspaceStatus status
) {

    public WorkspaceContext {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
        Objects.requireNonNull(status, "status");
    }
}
