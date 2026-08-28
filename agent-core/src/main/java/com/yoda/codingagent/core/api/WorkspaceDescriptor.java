package com.yoda.codingagent.core.api;

import java.nio.file.Path;
import java.util.Objects;

public record WorkspaceDescriptor(
        WorkspaceId workspaceId,
        String displayName,
        Path root,
        WorkspaceStatus status
) {

    public WorkspaceDescriptor {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.trim();
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
        Objects.requireNonNull(status, "status");
    }
}
