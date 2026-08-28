package com.yoda.codingagent.core.api;

import java.util.Objects;

public record SessionConfig(WorkspaceId workspaceId, RunLimits limits) {

    public SessionConfig {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(limits, "limits");
    }
}
