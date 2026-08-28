package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.tool.ToolCall;
import java.util.List;
import java.util.Objects;

public sealed interface Message permits Message.SystemMessage, Message.UserMessage,
        Message.AssistantMessage, Message.AssistantToolCallsMessage, Message.ToolResultMessage {

    record SystemMessage(String content) implements Message {
        public SystemMessage { content = requireContent(content); }
    }

    record UserMessage(String content) implements Message {
        public UserMessage { content = requireContent(content); }
    }

    record AssistantMessage(String content) implements Message {
        public AssistantMessage { content = requireContent(content); }
    }

    record AssistantToolCallsMessage(String visibleText, List<ToolCall> toolCalls)
            implements Message {
        public AssistantToolCallsMessage {
            visibleText = visibleText == null ? "" : visibleText;
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
            if (toolCalls.isEmpty()) {
                throw new IllegalArgumentException("toolCalls must not be empty");
            }
        }
    }

    record ToolResultMessage(String callId, String content) implements Message {
        public ToolResultMessage {
            if (callId == null || callId.isBlank()) {
                throw new IllegalArgumentException("callId must not be blank");
            }
            content = Objects.requireNonNull(content, "content");
        }
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return value;
    }
}
