package com.yoda.codingagent.core.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.persistence.StateStore;
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
        SqliteStateStore first = SqliteStateStore.open(config);
        WorkspaceDescriptor workspace = first.registerWorkspace("Workspace",
                Files.createDirectory(tempDirectory.resolve("workspace")));
        SessionDescriptor session = first.createSessionWithSystemMessage(
                new SessionConfig(workspace.workspaceId(), limits()), "system");
        TurnId turnId = TurnId.random();
        SqliteStateFixture fixture = new SqliteStateFixture(config.databasePath());
        fixture.insertRecoverableToolTurn(session.sessionId(), turnId);

        SqliteStateStore recovered = SqliteStateStore.open(config);
        SqliteStateFixture.RecoveryState state = fixture.readRecoveryState(turnId);
        assertEquals("INTERRUPTED", state.turnStatus());
        assertEquals("ABORTED", state.stepStatus());
        assertEquals(List.of("UNKNOWN", "CANCELLED"), state.toolStatuses());
        assertTrue(recovered.loadCanonicalHistory(session.sessionId())
                .completedTurns().isEmpty());

        StateStore.RecoverySummary secondPass = recovered.recoverInterruptedTurns();
        assertEquals(new StateStore.RecoverySummary(0, 0, 0, 0), secondPass);
        recovered.closeSession(session.sessionId());
    }

    private static RunLimits limits() {
        return new RunLimits(4, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 8_192, 1_024, 2);
    }
}
