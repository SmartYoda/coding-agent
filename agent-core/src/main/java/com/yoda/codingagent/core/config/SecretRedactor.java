package com.yoda.codingagent.core.config;

import java.util.Objects;

public final class SecretRedactor {

    private final String secret;

    public SecretRedactor(String secret) {
        this.secret = Objects.requireNonNull(secret, "secret");
    }

    public String redact(String value) {
        if (value == null || secret.isBlank()) {
            return value;
        }
        return value.replace(secret, "<redacted>");
    }
}
