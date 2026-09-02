package com.yoda.codingagent.core.persistence;

import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.CommandAccessMode;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.context.CanonicalHistory;
import com.yoda.codingagent.core.context.TurnDigest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StateStore {

    WorkspaceDescriptor registerWorkspace(String displayName, Path root);

    List<WorkspaceDescriptor> listWorkspaces();

    void archiveWorkspace(WorkspaceId workspaceId);

    void markWorkspaceUnavailable(WorkspaceId workspaceId);

    SessionDescriptor createSessionWithSystemMessage(SessionConfig config, String systemPrompt);

    List<SessionDescriptor> listSessions(WorkspaceId workspaceId);

    StoredSession loadSession(SessionId sessionId);

    SessionContextSummary loadSessionContextSummary(SessionId sessionId);

    void closeSession(SessionId sessionId);

    CanonicalHistory loadCanonicalHistory(SessionId sessionId);

    void beginTurn(TurnId turnId, SessionId sessionId, Instant startedAt, String userInput,
                   boolean thinkingEnabled);

    default void beginTurn(TurnId turnId, SessionId sessionId, Instant startedAt,
                           String userInput, boolean thinkingEnabled,
                           CommandAccessMode commandAccessMode) {
        java.util.Objects.requireNonNull(commandAccessMode, "commandAccessMode");
        beginTurn(turnId, sessionId, startedAt, userInput, thinkingEnabled);
    }

    void markTurnStreaming(TurnId turnId, int stepNo);

    StagedModelStep stageToolStep(TurnId turnId, int stepNo, ModelResponse response,
                                  int contextEstimatedTokens);

    void markToolExecuting(StagedModelStep step, ToolCall call);

    void recordToolResult(StagedModelStep step, ToolCall call, ToolResult result);

    void commitToolStep(StagedModelStep step);

    void completeTurn(TurnId turnId, int stepNo, ModelResponse response,
                      int contextEstimatedTokens, TurnDigest digest, Instant finishedAt);

    void failTurn(TurnId turnId, TurnStatus status, ErrorCode reason, Instant finishedAt);

    RecoverySummary recoverInterruptedTurns();

    record StoredSession(SessionDescriptor descriptor, RunLimits limits) {
        public StoredSession {
            java.util.Objects.requireNonNull(descriptor, "descriptor");
            java.util.Objects.requireNonNull(limits, "limits");
        }
    }

    record StagedModelStep(UUID stepId, TurnId turnId, int stepNo) {
        public StagedModelStep {
            java.util.Objects.requireNonNull(stepId, "stepId");
            java.util.Objects.requireNonNull(turnId, "turnId");
            if (stepNo < 1) {
                throw new IllegalArgumentException("stepNo must be positive");
            }
        }
    }

    record RecoverySummary(
            int interruptedTurns,
            int abortedSteps,
            int unknownToolCalls,
            int cancelledToolCalls
    ) {
        public RecoverySummary {
            if (interruptedTurns < 0 || abortedSteps < 0
                    || unknownToolCalls < 0 || cancelledToolCalls < 0) {
                throw new IllegalArgumentException("recovery counts must not be negative");
            }
        }
    }
}
