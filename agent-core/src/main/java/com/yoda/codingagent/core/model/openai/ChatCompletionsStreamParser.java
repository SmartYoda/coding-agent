package com.yoda.codingagent.core.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.ModelStreamEvent.Usage;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.model.openai.SseFrameParser.SseFrame;
import java.io.IOException;
import java.util.Objects;

public final class ChatCompletionsStreamParser {

    private final ObjectMapper objectMapper;
    private boolean started;
    private boolean ended;

    public ChatCompletionsStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void accept(SseFrame frame, ModelStreamSink sink) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(sink, "sink");
        if (ended) {
            throw protocolError("received data after stream end");
        }
        if ("[DONE]".equals(frame.data().trim())) {
            ended = true;
            sink.onEvent(new ModelStreamEvent.StreamEnded());
            return;
        }
        if (frame.data().isBlank()) {
            return;
        }
        JsonNode root = readJson(frame.data());
        if (root.hasNonNull("error")) {
            throw protocolError("model stream returned an error payload");
        }
        if (!started) {
            started = true;
            sink.onEvent(new ModelStreamEvent.ResponseStarted(textOrNull(root.get("id"))));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isMissingNode() && !choices.isArray()) {
            throw protocolError("choices must be an array");
        }
        if (choices.isArray() && choices.size() > 1) {
            throw protocolError("multiple completion choices are not supported");
        }
        if (choices.isArray() && !choices.isEmpty()) {
            parseChoice(choices.get(0), sink);
        }
        JsonNode usage = root.get("usage");
        if (usage != null && !usage.isNull()) {
            sink.onEvent(new ModelStreamEvent.UsageReceived(new Usage(
                    nonNegativeLong(usage, "prompt_tokens"),
                    nonNegativeLong(usage, "completion_tokens"),
                    nonNegativeLong(usage, "total_tokens"))));
        }
    }

    private void parseChoice(JsonNode choice, ModelStreamSink sink) {
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) {
            throw protocolError("choice delta must be an object");
        }
        JsonNode content = delta.get("content");
        if (content != null && !content.isNull()) {
            if (!content.isTextual()) {
                throw protocolError("text delta must be a string");
            }
            if (!content.textValue().isEmpty()) {
                sink.onEvent(new ModelStreamEvent.TextDelta(content.textValue()));
            }
        }
        JsonNode calls = delta.get("tool_calls");
        if (calls != null && !calls.isNull()) {
            if (!calls.isArray()) {
                throw protocolError("tool_calls delta must be an array");
            }
            for (JsonNode call : calls) {
                parseToolCall(call, sink);
            }
        }
        JsonNode finishReason = choice.get("finish_reason");
        if (finishReason != null && !finishReason.isNull()) {
            if (!finishReason.isTextual() || finishReason.textValue().isBlank()) {
                throw protocolError("finish_reason must be a non-blank string");
            }
            sink.onEvent(new ModelStreamEvent.ResponseFinished(finishReason.textValue()));
        }
    }

    private void parseToolCall(JsonNode call, ModelStreamSink sink) {
        JsonNode indexNode = call.get("index");
        if (indexNode == null || !indexNode.canConvertToInt() || indexNode.intValue() < 0) {
            throw protocolError("tool call index must be a non-negative integer");
        }
        JsonNode function = call.path("function");
        String name = function.isObject() ? textOrNull(function.get("name")) : null;
        String arguments = function.isObject() ? textOrNull(function.get("arguments")) : null;
        sink.onEvent(new ModelStreamEvent.ToolCallDelta(indexNode.intValue(),
                textOrNull(call.get("id")), name, arguments));
    }

    private JsonNode readJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                    "model stream contained invalid JSON", exception);
        }
    }

    private static long nonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw protocolError("usage field " + field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw protocolError("expected a string field");
        }
        return node.textValue();
    }

    private static AgentException protocolError(String message) {
        return new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR, message);
    }
}
