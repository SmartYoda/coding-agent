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
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.persistence.StateStore;
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
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

        SqliteStateStore.open(application.config().databasePath(),
                application.config().databaseBusyTimeout());

        assertEquals("INTERRUPTED", new SqliteStateFixture(application.config().databasePath())
                .readTurnStatus(result.turnId()));
    }

    private TestApplication application(Path tempDirectory, ModelClient model,
                                        ToolRegistry tools) throws Exception {
        return application(tempDirectory, model, tools, null);
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
        AgentConfig config = new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
        SqliteStateStore store = SqliteStateStore.open(
                config.databasePath(), config.databaseBusyTimeout());
        StateStore effectiveStore = failurePoint == null
                ? store : new FailingStateStore(store, failurePoint);
        Path root = tempDirectory.resolve("workspace");
        Files.createDirectories(root);
        WorkspaceRegistry workspaces = new WorkspaceRegistry(effectiveStore,
                new WorkspaceResolver(config.dataDirectory()));
        WorkspaceDescriptor workspace = workspaces.register("Workspace", root);
        SessionRegistry sessions = new SessionRegistry(effectiveStore, workspaces);
        AgentRunner runner = new AgentRunner(model,
                new ToolDispatcher(tools, new SecretRedactor(config.apiKey())::redact,
                        new ToolOutputTruncator()), objectMapper, config.model(),
                config.maxResponseCharacters(), effectiveStore,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory());
        DefaultAgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT);
        SessionDescriptor session = service.openSession(
                new SessionConfig(workspace.workspaceId(), runLimits));
        return new TestApplication(config, store, service, session);
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
}
