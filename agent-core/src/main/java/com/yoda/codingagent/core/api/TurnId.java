package com.yoda.codingagent.core.api;

import java.util.Objects;
import java.util.UUID;

public record TurnId(UUID value) {

    public TurnId {
        Objects.requireNonNull(value, "value");
    }

    public static TurnId random() {
        return new TurnId(UUID.randomUUID());
    }
}
