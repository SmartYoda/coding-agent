package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.tool.ToolCall;
import java.util.List;
import java.util.Objects;

public sealed interface Message permits Message.SystemMessage, Message.UserMessage,
        Message.AssistantMessage, Message.AssistantToolCallsMessage, Message.ToolResultMessage,
        Message.TurnDigestMessage {

    record SystemMessage(String content) implements Message {
        public SystemMessage { content = requireContent(content); }
    }

    record UserMessage(TurnId turnId, String content) implements Message {
        public UserMessage {
            Objects.requireNonNull(turnId, "turnId");
            content = requireContent(content);
        }
    }

    record AssistantMessage(TurnId turnId, String content) implements Message {
        public AssistantMessage {
            Objects.requireNonNull(turnId, "turnId");
            content = requireContent(content);
        }
    }

    record AssistantToolCallsMessage(TurnId turnId, String visibleText, List<ToolCall> toolCalls)
            implements Message {
        public AssistantToolCallsMessage {
            Objects.requireNonNull(turnId, "turnId");
            visibleText = visibleText == null ? "" : visibleText;
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
            if (toolCalls.isEmpty()) {
                throw new IllegalArgumentException("toolCalls must not be empty");
            }
        }
    }

    record ToolResultMessage(TurnId turnId, String callId, String content) implements Message {
        public ToolResultMessage {
            Objects.requireNonNull(turnId, "turnId");
            if (callId == null || callId.isBlank()) {
                throw new IllegalArgumentException("callId must not be blank");
            }
            content = Objects.requireNonNull(content, "content");
        }
    }

    record TurnDigestMessage(TurnId turnId, String content) implements Message {
        public TurnDigestMessage {
            Objects.requireNonNull(turnId, "turnId");
            content = requireContent(content);
        }
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return value;
    }
}
