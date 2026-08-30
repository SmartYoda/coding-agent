package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.TurnStatus;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public final class StopPolicy {

    private final Clock clock;

    public StopPolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<Decision> evaluate(AgentTurn turn, RunLimits limits,
                                       CancellationToken cancellationToken,
                                       boolean beforeModelStep) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (cancellationToken.isCancelled()) {
            return Optional.of(new Decision(TurnStatus.CANCELLED, ErrorCode.CANCELLED,
                    "agent turn cancelled"));
        }
        if (!clock.instant().isBefore(turn.startedAt().plus(limits.turnTimeout()))) {
            return Optional.of(new Decision(TurnStatus.LIMIT_REACHED, ErrorCode.TURN_LIMIT,
                    "agent turn timed out"));
        }
        if (beforeModelStep && turn.stepCount() >= limits.maxSteps()) {
            return Optional.of(new Decision(TurnStatus.LIMIT_REACHED, ErrorCode.TURN_LIMIT,
                    "agent reached the maximum number of model steps"));
        }
        return Optional.empty();
    }

    public record Decision(TurnStatus status, ErrorCode errorCode, String safeMessage) {
        public Decision {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(errorCode, "errorCode");
            if (safeMessage == null || safeMessage.isBlank()) {
                throw new IllegalArgumentException("safeMessage must not be blank");
            }
            if (status != TurnStatus.CANCELLED && status != TurnStatus.LIMIT_REACHED) {
                throw new IllegalArgumentException("stop policy produced an invalid status");
            }
        }
    }
}
