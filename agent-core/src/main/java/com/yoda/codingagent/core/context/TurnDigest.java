package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import java.util.List;
import java.util.Objects;

public record TurnDigest(
        TurnId turnId,
        String userGoal,
        TurnStatus status,
        String finalSummary,
        List<String> filesRead,
        List<String> filesModified,
        List<String> commands,
        List<String> importantErrors,
        List<String> openItems
) {

    public TurnDigest {
        Objects.requireNonNull(turnId, "turnId");
        userGoal = requireText(userGoal, "userGoal");
        Objects.requireNonNull(status, "status");
        if (status == TurnStatus.CREATED || status == TurnStatus.RUNNING
                || status == TurnStatus.STREAMING_MODEL
                || status == TurnStatus.EXECUTING_TOOL) {
            throw new IllegalArgumentException("digest status must be terminal");
        }
        finalSummary = Objects.requireNonNull(finalSummary, "finalSummary");
        filesRead = copy(filesRead, "filesRead");
        filesModified = copy(filesModified, "filesModified");
        commands = copy(commands, "commands");
        importantErrors = copy(importantErrors, "importantErrors");
        openItems = copy(openItems, "openItems");
    }

    public String toContextText() {
        return """
                Completed turn summary:
                Goal: %s
                Status: %s
                Final: %s
                Files read: %s
                Files modified: %s
                Commands: %s
                Important errors: %s
                Open items: %s
                """.formatted(userGoal, status, finalSummary,
                render(filesRead), render(filesModified), render(commands),
                render(importantErrors), render(openItems));
    }

    private static List<String> copy(List<String> values, String name) {
        List<String> copied = List.copyOf(Objects.requireNonNull(values, name));
        if (copied.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must not contain blank values");
        }
        return copied;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String render(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }
}
