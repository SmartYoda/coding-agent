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
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.model.ModelResponseAccumulator;
import com.yoda.codingagent.core.model.ModelRetryPolicy;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.RetryWaiter;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentRunner {

    private final ModelClient modelClient;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxResponseCharacters;
    private final StateStore stateStore;
    private final ContextManager contextManager;
    private final TurnDigestFactory digestFactory;
    private final ModelRetryPolicy modelRetryPolicy;
    private final RetryWaiter retryWaiter;
    private final Clock clock;
    private final StopPolicy stopPolicy;
    private final SecretRedactor secretRedactor;

    public AgentRunner(ModelClient modelClient, ToolDispatcher toolDispatcher,
                       ObjectMapper objectMapper,
                       String model, int maxResponseCharacters, StateStore stateStore,
                       ContextManager contextManager, TurnDigestFactory digestFactory,
                       SecretRedactor secretRedactor) {
        this(modelClient, toolDispatcher, objectMapper, model, maxResponseCharacters,
                stateStore, contextManager, digestFactory, new ModelRetryPolicy(),
                RetryWaiter.cancellableSleep(), Clock.systemUTC(), secretRedactor);
    }

    public AgentRunner(ModelClient modelClient, ToolDispatcher toolDispatcher,
                       ObjectMapper objectMapper,
                       String model, int maxResponseCharacters, StateStore stateStore,
                       ContextManager contextManager, TurnDigestFactory digestFactory,
                       ModelRetryPolicy modelRetryPolicy, RetryWaiter retryWaiter, Clock clock,
                       SecretRedactor secretRedactor) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.toolDispatcher = Objects.requireNonNull(toolDispatcher, "toolDispatcher");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = requireText(model, "model");
        if (maxResponseCharacters < 1) {
            throw new IllegalArgumentException("maxResponseCharacters must be positive");
        }
        this.maxResponseCharacters = maxResponseCharacters;
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.digestFactory = Objects.requireNonNull(digestFactory, "digestFactory");
        this.modelRetryPolicy = Objects.requireNonNull(modelRetryPolicy, "modelRetryPolicy");
        this.retryWaiter = Objects.requireNonNull(retryWaiter, "retryWaiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secretRedactor = Objects.requireNonNull(secretRedactor, "secretRedactor");
        this.stopPolicy = new StopPolicy(clock);
    }

    AgentResult run(AgentSession session, AgentRequest request, boolean thinkingEnabled,
                    AgentEventSink eventSink, CancellationToken cancellationToken) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        AgentTurn turn = new AgentTurn(TurnId.random(), session.sessionId(), clock.instant(),
                thinkingEnabled);
        EventEmitter events = new EventEmitter(session.workspace().workspaceId(),
                session.sessionId(), turn.turnId(),
                Objects.requireNonNull(eventSink, "eventSink"), clock);
        boolean began = false;
        try {
            stateStore.beginTurn(turn.turnId(), turn.sessionId(), turn.startedAt(),
                    request.input(), turn.thinkingEnabled());
            began = true;
            events.turnStarted();
            CanonicalHistory history = stateStore.loadCanonicalHistory(session.sessionId());
            List<Message> currentTurn = new ArrayList<>();
            currentTurn.add(new Message.UserMessage(turn.turnId(), request.input()));

            while (true) {
                finishIfStopped(turn, session, cancellationToken, true);
                ContextSnapshot snapshot = contextManager.buildSnapshot(history,
                        session.workspace().workspaceId(), session.workspace().root(),
                        currentTurn, toolDispatcher.definitions(),
                        ContextBudgetPolicy.from(session.limits()));
                events.contextBudgetEvaluated(snapshot.budget());
                if (snapshot.compacted()) {
                    events.contextCompacted(snapshot.compactionDecision());
                }
                int stepNo = turn.nextStepNo();
                stateStore.markTurnStreaming(turn.turnId(), stepNo);
                turn.beginNextStep();
                events.modelRequestStarted(turn.stepCount());
                ModelResponse response = streamModelWithRetry(
                        session, turn, snapshot, events, cancellationToken);
                finishIfStopped(turn, session, cancellationToken, false);
                if (!response.toolCalls().isEmpty()) {
                    try {
                        turn.registerAllToolCallIdsOrThrow(response.toolCalls());
                    } catch (IllegalArgumentException exception) {
                        throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                                "model reused a tool call id in the same turn", exception);
                    }
                }
                if (response.toolCalls().isEmpty() && response.visibleText().isBlank()) {
                    throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                            "model completed without final text");
                }
                events.modelRequestCompleted(turn.stepCount(), response.finishReason());

                if (response.toolCalls().isEmpty()) {
                    List<Message> completedMessages = new ArrayList<>(currentTurn);
                    completedMessages.add(new Message.AssistantMessage(
                            turn.turnId(), response.visibleText()));
                    TurnDigest digest = digestFactory.create(
                            new CanonicalHistory.TurnHistory(turn.turnId(), completedMessages));
                    finishIfStopped(turn, session, cancellationToken, false);
                    AgentTurn.TerminalSnapshot terminal =
                            turn.prepareCompletion(clock.instant());
                    AgentResult completedResult = completedResult(
                            session, terminal, response.visibleText());
                    finishIfStopped(turn, session, cancellationToken, false);
                    stateStore.completeTurn(turn.turnId(), turn.stepCount(), response,
                            snapshot.budget().estimatedInputTokens(), digest,
                            terminal.finishedAt());
                    turn.sealTerminal(terminal);
                    events.turnDigestCreated(digest, terminal.finishedAt());
                    events.turnCompleted(terminal.finishedAt());
                    return completedResult;
                }

                finishIfStopped(turn, session, cancellationToken, false);
                StateStore.StagedModelStep step = stateStore.stageToolStep(
                        turn.turnId(), turn.stepCount(), response,
                        snapshot.budget().estimatedInputTokens());
                List<Message.ToolResultMessage> resultMessages = new ArrayList<>();
                for (ToolCall call : response.toolCalls()) {
                    finishIfStopped(turn, session, cancellationToken, false);
                    stateStore.markToolExecuting(step, call);
                    finishIfStopped(turn, session, cancellationToken, false);
                    turn.beginToolCall();
                    ToolResult result = executeTool(call, session, turn, cancellationToken, events);
                    stateStore.recordToolResult(step, call, result);
                    resultMessages.add(new Message.ToolResultMessage(
                            turn.turnId(), call.callId(), result));
                    finishIfStopped(turn, session, cancellationToken, false);
                }
                finishIfStopped(turn, session, cancellationToken, false);
                stateStore.commitToolStep(step);
                currentTurn.add(new Message.AssistantToolCallsMessage(
                        turn.turnId(), response.visibleText(), response.toolCalls()));
                currentTurn.addAll(resultMessages);
            }
        } catch (AgentException exception) {
            return terminateOnce(session, turn, began,
                    preferHigherPriorityStop(turn, session, cancellationToken, exception), events);
        } catch (RuntimeException exception) {
            return terminateOnce(session, turn, began,
                    preferHigherPriorityStop(turn, session, cancellationToken,
                            new AgentException(ErrorCode.INTERNAL_ERROR,
                                    "agent turn failed unexpectedly ("
                                            + exception.getClass().getSimpleName() + ")",
                                    exception)), events);
        }
    }

    private AgentResult completedResult(AgentSession session,
                                        AgentTurn.TerminalSnapshot terminal,
                                        String finalText) {
        return AgentResult.completed(session.workspace().workspaceId(), session.sessionId(),
                terminal.turnId(), finalText, terminal.stepCount(), terminal.toolCallCount(),
                terminal.duration());
    }

    private AgentResult failedResult(AgentSession session,
                                     AgentTurn.TerminalSnapshot terminal) {
        return AgentResult.failed(session.workspace().workspaceId(), session.sessionId(),
                terminal.turnId(), terminal.status(), terminal.errorCode(),
                terminal.safeMessage(), terminal.stepCount(), terminal.toolCallCount(),
                terminal.duration());
    }

    private AgentResult terminateOnce(AgentSession session, AgentTurn turn, boolean began,
                                      AgentException failure, EventEmitter events) {
        Instant finishedAt = clock.instant();
        AgentTurn.TerminalSnapshot terminal = turn.prepareFailure(
                terminalStatus(failure.errorCode()), failure.errorCode(),
                secretRedactor.redact(failure.getMessage()), finishedAt);
        AgentResult result = failedResult(session, terminal);
        if (began) {
            try {
                stateStore.failTurn(turn.turnId(), terminal.status(),
                        terminal.errorCode(), terminal.finishedAt());
            } catch (RuntimeException persistenceFailure) {
                terminal = turn.prepareFailure(TurnStatus.FAILED, ErrorCode.STORAGE_ERROR,
                        "could not persist the terminal turn state", finishedAt);
                result = failedResult(session, terminal);
            }
        }
        turn.sealTerminal(terminal);
        events.turnTerminated(terminal);
        return result;
    }

    private ModelResponse streamModelWithRetry(
            AgentSession session, AgentTurn turn, ContextSnapshot snapshot,
            EventEmitter events, CancellationToken cancellationToken) {
        Instant turnDeadline = turn.startedAt().plus(session.limits().turnTimeout());
        Instant modelDeadline = min(turnDeadline,
                clock.instant().plus(session.limits().modelTimeout()));
        for (int attempt = 1; attempt <= ModelRetryPolicy.MAX_ATTEMPTS; attempt++) {
            finishIfStopped(turn, session, cancellationToken, false);
            Duration remaining = Duration.between(clock.instant(), modelDeadline);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new AgentException(ErrorCode.MODEL_TIMEOUT,
                        "model request timed out");
            }
            ModelResponseAccumulator accumulator =
                    new ModelResponseAccumulator(objectMapper, maxResponseCharacters);
            SecretRedactor.StreamingRedactor streamingText = secretRedactor.streaming();
            boolean[] semanticDeltaSeen = {false};
            try {
                modelClient.stream(new ModelRequest(model, snapshot.messages(),
                                toolDispatcher.definitions(), remaining,
                                session.limits().reservedOutputTokens(),
                                turn.thinkingEnabled()), event -> {
                            if (isSemanticDelta(event)) {
                                semanticDeltaSeen[0] = true;
                            }
                            consumeEvent(event, accumulator, events, streamingText);
                        }, cancellationToken);
                ModelResponse response = accumulator.response();
                rejectSecretInProtocol(response);
                return response;
            } catch (AgentException failure) {
                finishIfStopped(turn, session, cancellationToken, false);
                Duration retryRemaining = Duration.between(clock.instant(), modelDeadline);
                ModelRetryPolicy.Decision decision = modelRetryPolicy.evaluate(
                        attempt, failure, semanticDeltaSeen[0],
                        cancellationToken.isCancelled(), retryRemaining);
                if (!decision.retry()) {
                    if (decision.deadlineExhausted()) {
                        throw new AgentException(ErrorCode.MODEL_TIMEOUT,
                                "model request timed out");
                    }
                    throw failure;
                }
                events.retryScheduled(decision.nextAttempt(),
                        ModelRetryPolicy.MAX_ATTEMPTS, decision.delay(), failure.errorCode());
                retryWaiter.await(decision.delay(), cancellationToken);
                finishIfStopped(turn, session, cancellationToken, false);
                if (!clock.instant().isBefore(modelDeadline)) {
                    throw new AgentException(ErrorCode.MODEL_TIMEOUT,
                            "model request timed out");
                }
            }
        }
        throw new IllegalStateException("model retry loop exhausted without an outcome");
    }

    private static boolean isSemanticDelta(ModelStreamEvent event) {
        return event instanceof ModelStreamEvent.TextDelta text && !text.text().isEmpty()
                || event instanceof ModelStreamEvent.ToolCallDelta;
    }

    private static Instant min(Instant first, Instant second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private ToolResult executeTool(ToolCall call, AgentSession session, AgentTurn turn,
                                   CancellationToken cancellationToken, EventEmitter events) {
        events.toolStarted(call.callId(), call.name());
        ToolResult result = toolDispatcher.dispatch(call,
                new ToolContext(session.workspace().workspaceId(),
                        session.workspace().root(), turn.turnId(), call.callId(),
                        turn.startedAt().plus(session.limits().turnTimeout()),
                        session.limits(), cancellationToken));
        events.toolCompleted(call.callId(), call.name(), result.success());
        return result;
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

    private void finishIfStopped(AgentTurn turn, AgentSession session,
                                 CancellationToken cancellationToken,
                                 boolean beforeModelStep) {
        stopPolicy.evaluate(turn, session.limits(), cancellationToken, beforeModelStep)
                .ifPresent(decision -> {
                    throw new AgentException(decision.errorCode(), decision.safeMessage());
                });
    }

    private AgentException preferHigherPriorityStop(
            AgentTurn turn, AgentSession session, CancellationToken cancellationToken,
            AgentException operationFailure) {
        return stopPolicy.evaluate(turn, session.limits(), cancellationToken, false)
                .<AgentException>map(decision -> new AgentException(
                        decision.errorCode(), decision.safeMessage(), operationFailure))
                .orElse(operationFailure);
    }

    private static void consumeEvent(ModelStreamEvent event,
                                     ModelResponseAccumulator accumulator,
                                     EventEmitter events,
                                     SecretRedactor.StreamingRedactor streamingText) {
        if (event instanceof ModelStreamEvent.TextDelta textDelta) {
            String safeText = streamingText.accept(textDelta.text());
            if (!safeText.isEmpty()) {
                accumulator.onEvent(new ModelStreamEvent.TextDelta(safeText));
                events.modelTextDelta(safeText);
            }
        } else if (event instanceof ModelStreamEvent.ResponseFinished) {
            String safeTail = streamingText.finish();
            if (!safeTail.isEmpty()) {
                accumulator.onEvent(new ModelStreamEvent.TextDelta(safeTail));
                events.modelTextDelta(safeTail);
            }
            accumulator.onEvent(event);
        } else if (event instanceof ModelStreamEvent.ToolCallDelta toolDelta) {
            accumulator.onEvent(event);
            String arguments = toolDelta.argumentsDelta();
            if (arguments != null && !arguments.isEmpty()) {
                events.modelToolCallDelta(toolDelta.index(), arguments.length());
            }
        } else {
            accumulator.onEvent(event);
        }
    }

    private void rejectSecretInProtocol(ModelResponse response) {
        if (secretRedactor.containsSecret(response.providerResponseId())) {
            throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                    "model response metadata contains protected credentials");
        }
        for (ToolCall call : response.toolCalls()) {
            if (secretRedactor.containsSecret(call.callId())
                    || secretRedactor.containsSecret(call.name())
                    || secretRedactor.containsSecret(call.arguments().toString())) {
                throw new AgentException(ErrorCode.MODEL_PROTOCOL_ERROR,
                        "model tool call contains protected credentials");
            }
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
        private final Clock clock;
        private long sequence;

        private EventEmitter(WorkspaceId workspaceId, SessionId sessionId, TurnId turnId,
                             AgentEventSink sink, Clock clock) {
            this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.turnId = Objects.requireNonNull(turnId, "turnId");
            this.sink = sink;
            this.clock = Objects.requireNonNull(clock, "clock");
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

        private void modelToolCallDelta(int index, int characters) {
            emit(new AgentEvent.ModelToolCallDelta(workspaceId, sessionId, turnId,
                    next(), now(), index, characters));
        }

        private void modelRequestCompleted(int step, String finishReason) {
            emit(new AgentEvent.ModelRequestCompleted(workspaceId, sessionId, turnId,
                    next(), now(), step, finishReason));
        }

        private void retryScheduled(int nextAttempt, int maxAttempts,
                                    Duration delay, ErrorCode errorCode) {
            emit(new AgentEvent.RetryScheduled(workspaceId, sessionId, turnId,
                    next(), now(), nextAttempt, maxAttempts, delay.toMillis(), errorCode));
        }

        private void toolStarted(String callId, String toolName) {
            emit(new AgentEvent.ToolStarted(workspaceId, sessionId, turnId,
                    next(), now(), callId, toolName));
        }

        private void toolCompleted(String callId, String toolName, boolean success) {
            emit(new AgentEvent.ToolCompleted(workspaceId, sessionId, turnId,
                    next(), now(), callId, toolName, success));
        }

        private void contextBudgetEvaluated(ContextSnapshot.Budget budget) {
            emit(new AgentEvent.ContextBudgetEvaluated(workspaceId, sessionId, turnId,
                    next(), now(), budget.fixedTokens(), budget.toolTokens(),
                    budget.currentTokens(), budget.recentTokens(), budget.digestTokens(),
                    budget.estimatedInputTokens(), budget.reservedOutputTokens(),
                    budget.maxInputTokens()));
        }

        private void contextCompacted(ContextSnapshot.CompactionDecision decision) {
            emit(new AgentEvent.ContextCompacted(workspaceId, sessionId, turnId,
                    next(), now(), decision.fullTurnIds(), decision.digestTurnIds(),
                    decision.omittedTurnCount(), decision.estimatedTokensBefore(),
                    decision.estimatedTokensAfter(), "TOKEN_BUDGET"));
        }

        private void turnDigestCreated(TurnDigest digest, Instant finishedAt) {
            emit(new AgentEvent.TurnDigestCreated(workspaceId, sessionId, turnId,
                    next(), finishedAt, digest.filesModified().size(), digest.commands().size(),
                    digest.importantErrors().size()));
        }

        private void turnCompleted(Instant finishedAt) {
            emit(new AgentEvent.TurnCompleted(
                    workspaceId, sessionId, turnId, next(), finishedAt));
        }

        private void turnTerminated(AgentTurn.TerminalSnapshot terminal) {
            if (terminal.status() == TurnStatus.CANCELLED) {
                emit(new AgentEvent.TurnCancelled(
                        workspaceId, sessionId, turnId, next(), terminal.finishedAt()));
            } else if (terminal.status() == TurnStatus.LIMIT_REACHED) {
                emit(new AgentEvent.TurnLimitReached(
                        workspaceId, sessionId, turnId, next(), terminal.finishedAt(),
                        terminal.errorCode(), terminal.safeMessage()));
            } else {
                emit(new AgentEvent.TurnFailed(
                        workspaceId, sessionId, turnId, next(), terminal.finishedAt(),
                        terminal.errorCode(), terminal.safeMessage()));
            }
        }

        private long next() { return ++sequence; }

        private Instant now() { return clock.instant(); }

        private void emit(AgentEvent event) {
            try {
                sink.publish(event);
            } catch (RuntimeException ignored) {
                // A display adapter cannot change the agent result.
            }
        }
    }
}
