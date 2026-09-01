package com.yoda.codingagent.core.config;

import com.yoda.codingagent.core.api.RunLimits;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class AgentConfig {

    private final URI baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration modelTimeout;
    private final int maxSseEventBytes;
    private final int maxResponseCharacters;
    private final boolean defaultThinkingEnabled;
    private final Path dataDirectory;
    private final Duration databaseBusyTimeout;
    private final RunLimits defaultRunLimits;

    public AgentConfig(URI baseUrl, String apiKey, String model, Duration modelTimeout,
                       int maxSseEventBytes, int maxResponseCharacters,
                       boolean defaultThinkingEnabled, Path dataDirectory,
                       Duration databaseBusyTimeout) {
        this(baseUrl, apiKey, model, modelTimeout, maxSseEventBytes,
                maxResponseCharacters, defaultThinkingEnabled, dataDirectory,
                databaseBusyTimeout, new RunLimits(20, Duration.ofSeconds(900),
                        modelTimeout, Duration.ofSeconds(30), 20_000,
                        131_072, 8_192, 4));
    }

    public AgentConfig(URI baseUrl, String apiKey, String model, Duration modelTimeout,
                       int maxSseEventBytes, int maxResponseCharacters,
                       boolean defaultThinkingEnabled, Path dataDirectory,
                       Duration databaseBusyTimeout, RunLimits defaultRunLimits) {
        this.baseUrl = requireSupportedBaseUrl(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        if (this.model.length() > 200) {
            throw new IllegalArgumentException("model must not exceed 200 characters");
        }
        this.modelTimeout = requirePositive(modelTimeout, "modelTimeout");
        this.maxSseEventBytes = requireRange(maxSseEventBytes,
                "maxSseEventBytes", 1_024, 4_194_304);
        this.maxResponseCharacters = requireRange(maxResponseCharacters,
                "maxResponseCharacters", 1_024, 16_777_216);
        this.defaultThinkingEnabled = defaultThinkingEnabled;
        this.dataDirectory = requireAbsolutePath(dataDirectory, "dataDirectory");
        this.databaseBusyTimeout = requireRange(databaseBusyTimeout,
                "databaseBusyTimeout", Duration.ofMillis(1), Duration.ofSeconds(60));
        this.defaultRunLimits = Objects.requireNonNull(defaultRunLimits, "defaultRunLimits");
        if (!this.defaultRunLimits.modelTimeout().equals(this.modelTimeout)) {
            throw new IllegalArgumentException(
                    "modelTimeout and defaultRunLimits.modelTimeout must match");
        }
    }

    public URI baseUrl() { return baseUrl; }

    public String apiKey() { return apiKey; }

    public String model() { return model; }

    public Duration modelTimeout() { return modelTimeout; }

    public int maxSseEventBytes() { return maxSseEventBytes; }

    public int maxResponseCharacters() { return maxResponseCharacters; }

    public boolean defaultThinkingEnabled() { return defaultThinkingEnabled; }

    public Path dataDirectory() { return dataDirectory; }

    public Path databasePath() { return dataDirectory.resolve("agent.db"); }

    public Duration databaseBusyTimeout() { return databaseBusyTimeout; }

    public RunLimits defaultRunLimits() { return defaultRunLimits; }

    @Override
    public String toString() {
        return "AgentConfig[baseUrl=" + baseUrl + ", apiKey=<redacted>, model=" + model
                + ", modelTimeout=" + modelTimeout
                + ", maxSseEventBytes=" + maxSseEventBytes
                + ", maxResponseCharacters=" + maxResponseCharacters
                + ", defaultThinkingEnabled=" + defaultThinkingEnabled
                + ", dataDirectory=" + dataDirectory
                + ", databaseBusyTimeout=" + databaseBusyTimeout + "]";
    }

    private static URI requireSupportedBaseUrl(URI value) {
        Objects.requireNonNull(value, "baseUrl");
        String scheme = value.getScheme();
        String host = value.getHost();
        if (host == null || host.isBlank() || value.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must contain a host and no user info");
        }
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host));
        if (!"https".equalsIgnoreCase(scheme) && !loopbackHttp) {
            throw new IllegalArgumentException("baseUrl must use HTTPS or loopback HTTP");
        }
        if (value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain query or fragment");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requireRange(Duration value, String name, Duration minimum,
                                         Duration maximum) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum.toMillis() + " and " + maximum.toMillis() + " milliseconds");
        }
        return value;
    }

    private static Path requireAbsolutePath(Path value, String name) {
        Objects.requireNonNull(value, name);
        Path normalized = value.toAbsolutePath().normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
        return normalized;
    }

    private static int requireRange(int value, String name, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum + " and " + maximum);
        }
        return value;
    }
}
