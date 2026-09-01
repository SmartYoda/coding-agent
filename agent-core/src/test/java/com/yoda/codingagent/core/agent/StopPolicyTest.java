package com.yoda.codingagent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StopPolicyTest {

    @Test
    void cancellationWinsOverDeadlineAndStepLimit() {
        Instant startedAt = Instant.parse("2026-08-30T00:00:00Z");
        AgentTurn turn = new AgentTurn(TurnId.random(), SessionId.random(), startedAt, false);
        turn.beginNextStep();
        RunLimits limits = new RunLimits(1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                1024, 8192, 512, 0);
        StopPolicy policy = new StopPolicy(Clock.fixed(
                startedAt.plusSeconds(2), ZoneOffset.UTC));

        StopPolicy.Decision cancelled = policy.evaluate(turn, limits, () -> true, true)
                .orElseThrow();
        StopPolicy.Decision timedOut = policy.evaluate(turn, limits, () -> false, true)
                .orElseThrow();

        assertEquals(ErrorCode.CANCELLED, cancelled.errorCode());
        assertEquals(ErrorCode.TURN_LIMIT, timedOut.errorCode());
        assertTrue(timedOut.safeMessage().contains("timed out"));
    }

    @Test
    void stepLimitAppliesOnlyBeforeStartingAnotherModelStep() {
        Instant startedAt = Instant.parse("2026-08-30T00:00:00Z");
        AgentTurn turn = new AgentTurn(TurnId.random(), SessionId.random(), startedAt, false);
        turn.beginNextStep();
        RunLimits limits = new RunLimits(1, Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(5),
                1024, 8192, 512, 0);
        StopPolicy policy = new StopPolicy(Clock.fixed(
                startedAt.plusSeconds(1), ZoneOffset.UTC));

        assertTrue(policy.evaluate(turn, limits, () -> false, false).isEmpty());
        assertEquals(ErrorCode.TURN_LIMIT,
                policy.evaluate(turn, limits, () -> false, true)
                        .orElseThrow().errorCode());
    }
}
