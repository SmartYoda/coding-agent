package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.model.Message;
import java.util.List;
import java.util.Objects;

public record ContextSnapshot(
        List<Message> messages,
        Budget budget,
        boolean compacted
) {

    public ContextSnapshot {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("context snapshot must not be empty");
        }
        Objects.requireNonNull(budget, "budget");
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
