package com.yoda.codingagent.core.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import com.yoda.codingagent.core.api.RunLimits;

public final class AgentConfigLoader {

    public static final String DEFAULT_MODEL = "qwen3.8-flash";
    public static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public AgentConfig load(Map<String, String> overrides, Map<String, String> environment) {
        Objects.requireNonNull(overrides, "overrides");
        Objects.requireNonNull(environment, "environment");
        String apiKey = firstNonBlank(overrides.get("apiKey"),
                environment.get("LLM_API_KEY"), environment.get("DASHSCOPE_API_KEY"));
        if (apiKey == null) {
            throw new IllegalArgumentException(
                    "missing API key; set LLM_API_KEY or DASHSCOPE_API_KEY");
        }
        String baseUrl = value(overrides, environment, "baseUrl", "LLM_BASE_URL",
                DEFAULT_BASE_URL);
        String model = value(overrides, environment, "model", "LLM_MODEL", DEFAULT_MODEL);
        long timeoutSeconds = parseLong(value(overrides, environment, "modelTimeoutSeconds",
                "LLM_MODEL_TIMEOUT_SECONDS", "120"), "modelTimeoutSeconds");
        int maxEventBytes = parseInt(value(overrides, environment, "maxSseEventBytes",
                "LLM_MAX_SSE_EVENT_BYTES", "1048576"), "maxSseEventBytes");
        int maxResponseCharacters = parseInt(value(overrides, environment,
                "maxResponseCharacters", "LLM_MAX_RESPONSE_CHARACTERS", "8388608"),
                "maxResponseCharacters");
        boolean thinking = parseBoolean(value(overrides, environment,
                "enableThinking", "LLM_ENABLE_THINKING", "false"), "enableThinking");
        Path dataDirectory = Path.of(value(overrides, environment, "dataDirectory",
                "CODING_AGENT_DATA_DIR", defaultDataDirectory().toString()));
        int databaseBusyTimeoutMillis = parseInt(value(overrides, environment,
                "databaseBusyTimeout", "CODING_AGENT_DB_BUSY_TIMEOUT_MS", "5000"),
                "databaseBusyTimeout");
        int maxSteps = parseInt(value(overrides, environment, "maxSteps",
                "CODING_AGENT_MAX_STEPS", "20"), "maxSteps");
        long turnTimeoutSeconds = parseLong(value(overrides, environment, "turnTimeoutSeconds",
                "CODING_AGENT_TURN_TIMEOUT_SECONDS", "900"), "turnTimeoutSeconds");
        long commandTimeoutSeconds = parseLong(value(overrides, environment,
                "commandTimeoutSeconds", "CODING_AGENT_COMMAND_TIMEOUT_SECONDS", "30"),
                "commandTimeoutSeconds");
        int maxToolOutputChars = parseInt(value(overrides, environment,
                "maxToolOutputChars", "CODING_AGENT_MAX_TOOL_OUTPUT_CHARS", "20000"),
                "maxToolOutputChars");
        int maxInputTokens = parseInt(value(overrides, environment, "maxInputTokens",
                "CODING_AGENT_MAX_INPUT_TOKENS", "131072"), "maxInputTokens");
        int reservedOutputTokens = parseInt(value(overrides, environment,
                "reservedOutputTokens", "CODING_AGENT_RESERVED_OUTPUT_TOKENS", "8192"),
                "reservedOutputTokens");
        int recentFullTurns = parseInt(value(overrides, environment, "recentFullTurns",
                "CODING_AGENT_RECENT_FULL_TURNS", "4"), "recentFullTurns");
        RunLimits defaultRunLimits = new RunLimits(maxSteps,
                Duration.ofSeconds(turnTimeoutSeconds), Duration.ofSeconds(timeoutSeconds),
                Duration.ofSeconds(commandTimeoutSeconds), maxToolOutputChars,
                maxInputTokens, reservedOutputTokens, recentFullTurns);
        return new AgentConfig(URI.create(baseUrl), apiKey, model, Duration.ofSeconds(timeoutSeconds),
                maxEventBytes, maxResponseCharacters, thinking, dataDirectory,
                Duration.ofMillis(databaseBusyTimeoutMillis), defaultRunLimits);
    }

    private static String value(Map<String, String> overrides, Map<String, String> environment,
                                String overrideName, String environmentName, String defaultValue) {
        String selected;
        if (overrides.containsKey(overrideName)) {
            selected = overrides.get(overrideName);
        } else if (environment.containsKey(environmentName)) {
            selected = environment.get(environmentName);
        } else {
            return defaultValue;
        }
        if (selected == null || selected.isBlank()) {
            throw new IllegalArgumentException(overrideName + " must not be blank");
        }
        return selected;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static Path defaultDataDirectory() {
        return Path.of(System.getProperty("user.home"), ".coding-agent");
    }
}
