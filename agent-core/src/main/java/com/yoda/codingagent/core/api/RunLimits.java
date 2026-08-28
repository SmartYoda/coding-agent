package com.yoda.codingagent.core.api;

import java.time.Duration;
import java.util.Objects;

public record RunLimits(
        int maxSteps,
        Duration turnTimeout,
        Duration modelTimeout,
        Duration commandTimeout,
        int maxToolOutputChars,
        int maxInputTokens,
        int reservedOutputTokens,
        int recentFullTurns
) {

    public RunLimits {
        if (maxSteps < 1 || maxToolOutputChars < 1 || maxInputTokens < 1
                || reservedOutputTokens < 1) {
            throw new IllegalArgumentException("run limits must be positive");
        }
        if (recentFullTurns < 0) {
            throw new IllegalArgumentException("recentFullTurns must not be negative");
        }
        if (reservedOutputTokens >= maxInputTokens) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens must be smaller than maxInputTokens");
        }
        turnTimeout = requirePositive(turnTimeout, "turnTimeout");
        modelTimeout = requirePositive(modelTimeout, "modelTimeout");
        commandTimeout = requirePositive(commandTimeout, "commandTimeout");
        if (modelTimeout.compareTo(turnTimeout) > 0
                || commandTimeout.compareTo(turnTimeout) > 0) {
            throw new IllegalArgumentException(
                    "modelTimeout and commandTimeout must not exceed turnTimeout");
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
