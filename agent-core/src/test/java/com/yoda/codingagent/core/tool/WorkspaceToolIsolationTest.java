package com.yoda.codingagent.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.agent.SessionRegistry;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelClient;
import com.yoda.codingagent.core.model.ModelRequest;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.model.ModelStreamSink;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import com.yoda.codingagent.core.tool.builtin.ExecuteCommandTool;
import com.yoda.codingagent.core.tool.builtin.ReadFileTool;
import com.yoda.codingagent.core.tool.process.CommandResult;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolIsolationTest {

    @Test
    void sessionsKeepSameNamedFilesCommandCwdAndLimitsInsideTheirBoundWorkspace(
            @TempDir Path temp) throws Exception {
        Path state = Files.createDirectory(temp.resolve("state"));
        Path alphaRoot = Files.createDirectory(temp.resolve("alpha")).toRealPath();
        Path betaRoot = Files.createDirectory(temp.resolve("beta")).toRealPath();
        Files.writeString(alphaRoot.resolve("same.txt"), "alpha-only");
        Files.writeString(betaRoot.resolve("same.txt"), "beta-only");
        DataDirectoryLock lock = DataDirectoryLock.acquire(state);
        SqliteStateStore store = SqliteStateStore.open(lock,
                state.resolve("agent.db"), Duration.ofSeconds(5));
        WorkspaceRegistry workspaces = new WorkspaceRegistry(
                store, new WorkspaceResolver(state));
        var alpha = workspaces.register("Alpha", alphaRoot);
        var beta = workspaces.register("Beta", betaRoot);
        SessionRegistry sessions = new SessionRegistry(store, workspaces);

        List<ToolContext> readContexts = new ArrayList<>();
        ReadFileTool readDelegate = new ReadFileTool(state);
        Tool capturingRead = new Tool() {
            @Override
            public ToolDefinition definition() {
                return readDelegate.definition();
            }

            @Override
            public ToolResult execute(ToolContext context, ToolArguments arguments) {
                readContexts.add(context);
                return readDelegate.execute(context, arguments);
            }
        };
        List<Path> commandDirectories = new ArrayList<>();
        List<Duration> commandTimeouts = new ArrayList<>();
        List<Integer> commandOutputLimits = new ArrayList<>();
        ExecuteCommandTool execute = new ExecuteCommandTool(state,
                (argv, cwd, timeout, maximumBytes, token) -> {
                    commandDirectories.add(cwd);
                    commandTimeouts.add(timeout);
                    commandOutputLimits.add(maximumBytes);
                    return new CommandResult(0, "tests passed", "", Duration.ofMillis(1),
                            false, false, false, null);
                });
        IsolationModel model = new IsolationModel(alphaRoot, betaRoot);
        AgentRunner runner = new AgentRunner(model,
                new ToolDispatcher(new ToolRegistry(List.of(capturingRead, execute)),
                        value -> value, new ToolOutputTruncator()),
                new ObjectMapper(), "test-model", 100_000, store,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory(),
                new SecretRedactor("test-key"));
        DefaultAgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT,
                new SecretRedactor("test-key"), false);
        RunLimits alphaLimits = limits(7, 4_096);
        RunLimits betaLimits = limits(11, 8_192);
        var alphaSession = service.openSession(
                new SessionConfig(alpha.workspaceId(), alphaLimits));
        var betaSession = service.openSession(
                new SessionConfig(beta.workspaceId(), betaLimits));

        AgentResult alphaResult = service.runTurn(alphaSession.sessionId(),
                new AgentRequest("inspect alpha"), ignored -> { }, CancellationToken.NONE);
        AgentResult betaResult = service.runTurn(betaSession.sessionId(),
                new AgentRequest("inspect beta"), ignored -> { }, CancellationToken.NONE);

        assertEquals(TurnStatus.COMPLETED, alphaResult.status(), alphaResult.errorMessage());
        assertEquals(TurnStatus.COMPLETED, betaResult.status(), betaResult.errorMessage());
        assertEquals(List.of(alphaRoot, betaRoot),
                readContexts.stream().map(ToolContext::workspaceRoot).toList());
        assertEquals(List.of(alpha.workspaceId(), beta.workspaceId()),
                readContexts.stream().map(ToolContext::workspaceId).toList());
        assertEquals(List.of(alphaLimits, betaLimits),
                readContexts.stream().map(ToolContext::runLimits).toList());
        assertTrue(readContexts.stream().allMatch(context ->
                context.turnDeadline().isAfter(Instant.now().plusSeconds(90))));
        assertEquals(List.of(alphaRoot, betaRoot), commandDirectories);
        assertEquals(List.of(Duration.ofSeconds(7), Duration.ofSeconds(11)), commandTimeouts);
        assertEquals(List.of(4_096, 8_192), commandOutputLimits);
        assertEquals(6, model.requests.size());
    }

    private static RunLimits limits(int commandTimeoutSeconds, int outputCharacters) {
        return new RunLimits(5, Duration.ofSeconds(120), Duration.ofSeconds(30),
                Duration.ofSeconds(commandTimeoutSeconds), outputCharacters,
                16_384, 1_024, 2);
    }

    private static final class IsolationModel implements ModelClient {
        private final Path alphaRoot;
        private final Path betaRoot;
        private final AtomicInteger step = new AtomicInteger();
        private final List<ModelRequest> requests = new ArrayList<>();

        private IsolationModel(Path alphaRoot, Path betaRoot) {
            this.alphaRoot = alphaRoot;
            this.betaRoot = betaRoot;
        }

        @Override
        public void stream(ModelRequest request, ModelStreamSink sink,
                           CancellationToken cancellationToken) {
            requests.add(request);
            int current = step.getAndIncrement();
            Path expectedRoot = current < 3 ? alphaRoot : betaRoot;
            String fixedContext = ((Message.SystemMessage) request.messages().getFirst())
                    .content();
            assertTrue(fixedContext.contains(expectedRoot.toString()));
            assertFalse(fixedContext.contains(
                    (current < 3 ? betaRoot : alphaRoot).toString()));
            switch (current % 3) {
                case 0 -> toolCall(sink, "read-" + current, "read_file",
                        "{\"path\":\"same.txt\"}");
                case 1 -> {
                    Message.ToolResultMessage result =
                            (Message.ToolResultMessage) request.messages().getLast();
                    assertTrue(result.result().output().contains(
                            current < 3 ? "alpha-only" : "beta-only"));
                    toolCall(sink, "command-" + current, "execute_command",
                            "{\"argv\":[\"mvn\",\"test\"],\"cwd\":\".\"}");
                }
                case 2 -> finalText(sink, "completed " + expectedRoot.getFileName());
                default -> throw new AssertionError("unreachable");
            }
        }

        private static void toolCall(ModelStreamSink sink, String callId,
                                     String name, String arguments) {
            sink.onEvent(new ModelStreamEvent.ResponseStarted(callId));
            sink.onEvent(new ModelStreamEvent.ToolCallDelta(
                    0, callId, name, arguments));
            sink.onEvent(new ModelStreamEvent.ResponseFinished("tool_calls"));
            sink.onEvent(new ModelStreamEvent.StreamEnded());
        }

        private static void finalText(ModelStreamSink sink, String text) {
            sink.onEvent(new ModelStreamEvent.ResponseStarted("final"));
            sink.onEvent(new ModelStreamEvent.TextDelta(text));
            sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
            sink.onEvent(new ModelStreamEvent.StreamEnded());
        }
    }
}
