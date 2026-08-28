package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.model.ModelStreamEvent.Usage;
import com.yoda.codingagent.core.tool.ToolCall;
import java.util.List;
import java.util.Objects;

public record ModelResponse(String visibleText, List<ToolCall> toolCalls, Usage usage,
                            String providerResponseId, String finishReason) {

    public ModelResponse {
        visibleText = Objects.requireNonNull(visibleText, "visibleText");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        finishReason = Objects.requireNonNull(finishReason, "finishReason");
    }
}
