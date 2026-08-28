package com.yoda.codingagent.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.ModelStreamEvent.ResponseFinished;
import com.yoda.codingagent.core.model.ModelStreamEvent.ResponseStarted;
import com.yoda.codingagent.core.model.ModelStreamEvent.StreamEnded;
import com.yoda.codingagent.core.model.ModelStreamEvent.TextDelta;
import com.yoda.codingagent.core.model.ModelStreamEvent.ToolCallDelta;
import com.yoda.codingagent.core.model.ModelStreamEvent.Usage;
import com.yoda.codingagent.core.model.ModelStreamEvent.UsageReceived;
import com.yoda.codingagent.core.tool.ToolCall;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class ModelResponseAccumulator implements ModelStreamSink {

    private final ObjectMapper objectMapper;
    private final int maxCharacters;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, ToolSlot> toolSlots = new TreeMap<>();
    private int accumulatedCharacters;
    private String providerResponseId;
    private String finishReason;
    private Usage usage;
    private boolean started;
    private boolean ended;

    public ModelResponseAccumulator(ObjectMapper objectMapper, int maxCharacters) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        this.maxCharacters = maxCharacters;
    }

    @Override
    public void onEvent(ModelStreamEvent event) {
        Objects.requireNonNull(event, "event");
        if (ended) {
            throw protocolError("received an event after stream end");
        }
        if (finishReason != null
                && (event instanceof TextDelta || event instanceof ToolCallDelta)) {
            throw protocolError("received a response delta after response finish");
        }
        if (event instanceof ResponseStarted responseStarted) {
            if (started) {
                throw protocolError("response started more than once");
            }
            started = true;
            providerResponseId = blankToNull(responseStarted.providerResponseId());
        } else if (event instanceof TextDelta delta) {
            requireStarted();
            append(text, Objects.requireNonNull(delta.text(), "text"));
        } else if (event instanceof ToolCallDelta delta) {
            requireStarted();
            if (delta.index() < 0) {
                throw protocolError("tool call index must not be negative");
            }
            ToolSlot slot = toolSlots.computeIfAbsent(delta.index(), ignored -> new ToolSlot());
            slot.accept(delta);
        } else if (event instanceof UsageReceived usageReceived) {
            requireStarted();
            usage = Objects.requireNonNull(usageReceived.usage(), "usage");
        } else if (event instanceof ResponseFinished finished) {
            requireStarted();
            if (finishReason != null) {
                throw protocolError("response finished more than once");
            }
            finishReason = Objects.requireNonNull(finished.finishReason(), "finishReason");
        } else if (event instanceof StreamEnded) {
            requireStarted();
            ended = true;
        }
    }

    public boolean isComplete() {
        return finishReason != null && ended;
    }

    public ModelResponse response() {
        if (!isComplete()) {
            throw protocolError("model response did not finish cleanly");
        }
        List<ToolCall> calls = buildCalls();
        switch (finishReason) {
            case "stop" -> {
                if (!calls.isEmpty()) {
                    throw protocolError("stop response unexpectedly contained tool calls");
                }
            }
            case "tool_calls" -> {
                if (calls.isEmpty()) {
                    throw protocolError("tool_calls finish reason requires a tool call");
                }
            }
            default -> throw protocolError("unsupported finish reason: " + finishReason);
        }
        return new ModelResponse(text.toString(), calls, usage, providerResponseId, finishReason);
    }

    private List<ToolCall> buildCalls() {
        List<ToolCall> calls = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        for (ToolSlot slot : toolSlots.values()) {
            String callId = blankToNull(slot.callId);
            String name = blankToNull(slot.name.toString());
            if (callId == null || name == null || slot.arguments.isEmpty()) {
                throw protocolError("tool call is incomplete");
            }
            if (!callIds.add(callId)) {
                throw protocolError("duplicate tool call id");
            }
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(slot.arguments.toString());
            } catch (IOException exception) {
                throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                        "tool arguments are not valid JSON", exception);
            }
            if (!(parsed instanceof ObjectNode objectNode)) {
                throw protocolError("tool arguments root must be an object");
            }
            calls.add(new ToolCall(callId, name, objectNode));
        }
        return List.copyOf(calls);
    }

    private void requireStarted() {
        if (!started) {
            throw protocolError("response event arrived before response start");
        }
    }

    private void append(StringBuilder target, String value) {
        if (value.isEmpty()) {
            return;
        }
        if ((long) accumulatedCharacters + value.length() > maxCharacters) {
            throw protocolError("model response exceeds configured size limit");
        }
        target.append(value);
        accumulatedCharacters += value.length();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static AgentException protocolError(String message) {
        return new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR, message);
    }

    private final class ToolSlot {
        private String callId;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private void accept(ToolCallDelta delta) {
            String newCallId = blankToNull(delta.callId());
            if (newCallId != null) {
                if (callId != null && !callId.equals(newCallId)) {
                    throw protocolError("conflicting tool call id for index " + delta.index());
                }
                callId = newCallId;
            }
            append(name, delta.nameDelta() == null ? "" : delta.nameDelta());
            append(arguments,
                    delta.argumentsDelta() == null ? "" : delta.argumentsDelta());
        }
    }
}
