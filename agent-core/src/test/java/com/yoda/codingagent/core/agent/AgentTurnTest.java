package com.yoda.codingagent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.tool.ToolCall;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTurnTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersToolCallGroupsAtomicallyAcrossSteps() {
        AgentTurn turn = turn();
        ToolCall first = call("first");
        ToolCall repeated = call("first");
        ToolCall newCall = call("second");

        turn.registerAllToolCallIdsOrThrow(List.of(first));
        assertThrows(IllegalArgumentException.class,
                () -> turn.registerAllToolCallIdsOrThrow(List.of(newCall, repeated)));
        turn.registerAllToolCallIdsOrThrow(List.of(newCall));
    }

    @Test
    void sealsExactlyOneMatchingTerminalSnapshot() {
        AgentTurn turn = turn();
        turn.beginNextStep();
        turn.beginToolCall();
        Instant finishedAt = turn.startedAt().plusSeconds(3);
        AgentTurn.TerminalSnapshot candidate = turn.prepareFailure(
                TurnStatus.FAILED, ErrorCode.INTERNAL_ERROR, "failed", finishedAt);

        turn.sealTerminal(candidate);

        assertEquals(candidate, turn.terminalSnapshot());
        assertEquals(Duration.ofSeconds(3), candidate.duration());
        assertEquals(1, candidate.stepCount());
        assertEquals(1, candidate.toolCallCount());
        assertThrows(IllegalStateException.class, () -> turn.sealTerminal(candidate));
        assertThrows(IllegalStateException.class, turn::beginNextStep);
    }

    @Test
    void rejectsContradictoryTerminalClassification() {
        AgentTurn turn = turn();
        Instant finishedAt = turn.startedAt().plusSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> turn.prepareFailure(
                TurnStatus.CANCELLED, ErrorCode.INTERNAL_ERROR, "failed", finishedAt));
        assertThrows(IllegalArgumentException.class, () -> turn.prepareFailure(
                TurnStatus.LIMIT_REACHED, ErrorCode.INTERNAL_ERROR, "failed", finishedAt));
        assertThrows(IllegalArgumentException.class, () -> turn.prepareFailure(
                TurnStatus.FAILED, ErrorCode.CONTEXT_LIMIT, "failed", finishedAt));
    }

    private AgentTurn turn() {
        return new AgentTurn(TurnId.random(), SessionId.random(),
                Instant.parse("2026-08-30T00:00:00Z"), false);
    }

    private ToolCall call(String id) {
        return new ToolCall(id, "test", objectMapper.createObjectNode());
    }
}
