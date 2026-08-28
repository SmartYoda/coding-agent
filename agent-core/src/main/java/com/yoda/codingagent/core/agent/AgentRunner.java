package com.yoda.codingagent.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentEventSink;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.model.ModelResponseAccumulator;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentRunner {

    private static final String SYSTEM_PROMPT = """
            You are a coding agent operating in one local workspace.
            Inspect available context before changing anything. Use only declared tools.
            Never invent tool results. After a change, run the relevant verification when possible.
            Finish with a concise summary of changes and verification.
            """;

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Duration modelTimeout;
    private final int maxOutputTokens;
    private final int maxResponseCharacters;
    private final int maxSteps;

    public AgentRunner(ModelClient modelClient, ToolRegistry toolRegistry, ObjectMapper objectMapper,
                       String model, Duration modelTimeout, int maxOutputTokens,
                       int maxResponseCharacters, int maxSteps) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = requireText(model, "model");
        this.modelTimeout = Objects.requireNonNull(modelTimeout, "modelTimeout");
        if (maxOutputTokens < 1 || maxResponseCharacters < 1 || maxSteps < 1) {
            throw new IllegalArgumentException("runner limits must be positive");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.maxResponseCharacters = maxResponseCharacters;
        this.maxSteps = maxSteps;
    }

    public AgentResult run(WorkspaceId workspaceId, SessionId sessionId, AgentRequest request,
                           AgentEventSink eventSink, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        TurnId turnId = TurnId.random();
        EventEmitter events = new EventEmitter(workspaceId, sessionId, turnId,
                Objects.requireNonNull(eventSink, "eventSink"));
        events.turnStarted();
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.SystemMessage(SYSTEM_PROMPT));
        messages.add(new Message.UserMessage(request.input()));
        try {
            for (int step = 1; step <= maxSteps; step++) {
                checkCancelled(cancellationToken);
                events.modelRequestStarted(step);
                ModelResponseAccumulator accumulator =
                        new ModelResponseAccumulator(objectMapper, maxResponseCharacters);
                modelClient.stream(new ModelRequest(model, messages, toolRegistry.definitions(),
                                modelTimeout, maxOutputTokens),
                        event -> consumeEvent(event, accumulator, events), cancellationToken);
                ModelResponse response = accumulator.response();
                events.modelRequestCompleted(step, response.finishReason());
                if (response.toolCalls().isEmpty()) {
                    if (response.visibleText().isBlank()) {
                        throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                                "model completed without final text");
                    }
                    events.turnCompleted();
                    return AgentResult.completed(turnId, response.visibleText());
                }
                messages.add(new Message.AssistantToolCallsMessage(
                        response.visibleText(), response.toolCalls()));
                for (ToolCall call : response.toolCalls()) {
                    checkCancelled(cancellationToken);
                    ToolResult result = executeTool(call, workspaceId, turnId,
                            cancellationToken, events);
                    messages.add(new Message.ToolResultMessage(call.callId(), result.output()));
                }
            }
            throw new AgentException(ErrorCode.TURN_LIMIT,
                    "agent reached the maximum number of model steps");
        } catch (AgentException exception) {
            TurnStatus status = exception.errorCode() == ErrorCode.CANCELLED
                    ? TurnStatus.CANCELLED : TurnStatus.FAILED;
            events.turnFailed(exception.errorCode(), exception.getMessage());
            return AgentResult.failed(turnId, status, exception.errorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            String safeMessage = "agent turn failed unexpectedly";
            events.turnFailed(ErrorCode.INTERNAL_ERROR, safeMessage);
            return AgentResult.failed(turnId, TurnStatus.FAILED,
                    ErrorCode.INTERNAL_ERROR, safeMessage);
        }
    }

    private ToolResult executeTool(ToolCall call, WorkspaceId workspaceId, TurnId turnId,
                                   CancellationToken cancellationToken, EventEmitter events) {
        events.toolStarted(call.callId(), call.name());
        Tool tool = toolRegistry.find(call.name()).orElse(null);
        ToolResult result;
        if (tool == null) {
            result = ToolResult.failure(ErrorCode.UNKNOWN_TOOL,
                    "Unknown tool: " + call.name());
        } else {
            try {
                result = Objects.requireNonNull(tool.execute(
                        new ToolContext(workspaceId, turnId, cancellationToken),
                        call.arguments()), "tool result");
            } catch (RuntimeException exception) {
                result = ToolResult.failure(ErrorCode.INTERNAL_ERROR,
                        "Tool execution failed");
            }
        }
        events.toolCompleted(call.callId(), call.name(), result.success());
        return result;
    }

    private static void consumeEvent(ModelStreamEvent event,
                                     ModelResponseAccumulator accumulator,
                                     EventEmitter events) {
        accumulator.onEvent(event);
        if (event instanceof ModelStreamEvent.TextDelta textDelta) {
            events.modelTextDelta(textDelta.text());
        } else if (event instanceof ModelStreamEvent.ToolCallDelta toolDelta) {
            String arguments = toolDelta.argumentsDelta();
            events.modelToolCallDelta(toolDelta.index(), toolDelta.callId(),
                    arguments == null ? 0 : arguments.length());
        }
    }

    private static void checkCancelled(CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new AgentException(ErrorCode.CANCELLED, "agent turn cancelled");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class EventEmitter {
        private final WorkspaceId workspaceId;
        private final SessionId sessionId;
        private final TurnId turnId;
        private final AgentEventSink sink;
        private long sequence;

        private EventEmitter(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                             AgentEventSink sink) {
            this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.turnId = Objects.requireNonNull(turnId, "turnId");
            this.sink = sink;
        }

        private void turnStarted() {
            emit(new AgentEvent.TurnStarted(workspaceId, sessionId, turnId, next(), now()));
        }

        private void modelRequestStarted(int step) {
            emit(new AgentEvent.ModelRequestStarted(
                    workspaceId, sessionId, turnId, next(), now(), step));
        }

        private void modelTextDelta(String text) {
            emit(new AgentEvent.ModelTextDelta(
                    workspaceId, sessionId, turnId, next(), now(), text));
        }

        private void modelToolCallDelta(int index, String callId, int characters) {
            emit(new AgentEvent.ModelToolCallDelta(workspaceId, sessionId, turnId,
                    next(), now(), index, callId, characters));
        }

        private void modelRequestCompleted(int step, String finishReason) {
            emit(new AgentEvent.ModelRequestCompleted(workspaceId, sessionId, turnId,
                    next(), now(), step, finishReason));
        }

        private void toolStarted(String callId, String toolName) {
            emit(new AgentEvent.ToolStarted(workspaceId, sessionId, turnId,
                    next(), now(), callId, toolName));
        }

        private void toolCompleted(String callId, String toolName, boolean success) {
            emit(new AgentEvent.ToolCompleted(workspaceId, sessionId, turnId,
                    next(), now(), callId, toolName, success));
        }

        private void turnCompleted() {
            emit(new AgentEvent.TurnCompleted(workspaceId, sessionId, turnId, next(), now()));
        }

        private void turnFailed(ErrorCode code, String message) {
            emit(new AgentEvent.TurnFailed(
                    workspaceId, sessionId, turnId, next(), now(), code, message));
        }

        private long next() { return ++sequence; }

        private static Instant now() { return Instant.now(); }

        private void emit(AgentEvent event) {
            try {
                sink.publish(event);
            } catch (RuntimeException ignored) {
                // A display adapter cannot change the agent result.
            }
        }
    }
}
