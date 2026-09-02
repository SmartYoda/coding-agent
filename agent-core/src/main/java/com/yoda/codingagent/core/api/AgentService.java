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

    SessionContextSummary getSessionContext(SessionId sessionId);

    void closeSession(SessionId sessionId);

    default AgentResult runTurn(SessionId sessionId, AgentRequest request,
                                AgentEventSink eventSink,
                                CancellationToken cancellationToken) {
        return runTurn(sessionId, request, eventSink, cancellationToken,
                CommandApprovalGateway.denyAll());
    }

    AgentResult runTurn(SessionId sessionId, AgentRequest request,
                        AgentEventSink eventSink, CancellationToken cancellationToken,
                        CommandApprovalGateway approvalGateway);
}
