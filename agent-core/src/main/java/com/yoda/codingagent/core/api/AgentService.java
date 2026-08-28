package com.yoda.codingagent.core.api;

import java.nio.file.Path;
import java.util.List;

public interface AgentService {

    WorkspaceDescriptor registerWorkspace(String displayName, Path root);

    List<WorkspaceDescriptor> listWorkspaces();

    void archiveWorkspace(WorkspaceId workspaceId);

    SessionDescriptor openSession(SessionConfig config);

    List<SessionDescriptor> listSessions(WorkspaceId workspaceId);

    SessionDescriptor getSession(SessionId sessionId);

    void closeSession(SessionId sessionId);

    AgentResult runTurn(SessionId sessionId, AgentRequest request,
                        AgentEventSink eventSink, CancellationToken cancellationToken);
}
