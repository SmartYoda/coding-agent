package com.yoda.codingagent.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceRegistry;
import com.yoda.codingagent.core.workspace.WorkspaceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionServiceTest {

    @Test
    void createsIsolatedSessionsAndPersistsOneSystemPromptEach(@TempDir Path tempDirectory)
            throws Exception {
        TestApplication application = application(tempDirectory);
        WorkspaceDescriptor alpha = register(application, tempDirectory, "Alpha", "alpha");
        WorkspaceDescriptor beta = register(application, tempDirectory, "Beta", "beta");

        SessionDescriptor alphaOne = application.service().openSession(
                new SessionConfig(alpha.workspaceId(), limits()));
        SessionDescriptor alphaTwo = application.service().openSession(
                new SessionConfig(alpha.workspaceId(), limits()));
        SessionDescriptor betaOne = application.service().openSession(
                new SessionConfig(beta.workspaceId(), limits()));

        assertEquals(List.of(alphaOne, alphaTwo),
                application.service().listSessions(alpha.workspaceId()));
        assertEquals(List.of(betaOne), application.service().listSessions(beta.workspaceId()));
        assertEquals(3, systemMessageCount(application.config().databasePath()));
        assertEquals(3, distinctSystemMessageSessionCount(application.config().databasePath()));
        assertEquals(DefaultAgentService.DEFAULT_SYSTEM_PROMPT,
                firstSystemPrompt(application.config().databasePath(), alphaOne.sessionId()));

        TestApplication restarted = application(tempDirectory);
        try (SessionRegistry.Lease lease = restarted.sessions().acquire(alphaOne.sessionId())) {
            assertEquals(alpha.workspaceId(), lease.session().workspace().workspaceId());
            assertEquals(limits(), lease.session().limits());
        }
    }

    @Test
    void openSessionsBlockArchiveUntilEverySessionIsClosed(@TempDir Path tempDirectory)
            throws IOException {
        TestApplication application = application(tempDirectory);
        WorkspaceDescriptor workspace = register(application, tempDirectory,
                "Workspace", "workspace");
        SessionDescriptor first = application.service().openSession(
                new SessionConfig(workspace.workspaceId(), limits()));
        SessionDescriptor second = application.service().openSession(
                new SessionConfig(workspace.workspaceId(), limits()));

        AgentException inUse = assertThrows(AgentException.class,
                () -> application.service().archiveWorkspace(workspace.workspaceId()));
        assertEquals(ErrorCode.WORKSPACE_IN_USE, inUse.errorCode());

        application.service().closeSession(first.sessionId());
        application.service().closeSession(first.sessionId());
        assertEquals(SessionStatus.CLOSED,
                application.service().getSession(first.sessionId()).status());
        AgentException stillInUse = assertThrows(AgentException.class,
                () -> application.service().archiveWorkspace(workspace.workspaceId()));
        assertEquals(ErrorCode.WORKSPACE_IN_USE, stillInUse.errorCode());

        application.service().closeSession(second.sessionId());
        application.service().archiveWorkspace(workspace.workspaceId());
        AgentException archived = assertThrows(AgentException.class,
                () -> application.service().openSession(
                        new SessionConfig(workspace.workspaceId(), limits())));
        assertEquals(ErrorCode.WORKSPACE_ARCHIVED, archived.errorCode());
    }

    @Test
    void leaseIsExclusiveAndAllPathsReleaseOrReportStableErrors(@TempDir Path tempDirectory)
            throws IOException {
        TestApplication application = application(tempDirectory);
        WorkspaceDescriptor workspace = register(application, tempDirectory,
                "Workspace", "workspace");
        SessionDescriptor session = application.service().openSession(
                new SessionConfig(workspace.workspaceId(), limits()));

        try (SessionRegistry.Lease ignored = application.sessions().acquire(session.sessionId())) {
            AgentException busy = assertThrows(AgentException.class,
                    () -> application.sessions().acquire(session.sessionId()));
            assertEquals(ErrorCode.SESSION_BUSY, busy.errorCode());
            AgentException closeBusy = assertThrows(AgentException.class,
                    () -> application.service().closeSession(session.sessionId()));
            assertEquals(ErrorCode.SESSION_BUSY, closeBusy.errorCode());
        }

        try (SessionRegistry.Lease ignored = application.sessions().acquire(session.sessionId())) {
            assertEquals(session.sessionId(), ignored.session().sessionId());
        }
        application.service().closeSession(session.sessionId());
        AgentException closed = assertThrows(AgentException.class,
                () -> application.sessions().acquire(session.sessionId()));
        assertEquals(ErrorCode.SESSION_CLOSED, closed.errorCode());

        AgentException unknown = assertThrows(AgentException.class,
                () -> application.service().getSession(SessionId.random()));
        assertEquals(ErrorCode.UNKNOWN_SESSION, unknown.errorCode());
    }

    private static TestApplication application(Path tempDirectory) {
        AgentConfig config = new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
        SqliteStateStore store = SqliteStateStore.open(config);
        WorkspaceRegistry workspaces = new WorkspaceRegistry(store,
                new WorkspaceResolver(config.dataDirectory()));
        SessionRegistry sessions = new SessionRegistry(store, workspaces);
        AgentRunner runner = new AgentRunner((request, sink, token) -> {
            throw new AssertionError("model must not be called by session tests");
        }, new ToolRegistry(List.of()), new ObjectMapper(), config.model(),
                config.maxResponseCharacters(), store,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory());
        AgentService service = new DefaultAgentService(workspaces, sessions, runner,
                DefaultAgentService.DEFAULT_SYSTEM_PROMPT);
        return new TestApplication(config, sessions, service);
    }

    private static WorkspaceDescriptor register(TestApplication application, Path parent,
                                                String displayName, String directoryName)
            throws IOException {
        Path root = parent.resolve(directoryName);
        if (!Files.exists(root)) {
            Files.createDirectory(root);
        }
        return application.service().registerWorkspace(displayName, root);
    }

    private static RunLimits limits() {
        return new RunLimits(4, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 16_384, 8_192, 1_024, 2);
    }

    private static int systemMessageCount(Path databasePath) throws Exception {
        return queryInt(databasePath, """
                SELECT COUNT(*) FROM messages
                WHERE role = 'SYSTEM' AND kind = 'SYSTEM_PROMPT'
                """);
    }

    private static int distinctSystemMessageSessionCount(Path databasePath) throws Exception {
        return queryInt(databasePath, """
                SELECT COUNT(DISTINCT session_id) FROM messages
                WHERE role = 'SYSTEM' AND kind = 'SYSTEM_PROMPT'
                """);
    }

    private static int queryInt(Path databasePath, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String firstSystemPrompt(Path databasePath, SessionId sessionId)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT content FROM messages
                        WHERE session_id = ? AND sequence_no = 1
                        """)) {
            statement.setString(1, sessionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private record TestApplication(
            AgentConfig config,
            SessionRegistry sessions,
            AgentService service
    ) { }
}
