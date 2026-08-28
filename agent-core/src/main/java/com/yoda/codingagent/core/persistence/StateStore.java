package com.yoda.codingagent.core.persistence;

import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.agent.AgentTurn;
import com.yoda.codingagent.core.context.CanonicalHistory;
import com.yoda.codingagent.core.context.TurnDigest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionId;
import java.nio.file.Path;
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

    void closeSession(SessionId sessionId);

    CanonicalHistory loadCanonicalHistory(SessionId sessionId);

    void beginTurn(AgentTurn turn, String userInput);

    void markTurnStreaming(AgentTurn turn);

    StagedModelStep stageToolStep(AgentTurn turn, ModelResponse response,
                                  int contextEstimatedTokens);

    void markToolExecuting(StagedModelStep step, ToolCall call);

    void recordToolResult(StagedModelStep step, ToolCall call, ToolResult result);

    void commitToolStep(StagedModelStep step);

    void completeTurn(AgentTurn turn, ModelResponse response,
                      int contextEstimatedTokens, TurnDigest digest);

    void failTurn(AgentTurn turn, TurnStatus status, ErrorCode reason);

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
