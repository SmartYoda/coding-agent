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
import com.yoda.codingagent.core.context.CanonicalHistory;
import com.yoda.codingagent.core.context.ContextBudgetPolicy;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.ContextSnapshot;
import com.yoda.codingagent.core.context.TurnDigest;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.model.ModelResponseAccumulator;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.persistence.StateStore;
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

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxResponseCharacters;
    private final StateStore stateStore;
    private final ContextManager contextManager;
    private final TurnDigestFactory digestFactory;

    public AgentRunner(ModelClient modelClient, ToolRegistry toolRegistry, ObjectMapper objectMapper,
                       String model, int maxResponseCharacters, StateStore stateStore,
                       ContextManager contextManager, TurnDigestFactory digestFactory) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = requireText(model, "model");
        if (maxResponseCharacters < 1) {
            throw new IllegalArgumentException("maxResponseCharacters must be positive");
        }
        this.maxResponseCharacters = maxResponseCharacters;
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.digestFactory = Objects.requireNonNull(digestFactory, "digestFactory");
    }

    AgentResult run(AgentSession session, AgentRequest request,
                    AgentEventSink eventSink, CancellationToken cancellationToken) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        AgentTurn turn = new AgentTurn(session.sessionId());
        EventEmitter events = new EventEmitter(session.workspace().workspaceId(),
                session.sessionId(), turn.turnId(),
                Objects.requireNonNull(eventSink, "eventSink"));
        boolean began = false;
        try {
            stateStore.beginTurn(turn, request.input());
            began = true;
            events.turnStarted();
            CanonicalHistory history = stateStore.loadCanonicalHistory(session.sessionId());
            List<Message> currentTurn = new ArrayList<>();
            currentTurn.add(new Message.UserMessage(turn.turnId(), request.input()));

            while (turn.stepCount() < session.limits().maxSteps()) {
                checkStopped(turn, session, cancellationToken);
                turn.beginNextStep();
                ContextSnapshot snapshot = contextManager.buildSnapshot(history,
                        session.workspace(), currentTurn, toolRegistry.definitions(),
                        ContextBudgetPolicy.from(session.limits()));
                stateStore.markTurnStreaming(turn);
                events.modelRequestStarted(turn.stepCount());
                ModelResponseAccumulator accumulator =
                        new ModelResponseAccumulator(objectMapper, maxResponseCharacters);
                modelClient.stream(new ModelRequest(model, snapshot.messages(),
                                toolRegistry.definitions(), session.limits().modelTimeout(),
                                session.limits().reservedOutputTokens()),
                        event -> consumeEvent(event, accumulator, events), cancellationToken);
                ModelResponse response = accumulator.response();
                events.modelRequestCompleted(turn.stepCount(), response.finishReason());

                if (response.toolCalls().isEmpty()) {
                    if (response.visibleText().isBlank()) {
                        throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                                "model completed without final text");
                    }
                    List<Message> completedMessages = new ArrayList<>(currentTurn);
                    completedMessages.add(new Message.AssistantMessage(
                            turn.turnId(), response.visibleText()));
                    TurnDigest digest = digestFactory.create(
                            new CanonicalHistory.TurnHistory(turn.turnId(), completedMessages));
                    stateStore.completeTurn(turn, response,
                            snapshot.budget().estimatedInputTokens(), digest);
                    events.turnCompleted();
                    return AgentResult.completed(turn.turnId(), response.visibleText());
                }

                StateStore.StagedModelStep step = stateStore.stageToolStep(turn, response,
                        snapshot.budget().estimatedInputTokens());
                List<Message.ToolResultMessage> resultMessages = new ArrayList<>();
                for (ToolCall call : response.toolCalls()) {
                    checkStopped(turn, session, cancellationToken);
                    stateStore.markToolExecuting(step, call);
                    ToolResult result = executeTool(call, session, turn, cancellationToken, events);
                    stateStore.recordToolResult(step, call, result);
                    resultMessages.add(new Message.ToolResultMessage(
                            turn.turnId(), call.callId(), result));
                }
                stateStore.commitToolStep(step);
                currentTurn.add(new Message.AssistantToolCallsMessage(
                        turn.turnId(), response.visibleText(), response.toolCalls()));
                currentTurn.addAll(resultMessages);
            }
            throw new AgentException(ErrorCode.TURN_LIMIT,
                    "agent reached the maximum number of model steps");
        } catch (AgentException exception) {
            Failure failure = persistFailure(turn, began, terminalStatus(exception.errorCode()),
                    exception.errorCode(), exception.getMessage());
            events.turnFailed(failure.errorCode(), failure.safeMessage());
            return AgentResult.failed(turn.turnId(), failure.status(),
                    failure.errorCode(), failure.safeMessage());
        } catch (RuntimeException exception) {
            Failure failure = persistFailure(turn, began, TurnStatus.FAILED,
                    ErrorCode.INTERNAL_ERROR, "agent turn failed unexpectedly");
            events.turnFailed(failure.errorCode(), failure.safeMessage());
            return AgentResult.failed(turn.turnId(), failure.status(),
                    failure.errorCode(), failure.safeMessage());
        }
    }

    private ToolResult executeTool(ToolCall call, AgentSession session, AgentTurn turn,
                                   CancellationToken cancellationToken, EventEmitter events) {
        events.toolStarted(call.callId(), call.name());
        Instant startedAt = Instant.now();
        Tool tool = toolRegistry.find(call.name()).orElse(null);
        ToolResult result;
        if (tool == null) {
            result = ToolResult.failure(ErrorCode.UNKNOWN_TOOL,
                    "Unknown tool: " + call.name());
        } else {
            try {
                result = Objects.requireNonNull(tool.execute(
                        new ToolContext(session.workspace().workspaceId(),
                                session.workspace().root(), turn.turnId(), cancellationToken),
                        call.arguments()), "tool result");
            } catch (RuntimeException exception) {
                result = ToolResult.failure(ErrorCode.INTERNAL_ERROR,
                        "Tool execution failed");
            }
        }
        Duration duration = Duration.between(startedAt, Instant.now());
        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }
        duration = Duration.ofMillis(duration.toMillis());
        result = result.withDuration(duration);
        result = boundToolResult(result, session.limits().maxToolOutputChars());
        events.toolCompleted(call.callId(), call.name(), result.success());
        return result;
    }

    private static ToolResult boundToolResult(ToolResult result, int maximumCharacters) {
        if (result.output().length() <= maximumCharacters) {
            return result;
        }
        String marker = "\n…[tool output truncated]";
        int contentLimit = Math.max(0, maximumCharacters - marker.length());
        String output = result.output().substring(0, contentLimit)
                + marker.substring(0, Math.min(marker.length(), maximumCharacters - contentLimit));
        return new ToolResult(result.status(), output, result.errorCode(), true,
                result.duration(), result.metadata());
    }

    private Failure persistFailure(AgentTurn turn, boolean began, TurnStatus status,
                                   ErrorCode errorCode, String safeMessage) {
        if (!began) {
            return new Failure(status, errorCode, safeMessage);
        }
        try {
            stateStore.failTurn(turn, status, errorCode);
            return new Failure(status, errorCode, safeMessage);
        } catch (RuntimeException persistenceFailure) {
            return new Failure(TurnStatus.FAILED, ErrorCode.STORAGE_ERROR,
                    "could not persist the terminal turn state");
        }
    }

    private static TurnStatus terminalStatus(ErrorCode errorCode) {
        if (errorCode == ErrorCode.CANCELLED) {
            return TurnStatus.CANCELLED;
        }
        if (errorCode == ErrorCode.TURN_LIMIT || errorCode == ErrorCode.CONTEXT_LIMIT) {
            return TurnStatus.LIMIT_REACHED;
        }
        return TurnStatus.FAILED;
    }

    private static void checkStopped(AgentTurn turn, AgentSession session,
                                     CancellationToken cancellationToken) {
        if (cancellationToken.isCancelled()) {
            throw new AgentException(ErrorCode.CANCELLED, "agent turn cancelled");
        }
        if (!Instant.now().isBefore(turn.startedAt().plus(session.limits().turnTimeout()))) {
            throw new AgentException(ErrorCode.TURN_LIMIT, "agent turn timed out");
        }
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record Failure(TurnStatus status, ErrorCode errorCode, String safeMessage) { }

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
