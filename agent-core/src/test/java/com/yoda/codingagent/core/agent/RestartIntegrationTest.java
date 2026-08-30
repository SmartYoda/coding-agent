package com.yoda.codingagent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.AgentResult;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.TurnId;
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
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateFixture;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolOutputTruncator;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestartIntegrationTest {

    @Test
    void thirdTurnAfterRestartUsesOnlyItsOwnSessionHistory(@TempDir Path tempDirectory)
            throws Exception {
        AgentConfig config = new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
        FinalAnswerModel firstModel = new FinalAnswerModel(List.of(
                "a1-first-result", "a1-second-result",
                "a2-private-result", "b1-private-result"));
        TestApplication first = application(config, firstModel);
        WorkspaceDescriptor alpha = first.workspaces().register("Alpha",
                Files.createDirectory(tempDirectory.resolve("alpha")));
        WorkspaceDescriptor beta = first.workspaces().register("Beta",
                Files.createDirectory(tempDirectory.resolve("beta")));
        SessionDescriptor alphaOne = first.service().openSession(
                new SessionConfig(alpha.workspaceId(), limits()));
        SessionDescriptor alphaTwo = first.service().openSession(
                new SessionConfig(alpha.workspaceId(), limits()));
        SessionDescriptor betaOne = first.service().openSession(
                new SessionConfig(beta.workspaceId(), limits()));

        var firstTurn = run(first, alphaOne, "a1-first-input");
        var secondTurn = run(first, alphaOne, "a1-second-input");
        var alphaTwoTurn = run(first, alphaTwo, "a2-private-input");
        var betaTurn = run(first, betaOne, "b1-private-input");
        first.dataDirectoryLock().close();

        FinalAnswerModel restartedModel = new FinalAnswerModel(List.of("a1-third-result"));
        TestApplication restarted = application(config, restartedModel);
        var thirdTurn = run(restarted, alphaOne, "a1-third-input");

        ModelRequest thirdRequest = restartedModel.requests.getFirst();
        String flattened = thirdRequest.messages().toString();
        assertTrue(flattened.contains("a1-first-input"));
        assertTrue(flattened.contains("a1-first-result"));
        assertTrue(flattened.contains("a1-second-input"));
        assertTrue(flattened.contains("a1-second-result"));
        assertTrue(flattened.contains("a1-third-input"));
        assertFalse(flattened.contains("a2-private-input"));
        assertFalse(flattened.contains("b1-private-input"));
        String fixedContext = ((Message.SystemMessage) thirdRequest.messages().getFirst())
                .content();
        assertTrue(fixedContext.contains(alpha.root().toString()));
        assertFalse(fixedContext.contains(beta.root().toString()));
        Set<TurnId> requestTurnIds = thirdRequest.messages().stream()
                .map(RestartIntegrationTest::turnIdOf)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(Set.of(firstTurn.turnId(), secondTurn.turnId(), thirdTurn.turnId()),
                requestTurnIds);
        assertFalse(requestTurnIds.contains(alphaTwoTurn.turnId()));
        assertFalse(requestTurnIds.contains(betaTurn.turnId()));
        assertEquals(1, thirdRequest.messages().stream()
                .filter(Message.SystemMessage.class::isInstance).count());
        var recoveredHistory = restarted.store().loadCanonicalHistory(alphaOne.sessionId());
        assertEquals(3, recoveredHistory.completedTurns().size());
        assertEquals(3, recoveredHistory.digests().size());
        assertEquals(2, restarted.service().listWorkspaces().size());
        var usage = new SqliteStateFixture(config.databasePath())
                .readFinalStepUsage(firstTurn.turnId());
        assertEquals(11, usage.promptTokens());
        assertEquals(7, usage.completionTokens());
    }

    private static TurnId turnIdOf(Message message) {
        if (message instanceof Message.UserMessage user) {
            return user.turnId();
        }
        if (message instanceof Message.AssistantMessage assistant) {
            return assistant.turnId();
        }
        if (message instanceof Message.AssistantToolCallsMessage calls) {
            return calls.turnId();
        }
        if (message instanceof Message.ToolResultMessage result) {
            return result.turnId();
        }
        if (message instanceof Message.TurnDigestMessage digest) {
            return digest.turnId();
        }
        return null;
    }

    private static AgentResult run(
            TestApplication application, SessionDescriptor session, String input) {
        var result = application.service().runTurn(session.sessionId(),
                new AgentRequest(input), ignored -> { }, CancellationToken.NONE);
        assertEquals(TurnStatus.COMPLETED, result.status(), result.errorMessage());
        return result;
    }

    private static TestApplication application(AgentConfig config, ModelClient model) {
        DataDirectoryLock dataDirectoryLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore store = SqliteStateStore.open(dataDirectoryLock,
                config.databasePath(), config.databaseBusyTimeout());
        WorkspaceRegistry workspaces = new WorkspaceRegistry(store,
                new WorkspaceResolver(config.dataDirectory()));
        SessionRegistry sessions = new SessionRegistry(store, workspaces);
        AgentRunner runner = new AgentRunner(model,
                new ToolDispatcher(new ToolRegistry(List.of()),
                        new SecretRedactor(config.apiKey())::redact, new ToolOutputTruncator()),
                new ObjectMapper(), config.model(), config.maxResponseCharacters(), store,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory(),
                new SecretRedactor(config.apiKey()));
        DefaultAgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT,
                new SecretRedactor(config.apiKey()));
        return new TestApplication(dataDirectoryLock, store, workspaces, service);
    }

    private static RunLimits limits() {
        return new RunLimits(4, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 16_384, 1_024, 3);
    }

    private record TestApplication(
            DataDirectoryLock dataDirectoryLock,
            SqliteStateStore store,
            WorkspaceRegistry workspaces,
            DefaultAgentService service
    ) { }

    private static final class FinalAnswerModel implements ModelClient {
        private final Deque<String> answers;
        private final List<ModelRequest> requests = new ArrayList<>();

        private FinalAnswerModel(List<String> answers) {
            this.answers = new ArrayDeque<>(answers);
        }

        @Override
        public void stream(ModelRequest request, ModelStreamSink sink,
                           CancellationToken token) {
            requests.add(request);
            String answer = answers.removeFirst();
            sink.onEvent(new ModelStreamEvent.ResponseStarted("response-" + requests.size()));
            sink.onEvent(new ModelStreamEvent.TextDelta(answer));
            sink.onEvent(new ModelStreamEvent.UsageReceived(
                    new ModelStreamEvent.Usage(11, 7, 18)));
            sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
            sink.onEvent(new ModelStreamEvent.StreamEnded());
        }
    }
}
