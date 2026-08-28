package com.yoda.codingagent.core.api;

import java.time.Instant;
import java.util.Objects;

public sealed interface AgentEvent permits AgentEvent.TurnStarted,
        AgentEvent.ModelRequestStarted, AgentEvent.ModelTextDelta,
        AgentEvent.ModelToolCallDelta, AgentEvent.ModelRequestCompleted,
        AgentEvent.ToolStarted, AgentEvent.ToolCompleted,
        AgentEvent.TurnCompleted, AgentEvent.TurnFailed {

    WorkspaceId workspaceId();

    SessionId sessionId();

    TurnId turnId();

    long sequence();

    Instant timestamp();

    record TurnStarted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                       long sequence, Instant timestamp) implements AgentEvent {
        public TurnStarted { validate(workspaceId, sessionId, turnId, sequence, timestamp); }
    }

    record ModelRequestStarted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                               long sequence, Instant timestamp, int step) implements AgentEvent {
        public ModelRequestStarted { validate(workspaceId, sessionId, turnId, sequence, timestamp); }
    }

    record ModelTextDelta(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                          long sequence, Instant timestamp, String text) implements AgentEvent {
        public ModelTextDelta {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            Objects.requireNonNull(text, "text");
        }
    }

    record ModelToolCallDelta(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                              long sequence, Instant timestamp, int index,
                              String callId, int argumentCharacters) implements AgentEvent {
        public ModelToolCallDelta { validate(workspaceId, sessionId, turnId, sequence, timestamp); }
    }

    record ModelRequestCompleted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                                 long sequence, Instant timestamp, int step,
                                 String finishReason) implements AgentEvent {
        public ModelRequestCompleted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            Objects.requireNonNull(finishReason, "finishReason");
        }
    }

    record ToolStarted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                       long sequence, Instant timestamp, String callId,
                       String toolName) implements AgentEvent {
        public ToolStarted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
        }
    }

    record ToolCompleted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                         long sequence, Instant timestamp, String callId,
                         String toolName, boolean success) implements AgentEvent {
        public ToolCompleted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
        }
    }

    record TurnCompleted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                         long sequence, Instant timestamp) implements AgentEvent {
        public TurnCompleted { validate(workspaceId, sessionId, turnId, sequence, timestamp); }
    }

    record TurnFailed(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                      long sequence, Instant timestamp, ErrorCode errorCode,
                      String safeMessage) implements AgentEvent {
        public TurnFailed {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(safeMessage, "safeMessage");
        }
    }

    private static void validate(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                                 long sequence, Instant timestamp) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
