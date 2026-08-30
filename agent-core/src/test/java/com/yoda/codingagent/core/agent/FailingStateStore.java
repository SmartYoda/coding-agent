package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.TurnId;
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
import java.time.Instant;
import java.util.List;

final class FailingStateStore implements StateStore {

    enum FailurePoint {
        BEGIN_TURN,
        MARK_TURN_STREAMING,
        STAGE_TOOL_STEP,
        MARK_TOOL_EXECUTING,
        RECORD_TOOL_RESULT,
        COMMIT_TOOL_STEP,
        COMPLETE_TURN,
        FAIL_TURN
    }

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
    public SessionContextSummary loadSessionContextSummary(SessionId sessionId) {
        return delegate.loadSessionContextSummary(sessionId);
    }

    @Override
    public void closeSession(SessionId sessionId) { delegate.closeSession(sessionId); }

    @Override
    public CanonicalHistory loadCanonicalHistory(SessionId sessionId) {
        return delegate.loadCanonicalHistory(sessionId);
    }

    @Override
    public void beginTurn(TurnId turnId, SessionId sessionId, Instant startedAt,
                          String userInput) {
        failAt(FailurePoint.BEGIN_TURN);
        delegate.beginTurn(turnId, sessionId, startedAt, userInput);
    }

    @Override
    public void markTurnStreaming(TurnId turnId, int stepNo) {
        failAt(FailurePoint.MARK_TURN_STREAMING);
        delegate.markTurnStreaming(turnId, stepNo);
    }

    @Override
    public StagedModelStep stageToolStep(TurnId turnId, int stepNo, ModelResponse response,
                                         int contextEstimatedTokens) {
        failAt(FailurePoint.STAGE_TOOL_STEP);
        return delegate.stageToolStep(turnId, stepNo, response, contextEstimatedTokens);
    }

    @Override
    public void markToolExecuting(StagedModelStep step, ToolCall call) {
        failAt(FailurePoint.MARK_TOOL_EXECUTING);
        delegate.markToolExecuting(step, call);
    }

    @Override
    public void recordToolResult(StagedModelStep step, ToolCall call, ToolResult result) {
        failAt(FailurePoint.RECORD_TOOL_RESULT);
        delegate.recordToolResult(step, call, result);
    }

    @Override
    public void commitToolStep(StagedModelStep step) {
        failAt(FailurePoint.COMMIT_TOOL_STEP);
        delegate.commitToolStep(step);
    }

    @Override
    public void completeTurn(TurnId turnId, int stepNo, ModelResponse response,
                             int contextEstimatedTokens, TurnDigest digest,
                             Instant finishedAt) {
        failAt(FailurePoint.COMPLETE_TURN);
        delegate.completeTurn(turnId, stepNo, response, contextEstimatedTokens, digest,
                finishedAt);
    }

    @Override
    public void failTurn(TurnId turnId, TurnStatus status, ErrorCode reason,
                         Instant finishedAt) {
        failAt(FailurePoint.FAIL_TURN);
        delegate.failTurn(turnId, status, reason, finishedAt);
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
