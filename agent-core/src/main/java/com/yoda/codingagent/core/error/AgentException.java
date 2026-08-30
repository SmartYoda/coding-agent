package com.yoda.codingagent.core.error;

import com.yoda.codingagent.core.api.ErrorCode;
import java.time.Duration;
import java.util.Objects;

public final class AgentException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Duration retryAfter;

    public AgentException(ErrorCode errorCode, String safeMessage) {
        this(errorCode, safeMessage, null, null);
    }

    public AgentException(ErrorCode errorCode, String safeMessage, Throwable cause) {
        this(errorCode, safeMessage, null, cause);
    }

    public AgentException(ErrorCode errorCode, String safeMessage, Duration retryAfter) {
        this(errorCode, safeMessage, retryAfter, null);
    }

    public AgentException(ErrorCode errorCode, String safeMessage,
                          Duration retryAfter, Throwable cause) {
        super(requireSafeMessage(safeMessage), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.retryAfter = retryAfter;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static String requireSafeMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return value;
    }
}
