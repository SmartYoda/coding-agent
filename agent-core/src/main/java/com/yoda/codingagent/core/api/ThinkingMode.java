package com.yoda.codingagent.core.api;

public enum ThinkingMode {
    DEFAULT,
    ENABLED,
    DISABLED;

    public boolean resolve(boolean defaultValue) {
        return switch (this) {
            case DEFAULT -> defaultValue;
            case ENABLED -> true;
            case DISABLED -> false;
        };
    }
}
