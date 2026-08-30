package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.workspace.WorkspaceContext;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SessionRegistry {

    private final StateStore stateStore;
    private final WorkspaceRegistry workspaceRegistry;
    private final Set<SessionId> leasedSessions = new HashSet<>();

    public SessionRegistry(StateStore stateStore, WorkspaceRegistry workspaceRegistry) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.workspaceRegistry = Objects.requireNonNull(workspaceRegistry, "workspaceRegistry");
    }

    public SessionDescriptor create(SessionConfig config, String systemPrompt) {
        Objects.requireNonNull(config, "config");
        workspaceRegistry.activeContext(config.workspaceId());
        return stateStore.createSessionWithSystemMessage(config, systemPrompt);
    }

    public List<SessionDescriptor> list(WorkspaceId workspaceId) {
        return stateStore.listSessions(workspaceId);
    }

    public SessionDescriptor get(SessionId sessionId) {
        return stateStore.loadSession(sessionId).descriptor();
    }

    public SessionContextSummary contextSummary(SessionId sessionId) {
        return stateStore.loadSessionContextSummary(sessionId);
    }

    public synchronized void close(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (leasedSessions.contains(sessionId)) {
            throw new AgentException(ErrorCode.SESSION_BUSY,
                    "session has an active turn");
        }
        stateStore.closeSession(sessionId);
    }

    synchronized Lease acquire(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        StateStore.StoredSession stored = stateStore.loadSession(sessionId);
        if (stored.descriptor().status() == SessionStatus.CLOSED) {
            throw new AgentException(ErrorCode.SESSION_CLOSED, "session is closed");
        }
        if (!leasedSessions.add(sessionId)) {
            throw new AgentException(ErrorCode.SESSION_BUSY,
                    "session has an active turn");
        }
        try {
            WorkspaceContext workspace = workspaceRegistry.activeContext(
                    stored.descriptor().workspaceId());
            return new Lease(this, new AgentSession(sessionId, workspace, stored.limits(),
                    stored.descriptor().status()));
        } catch (RuntimeException exception) {
            leasedSessions.remove(sessionId);
            throw exception;
        }
    }

    private synchronized void release(SessionId sessionId) {
        leasedSessions.remove(sessionId);
    }

    static final class Lease implements AutoCloseable {
        private final SessionRegistry owner;
        private final AgentSession session;
        private boolean closed;

        private Lease(SessionRegistry owner, AgentSession session) {
            this.owner = owner;
            this.session = session;
        }

        AgentSession session() {
            return session;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                owner.release(session.sessionId());
            }
        }
    }
}
