package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.context.CanonicalHistory;
import com.yoda.codingagent.core.context.TurnDigest;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;

final class FailingStateStore implements StateStore {

    enum FailurePoint { BEGIN_TURN, STAGE_TOOL_STEP, RECORD_TOOL_RESULT, FAIL_TURN }

    private final StateStore delegate;
    private final FailurePoint failurePoint;

    FailingStateStore(StateStore delegate, FailurePoint failurePoint) {
        this.delegate = delegate;
        this.failurePoint = failurePoint;
    }

    @Override
    public WorkspaceDescriptor registerWorkspace(String displayName, Path root) {
        return delegate.registerWorkspace(displayName, root);
    }

    @Override
    public List<WorkspaceDescriptor> listWorkspaces() { return delegate.listWorkspaces(); }

    @Override
    public void archiveWorkspace(WorkspaceId workspaceId) {
        delegate.archiveWorkspace(workspaceId);
    }

    @Override
    public void markWorkspaceUnavailable(WorkspaceId workspaceId) {
        delegate.markWorkspaceUnavailable(workspaceId);
    }

    @Override
    public SessionDescriptor createSessionWithSystemMessage(
            SessionConfig config, String systemPrompt) {
        return delegate.createSessionWithSystemMessage(config, systemPrompt);
    }

    @Override
    public List<SessionDescriptor> listSessions(WorkspaceId workspaceId) {
        return delegate.listSessions(workspaceId);
    }

    @Override
    public StoredSession loadSession(SessionId sessionId) {
        return delegate.loadSession(sessionId);
    }

    @Override
    public void closeSession(SessionId sessionId) { delegate.closeSession(sessionId); }

    @Override
    public CanonicalHistory loadCanonicalHistory(SessionId sessionId) {
        return delegate.loadCanonicalHistory(sessionId);
    }

    @Override
    public void beginTurn(AgentTurn turn, String userInput) {
        failAt(FailurePoint.BEGIN_TURN);
        delegate.beginTurn(turn, userInput);
    }

    @Override
    public void markTurnStreaming(AgentTurn turn) { delegate.markTurnStreaming(turn); }

    @Override
    public StagedModelStep stageToolStep(AgentTurn turn, ModelResponse response,
                                         int contextEstimatedTokens) {
        failAt(FailurePoint.STAGE_TOOL_STEP);
        return delegate.stageToolStep(turn, response, contextEstimatedTokens);
    }

    @Override
    public void markToolExecuting(StagedModelStep step, ToolCall call) {
        delegate.markToolExecuting(step, call);
    }

    @Override
    public void recordToolResult(StagedModelStep step, ToolCall call, ToolResult result) {
        failAt(FailurePoint.RECORD_TOOL_RESULT);
        delegate.recordToolResult(step, call, result);
    }

    @Override
    public void commitToolStep(StagedModelStep step) { delegate.commitToolStep(step); }

    @Override
    public void completeTurn(AgentTurn turn, ModelResponse response,
                             int contextEstimatedTokens, TurnDigest digest) {
        delegate.completeTurn(turn, response, contextEstimatedTokens, digest);
    }

    @Override
    public void failTurn(AgentTurn turn, TurnStatus status, ErrorCode reason) {
        failAt(FailurePoint.FAIL_TURN);
        delegate.failTurn(turn, status, reason);
    }

    @Override
    public RecoverySummary recoverInterruptedTurns() {
        return delegate.recoverInterruptedTurns();
    }

    private void failAt(FailurePoint point) {
        if (failurePoint == point) {
            throw new AgentException(ErrorCode.STORAGE_ERROR,
                    "injected state store failure");
        }
    }
}
