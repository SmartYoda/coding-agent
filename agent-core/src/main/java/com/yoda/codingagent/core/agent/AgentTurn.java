package com.yoda.codingagent.core.agent;

import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import java.time.Instant;
import java.util.Objects;

public final class AgentTurn {

    private final TurnId turnId;
    private final SessionId sessionId;
    private final Instant startedAt;
    private int stepCount;

    public AgentTurn(SessionId sessionId) {
        this(TurnId.random(), sessionId, Instant.now());
    }

    public AgentTurn(TurnId turnId, SessionId sessionId, Instant startedAt) {
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public TurnId turnId() { return turnId; }

    public SessionId sessionId() { return sessionId; }

    public Instant startedAt() { return startedAt; }

    public int stepCount() { return stepCount; }

    public int beginNextStep() { return ++stepCount; }
}
