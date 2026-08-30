package com.yoda.codingagent.cli;

import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import java.util.List;

final class ContextView {

    private Snapshot snapshot;

    synchronized void accept(long generation, AgentEvent event) {
        if (snapshot == null || generation > snapshot.generation()
                || generation == snapshot.generation()
                && event.turnId().equals(snapshot.turnId())) {
            if (event instanceof AgentEvent.ContextBudgetEvaluated budget) {
                snapshot = new Snapshot(generation, event.sessionId(), event.turnId(),
                        budget.estimatedInputTokens(), budget.maxInputTokens(),
                        List.of(), List.of(), 0);
            } else if (event instanceof AgentEvent.ContextCompacted compacted
                    && snapshot != null && generation == snapshot.generation()
                    && event.turnId().equals(snapshot.turnId())) {
                snapshot = new Snapshot(generation, event.sessionId(), event.turnId(),
                        snapshot.estimatedInputTokens(), snapshot.maxInputTokens(),
                        compacted.fullTurnIds(), compacted.digestTurnIds(),
                        compacted.omittedTurnCount());
            }
        }
    }

    synchronized Snapshot snapshotFor(SessionId sessionId) {
        return snapshot != null && snapshot.sessionId().equals(sessionId) ? snapshot : null;
    }

    record Snapshot(long generation, SessionId sessionId, TurnId turnId,
                    int estimatedInputTokens, int maxInputTokens,
                    List<TurnId> fullTurnIds, List<TurnId> digestTurnIds,
                    int omittedTurnCount) {
        Snapshot {
            fullTurnIds = List.copyOf(fullTurnIds);
            digestTurnIds = List.copyOf(digestTurnIds);
        }
    }
}
