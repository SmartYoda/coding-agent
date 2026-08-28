package com.yoda.codingagent.core.api;

public record AgentRequest(String input) {

    public static final int MAX_INPUT_CHARACTERS = 100_000;

    public AgentRequest {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        if (input.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("input exceeds the character limit");
        }
    }
}
