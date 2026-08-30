package com.yoda.codingagent.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ModelRetryPolicyTest {

    private final ModelRetryPolicy policy = new ModelRetryPolicy();

    @Test
    void retriesOnlyTransientFailuresWithoutSemanticDelta() {
        ModelRetryPolicy.Decision unavailable = policy.evaluate(1,
                failure(ErrorCode.MODEL_UNAVAILABLE), false, false,
                Duration.ofSeconds(10));
        assertTrue(unavailable.retry());
        assertEquals(2, unavailable.nextAttempt());
        assertEquals(Duration.ofSeconds(1), unavailable.delay());

        ModelRetryPolicy.Decision rateLimit = policy.evaluate(2,
                new AgentException(ErrorCode.MODEL_RATE_LIMIT, "rate limited",
                        Duration.ofMillis(250)), false, false, Duration.ofSeconds(10));
        assertTrue(rateLimit.retry());
        assertEquals(3, rateLimit.nextAttempt());
        assertEquals(Duration.ofMillis(250), rateLimit.delay());

        assertFalse(policy.evaluate(1, failure(ErrorCode.MODEL_UNAVAILABLE),
                true, false, Duration.ofSeconds(10)).retry());
        assertFalse(policy.evaluate(1, failure(ErrorCode.MODEL_PROTOCOL_ERROR),
                false, false, Duration.ofSeconds(10)).retry());
        assertFalse(policy.evaluate(3, failure(ErrorCode.MODEL_UNAVAILABLE),
                false, false, Duration.ofSeconds(10)).retry());
    }

    @Test
    void capsRetryAfterAndStopsWhenDelayConsumesDeadline() {
        ModelRetryPolicy.Decision capped = policy.evaluate(1,
                new AgentException(ErrorCode.MODEL_RATE_LIMIT, "rate limited",
                        Duration.ofMinutes(5)), false, false, Duration.ofSeconds(31));
        assertTrue(capped.retry());
        assertEquals(Duration.ofSeconds(30), capped.delay());

        ModelRetryPolicy.Decision exhausted = policy.evaluate(1,
                failure(ErrorCode.MODEL_UNAVAILABLE), false, false,
                Duration.ofSeconds(1));
        assertFalse(exhausted.retry());
        assertTrue(exhausted.deadlineExhausted());
    }

    @Test
    void cancellationAndModelTimeoutAreNeverRetried() {
        assertFalse(policy.evaluate(1, failure(ErrorCode.MODEL_UNAVAILABLE),
                false, true, Duration.ofSeconds(10)).retry());
        assertFalse(policy.evaluate(1, failure(ErrorCode.MODEL_TIMEOUT),
                false, false, Duration.ofSeconds(10)).retry());
    }

    @Test
    void defaultWaiterObservesCancellationWithoutSleepingTheFullDelay() {
        AgentException cancelled = assertThrows(AgentException.class,
                () -> RetryWaiter.cancellableSleep().await(
                        Duration.ofSeconds(10), () -> true));
        assertEquals(ErrorCode.CANCELLED, cancelled.errorCode());
    }

    private static AgentException failure(ErrorCode code) {
        return new AgentException(code, "safe failure");
    }
}
