package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.tool.ToolCall;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentTurn {

    private final TurnId turnId;
    private final SessionId sessionId;
    private final Instant startedAt;
    private final Set<String> acceptedToolCallIds = new HashSet<>();
    private int stepCount;
    private int toolCallCount;
    private TerminalSnapshot terminal;

    public AgentTurn(TurnId turnId, SessionId sessionId, Instant startedAt) {
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public TurnId turnId() { return turnId; }

    public SessionId sessionId() { return sessionId; }

    public Instant startedAt() { return startedAt; }

    public int stepCount() { return stepCount; }

    public int nextStepNo() {
        requireRunning();
        return Math.addExact(stepCount, 1);
    }

    public int beginNextStep() {
        requireRunning();
        return ++stepCount;
    }

    public int toolCallCount() { return toolCallCount; }

    public int beginToolCall() {
        requireRunning();
        return ++toolCallCount;
    }

    public void registerAllToolCallIdsOrThrow(List<ToolCall> calls) {
        requireRunning();
        List<ToolCall> copy = List.copyOf(Objects.requireNonNull(calls, "calls"));
        Set<String> group = new HashSet<>();
        for (ToolCall call : copy) {
            String callId = Objects.requireNonNull(call, "call").callId();
            if (!group.add(callId) || acceptedToolCallIds.contains(callId)) {
                throw new IllegalArgumentException("model reused a tool call id in the same turn");
            }
        }
        acceptedToolCallIds.addAll(group);
    }

    public TerminalSnapshot prepareCompletion(Instant finishedAt) {
        return prepare(TurnStatus.COMPLETED, null, null, finishedAt);
    }

    public TerminalSnapshot prepareFailure(TurnStatus status, ErrorCode errorCode,
                                           String safeMessage, Instant finishedAt) {
        if (status == TurnStatus.COMPLETED) {
            throw new IllegalArgumentException("failure snapshot cannot be completed");
        }
        return prepare(status, Objects.requireNonNull(errorCode, "errorCode"),
                safeMessage, finishedAt);
    }

    public void sealTerminal(TerminalSnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (terminal != null) {
            throw new IllegalStateException("turn terminal snapshot is already sealed");
        }
        if (!candidate.turnId().equals(turnId)
                || !candidate.sessionId().equals(sessionId)
                || !candidate.startedAt().equals(startedAt)
                || candidate.stepCount() != stepCount
                || candidate.toolCallCount() != toolCallCount) {
            throw new IllegalArgumentException("terminal snapshot does not match this turn");
        }
        terminal = candidate;
    }

    public TerminalSnapshot terminalSnapshot() {
        if (terminal == null) {
            throw new IllegalStateException("turn terminal snapshot is not sealed");
        }
        return terminal;
    }

    private TerminalSnapshot prepare(TurnStatus status, ErrorCode errorCode,
                                     String safeMessage, Instant finishedAt) {
        requireRunning();
        return new TerminalSnapshot(turnId, sessionId, status, errorCode, safeMessage,
                startedAt, Objects.requireNonNull(finishedAt, "finishedAt"),
                stepCount, toolCallCount);
    }

    private void requireRunning() {
        if (terminal != null) {
            throw new IllegalStateException("turn is already terminal");
        }
    }

    public record TerminalSnapshot(
            TurnId turnId,
            SessionId sessionId,
            TurnStatus status,
            ErrorCode errorCode,
            String safeMessage,
            Instant startedAt,
            Instant finishedAt,
            int stepCount,
            int toolCallCount,
            Duration duration) {

        public TerminalSnapshot(TurnId turnId, SessionId sessionId, TurnStatus status,
                                ErrorCode errorCode, String safeMessage,
                                Instant startedAt, Instant finishedAt,
                                int stepCount, int toolCallCount) {
            this(turnId, sessionId, status, errorCode, safeMessage, startedAt, finishedAt,
                    stepCount, toolCallCount, Duration.between(startedAt, finishedAt));
        }

        public TerminalSnapshot {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            Objects.requireNonNull(duration, "duration");
            if (status != TurnStatus.COMPLETED && status != TurnStatus.FAILED
                    && status != TurnStatus.CANCELLED && status != TurnStatus.LIMIT_REACHED
                    && status != TurnStatus.INTERRUPTED) {
                throw new IllegalArgumentException("snapshot requires a terminal status");
            }
            if (stepCount < 0 || toolCallCount < 0 || duration.isNegative()
                    || !Duration.between(startedAt, finishedAt).equals(duration)) {
                throw new IllegalArgumentException("invalid terminal timing or counters");
            }
            if (status == TurnStatus.COMPLETED) {
                if (errorCode != null || safeMessage != null) {
                    throw new IllegalArgumentException("completed snapshot cannot contain error");
                }
            } else if (errorCode == null || safeMessage == null || safeMessage.isBlank()) {
                throw new IllegalArgumentException("failed snapshot requires a safe error");
            } else {
                boolean limitError = errorCode == ErrorCode.TURN_LIMIT
                        || errorCode == ErrorCode.CONTEXT_LIMIT;
                if (status == TurnStatus.CANCELLED && errorCode != ErrorCode.CANCELLED
                        || status == TurnStatus.LIMIT_REACHED && !limitError
                        || status == TurnStatus.FAILED
                        && (errorCode == ErrorCode.CANCELLED || limitError)
                        || status == TurnStatus.INTERRUPTED
                        && errorCode != ErrorCode.INTERNAL_ERROR) {
                    throw new IllegalArgumentException(
                            "terminal status and error code do not match");
                }
            }
        }
    }
}
