package com.yoda.codingagent.core.context;

import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TokenEstimator {

    private static final int MESSAGE_OVERHEAD_TOKENS = 12;
    private static final int TOOL_OVERHEAD_TOKENS = 20;

    public int estimateMessages(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total = Math.addExact(total, estimateMessage(message));
        }
        return total;
    }

    public int estimateTools(List<ToolDefinition> tools) {
        int total = 0;
        for (ToolDefinition tool : tools) {
            total = Math.addExact(total, TOOL_OVERHEAD_TOKENS);
            total = Math.addExact(total, estimateText(tool.name()));
            total = Math.addExact(total, estimateText(tool.description()));
            total = Math.addExact(total, estimateText(tool.inputSchema().toString()));
        }
        return total;
    }

    private int estimateMessage(Message message) {
        int total = MESSAGE_OVERHEAD_TOKENS;
        if (message instanceof Message.SystemMessage system) {
            return Math.addExact(total, estimateText(system.content()));
        }
        if (message instanceof Message.UserMessage user) {
            return Math.addExact(total, estimateText(user.content()));
        }
        if (message instanceof Message.AssistantMessage assistant) {
            return Math.addExact(total, estimateText(assistant.content()));
        }
        if (message instanceof Message.AssistantToolCallsMessage calls) {
            total = Math.addExact(total, estimateText(calls.visibleText()));
            for (ToolCall call : calls.toolCalls()) {
                total = Math.addExact(total, TOOL_OVERHEAD_TOKENS);
                total = Math.addExact(total, estimateText(call.callId()));
                total = Math.addExact(total, estimateText(call.name()));
                total = Math.addExact(total, estimateText(call.arguments().toString()));
            }
            return total;
        }
        if (message instanceof Message.ToolResultMessage result) {
            total = Math.addExact(total, estimateText(result.callId()));
            return Math.addExact(total, estimateText(result.content()));
        }
        Message.TurnDigestMessage digest = (Message.TurnDigestMessage) message;
        return Math.addExact(total, estimateText(digest.content()));
    }

    private static int estimateText(String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, Math.ceilDiv(bytes, 3));
    }
}
