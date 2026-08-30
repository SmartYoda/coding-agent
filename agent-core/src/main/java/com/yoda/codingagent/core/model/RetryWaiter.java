package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
public interface RetryWaiter {

    void await(Duration delay, CancellationToken cancellationToken);

    static RetryWaiter cancellableSleep() {
        return (delay, cancellationToken) -> {
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(cancellationToken, "cancellationToken");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
            long remainingNanos = delay.toNanos();
            long deadline = System.nanoTime() + remainingNanos;
            while (remainingNanos > 0) {
                if (cancellationToken.isCancelled()) {
                    throw new AgentException(ErrorCode.CANCELLED,
                            "model retry cancelled");
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(
                            remainingNanos, Duration.ofMillis(25).toNanos()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AgentException(ErrorCode.CANCELLED,
                            "model retry interrupted", exception);
                }
                remainingNanos = deadline - System.nanoTime();
            }
            if (cancellationToken.isCancelled()) {
                throw new AgentException(ErrorCode.CANCELLED, "model retry cancelled");
            }
        };
    }
}
