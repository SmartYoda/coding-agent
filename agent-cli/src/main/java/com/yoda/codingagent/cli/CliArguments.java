package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.SessionId;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CliArguments(
        boolean help,
        String workspaceName,
        Path workspacePath,
        SessionId sessionId,
        Map<String, String> configOverrides
) {
    private static final Map<String, String> CONFIG_FLAGS = Map.ofEntries(
            Map.entry("--base-url", "baseUrl"),
            Map.entry("--model", "model"),
            Map.entry("--enable-thinking", "enableThinking"),
            Map.entry("--data-dir", "dataDirectory"),
            Map.entry("--max-steps", "maxSteps"),
            Map.entry("--turn-timeout-seconds", "turnTimeoutSeconds"),
            Map.entry("--model-timeout-seconds", "modelTimeoutSeconds"),
            Map.entry("--command-timeout-seconds", "commandTimeoutSeconds"),
            Map.entry("--max-tool-output-chars", "maxToolOutputChars"),
            Map.entry("--max-input-tokens", "maxInputTokens"),
            Map.entry("--reserved-output-tokens", "reservedOutputTokens"),
            Map.entry("--recent-full-turns", "recentFullTurns"));

    public CliArguments {
        configOverrides = Map.copyOf(configOverrides);
    }

    public static CliArguments parse(String[] args) {
        boolean help = false;
        String workspaceName = null;
        Path workspacePath = null;
        SessionId sessionId = null;
        Map<String, String> overrides = new LinkedHashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int index = 0; index < args.length; index++) {
            String flag = args[index];
            if (flag.equals("--help")) {
                if (!seen.add(flag)) {
                    throw new IllegalArgumentException("duplicate option: " + flag);
                }
                help = true;
                continue;
            }
            if (!seen.add(flag)) {
                throw new IllegalArgumentException("duplicate option: " + flag);
            }
            if (++index >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String value = args[index];
            if (value.isBlank()) {
                throw new IllegalArgumentException("blank value for " + flag);
            }
            if (flag.equals("--workspace")) {
                int separator = value.indexOf('=');
                if (separator < 1 || separator == value.length() - 1) {
                    throw new IllegalArgumentException(
                            "--workspace must use name=path");
                }
                workspaceName = value.substring(0, separator);
                if (workspaceName.isBlank() || workspaceName.length() > 64
                        || workspaceName.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("invalid workspace name");
                }
                workspacePath = Path.of(value.substring(separator + 1));
            } else if (flag.equals("--session")) {
                try {
                    sessionId = new SessionId(UUID.fromString(value));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("--session must be a UUID", exception);
                }
            } else {
                String key = CONFIG_FLAGS.get(flag);
                if (key == null) {
                    throw new IllegalArgumentException("unknown option: " + flag);
                }
                overrides.put(key, value);
            }
        }
        return new CliArguments(help, workspaceName, workspacePath, sessionId, overrides);
    }
}
