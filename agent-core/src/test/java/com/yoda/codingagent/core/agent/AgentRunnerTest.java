package com.yoda.codingagent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.AgentEvent;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.CommandAccessMode;
import com.yoda.codingagent.core.api.CommandApprovalDecision;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.ThinkingMode;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelRetryPolicy;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.model.RetryWaiter;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateFixture;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolArguments;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolOutputTruncator;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.tool.builtin.ExecuteCommandTool;
import com.yoda.codingagent.core.tool.process.CommandResult;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesPersistedToolRoundTripAndExecutesOnlyOnce(@TempDir Path tempDirectory)
            throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("one"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "call-1", "test_", "{\"value\":"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "", "echo", "\"hello\"}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("two"),
                        new ModelStreamEvent.TextDelta("任务完成"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions, tempDirectory.resolve("workspace")))));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("运行测试工具"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("任务完成", result.finalText());
        assertEquals(1, executions.get());
        assertEquals(2, model.requests.size());
        assertInstanceOf(Message.AssistantToolCallsMessage.class,
                model.requests.get(1).messages().get(2));
        assertInstanceOf(Message.ToolResultMessage.class,
                model.requests.get(1).messages().get(3));
        assertEquals(1, application.store().loadCanonicalHistory(
                application.session().sessionId()).completedTurns().size());
        assertTrue(events.stream().anyMatch(AgentEvent.ModelTextDelta.class::isInstance));
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1L, events.get(index).sequence());
        }
    }

    @Test
    void askModeEmitsApprovalEventsAndPersistsTheCapturedMode(@TempDir Path tempDirectory)
            throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("one"),
                        new ModelStreamEvent.ToolCallDelta(0, "approval-call",
                                "execute_command",
                                "{\"argv\":[\"curl\",\"https://example.com\"]}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("two"),
                        new ModelStreamEvent.TextDelta("approved"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        ExecuteCommandTool commandTool = new ExecuteCommandTool(
                tempDirectory.resolve("state"),
                (argv, cwd, timeout, maximumBytes, token) -> {
                    executions.incrementAndGet();
                    return new CommandResult(0, "ok", "", Duration.ofMillis(1),
                            false, false, false, null);
                });
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(commandTool)));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(
                application.session().sessionId(),
                new AgentRequest("download", ThinkingMode.DEFAULT, CommandAccessMode.ASK),
                events::add, CancellationToken.NONE,
                (request, token) -> CommandApprovalDecision.APPROVED);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, executions.get());
        assertTrue(events.stream().anyMatch(event ->
                event instanceof AgentEvent.CommandApprovalRequested requested
                        && requested.callId().equals("approval-call")
                        && !requested.approvalId().equals(requested.callId())));
        assertTrue(events.stream().anyMatch(event ->
                event instanceof AgentEvent.CommandApprovalResolved resolved
                        && resolved.decision() == CommandApprovalDecision.APPROVED));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + application.config().databasePath());
             var statement = connection.prepareStatement(
                     "SELECT command_access_mode FROM turns WHERE id = ?")) {
            statement.setString(1, result.turnId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals("ASK", resultSet.getString(1));
            }
        }
    }

    @Test
    void resolvesThinkingPerTurnAndPersistsTheEffectiveValue(@TempDir Path tempDirectory)
            throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("tool"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "call-1", "test_echo", "{\"value\":\"hello\"}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("default-final"),
                        new ModelStreamEvent.TextDelta("default done"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("enabled"),
                        new ModelStreamEvent.TextDelta("enabled done"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("disabled"),
                        new ModelStreamEvent.TextDelta("disabled done"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded())));
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(new AtomicInteger(),
                        tempDirectory.resolve("workspace")))), true);

        application.service().runTurn(application.session().sessionId(),
                new AgentRequest("default"), ignored -> { }, CancellationToken.NONE);
        application.service().runTurn(application.session().sessionId(),
                new AgentRequest("enabled", ThinkingMode.ENABLED),
                ignored -> { }, CancellationToken.NONE);
        application.service().runTurn(application.session().sessionId(),
                new AgentRequest("disabled", ThinkingMode.DISABLED),
                ignored -> { }, CancellationToken.NONE);

        assertEquals(List.of(true, true, true, false), model.requests.stream()
                .map(ModelRequest::thinkingEnabled).toList());
        List<Integer> persisted = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + application.config().databasePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT thinking_enabled FROM turns ORDER BY turn_no")) {
            while (resultSet.next()) {
                persisted.add(resultSet.getInt(1));
            }
        }
        assertEquals(List.of(1, 1, 0), persisted);
    }

    @Test
    void preservesWhitespaceOnlyStreamDeltasAndCompletesTurn(@TempDir Path tempDirectory)
            throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.TextDelta("修改完成"),
                new ModelStreamEvent.TextDelta("\n"),
                new ModelStreamEvent.TextDelta("已验证"),
                new ModelStreamEvent.ResponseFinished("stop"),
                new ModelStreamEvent.StreamEnded())));
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("完成任务"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("修改完成\n已验证", result.finalText());
        String streamed = events.stream().filter(AgentEvent.ModelTextDelta.class::isInstance)
                .map(AgentEvent.ModelTextDelta.class::cast)
                .map(AgentEvent.ModelTextDelta::text)
                .collect(java.util.stream.Collectors.joining());
        assertEquals(result.finalText(), streamed);
        assertEquals(1, application.store().loadCanonicalHistory(
                application.session().sessionId()).completedTurns().size());
    }

    @Test
    void incompleteToolCallNeverExecutesOrEntersCanonicalHistory(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "call-1", "test_echo", "{\"value\":"))));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions, tempDirectory.resolve("workspace")))));

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(0, executions.get());
        assertTrue(application.store().loadCanonicalHistory(application.session().sessionId())
                .completedTurns().isEmpty());
    }

    @Test
    void rejectsCallIdReusedByLaterModelStepBeforeSecondExecution(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("one"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "same-call", "test_echo", "{\"value\":\"one\"}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("two"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "same-call", "test_echo", "{\"value\":\"two\"}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.MODEL_PROTOCOL_ERROR, result.errorCode());
        assertEquals(1, executions.get());
        assertEquals(1, events.stream().filter(AgentEvent.ToolStarted.class::isInstance).count());
    }

    @Test
    void rejectsDuplicateCallIdInOneModelResponseBeforeAnyExecution(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "same-call", "test_echo", "{\"value\":\"one\"}"),
                new ModelStreamEvent.ToolCallDelta(
                        1, "same-call", "test_echo", "{\"value\":\"two\"}"),
                new ModelStreamEvent.ResponseFinished("tool_calls"),
                new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.MODEL_PROTOCOL_ERROR, result.errorCode());
        assertEquals(0, executions.get());
        assertEquals(0, events.stream().filter(AgentEvent.ToolStarted.class::isInstance).count());
        assertTrue(application.store().loadCanonicalHistory(application.session().sessionId())
                .completedTurns().isEmpty());
    }

    @Test
    void feedsUnknownAndInvalidToolFailuresBackUntilTheModelCompletes(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("one"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "unknown", "missing_tool", "{}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("two"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "invalid", "test_echo", "{\"extra\":true}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("three"),
                        new ModelStreamEvent.TextDelta("recovered"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))));

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("recovered", result.finalText());
        assertEquals(0, executions.get());
        assertEquals(3, model.requests.size());
        Message.ToolResultMessage unknown = (Message.ToolResultMessage)
                model.requests.get(1).messages().getLast();
        Message.ToolResultMessage invalid = (Message.ToolResultMessage)
                model.requests.get(2).messages().getLast();
        assertEquals(ErrorCode.UNKNOWN_TOOL, unknown.result().errorCode());
        assertEquals(ErrorCode.INVALID_TOOL_ARGUMENTS, invalid.result().errorCode());
    }

    @Test
    void boundsToolOutputBeforeModelFeedbackAndPersistence(@TempDir Path tempDirectory)
            throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                List.of(new ModelStreamEvent.ResponseStarted("one"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "call-1", "test_large", "{}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("two"),
                        new ModelStreamEvent.TextDelta("done"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded())));
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        Tool largeTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test_large", "Return large output", schema);
            }

            @Override
            public ToolResult execute(ToolContext context, ToolArguments arguments) {
                arguments.allowOnly();
                return ToolResult.success("test-key" + "x".repeat(20_000), false,
                        Map.of("secretValue", "prefix-test-key-suffix"));
            }
        };
        RunLimits boundedLimits = new RunLimits(4, Duration.ofMinutes(2),
                Duration.ofSeconds(30), Duration.ofSeconds(10),
                1_024, 8_192, 1_024, 2);
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(largeTool)), null, boundedLimits);

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("large"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        Message.ToolResultMessage feedback = (Message.ToolResultMessage) model.requests.get(1)
                .messages().stream().filter(Message.ToolResultMessage.class::isInstance)
                .findFirst().orElseThrow();
        assertEquals(1_024, feedback.content().length());
        assertTrue(feedback.result().truncated());
        assertFalse(feedback.content().contains("test-key"));
        assertFalse(feedback.result().metadata().get("secretValue").contains("test-key"));
        Message.ToolResultMessage persisted = (Message.ToolResultMessage) application.store()
                .loadCanonicalHistory(application.session().sessionId()).messages().stream()
                .filter(Message.ToolResultMessage.class::isInstance)
                .findFirst().orElseThrow();
        assertEquals(feedback.result(), persisted.result());
        try (var stateFiles = Files.list(application.config().dataDirectory())) {
            for (Path stateFile : stateFiles.filter(Files::isRegularFile).toList()) {
                String persistedBytes = new String(Files.readAllBytes(stateFile),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(persistedBytes.contains("test-key"), stateFile.toString());
            }
        }
    }

    @Test
    void beginTurnStorageFailureCallsNeitherModelNorTool(@TempDir Path tempDirectory)
            throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.TextDelta("unused"),
                new ModelStreamEvent.ResponseFinished("stop"),
                new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions, tempDirectory.resolve("workspace")))),
                FailingStateStore.FailurePoint.BEGIN_TURN);

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(0, model.requests.size());
        assertEquals(0, executions.get());
    }

    @Test
    void markStreamingFailureDoesNotConsumeAStepOrCallTheModel(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = finalTextModel("unused");
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()),
                FailingStateStore.FailurePoint.MARK_TURN_STREAMING);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(0, result.stepCount());
        assertEquals(0, result.toolCallCount());
        assertEquals(0, model.requests.size());
        assertEquals("FAILED", new SqliteStateFixture(application.config().databasePath())
                .readTurnStatus(result.turnId()));
        assertSingleTerminalEvent(events);
    }

    @Test
    void stagedWriteFailureExecutesNoToolAndStartsNoSecondModelRequest(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "call-1", "test_echo", "{\"value\":\"hello\"}"),
                new ModelStreamEvent.ResponseFinished("tool_calls"),
                new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions, tempDirectory.resolve("workspace")))),
                FailingStateStore.FailurePoint.STAGE_TOOL_STEP);

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(1, model.requests.size());
        assertEquals(0, executions.get());
        assertTrue(application.store().loadCanonicalHistory(application.session().sessionId())
                .completedTurns().isEmpty());
    }

    @Test
    void resultWriteFailureAuditsExecutedCallAsUnknownAndAbortsStep(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "call-1", "test_echo", "{\"value\":\"hello\"}"),
                new ModelStreamEvent.ResponseFinished("tool_calls"),
                new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions, tempDirectory.resolve("workspace")))),
                FailingStateStore.FailurePoint.RECORD_TOOL_RESULT);

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(1, executions.get());
        assertEquals(1, model.requests.size());
        var state = new SqliteStateFixture(application.config().databasePath())
                .readRecoveryState(result.turnId());
        assertEquals("FAILED", state.turnStatus());
        assertEquals("ABORTED", state.stepStatus());
        assertEquals(List.of("UNKNOWN"), state.toolStatuses());
    }

    @Test
    void markToolExecutingFailureCancelsPendingCallWithoutExecutingIt(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = oneToolModel();
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))),
                FailingStateStore.FailurePoint.MARK_TOOL_EXECUTING);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(1, result.stepCount());
        assertEquals(0, result.toolCallCount());
        assertEquals(0, executions.get());
        assertEquals(1, model.requests.size());
        var state = new SqliteStateFixture(application.config().databasePath())
                .readRecoveryState(result.turnId());
        assertEquals("FAILED", state.turnStatus());
        assertEquals("ABORTED", state.stepStatus());
        assertEquals(List.of("CANCELLED"), state.toolStatuses());
        assertSingleTerminalEvent(events);
    }

    @Test
    void cancellationObservedAfterMarkExecutingPreventsToolSideEffect(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = oneToolModel();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger cancellationChecks = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))));
        List<AgentEvent> events = new ArrayList<>();
        CancellationToken token = () -> cancellationChecks.incrementAndGet() >= 6;

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, token);

        assertEquals(TurnStatus.CANCELLED, result.status());
        assertEquals(ErrorCode.CANCELLED, result.errorCode());
        assertEquals(0, result.toolCallCount());
        assertEquals(0, executions.get());
        var state = new SqliteStateFixture(application.config().databasePath())
                .readRecoveryState(result.turnId());
        assertEquals("CANCELLED", state.turnStatus());
        assertEquals("ABORTED", state.stepStatus());
        assertEquals(List.of("UNKNOWN"), state.toolStatuses());
        assertSingleTerminalEvent(events);
    }

    @Test
    void commitToolStepFailurePreservesAuditButStartsNoSecondModelRequest(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = oneToolModel();
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))),
                FailingStateStore.FailurePoint.COMMIT_TOOL_STEP);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(1, result.stepCount());
        assertEquals(1, result.toolCallCount());
        assertEquals(1, executions.get());
        assertEquals(1, model.requests.size());
        var state = new SqliteStateFixture(application.config().databasePath())
                .readRecoveryState(result.turnId());
        assertEquals("FAILED", state.turnStatus());
        assertEquals("ABORTED", state.stepStatus());
        assertEquals(List.of("SUCCESS"), state.toolStatuses());
        assertSingleTerminalEvent(events);
    }

    @Test
    void completeTurnFailurePublishesOnlyFailureAndCommitsNoHistory(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = finalTextModel("done");
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()), FailingStateStore.FailurePoint.COMPLETE_TURN);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals(1, result.stepCount());
        assertEquals(0, result.toolCallCount());
        assertEquals(1, model.requests.size());
        assertTrue(application.store().loadCanonicalHistory(application.session().sessionId())
                .completedTurns().isEmpty());
        assertEquals(0, events.stream()
                .filter(AgentEvent.TurnCompleted.class::isInstance).count());
        assertEquals(0, events.stream()
                .filter(AgentEvent.TurnDigestCreated.class::isInstance).count());
        assertSingleTerminalEvent(events);
    }

    @Test
    void startupRecoversTurnWhenWritingItsFailureStateAlsoFailed(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ResponseFinished("stop"),
                new ModelStreamEvent.StreamEnded())));
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()), FailingStateStore.FailurePoint.FAIL_TURN);

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.STORAGE_ERROR, result.errorCode());
        assertEquals("STREAMING_MODEL", new SqliteStateFixture(application.config().databasePath())
                .readTurnStatus(result.turnId()));

        application.dataDirectoryLock().close();
        DataDirectoryLock restartLock = DataDirectoryLock.acquire(
                application.config().dataDirectory());
        SqliteStateStore.open(restartLock, application.config().databasePath(),
                application.config().databaseBusyTimeout());

        assertEquals("INTERRUPTED", new SqliteStateFixture(application.config().databasePath())
                .readTurnStatus(result.turnId()));
    }

    @Test
    void retriesTransientModelFailureTwiceWithinOneLogicalStep(
            @TempDir Path tempDirectory) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Boolean> thinkingValues = new ArrayList<>();
        ModelClient model = (request, sink, token) -> {
            thinkingValues.add(request.thinkingEnabled());
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new AgentException(ErrorCode.MODEL_UNAVAILABLE,
                        "temporary model failure");
            }
            sink.onEvent(new ModelStreamEvent.ResponseStarted("success"));
            sink.onEvent(new ModelStreamEvent.TextDelta("done"));
            sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
            sink.onEvent(new ModelStreamEvent.StreamEnded());
        };
        List<Duration> waits = new ArrayList<>();
        RetryWaiter waiter = (delay, token) -> waits.add(delay);
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()), null, limits(), waiter);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run", ThinkingMode.ENABLED),
                events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, result.stepCount());
        assertEquals(3, attempts.get());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
        assertEquals(List.of(true, true, true), thinkingValues);
        assertEquals(1, events.stream()
                .filter(AgentEvent.ModelRequestStarted.class::isInstance).count());
        assertEquals(2, events.stream()
                .filter(AgentEvent.RetryScheduled.class::isInstance).count());
        assertEquals(1, events.stream()
                .filter(AgentEvent.ModelRequestCompleted.class::isInstance).count());
    }

    @Test
    void retryAttemptsShareOneLogicalModelDeadline(@TempDir Path tempDirectory)
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ModelClient unavailable = (request, sink, token) -> {
            attempts.incrementAndGet();
            throw new AgentException(ErrorCode.MODEL_UNAVAILABLE, "temporary failure");
        };
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        List<Duration> waits = new ArrayList<>();
        RetryWaiter waiter = (delay, token) -> {
            waits.add(delay);
            clock.advance(delay);
        };
        RunLimits limits = new RunLimits(4, Duration.ofSeconds(30), Duration.ofSeconds(3),
                Duration.ofSeconds(3), 16_384, 8_192, 1_024, 2);
        TestApplication application = application(tempDirectory, unavailable,
                new ToolRegistry(List.of()), null, limits, waiter, clock);
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(ErrorCode.MODEL_TIMEOUT, result.errorCode());
        assertEquals(2, attempts.get());
        assertEquals(List.of(Duration.ofSeconds(1)), waits);
        assertEquals(1, result.stepCount());
        assertEquals(1, events.stream()
                .filter(AgentEvent.RetryScheduled.class::isInstance).count());
    }

    @Test
    void neverRetriesAfterSemanticDelta(@TempDir Path tempDirectory) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ModelClient model = (request, sink, token) -> {
            attempts.incrementAndGet();
            sink.onEvent(new ModelStreamEvent.ResponseStarted("partial"));
            sink.onEvent(new ModelStreamEvent.TextDelta("visible"));
            throw new AgentException(ErrorCode.MODEL_UNAVAILABLE,
                    "stream failed after output");
        };
        List<Duration> waits = new ArrayList<>();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()), null, limits(),
                (delay, token) -> waits.add(delay));

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), ignored -> { }, CancellationToken.NONE);

        assertEquals(ErrorCode.MODEL_UNAVAILABLE, result.errorCode());
        assertEquals(1, attempts.get());
        assertTrue(waits.isEmpty());
    }

    @Test
    void eventSinkFailureCannotChangeThePersistedTurnResult(@TempDir Path tempDirectory)
            throws Exception {
        TestApplication application = application(tempDirectory, finalTextModel("done"),
                new ToolRegistry(List.of()));

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), event -> {
                    throw new IllegalStateException("display failed");
                }, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("COMPLETED", new SqliteStateFixture(application.config().databasePath())
                .readTurnStatus(result.turnId()));
    }

    @Test
    void cancellationAndContextLimitPublishClassifiedTerminalsAndReleaseLease(
            @TempDir Path tempDirectory) throws Exception {
        TestApplication cancelledApplication = application(tempDirectory.resolve("cancelled"),
                finalTextModel("next succeeds"), new ToolRegistry(List.of()));
        List<AgentEvent> cancelledEvents = new ArrayList<>();
        AgentResult cancelled = cancelledApplication.service().runTurn(
                cancelledApplication.session().sessionId(), new AgentRequest("cancel now"),
                cancelledEvents::add, () -> true);
        assertEquals(TurnStatus.CANCELLED, cancelled.status());
        assertEquals(ErrorCode.CANCELLED, cancelled.errorCode());
        assertEquals(1, cancelledEvents.stream()
                .filter(AgentEvent.TurnCancelled.class::isInstance).count());
        AgentResult afterCancellation = cancelledApplication.service().runTurn(
                cancelledApplication.session().sessionId(), new AgentRequest("continue"),
                ignored -> { }, CancellationToken.NONE);
        assertEquals(TurnStatus.COMPLETED, afterCancellation.status());

        TestApplication limitedApplication = application(tempDirectory.resolve("context"),
                finalTextModel("next succeeds"), new ToolRegistry(List.of()));
        List<AgentEvent> limitedEvents = new ArrayList<>();
        AgentResult limited = limitedApplication.service().runTurn(
                limitedApplication.session().sessionId(),
                new AgentRequest("x".repeat(AgentRequest.MAX_INPUT_CHARACTERS)),
                limitedEvents::add, CancellationToken.NONE);
        assertEquals(TurnStatus.LIMIT_REACHED, limited.status());
        assertEquals(ErrorCode.CONTEXT_LIMIT, limited.errorCode());
        assertEquals(1, limitedEvents.stream()
                .filter(AgentEvent.TurnLimitReached.class::isInstance).count());
        AgentResult afterLimit = limitedApplication.service().runTurn(
                limitedApplication.session().sessionId(), new AgentRequest("continue"),
                ignored -> { }, CancellationToken.NONE);
        assertEquals(TurnStatus.COMPLETED, afterLimit.status());
    }

    @Test
    void maxStepsAndTurnDeadlineProduceStableLimitResults(@TempDir Path tempDirectory)
            throws Exception {
        RunLimits oneStep = new RunLimits(1, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 8_192, 1_024, 2);
        AtomicInteger executions = new AtomicInteger();
        TestApplication stepLimited = application(tempDirectory.resolve("steps"), oneToolModel(),
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("steps/workspace")))), null, oneStep);
        List<AgentEvent> stepEvents = new ArrayList<>();
        AgentResult stepResult = stepLimited.service().runTurn(
                stepLimited.session().sessionId(), new AgentRequest("run"),
                stepEvents::add, CancellationToken.NONE);
        assertEquals(TurnStatus.LIMIT_REACHED, stepResult.status());
        assertEquals(ErrorCode.TURN_LIMIT, stepResult.errorCode());
        assertEquals(1, stepResult.stepCount());
        assertEquals(1, stepResult.toolCallCount());
        assertEquals(1, executions.get());
        assertSingleTerminalEvent(stepEvents);

        RunLimits shortTurn = new RunLimits(4, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                16_384, 8_192, 1_024, 2);
        Clock advancing = new AdvancingClock(
                Instant.parse("2026-08-30T00:00:00Z"), Duration.ofSeconds(2));
        TestApplication timedOut = application(tempDirectory.resolve("timeout"),
                finalTextModel("unused"), new ToolRegistry(List.of()), null, shortTurn,
                (delay, token) -> { }, advancing);
        List<AgentEvent> timeoutEvents = new ArrayList<>();
        AgentResult timeout = timedOut.service().runTurn(timedOut.session().sessionId(),
                new AgentRequest("run"), timeoutEvents::add, CancellationToken.NONE);
        assertEquals(TurnStatus.LIMIT_REACHED, timeout.status());
        assertEquals(ErrorCode.TURN_LIMIT, timeout.errorCode());
        assertEquals(0, timeout.stepCount());
        assertSingleTerminalEvent(timeoutEvents);
    }

    @Test
    void redactsUserInputAndSecretSplitAcrossTextDeltasBeforeAnyOutputOrPersistence(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.TextDelta("prefix-te"),
                new ModelStreamEvent.TextDelta("st-key-suffix"),
                new ModelStreamEvent.ResponseFinished("stop"),
                new ModelStreamEvent.StreamEnded())));
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of()));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("input-test-key"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("prefix-<redacted>-suffix", result.finalText());
        assertFalse(model.requests.getFirst().messages().toString().contains("test-key"));
        String streamed = events.stream().filter(AgentEvent.ModelTextDelta.class::isInstance)
                .map(AgentEvent.ModelTextDelta.class::cast)
                .map(AgentEvent.ModelTextDelta::text)
                .collect(java.util.stream.Collectors.joining());
        assertEquals(result.finalText(), streamed);
        try (var stateFiles = Files.list(application.config().dataDirectory())) {
            for (Path stateFile : stateFiles.filter(Files::isRegularFile).toList()) {
                String persistedBytes = new String(Files.readAllBytes(stateFile),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(persistedBytes.contains("test-key"), stateFile.toString());
            }
        }
    }

    @Test
    void rejectsSecretSplitAcrossToolArgumentsBeforeStageOrExecution(
            @TempDir Path tempDirectory) throws Exception {
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "call-1", "test_echo", "{\"value\":\"te"),
                new ModelStreamEvent.ToolCallDelta(0, "", "", "st-key\"}"),
                new ModelStreamEvent.ResponseFinished("tool_calls"),
                new ModelStreamEvent.StreamEnded())));
        AtomicInteger executions = new AtomicInteger();
        TestApplication application = application(tempDirectory, model,
                new ToolRegistry(List.of(echoTool(executions,
                        tempDirectory.resolve("workspace")))));
        List<AgentEvent> events = new ArrayList<>();

        AgentResult result = application.service().runTurn(application.session().sessionId(),
                new AgentRequest("run"), events::add, CancellationToken.NONE);

        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(ErrorCode.MODEL_PROTOCOL_ERROR, result.errorCode());
        assertEquals(0, executions.get());
        assertEquals(0, events.stream()
                .filter(AgentEvent.ModelRequestCompleted.class::isInstance).count());
        assertEquals(0, events.stream()
                .filter(AgentEvent.ToolStarted.class::isInstance).count());
        assertTrue(application.store().loadCanonicalHistory(application.session().sessionId())
                .completedTurns().isEmpty());
        assertFalse(events.toString().contains("test-key"));
    }

    @Test
    void rejectsSecretsInProviderIdToolNameAndSplitCallIdWithoutPublicLeak(
            @TempDir Path tempDirectory) throws Exception {
        List<List<ModelStreamEvent>> scripts = List.of(
                List.of(new ModelStreamEvent.ResponseStarted("response-test-key"),
                        new ModelStreamEvent.TextDelta("done"),
                        new ModelStreamEvent.ResponseFinished("stop"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("response"),
                        new ModelStreamEvent.ToolCallDelta(
                                0, "call-1", "test-key", "{}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()),
                List.of(new ModelStreamEvent.ResponseStarted("response"),
                        new ModelStreamEvent.ToolCallDelta(0, "te", "test_echo", "{"),
                        new ModelStreamEvent.ToolCallDelta(0, "st-key", "", "}"),
                        new ModelStreamEvent.ResponseFinished("tool_calls"),
                        new ModelStreamEvent.StreamEnded()));
        for (int index = 0; index < scripts.size(); index++) {
            Path caseDirectory = tempDirectory.resolve("case-" + index);
            ScriptedModelClient model = new ScriptedModelClient(List.of(scripts.get(index)));
            AtomicInteger executions = new AtomicInteger();
            TestApplication application = application(caseDirectory, model,
                    new ToolRegistry(List.of(echoTool(executions,
                            caseDirectory.resolve("workspace")))));
            List<AgentEvent> events = new ArrayList<>();

            AgentResult result = application.service().runTurn(
                    application.session().sessionId(), new AgentRequest("run"),
                    events::add, CancellationToken.NONE);

            assertEquals(ErrorCode.MODEL_PROTOCOL_ERROR, result.errorCode());
            assertEquals(0, executions.get());
            assertEquals(0, events.stream()
                    .filter(AgentEvent.ModelRequestCompleted.class::isInstance).count());
            assertFalse(events.toString().contains("test-key"));
            assertFalse(result.toString().contains("test-key"));
            application.dataDirectoryLock().close();
        }
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools) throws Exception {
        return application(tempDirectory, model, tools, null);
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools, boolean defaultThinkingEnabled)
            throws Exception {
        return application(tempDirectory, model, tools, null, limits(), null,
                Clock.systemUTC(), defaultThinkingEnabled);
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools,
                                        FailingStateStore.FailurePoint failurePoint)
            throws Exception {
        return application(tempDirectory, model, tools, failurePoint, limits());
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools,
                                        FailingStateStore.FailurePoint failurePoint,
                                        RunLimits runLimits)
            throws Exception {
        return application(tempDirectory, model, tools, failurePoint, runLimits, null);
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools,
                                        FailingStateStore.FailurePoint failurePoint,
                                        RunLimits runLimits, RetryWaiter retryWaiter)
            throws Exception {
        return application(tempDirectory, model, tools, failurePoint, runLimits, retryWaiter,
                Clock.systemUTC());
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools,
                                        FailingStateStore.FailurePoint failurePoint,
                                        RunLimits runLimits, RetryWaiter retryWaiter,
                                        Clock clock) throws Exception {
        return application(tempDirectory, model, tools, failurePoint, runLimits, retryWaiter,
                clock, false);
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools,
                                        FailingStateStore.FailurePoint failurePoint,
                                        RunLimits runLimits, RetryWaiter retryWaiter,
                                        Clock clock, boolean defaultThinkingEnabled)
            throws Exception {
        AgentConfig config = new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
        DataDirectoryLock dataDirectoryLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore store = SqliteStateStore.open(dataDirectoryLock,
                config.databasePath(), config.databaseBusyTimeout());
        StateStore effectiveStore = failurePoint == null
                ? store : new FailingStateStore(store, failurePoint);
        Path root = tempDirectory.resolve("workspace");
        Files.createDirectories(root);
        WorkspaceRegistry workspaces = new WorkspaceRegistry(effectiveStore,
                new WorkspaceResolver(config.dataDirectory()));
        WorkspaceDescriptor workspace = workspaces.register("Workspace", root);
        SessionRegistry sessions = new SessionRegistry(effectiveStore, workspaces);
        ToolDispatcher dispatcher = new ToolDispatcher(tools,
                new SecretRedactor(config.apiKey())::redact, new ToolOutputTruncator());
        SecretRedactor redactor = new SecretRedactor(config.apiKey());
        AgentRunner runner = new AgentRunner(model, dispatcher, objectMapper, config.model(),
                config.maxResponseCharacters(), effectiveStore,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory(),
                new ModelRetryPolicy(), retryWaiter == null
                ? RetryWaiter.cancellableSleep() : retryWaiter, clock, redactor);
        DefaultAgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT, redactor,
                defaultThinkingEnabled);
        SessionDescriptor session = service.openSession(
                new SessionConfig(workspace.workspaceId(), runLimits));
        return new TestApplication(config, dataDirectoryLock, store, service, session);
    }

    private static ScriptedModelClient oneToolModel() {
        return new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.ToolCallDelta(
                        0, "call-1", "test_echo", "{\"value\":\"hello\"}"),
                new ModelStreamEvent.ResponseFinished("tool_calls"),
                new ModelStreamEvent.StreamEnded())));
    }

    private static ScriptedModelClient finalTextModel(String text) {
        return new ScriptedModelClient(List.of(List.of(
                new ModelStreamEvent.ResponseStarted("one"),
                new ModelStreamEvent.TextDelta(text),
                new ModelStreamEvent.ResponseFinished("stop"),
                new ModelStreamEvent.StreamEnded())));
    }

    private static void assertSingleTerminalEvent(List<AgentEvent> events) {
        long terminalEvents = events.stream().filter(event ->
                event instanceof AgentEvent.TurnCompleted
                        || event instanceof AgentEvent.TurnFailed
                        || event instanceof AgentEvent.TurnCancelled
                        || event instanceof AgentEvent.TurnLimitReached).count();
        assertEquals(1, terminalEvents);
    }

    private Tool echoTool(AtomicInteger executions, Path expectedRoot) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("value").put("type", "string");
        schema.putArray("required").add("value");
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test_echo", "Echo a test value", schema);
            }

            @Override
            public ToolResult execute(ToolContext context, ToolArguments arguments) {
                String value = arguments.allowOnly("value")
                        .requireString("value", 1, 1_000);
                try {
                    assertEquals(expectedRoot.toRealPath(), context.workspaceRoot());
                } catch (Exception exception) {
                    throw new AssertionError("workspace root must remain resolvable", exception);
                }
                executions.incrementAndGet();
                return ToolResult.success(value);
            }
        };
    }

    private static RunLimits limits() {
        return new RunLimits(4, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 8_192, 1_024, 2);
    }

    private record TestApplication(
            AgentConfig config,
            DataDirectoryLock dataDirectoryLock,
            SqliteStateStore store,
            DefaultAgentService service,
            SessionDescriptor session
    ) { }

    private static final class ScriptedModelClient implements ModelClient {
        private final List<List<ModelStreamEvent>> scripts;
        private final List<ModelRequest> requests = new ArrayList<>();

        private ScriptedModelClient(List<List<ModelStreamEvent>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public void stream(ModelRequest request, ModelStreamSink sink,
                           CancellationToken token) {
            int index = requests.size();
            requests.add(request);
            for (ModelStreamEvent event : scripts.get(index)) {
                sink.onEvent(event);
            }
        }
    }

    private static final class AdvancingClock extends Clock {
        private final Instant initial;
        private final Duration increment;
        private final AtomicInteger reads = new AtomicInteger();

        private AdvancingClock(Instant initial, Duration increment) {
            this.initial = initial;
            this.increment = increment;
        }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() {
            return initial.plus(increment.multipliedBy(reads.getAndIncrement()));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) { this.current = current; }

        private void advance(Duration duration) { current = current.plus(duration); }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return current; }
    }
}
