package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.api.RunLimits;
import java.util.Objects;

public record ContextBudgetPolicy(
        int maxInputTokens,
        int reservedOutputTokens,
        int recentFullTurns
) {

    public ContextBudgetPolicy {
        if (maxInputTokens < 1 || reservedOutputTokens < 1
                || reservedOutputTokens >= maxInputTokens) {
            throw new IllegalArgumentException("invalid context token budget");
        }
        if (recentFullTurns < 0 || recentFullTurns > RunLimits.MAX_RECENT_FULL_TURNS) {
            throw new IllegalArgumentException("recentFullTurns must be between 0 and "
                    + RunLimits.MAX_RECENT_FULL_TURNS);
        }
    }

    public static ContextBudgetPolicy from(RunLimits limits) {
        Objects.requireNonNull(limits, "limits");
        return new ContextBudgetPolicy(limits.maxInputTokens(),
                limits.reservedOutputTokens(), limits.recentFullTurns());
    }
}
