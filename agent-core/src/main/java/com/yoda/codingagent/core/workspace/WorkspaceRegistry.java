package com.yoda.codingagent.core.workspace;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.persistence.StateStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkspaceRegistry {

    private final StateStore stateStore;
    private final WorkspaceResolver resolver;
    private final Map<WorkspaceId, WorkspaceDescriptor> workspaces = new LinkedHashMap<>();

    public WorkspaceRegistry(StateStore stateStore, WorkspaceResolver resolver) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        restore();
    }

    public synchronized WorkspaceDescriptor register(String displayName, Path root) {
        Path canonicalRoot = resolver.resolveForRegistration(root);
        if (workspaces.values().stream()
                .anyMatch(existing -> existing.root().equals(canonicalRoot))) {
            throw new AgentException(ErrorCode.INVALID_REQUEST,
                    "workspace root is already registered");
        }
        WorkspaceDescriptor workspace = stateStore.registerWorkspace(displayName, canonicalRoot);
        workspaces.put(workspace.workspaceId(), workspace);
        return workspace;
    }

    public synchronized List<WorkspaceDescriptor> list() {
        return List.copyOf(workspaces.values());
    }

    public synchronized void archive(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        WorkspaceDescriptor existing = workspaces.get(workspaceId);
        if (existing == null) {
            throw new AgentException(ErrorCode.UNKNOWN_WORKSPACE,
                    "workspace does not exist");
        }
        stateStore.archiveWorkspace(workspaceId);
        if (existing.status() != WorkspaceStatus.ARCHIVED) {
            workspaces.put(workspaceId, withStatus(existing, WorkspaceStatus.ARCHIVED));
        }
    }

    public synchronized WorkspaceContext activeContext(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        WorkspaceDescriptor workspace = workspaces.get(workspaceId);
        if (workspace == null) {
            throw new AgentException(ErrorCode.UNKNOWN_WORKSPACE,
                    "workspace does not exist");
        }
        if (workspace.status() == WorkspaceStatus.ARCHIVED) {
            throw new AgentException(ErrorCode.WORKSPACE_ARCHIVED,
                    "workspace is archived");
        }
        if (workspace.status() == WorkspaceStatus.UNAVAILABLE) {
            throw new AgentException(ErrorCode.WORKSPACE_UNAVAILABLE,
                    "workspace is unavailable");
        }
        return new WorkspaceContext(workspace.workspaceId(), workspace.root(), workspace.status());
    }

    private void restore() {
        for (WorkspaceDescriptor stored : stateStore.listWorkspaces()) {
            WorkspaceDescriptor restored = stored;
            if (stored.status() == WorkspaceStatus.ACTIVE
                    && !resolver.isAvailable(stored.root())) {
                stateStore.markWorkspaceUnavailable(stored.workspaceId());
                restored = withStatus(stored, WorkspaceStatus.UNAVAILABLE);
            }
            workspaces.put(restored.workspaceId(), restored);
        }
    }

    private static WorkspaceDescriptor withStatus(WorkspaceDescriptor workspace,
                                                   WorkspaceStatus status) {
        return new WorkspaceDescriptor(workspace.workspaceId(), workspace.displayName(),
                workspace.root(), status);
    }
}
