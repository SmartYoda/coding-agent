package com.yoda.codingagent.core.tool.process;

import java.time.Duration;
import java.util.Objects;

public record CommandResult(
        Integer exitCode,
        String stdout,
        String stderr,
        Duration duration,
        boolean timedOut,
        boolean cancelled,
        boolean truncated,
        String startError
) {
    public CommandResult {
        stdout = Objects.requireNonNull(stdout, "stdout");
        stderr = Objects.requireNonNull(stderr, "stderr");
        duration = Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (startError != null && exitCode != null) {
            throw new IllegalArgumentException("start failure cannot have an exit code");
        }
    }

    public boolean startFailed() {
        return startError != null;
    }
}
