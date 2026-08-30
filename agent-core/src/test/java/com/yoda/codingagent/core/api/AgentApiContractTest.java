package com.yoda.codingagent.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AgentApiContractTest {

    private final WorkspaceId workspaceId = WorkspaceId.random();
    private final SessionId sessionId = SessionId.random();
    private final TurnId turnId = TurnId.random();

    @Test
    void completedAndFailedResultsEnforceTerminalPayloadAndMetrics() {
        AgentResult completed = AgentResult.completed(workspaceId, sessionId, turnId,
                "done", 2, 3, Duration.ofSeconds(4));
        assertEquals(workspaceId, completed.workspaceId());
        assertEquals(sessionId, completed.sessionId());
        assertEquals(2, completed.stepCount());
        assertEquals(3, completed.toolCallCount());

        assertThrows(IllegalArgumentException.class, () -> AgentResult.completed(
                workspaceId, sessionId, turnId, " ", 1, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.RUNNING,
                ErrorCode.INTERNAL_ERROR, "failed", 0, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.FAILED,
                ErrorCode.INTERNAL_ERROR, "failed", -1, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.FAILED,
                ErrorCode.INTERNAL_ERROR, "failed", 0, 0, Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.CANCELLED,
                ErrorCode.INTERNAL_ERROR, "failed", 0, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.LIMIT_REACHED,
                ErrorCode.INTERNAL_ERROR, "failed", 0, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.FAILED,
                ErrorCode.CANCELLED, "failed", 0, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AgentResult.failed(
                workspaceId, sessionId, turnId, TurnStatus.INTERRUPTED,
                ErrorCode.STORAGE_ERROR, "failed", 0, 0, Duration.ZERO));
    }

    @Test
    void toolCallDeltaCannotExposeProviderIdentifiersOrArguments() {
        Set<String> componentNames = Arrays.stream(
                        AgentEvent.ModelToolCallDelta.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("workspaceId", "sessionId", "turnId", "sequence", "timestamp",
                "index", "argumentDeltaCharacters"), componentNames);
        assertFalse(componentNames.contains("callId"));
        assertFalse(componentNames.contains("name"));
        assertFalse(componentNames.contains("arguments"));
    }

    @Test
    void eventRecordsRejectInvalidClassificationAndUnboundedCompaction() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new AgentEvent.TurnFailed(
                workspaceId, sessionId, turnId, 1, now,
                ErrorCode.CANCELLED, "cancelled"));
        assertThrows(IllegalArgumentException.class, () -> new AgentEvent.TurnLimitReached(
                workspaceId, sessionId, turnId, 1, now,
                ErrorCode.INTERNAL_ERROR, "failed"));
        assertThrows(IllegalArgumentException.class, () -> new AgentEvent.ModelToolCallDelta(
                workspaceId, sessionId, turnId, 1, now, 0, 0));

        List<TurnId> tooMany = java.util.stream.IntStream.rangeClosed(
                        1, AgentEvent.MAX_REPORTED_COMPACTION_TURNS + 1)
                .mapToObj(ignored -> TurnId.random())
                .toList();
        assertThrows(IllegalArgumentException.class, () -> new AgentEvent.ContextCompacted(
                workspaceId, sessionId, turnId, 1, now, tooMany, List.of(),
                0, 100, 50, "TOKEN_BUDGET"));
    }

    @Test
    void contextBudgetAndSummaryRejectContradictoryCounts() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> new AgentEvent.ContextBudgetEvaluated(
                        workspaceId, sessionId, turnId, 1, now,
                        1, 2, 3, 4, 5, 14, 1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionContextSummary(sessionId, workspaceId,
                        RunLimits.DEFAULTS, 1, 2));
    }
}
