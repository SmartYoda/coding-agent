package com.yoda.codingagent.core.model;

public sealed interface ModelStreamEvent permits ModelStreamEvent.ResponseStarted,
        ModelStreamEvent.TextDelta, ModelStreamEvent.ToolCallDelta,
        ModelStreamEvent.UsageReceived, ModelStreamEvent.ResponseFinished,
        ModelStreamEvent.StreamEnded {

    record ResponseStarted(String providerResponseId) implements ModelStreamEvent { }

    record TextDelta(String text) implements ModelStreamEvent { }

    record ToolCallDelta(int index, String callId, String nameDelta,
                         String argumentsDelta) implements ModelStreamEvent { }

    record UsageReceived(Usage usage) implements ModelStreamEvent { }

    record ResponseFinished(String finishReason) implements ModelStreamEvent { }

    record StreamEnded() implements ModelStreamEvent { }

    record Usage(long inputTokens, long outputTokens, long totalTokens) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("token usage must not be negative");
            }
        }
    }
}
