package com.yoda.codingagent.core.api;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(UUID value) {

    public WorkspaceId {
        Objects.requireNonNull(value, "value");
    }

    public static WorkspaceId random() {
        return new WorkspaceId(UUID.randomUUID());
    }
}
