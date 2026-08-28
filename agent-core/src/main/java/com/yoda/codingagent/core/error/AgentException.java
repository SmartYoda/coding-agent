package com.yoda.codingagent.core.error;

import com.yoda.codingagent.core.api.ErrorCode;
import java.util.Objects;

public final class AgentException extends RuntimeException {

    private final ErrorCode errorCode;

    public AgentException(ErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public AgentException(ErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
