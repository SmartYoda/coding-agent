package com.yoda.codingagent.core.api;

import java.util.Objects;

public record AgentRequest(String input, ThinkingMode thinkingMode,
                           CommandAccessMode commandAccessMode) {

    public static final int MAX_INPUT_CHARACTERS = 100_000;

    public AgentRequest {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        if (input.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("input exceeds the character limit");
        }
        Objects.requireNonNull(thinkingMode, "thinkingMode");
        Objects.requireNonNull(commandAccessMode, "commandAccessMode");
    }

    public AgentRequest(String input) {
        this(input, ThinkingMode.DEFAULT, CommandAccessMode.RESTRICTED);
    }

    public AgentRequest(String input, ThinkingMode thinkingMode) {
        this(input, thinkingMode, CommandAccessMode.RESTRICTED);
    }
}
