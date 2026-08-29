package com.yoda.codingagent.core.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.tool.ToolStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TurnDigestFactory {

    public TurnDigest create(CanonicalHistory.TurnHistory turn) {
        if (!(turn.messages().getFirst() instanceof Message.UserMessage user)
                || !(turn.messages().getLast() instanceof Message.AssistantMessage assistant)) {
            throw new IllegalArgumentException(
                    "completed turn must start with user and end with assistant");
        }
        Set<String> filesRead = new LinkedHashSet<>();
        Set<String> filesModified = new LinkedHashSet<>();
        List<String> commands = new ArrayList<>();
        List<String> importantErrors = new ArrayList<>();
        Map<String, ToolResult> results = new LinkedHashMap<>();
        for (Message message : turn.messages()) {
            if (message instanceof Message.ToolResultMessage resultMessage
                    && results.putIfAbsent(resultMessage.callId(), resultMessage.result()) != null) {
                throw new IllegalArgumentException("duplicate tool result callId in turn");
            }
        }
        Set<String> callsSeen = new LinkedHashSet<>();
        for (Message message : turn.messages()) {
            if (message instanceof Message.AssistantToolCallsMessage calls) {
                for (ToolCall call : calls.toolCalls()) {
                    if (!callsSeen.add(call.callId())) {
                        throw new IllegalArgumentException("duplicate tool callId in turn");
                    }
                    ToolResult result = results.remove(call.callId());
                    if (result == null) {
                        throw new IllegalArgumentException("tool call has no matching result");
                    }
                    collect(call, result, filesRead, filesModified,
                            commands, importantErrors);
                }
            }
        }
        if (!results.isEmpty()) {
            throw new IllegalArgumentException("orphan tool result in turn");
        }
        return new TurnDigest(turn.turnId(), truncate(user.content(), TurnDigest.TEXT_LIMIT),
                TurnStatus.COMPLETED,
                truncate(assistant.content(), TurnDigest.TEXT_LIMIT),
                limited(filesRead), limited(filesModified), limited(commands),
                limited(importantErrors), List.of());
    }

    private static void collect(ToolCall call, ToolResult result,
                                Set<String> filesRead, Set<String> filesModified,
                                List<String> commands, List<String> importantErrors) {
        switch (call.name()) {
            case "read_file" -> {
                if (result.status() == ToolStatus.SUCCESS) {
                    addPath(call, filesRead);
                }
            }
            case "write_file", "replace_in_file" -> {
                if (result.status() == ToolStatus.SUCCESS) {
                    addPath(call, filesModified);
                }
            }
            case "execute_command" -> {
                JsonNode argv = call.arguments().path("argv");
                if (argv.isArray()) {
                    StringBuilder command = new StringBuilder(argv.toString())
                            .append(" status=").append(result.status());
                    String exitCode = result.metadata().get("exitCode");
                    if (exitCode != null) {
                        command.append(" exitCode=").append(exitCode);
                    }
                    addLimited(commands, command.toString());
                }
            }
            default -> {
                // Other tools do not contribute to these deterministic digest fields.
            }
        }
        if (result.status() != ToolStatus.SUCCESS) {
            String errorCode = result.errorCode() == null
                    ? "UNKNOWN" : result.errorCode().name();
            addLimited(importantErrors, call.name() + "(" + call.callId() + "): "
                    + errorCode + " " + result.output());
        }
    }

    private static void addPath(ToolCall call, Set<String> target) {
        String path = call.arguments().path("path").asText("");
        if (!path.isBlank()) {
            target.add(truncate(path, TurnDigest.ITEM_LIMIT));
        }
    }

    private static List<String> limited(Iterable<String> values) {
        List<String> result = new ArrayList<>(
                TurnDigest.MAX_ITEMS);
        for (String value : values) {
            if (result.size() == TurnDigest.MAX_ITEMS) {
                break;
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void addLimited(List<String> target, String value) {
        if (target.size() < TurnDigest.MAX_ITEMS && value != null && !value.isBlank()) {
            target.add(truncate(value, TurnDigest.ITEM_LIMIT));
        }
    }

    private static String truncate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum - 1) + "…";
    }
}
