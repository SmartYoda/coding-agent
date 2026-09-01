package com.yoda.codingagent.core.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.agent.DefaultAgentService;
import com.yoda.codingagent.core.agent.AgentRunner;
import com.yoda.codingagent.core.agent.SessionRegistry;
import com.yoda.codingagent.core.api.AgentService;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.context.ContextManager;
import com.yoda.codingagent.core.context.TokenEstimator;
import com.yoda.codingagent.core.context.TurnDigestFactory;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.persistence.sqlite.DataDirectoryLock;
import com.yoda.codingagent.core.persistence.sqlite.SqliteStateStore;
import com.yoda.codingagent.core.tool.ToolRegistry;
import com.yoda.codingagent.core.tool.ToolDispatcher;
import com.yoda.codingagent.core.tool.ToolOutputTruncator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {

    @Test
    void registersListsAndArchivesTwoWorkspacesThroughAgentService(@TempDir Path tempDirectory)
            throws IOException {
        TestApplication application = application(tempDirectory);
        Path alphaRoot = Files.createDirectory(tempDirectory.resolve("alpha"));
        Path betaRoot = Files.createDirectory(tempDirectory.resolve("beta"));

        WorkspaceDescriptor alpha = application.service().registerWorkspace("Alpha", alphaRoot);
        WorkspaceDescriptor beta = application.service().registerWorkspace("Beta", betaRoot);

        assertEquals(List.of(alpha, beta), application.service().listWorkspaces());
        assertEquals(alphaRoot.toRealPath(),
                application.registry().activeContext(alpha.workspaceId()).root());

        application.service().archiveWorkspace(alpha.workspaceId());
        application.service().archiveWorkspace(alpha.workspaceId());
        assertEquals(WorkspaceStatus.ARCHIVED,
                application.service().listWorkspaces().getFirst().status());
        AgentException archived = assertThrows(AgentException.class,
                () -> application.registry().activeContext(alpha.workspaceId()));
        assertEquals(ErrorCode.WORKSPACE_ARCHIVED, archived.errorCode());
    }

    @Test
    void rejectsDuplicateInvalidAndProtectedRootsBeforePersistence(@TempDir Path tempDirectory)
            throws IOException {
        TestApplication application = application(tempDirectory);
        Path root = Files.createDirectory(tempDirectory.resolve("workspace"));
        application.service().registerWorkspace("First", root);

        AgentException duplicate = assertThrows(AgentException.class,
                () -> application.service().registerWorkspace("Duplicate", root.resolve(".")));
        assertEquals(ErrorCode.INVALID_REQUEST, duplicate.errorCode());

        AgentException missing = assertThrows(AgentException.class,
                () -> application.service().registerWorkspace("Missing",
                        tempDirectory.resolve("missing")));
        assertEquals(ErrorCode.INVALID_REQUEST, missing.errorCode());

        AgentException protectedRoot = assertThrows(AgentException.class,
                () -> application.service().registerWorkspace("State",
                        application.config().dataDirectory()));
        assertEquals(ErrorCode.INVALID_REQUEST, protectedRoot.errorCode());
        assertEquals(1, application.service().listWorkspaces().size());
    }

    @Test
    void restartMarksMissingActiveRootUnavailableInDatabase(@TempDir Path tempDirectory)
            throws IOException {
        TestApplication firstApplication = application(tempDirectory);
        Path root = Files.createDirectory(tempDirectory.resolve("temporary-workspace"));
        WorkspaceDescriptor workspace = firstApplication.service()
                .registerWorkspace("Temporary", root);
        Files.delete(root);
        firstApplication.dataDirectoryLock().close();

        TestApplication restartedApplication = application(tempDirectory);
        WorkspaceDescriptor unavailable = restartedApplication.service().listWorkspaces().getFirst();
        assertEquals(workspace.workspaceId(), unavailable.workspaceId());
        assertEquals(WorkspaceStatus.UNAVAILABLE, unavailable.status());
        AgentException exception = assertThrows(AgentException.class,
                () -> restartedApplication.registry().activeContext(workspace.workspaceId()));
        assertEquals(ErrorCode.WORKSPACE_UNAVAILABLE, exception.errorCode());

        restartedApplication.dataDirectoryLock().close();
        DataDirectoryLock independentLock = DataDirectoryLock.acquire(
                firstApplication.config().dataDirectory());
        SqliteStateStore independentlyReopened = SqliteStateStore.open(independentLock,
                firstApplication.config().databasePath(),
                firstApplication.config().databaseBusyTimeout());
        assertEquals(WorkspaceStatus.UNAVAILABLE,
                independentlyReopened.listWorkspaces().getFirst().status());
    }

    private static TestApplication application(Path tempDirectory) {
        AgentConfig config = new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", tempDirectory.resolve("state").toString()), Map.of());
        DataDirectoryLock dataDirectoryLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore stateStore = SqliteStateStore.open(dataDirectoryLock,
                config.databasePath(), config.databaseBusyTimeout());
        WorkspaceRegistry registry = new WorkspaceRegistry(stateStore,
                new WorkspaceResolver(config.dataDirectory()));
        SessionRegistry sessionRegistry = new SessionRegistry(stateStore, registry);
        AgentRunner runner = new AgentRunner((request, sink, token) -> {
            throw new AssertionError("model must not be called by workspace tests");
        }, new ToolDispatcher(new ToolRegistry(List.of()),
                new SecretRedactor(config.apiKey())::redact, new ToolOutputTruncator()),
                new ObjectMapper(), config.model(),
                config.maxResponseCharacters(), stateStore,
                new ContextManager(new TokenEstimator()), new TurnDigestFactory(),
                new SecretRedactor(config.apiKey()));
        return new TestApplication(config, dataDirectoryLock, registry, new DefaultAgentService(
                registry, sessionRegistry, runner, DefaultAgentService.DEFAULT_SYSTEM_PROMPT,
                new SecretRedactor(config.apiKey()), false));
    }

    private record TestApplication(
            AgentConfig config,
            DataDirectoryLock dataDirectoryLock,
            WorkspaceRegistry registry,
            AgentService service
    ) { }
}
