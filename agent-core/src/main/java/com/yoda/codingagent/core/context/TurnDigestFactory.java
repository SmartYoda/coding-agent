package com.yoda.codingagent.core.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TurnDigestFactory {

    public TurnDigest create(CanonicalHistory.TurnHistory turn) {
        Message.UserMessage user = (Message.UserMessage) turn.messages().getFirst();
        Message.AssistantMessage assistant =
                (Message.AssistantMessage) turn.messages().getLast();
        Set<String> filesRead = new LinkedHashSet<>();
        Set<String> filesModified = new LinkedHashSet<>();
        Set<String> commands = new LinkedHashSet<>();
        for (Message message : turn.messages()) {
            if (message instanceof Message.AssistantToolCallsMessage calls) {
                for (ToolCall call : calls.toolCalls()) {
                    collect(call, filesRead, filesModified, commands);
                }
            }
        }
        return new TurnDigest(turn.turnId(), truncate(user.content(), TurnDigest.TEXT_LIMIT),
                TurnStatus.COMPLETED,
                truncate(assistant.content(), TurnDigest.TEXT_LIMIT),
                limited(filesRead), limited(filesModified), limited(commands),
                List.of(), List.of());
    }

    private static void collect(ToolCall call, Set<String> filesRead,
                                Set<String> filesModified, Set<String> commands) {
        switch (call.name()) {
            case "read_file" -> addPath(call, filesRead);
            case "write_file", "replace_in_file" -> addPath(call, filesModified);
            case "run_command" -> {
                JsonNode argv = call.arguments().path("argv");
                String command = argv.isArray() ? argv.toString()
                        : call.arguments().path("command").asText("");
                if (!command.isBlank()) {
                    commands.add(truncate(command, TurnDigest.ITEM_LIMIT));
                }
            }
            default -> {
                // Other tools do not contribute to these deterministic digest fields.
            }
        }
    }

    private static void addPath(ToolCall call, Set<String> target) {
        String path = call.arguments().path("path").asText("");
        if (!path.isBlank()) {
            target.add(truncate(path, TurnDigest.ITEM_LIMIT));
        }
    }

    private static List<String> limited(Set<String> values) {
        List<String> result = new ArrayList<>(
                Math.min(values.size(), TurnDigest.MAX_ITEMS));
        for (String value : values) {
            if (result.size() == TurnDigest.MAX_ITEMS) {
                break;
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static String truncate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum - 1) + "…";
    }
}
