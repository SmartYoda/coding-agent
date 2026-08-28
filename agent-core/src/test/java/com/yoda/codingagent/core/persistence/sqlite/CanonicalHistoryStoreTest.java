package com.yoda.codingagent.core.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.context.CanonicalHistory;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalHistoryStoreTest {

    @Test
    void restoresOnlyCommittedCanonicalMessagesAndToolProtocol(@TempDir Path tempDirectory)
            throws Exception {
        AgentConfig config = config(tempDirectory);
        SqliteStateStore store = SqliteStateStore.open(config);
        Path root = Files.createDirectory(tempDirectory.resolve("workspace"));
        WorkspaceDescriptor workspace = store.registerWorkspace("Workspace", root);
        SessionDescriptor session = store.createSessionWithSystemMessage(
                new SessionConfig(workspace.workspaceId(), limits()),
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT);
        TurnId turnId = TurnId.random();
        SqliteStateFixture fixture = new SqliteStateFixture(config.databasePath());
        fixture.insertCompletedToolTurn(session.sessionId(), turnId);
        fixture.insertDigest(turnId);

        CanonicalHistory history = SqliteStateStore.open(config)
                .loadCanonicalHistory(session.sessionId());

        assertEquals(session.sessionId(), history.sessionId());
        assertEquals(workspace.workspaceId(), history.workspaceId());
        assertEquals(1, history.completedTurns().size());
        assertEquals("a.txt", history.digests().get(turnId).filesRead().getFirst());
        assertEquals(5, history.messages().size());
        assertInstanceOf(Message.AssistantToolCallsMessage.class, history.messages().get(2));
        Message.AssistantToolCallsMessage calls =
                (Message.AssistantToolCallsMessage) history.messages().get(2);
        assertEquals("call-read", calls.toolCalls().getFirst().callId());
        assertEquals("a.txt", calls.toolCalls().getFirst().arguments().path("path").asText());
        Message.ToolResultMessage result =
                (Message.ToolResultMessage) history.messages().get(3);
        assertEquals("call-read", result.callId());
        assertEquals("file-content", result.content());
    }

    @Test
    void unknownDatabaseMessageCombinationBecomesStorageError(@TempDir Path tempDirectory)
            throws Exception {
        AgentConfig config = config(tempDirectory);
        SqliteStateStore store = SqliteStateStore.open(config);
        Path root = Files.createDirectory(tempDirectory.resolve("workspace"));
        WorkspaceDescriptor workspace = store.registerWorkspace("Workspace", root);
        SessionDescriptor session = store.createSessionWithSystemMessage(
                new SessionConfig(workspace.workspaceId(), limits()), "system");
        new SqliteStateFixture(config.databasePath()).insertUnknownMessage(session.sessionId());

        AgentException exception = assertThrows(AgentException.class,
                () -> store.loadCanonicalHistory(session.sessionId()));
        assertEquals(ErrorCode.STORAGE_ERROR, exception.errorCode());
    }

    private static AgentConfig config(Path tempDirectory) {
        return new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
    }

    private static RunLimits limits() {
        return new RunLimits(4, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 8_192, 1_024, 2);
    }
}
