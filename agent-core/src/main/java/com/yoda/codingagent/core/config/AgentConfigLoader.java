package com.yoda.codingagent.core.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

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
        boolean thinking = Boolean.parseBoolean(value(overrides, environment,
                "enableThinking", "LLM_ENABLE_THINKING", "false"));
        Path dataDirectory = Path.of(value(overrides, environment, "dataDirectory",
                "CODING_AGENT_DATA_DIR", defaultDataDirectory().toString()));
        int databaseBusyTimeoutMillis = parseInt(value(overrides, environment,
                "databaseBusyTimeout", "CODING_AGENT_DB_BUSY_TIMEOUT_MS", "5000"),
                "databaseBusyTimeout");
        return new AgentConfig(URI.create(baseUrl), apiKey, model, Duration.ofSeconds(timeoutSeconds),
                maxEventBytes, maxResponseCharacters, thinking, dataDirectory,
                Duration.ofMillis(databaseBusyTimeoutMillis));
    }

    private static String value(Map<String, String> overrides, Map<String, String> environment,
                                String overrideName, String environmentName, String defaultValue) {
        String value = firstNonBlank(overrides.get(overrideName), environment.get(environmentName));
        return value == null ? defaultValue : value;
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

    private static Path defaultDataDirectory() {
        return Path.of(System.getProperty("user.home"), ".coding-agent");
    }
}
