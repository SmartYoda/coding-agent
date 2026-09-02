package com.yoda.codingagent.core.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CommandApprovalRequest(
        String approvalId,
        WorkspaceId workspaceId,
        TurnId turnId,
        String callId,
        List<String> argv,
        Path cwd,
        Instant deadline) {

    public CommandApprovalRequest {
        requireText(approvalId, "approvalId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(turnId, "turnId");
        requireText(callId, "callId");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("argv must contain non-empty values");
        }
        cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Objects.requireNonNull(deadline, "deadline");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
