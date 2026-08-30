package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.time.Duration;
import java.util.Objects;

public final class ModelRetryPolicy {

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    public Decision evaluate(int attempt, AgentException failure,
                             boolean semanticDeltaSeen, boolean cancelled,
                             Duration remaining) {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempt must be between 1 and 3");
        }
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(remaining, "remaining");
        if (cancelled || semanticDeltaSeen || attempt == MAX_ATTEMPTS
                || !retryable(failure.errorCode())) {
            return Decision.stop(false);
        }
        if (remaining.isZero() || remaining.isNegative()) {
            return Decision.stop(true);
        }
        Duration delay = failure.retryAfter() == null
                ? Duration.ofSeconds(attempt)
                : min(failure.retryAfter(), MAX_RETRY_AFTER);
        if (delay.compareTo(remaining) >= 0) {
            return Decision.stop(true);
        }
        return Decision.retry(attempt + 1, delay);
    }

    private static boolean retryable(ErrorCode code) {
        return code == ErrorCode.MODEL_UNAVAILABLE || code == ErrorCode.MODEL_RATE_LIMIT;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    public record Decision(boolean retry, int nextAttempt, Duration delay,
                           boolean deadlineExhausted) {

        public Decision {
            Objects.requireNonNull(delay, "delay");
            if (retry) {
                if (nextAttempt < 2 || nextAttempt > MAX_ATTEMPTS
                        || delay.isNegative() || deadlineExhausted) {
                    throw new IllegalArgumentException("invalid retry decision");
                }
            } else if (nextAttempt != 0 || !delay.isZero()) {
                throw new IllegalArgumentException("stop decision cannot carry a retry");
            }
        }

        private static Decision retry(int nextAttempt, Duration delay) {
            return new Decision(true, nextAttempt, delay, false);
        }

        private static Decision stop(boolean deadlineExhausted) {
            return new Decision(false, 0, Duration.ZERO, deadlineExhausted);
        }
    }
}
