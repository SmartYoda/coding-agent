package com.yoda.codingagent.core.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.agent.SessionRegistry;
import com.yoda.codingagent.core.api.AgentRequest;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.model.ModelStreamEvent;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolOutputTruncator;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {

    @Test
    void startupRecoveryNeverReplaysToolsAndIsIdempotent(@TempDir Path tempDirectory)
            throws Exception {
        AgentConfig config = new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
        DataDirectoryLock firstLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore first = SqliteStateStore.open(firstLock,
                config.databasePath(), config.databaseBusyTimeout());
        WorkspaceDescriptor workspace = first.registerWorkspace("Workspace",
                Files.createDirectory(tempDirectory.resolve("workspace")).toRealPath());
        SessionDescriptor session = first.createSessionWithSystemMessage(
                new SessionConfig(workspace.workspaceId(), limits()), "system");
        TurnId turnId = TurnId.random();
        TurnId runningTurn = TurnId.random();
        TurnId streamingTurn = TurnId.random();
        SqliteStateFixture fixture = new SqliteStateFixture(config.databasePath());
        fixture.insertRecoverableToolTurn(session.sessionId(), turnId);
        fixture.insertBareRecoverableTurn(session.sessionId(), runningTurn, "RUNNING");
        fixture.insertBareRecoverableTurn(session.sessionId(), streamingTurn, "STREAMING_MODEL");
        TurnId completedControl = fixture.insertCompletedTextTurns(
                session.sessionId(), 1).getFirst();

        firstLock.close();
        DataDirectoryLock recoveredLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore recovered = SqliteStateStore.open(recoveredLock,
                config.databasePath(), config.databaseBusyTimeout());
        assertEquals(new StateStore.RecoverySummary(3, 1, 1, 1),
                recovered.startupRecoverySummary());
        SqliteStateFixture.RecoveryState state = fixture.readRecoveryState(turnId);
        assertEquals("INTERRUPTED", state.turnStatus());
        assertEquals("ABORTED", state.stepStatus());
        assertEquals(List.of("UNKNOWN", "CANCELLED"), state.toolStatuses());
        assertTrue(recovered.loadCanonicalHistory(session.sessionId())
                .completedTurns().stream().allMatch(turn ->
                        turn.turnId().equals(completedControl)));
        assertEquals("INTERRUPTED", fixture.readTurnStatus(runningTurn));
        assertEquals("INTERRUPTED", fixture.readTurnStatus(streamingTurn));
        assertEquals("COMPLETED", fixture.readTurnStatus(completedControl));

        StateStore.RecoverySummary secondPass = recovered.recoverInterruptedTurns();
        assertEquals(new StateStore.RecoverySummary(0, 0, 0, 0), secondPass);

        WorkspaceRegistry workspaces = new WorkspaceRegistry(recovered,
                new WorkspaceResolver(config.dataDirectory()));
        SessionRegistry sessions = new SessionRegistry(recovered, workspaces);
        SecretRedactor redactor = new SecretRedactor(config.apiKey());
        AgentRunner runner = new AgentRunner((request, sink, token) -> {
            sink.onEvent(new ModelStreamEvent.ResponseStarted("continued"));
            sink.onEvent(new ModelStreamEvent.TextDelta("continued successfully"));
            sink.onEvent(new ModelStreamEvent.ResponseFinished("stop"));
            sink.onEvent(new ModelStreamEvent.StreamEnded());
        }, new ToolDispatcher(new ToolRegistry(List.of()), redactor::redact,
                new ToolOutputTruncator()), new ObjectMapper(), config.model(),
                config.maxResponseCharacters(), recovered,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory(), redactor);
        DefaultAgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT, redactor);
        var continued = service.runTurn(session.sessionId(), new AgentRequest("continue"),
                ignored -> { }, CancellationToken.NONE);
        assertEquals(TurnStatus.COMPLETED, continued.status());
        assertEquals(2, recovered.loadCanonicalHistory(session.sessionId())
                .completedTurns().size());
        recoveredLock.close();
    }

    private static RunLimits limits() {
        return new RunLimits(4, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 8_192, 1_024, 2);
    }
}
