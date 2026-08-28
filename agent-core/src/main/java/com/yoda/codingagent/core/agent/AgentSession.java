package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.workspace.WorkspaceContext;
import java.util.Objects;

record AgentSession(
        SessionId sessionId,
        WorkspaceContext workspace,
        RunLimits limits,
        SessionStatus status
) {

    AgentSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(status, "status");
    }
}
