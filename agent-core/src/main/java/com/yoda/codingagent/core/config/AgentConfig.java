package com.yoda.codingagent.core.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class AgentConfig {

    private final URI baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration modelTimeout;
    private final int maxSseEventBytes;
    private final int maxResponseCharacters;
    private final boolean thinkingEnabled;

    public AgentConfig(URI baseUrl, String apiKey, String model, Duration modelTimeout,
                       int maxSseEventBytes, int maxResponseCharacters,
                       boolean thinkingEnabled) {
        this.baseUrl = requireSupportedBaseUrl(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.modelTimeout = requirePositive(modelTimeout, "modelTimeout");
        this.maxSseEventBytes = requirePositive(maxSseEventBytes, "maxSseEventBytes");
        this.maxResponseCharacters =
                requirePositive(maxResponseCharacters, "maxResponseCharacters");
        this.thinkingEnabled = thinkingEnabled;
    }

    public URI baseUrl() { return baseUrl; }

    public String apiKey() { return apiKey; }

    public String model() { return model; }

    public Duration modelTimeout() { return modelTimeout; }

    public int maxSseEventBytes() { return maxSseEventBytes; }

    public int maxResponseCharacters() { return maxResponseCharacters; }

    public boolean thinkingEnabled() { return thinkingEnabled; }

    @Override
    public String toString() {
        return "AgentConfig[baseUrl=" + baseUrl + ", apiKey=<redacted>, model=" + model
                + ", modelTimeout=" + modelTimeout
                + ", maxSseEventBytes=" + maxSseEventBytes
                + ", maxResponseCharacters=" + maxResponseCharacters
                + ", thinkingEnabled=" + thinkingEnabled + "]";
    }

    private static URI requireSupportedBaseUrl(URI value) {
        Objects.requireNonNull(value, "baseUrl");
        String scheme = value.getScheme();
        String host = value.getHost();
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

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
