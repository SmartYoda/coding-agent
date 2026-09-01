package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.api.AgentEventSink;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class DefaultAgentService implements AgentService {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a coding agent operating in one local workspace.
            Inspect available context before changing anything. Use only declared tools.
            Never invent tool results. After a change, run the relevant verification when possible.
            A successful file change followed by one matching read is sufficient verification;
            do not repeat equivalent successful tool calls.
            If a command is denied, do not retry the same or an equivalent command.
            Finish with a concise summary of changes and verification.
            """;

    private final WorkspaceRegistry workspaceRegistry;
    private final SessionRegistry sessionRegistry;
    private final AgentRunner agentRunner;
    private final String systemPrompt;
    private final SecretRedactor secretRedactor;
    private final boolean defaultThinkingEnabled;

    public DefaultAgentService(WorkspaceRegistry workspaceRegistry,
                               SessionRegistry sessionRegistry,
                               AgentRunner agentRunner,
                               String systemPrompt,
                               SecretRedactor secretRedactor,
                               boolean defaultThinkingEnabled) {
        this.workspaceRegistry = Objects.requireNonNull(workspaceRegistry, "workspaceRegistry");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.agentRunner = Objects.requireNonNull(agentRunner, "agentRunner");
        this.secretRedactor = Objects.requireNonNull(secretRedactor, "secretRedactor");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        this.systemPrompt = systemPrompt;
        this.defaultThinkingEnabled = defaultThinkingEnabled;
    }

    @Override
    public WorkspaceDescriptor registerWorkspace(String displayName, Path root) {
        return workspaceRegistry.register(displayName, root);
    }

    @Override
    public List<WorkspaceDescriptor> listWorkspaces() {
        return workspaceRegistry.list();
    }

    @Override
    public void archiveWorkspace(WorkspaceId workspaceId) {
        workspaceRegistry.archive(workspaceId);
    }

    @Override
    public SessionDescriptor openSession(SessionConfig config) {
        return sessionRegistry.create(config, systemPrompt);
    }

    @Override
    public List<SessionDescriptor> listSessions(WorkspaceId workspaceId) {
        return sessionRegistry.list(workspaceId);
    }

    @Override
    public SessionDescriptor getSession(SessionId sessionId) {
        return sessionRegistry.get(sessionId);
    }

    @Override
    public SessionContextSummary getSessionContext(SessionId sessionId) {
        return sessionRegistry.contextSummary(sessionId);
    }

    @Override
    public void closeSession(SessionId sessionId) {
        sessionRegistry.close(sessionId);
    }

    @Override
    public AgentResult runTurn(SessionId sessionId, AgentRequest request,
                               AgentEventSink eventSink,
                               CancellationToken cancellationToken) {
        try (SessionRegistry.Lease lease = sessionRegistry.acquire(sessionId)) {
            Objects.requireNonNull(request, "request");
            AgentRequest safeRequest = new AgentRequest(
                    secretRedactor.redact(request.input()), request.thinkingMode());
            boolean thinkingEnabled = safeRequest.thinkingMode()
                    .resolve(defaultThinkingEnabled);
            return agentRunner.run(lease.session(), safeRequest, thinkingEnabled,
                    eventSink, cancellationToken);
        }
    }
}
