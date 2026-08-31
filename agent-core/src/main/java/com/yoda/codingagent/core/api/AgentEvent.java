package com.yoda.codingagent.core.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public sealed interface AgentEvent permits AgentEvent.TurnStarted,
        AgentEvent.ModelRequestStarted, AgentEvent.ModelTextDelta,
        AgentEvent.ModelToolCallDelta, AgentEvent.ModelRequestCompleted,
        AgentEvent.RetryScheduled, AgentEvent.ToolStarted, AgentEvent.ToolCompleted,
        AgentEvent.ContextBudgetEvaluated, AgentEvent.ContextCompacted,
        AgentEvent.TurnDigestCreated, AgentEvent.TurnCompleted,
        AgentEvent.TurnFailed, AgentEvent.TurnCancelled,
        AgentEvent.TurnLimitReached {

    int MAX_REPORTED_COMPACTION_TURNS = 32;

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
        public ModelRequestStarted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requirePositive(step, "step");
        }
    }

    record ModelTextDelta(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                          long sequence, Instant timestamp, String text) implements AgentEvent {
        public ModelTextDelta {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            Objects.requireNonNull(text, "text");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("text must not be empty");
            }
        }
    }

    record ModelToolCallDelta(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                              long sequence, Instant timestamp, int index,
                              int argumentDeltaCharacters) implements AgentEvent {
        public ModelToolCallDelta {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requireNonNegative(index, "index");
            requirePositive(argumentDeltaCharacters, "argumentDeltaCharacters");
        }
    }

    record ModelRequestCompleted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                                 long sequence, Instant timestamp, int step,
                                 String finishReason) implements AgentEvent {
        public ModelRequestCompleted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requirePositive(step, "step");
            requireText(finishReason, "finishReason");
        }
    }

    record RetryScheduled(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                          long sequence, Instant timestamp, int nextAttempt,
                          int maxAttempts, long delayMillis,
                          ErrorCode errorCode) implements AgentEvent {
        public RetryScheduled {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            if (nextAttempt < 2 || maxAttempts < nextAttempt) {
                throw new IllegalArgumentException("invalid retry attempt range");
            }
            requireNonNegative(delayMillis, "delayMillis");
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    record ToolStarted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                       long sequence, Instant timestamp, String callId,
                       String toolName) implements AgentEvent {
        public ToolStarted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requireText(callId, "callId");
            requireText(toolName, "toolName");
        }
    }

    record ToolCompleted(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                         long sequence, Instant timestamp, String callId,
                         String toolName, boolean success) implements AgentEvent {
        public ToolCompleted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requireText(callId, "callId");
            requireText(toolName, "toolName");
        }
    }

    record ContextBudgetEvaluated(
            WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
            long sequence, Instant timestamp, int fixedTokens, int toolTokens,
            int currentTokens, int recentTokens, int digestTokens,
            int estimatedInputTokens, int reservedOutputTokens, int maxInputTokens)
            implements AgentEvent {
        public ContextBudgetEvaluated {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requireNonNegative(fixedTokens, "fixedTokens");
            requireNonNegative(toolTokens, "toolTokens");
            requireNonNegative(currentTokens, "currentTokens");
            requireNonNegative(recentTokens, "recentTokens");
            requireNonNegative(digestTokens, "digestTokens");
            requireNonNegative(estimatedInputTokens, "estimatedInputTokens");
            requireNonNegative(reservedOutputTokens, "reservedOutputTokens");
            requirePositive(maxInputTokens, "maxInputTokens");
            long calculated = (long) fixedTokens + toolTokens + currentTokens
                    + recentTokens + digestTokens;
            if (calculated != estimatedInputTokens
                    || calculated + reservedOutputTokens > maxInputTokens) {
                throw new IllegalArgumentException("inconsistent context budget");
            }
        }
    }

    record ContextCompacted(
            WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
            long sequence, Instant timestamp, List<TurnId> fullTurnIds,
            List<TurnId> digestTurnIds, int omittedTurnCount,
            int estimatedTokensBefore, int estimatedTokensAfter, String reason)
            implements AgentEvent {
        public ContextCompacted {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            fullTurnIds = boundedDistinctIds(fullTurnIds, "fullTurnIds");
            digestTurnIds = boundedDistinctIds(digestTurnIds, "digestTurnIds");
            HashSet<TurnId> overlap = new HashSet<>(fullTurnIds);
            overlap.retainAll(digestTurnIds);
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException("a turn cannot be both full and digested");
            }
            requireNonNegative(omittedTurnCount, "omittedTurnCount");
            requireNonNegative(estimatedTokensBefore, "estimatedTokensBefore");
            requireNonNegative(estimatedTokensAfter, "estimatedTokensAfter");
            if (estimatedTokensAfter > estimatedTokensBefore) {
                throw new IllegalArgumentException("compaction cannot increase estimated tokens");
            }
            if (!"TOKEN_BUDGET".equals(reason)) {
                throw new IllegalArgumentException("unknown compaction reason");
            }
        }
    }

    record TurnDigestCreated(
            WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
            long sequence, Instant timestamp, int filesModified,
            int commandsExecuted, int importantErrors) implements AgentEvent {
        public TurnDigestCreated {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            requireNonNegative(filesModified, "filesModified");
            requireNonNegative(commandsExecuted, "commandsExecuted");
            requireNonNegative(importantErrors, "importantErrors");
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
            if (errorCode == ErrorCode.CANCELLED || errorCode == ErrorCode.TURN_LIMIT
                    || errorCode == ErrorCode.CONTEXT_LIMIT) {
                throw new IllegalArgumentException("error requires a classified terminal event");
            }
            requireText(safeMessage, "safeMessage");
        }
    }

    record TurnCancelled(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                         long sequence, Instant timestamp) implements AgentEvent {
        public TurnCancelled { validate(workspaceId, sessionId, turnId, sequence, timestamp); }
    }

    record TurnLimitReached(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                            long sequence, Instant timestamp, ErrorCode errorCode,
                            String safeMessage) implements AgentEvent {
        public TurnLimitReached {
            validate(workspaceId, sessionId, turnId, sequence, timestamp);
            if (errorCode != ErrorCode.TURN_LIMIT && errorCode != ErrorCode.CONTEXT_LIMIT) {
                throw new IllegalArgumentException("limit event requires a limit error code");
            }
            requireText(safeMessage, "safeMessage");
        }
    }

    private static void validate(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                                 long sequence, Instant timestamp) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(timestamp, "timestamp");
        requirePositive(sequence, "sequence");
    }

    private static List<TurnId> boundedDistinctIds(List<TurnId> ids, String name) {
        List<TurnId> copy = List.copyOf(Objects.requireNonNull(ids, name));
        if (copy.size() > MAX_REPORTED_COMPACTION_TURNS
                || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " must be bounded and distinct");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
