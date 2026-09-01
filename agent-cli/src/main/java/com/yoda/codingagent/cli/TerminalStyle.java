package com.yoda.codingagent.cli;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class TerminalStyle {

    enum ColorMode { AUTO, ALWAYS, NEVER }

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String BOLD_CYAN = "\u001B[1;36m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BOLD_YELLOW = "\u001B[1;33m";
    private static final String BOLD_RED = "\u001B[1;31m";
    private static final String DIM = "\u001B[2m";

    private static final TerminalStyle PLAIN = new TerminalStyle(false);

    private final boolean enabled;

    private TerminalStyle(boolean enabled) {
        this.enabled = enabled;
    }

    static TerminalStyle plain() {
        return PLAIN;
    }

    static TerminalStyle resolve(Map<String, String> environment,
                                 boolean interactiveTerminal) {
        Objects.requireNonNull(environment, "environment");
        if (!environment.getOrDefault("NO_COLOR", "").isEmpty()) {
            return plain();
        }
        ColorMode mode = parseMode(environment.get("CODING_AGENT_COLOR"));
        if (mode == ColorMode.NEVER) {
            return plain();
        }
        if (mode == ColorMode.ALWAYS) {
            return new TerminalStyle(true);
        }
        if ("dumb".equalsIgnoreCase(environment.getOrDefault("TERM", ""))) {
            return plain();
        }
        return interactiveTerminal ? new TerminalStyle(true) : plain();
    }

    String prompt(String text) {
        return wrap(BOLD_CYAN, text);
    }

    String tool(String text) {
        return wrap(MAGENTA, text);
    }

    String success(String text) {
        return wrap(GREEN, text);
    }

    String warning(String text) {
        return wrap(YELLOW, text);
    }

    String strongWarning(String text) {
        return wrap(BOLD_YELLOW, text);
    }

    String error(String text) {
        return wrap(BOLD_RED, text);
    }

    String label(String text) {
        return wrap(BOLD, text);
    }

    String identifier(String text) {
        return safe(text);
    }

    String inactive(String text) {
        return safe(text);
    }

    String muted(String text) {
        return wrap(DIM, text);
    }

    String safe(String untrustedText) {
        String value = String.valueOf(untrustedText);
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (allowed(codePoint)) {
                sanitized.appendCodePoint(codePoint);
            }
        }
        return sanitized.toString();
    }

    boolean enabled() {
        return enabled;
    }

    private static ColorMode parseMode(String configured) {
        if (configured == null || configured.isBlank()) {
            return ColorMode.AUTO;
        }
        try {
            return ColorMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "CODING_AGENT_COLOR must be auto, always, or never");
        }
    }

    private String wrap(String control, String text) {
        String sanitized = safe(text);
        return enabled ? control + sanitized + RESET : sanitized;
    }

    private static boolean allowed(int codePoint) {
        if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
            return true;
        }
        return codePoint >= 0x20
                && codePoint != 0x7F
                && (codePoint < 0x80 || codePoint > 0x9F);
    }
}
