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

    public boolean containsSecret(String value) {
        return value != null && !secret.isBlank() && value.contains(secret);
    }

    public StreamingRedactor streaming() {
        return new StreamingRedactor(secret);
    }

    public static final class StreamingRedactor {
        private final String secret;
        private final StringBuilder pending = new StringBuilder();

        private StreamingRedactor(String secret) {
            this.secret = secret;
        }

        public String accept(String fragment) {
            Objects.requireNonNull(fragment, "fragment");
            pending.append(fragment);
            return drain(false);
        }

        public String finish() {
            return drain(true);
        }

        private String drain(boolean finish) {
            if (secret.isBlank()) {
                String output = pending.toString();
                pending.setLength(0);
                return output;
            }
            StringBuilder output = new StringBuilder();
            while (!pending.isEmpty()
                    && (finish || pending.length() >= secret.length())) {
                if (startsWithSecret()) {
                    output.append("<redacted>");
                    pending.delete(0, secret.length());
                } else {
                    output.append(pending.charAt(0));
                    pending.deleteCharAt(0);
                }
            }
            return output.toString();
        }

        private boolean startsWithSecret() {
            if (pending.length() < secret.length()) {
                return false;
            }
            for (int index = 0; index < secret.length(); index++) {
                if (pending.charAt(index) != secret.charAt(index)) {
                    return false;
                }
            }
            return true;
        }
    }
}
