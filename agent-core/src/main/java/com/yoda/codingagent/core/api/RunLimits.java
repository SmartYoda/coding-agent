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

    public static final int MAX_RECENT_FULL_TURNS = 32;
    public static final RunLimits DEFAULTS = new RunLimits(
            20, Duration.ofSeconds(900), Duration.ofSeconds(120),
            Duration.ofSeconds(30), 20_000, 131_072, 8_192, 4);

    public RunLimits {
        if (maxSteps < 1 || maxSteps > 100) {
            throw new IllegalArgumentException("maxSteps must be between 1 and 100");
        }
        if (maxToolOutputChars < 1_024 || maxToolOutputChars > 200_000) {
            throw new IllegalArgumentException(
                    "maxToolOutputChars must be between 1024 and 200000");
        }
        if (maxInputTokens < 8_192 || maxInputTokens > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxInputTokens must be between 8192 and 1000000");
        }
        if (reservedOutputTokens < 512 || reservedOutputTokens > 200_000) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens must be between 512 and 200000");
        }
        if (recentFullTurns < 0 || recentFullTurns > MAX_RECENT_FULL_TURNS) {
            throw new IllegalArgumentException(
                    "recentFullTurns must be between 0 and " + MAX_RECENT_FULL_TURNS);
        }
        if (reservedOutputTokens >= maxInputTokens) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens must be smaller than maxInputTokens");
        }
        turnTimeout = requirePositive(turnTimeout, "turnTimeout");
        modelTimeout = requirePositive(modelTimeout, "modelTimeout");
        commandTimeout = requirePositive(commandTimeout, "commandTimeout");
        if (turnTimeout.compareTo(Duration.ofHours(1)) > 0
                || modelTimeout.compareTo(Duration.ofHours(1)) > 0
                || commandTimeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("run timeout exceeds its configured maximum");
        }
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
