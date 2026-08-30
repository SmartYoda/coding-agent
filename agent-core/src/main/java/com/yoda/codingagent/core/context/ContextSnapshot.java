package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.api.TurnId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ContextSnapshot(
        List<Message> messages,
        Budget budget,
        CompactionDecision compactionDecision
) {

    public ContextSnapshot {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("context snapshot must not be empty");
        }
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(compactionDecision, "compactionDecision");
    }

    public boolean compacted() {
        return compactionDecision.compacted();
    }

    public record CompactionDecision(
            List<TurnId> fullTurnIds,
            List<TurnId> digestTurnIds,
            int omittedTurnCount,
            int estimatedTokensBefore,
            int estimatedTokensAfter
    ) {
        private static final int MAX_REPORTED_TURN_IDS = 32;

        public CompactionDecision {
            fullTurnIds = boundedDistinct(fullTurnIds, "fullTurnIds");
            digestTurnIds = boundedDistinct(digestTurnIds, "digestTurnIds");
            HashSet<TurnId> overlap = new HashSet<>(fullTurnIds);
            overlap.retainAll(digestTurnIds);
            if (!overlap.isEmpty() || omittedTurnCount < 0
                    || estimatedTokensBefore < 0 || estimatedTokensAfter < 0
                    || estimatedTokensAfter > estimatedTokensBefore) {
                throw new IllegalArgumentException("invalid compaction decision");
            }
        }

        public boolean compacted() {
            return omittedTurnCount > 0 || !digestTurnIds.isEmpty();
        }

        private static List<TurnId> boundedDistinct(List<TurnId> ids, String name) {
            List<TurnId> copied = List.copyOf(Objects.requireNonNull(ids, name));
            if (copied.size() > MAX_REPORTED_TURN_IDS
                    || new HashSet<>(copied).size() != copied.size()) {
                throw new IllegalArgumentException(name + " must contain at most 32 unique ids");
            }
            return copied;
        }
    }

    public record Budget(
            int fixedTokens,
            int toolTokens,
            int currentTokens,
            int recentTokens,
            int digestTokens,
            int reservedOutputTokens,
            int maxInputTokens
    ) {
        public Budget {
            if (fixedTokens < 0 || toolTokens < 0 || currentTokens < 0
                    || recentTokens < 0 || digestTokens < 0 || reservedOutputTokens < 0
                    || maxInputTokens < 1 || totalWithReserveExact(fixedTokens, toolTokens,
                    currentTokens, recentTokens, digestTokens, reservedOutputTokens)
                    > maxInputTokens) {
                throw new IllegalArgumentException("invalid context budget");
            }
        }

        public int estimatedInputTokens() {
            return Math.addExact(Math.addExact(Math.addExact(Math.addExact(
                    fixedTokens, toolTokens), currentTokens), recentTokens), digestTokens);
        }

        public int totalWithReserve() {
            return Math.addExact(estimatedInputTokens(), reservedOutputTokens);
        }

        private static long totalWithReserveExact(int fixed, int tools, int current,
                                                  int recent, int digest, int reserve) {
            return (long) fixed + tools + current + recent + digest + reserve;
        }
    }
}
