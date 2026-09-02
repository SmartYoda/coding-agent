package com.yoda.codingagent.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.tool.ToolCall;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

final class ToolEventDetailFactory {

    private static final int MAX_CHARACTERS = 100;
    private static final Set<String> ACTION_COMMANDS = Set.of("git", "mvn", "mvnw");

    private ToolEventDetailFactory() { }

    static String create(ToolCall call, UnaryOperator<String> redactor) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(redactor, "redactor");
        ObjectNode arguments = call.arguments();
        String detail = switch (call.name()) {
            case "list_files" -> text(arguments, "path", ".");
            case "read_file", "write_file", "replace_in_file" ->
                    text(arguments, "path", "");
            case "search_text" -> searchDetail(arguments);
            case "execute_command" -> commandDetail(arguments.path("argv"));
            default -> "";
        };
        return truncate(singleLine(redactor.apply(detail)));
    }

    private static String searchDetail(ObjectNode arguments) {
        String query = text(arguments, "query", "");
        String path = text(arguments, "path", ".");
        if (query.isEmpty()) {
            return path;
        }
        return query + " @ " + path;
    }

    private static String commandDetail(JsonNode argv) {
        if (!argv.isArray() || argv.isEmpty()) {
            return "";
        }
        String command = argv.get(0).asText("");
        String executable = executableName(command);
        if (argv.size() < 2 || !ACTION_COMMANDS.contains(executable)) {
            return executable;
        }
        String action = argv.get(1).asText("");
        return action.matches("[A-Za-z][A-Za-z0-9:_-]{0,31}")
                ? executable + " " + action : executable;
    }

    private static String executableName(String command) {
        int separator = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        return separator >= 0 ? command.substring(separator + 1) : command;
    }

    private static String text(ObjectNode arguments, String field, String fallback) {
        JsonNode value = arguments.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : fallback;
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAX_CHARACTERS - 1) + "…";
    }
}
