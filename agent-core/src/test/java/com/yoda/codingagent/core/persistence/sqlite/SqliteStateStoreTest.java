package com.yoda.codingagent.core.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.config.AgentConfig;
import com.yoda.codingagent.core.config.AgentConfigLoader;
import com.yoda.codingagent.core.error.AgentException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStateStoreTest {

    @Test
    void opensMigratesAndPersistsWorkspaceSliceAcrossRestart(@TempDir Path tempDirectory)
            throws Exception {
        Path dataDirectory = tempDirectory.resolve("nested/state");
        AgentConfig config = config(dataDirectory, 1375);
        assertFalse(Files.exists(dataDirectory));

        DataDirectoryLock firstLock = DataDirectoryLock.acquire(config.dataDirectory());
        SqliteStateStore firstStore = SqliteStateStore.open(firstLock,
                config.databasePath(), config.databaseBusyTimeout());
        WorkspaceDescriptor alpha = firstStore.registerWorkspace("Alpha",
                tempDirectory.resolve("alpha"));
        WorkspaceDescriptor beta = firstStore.registerWorkspace(" Beta ",
                tempDirectory.resolve("beta/../beta"));

        assertTrue(Files.isRegularFile(config.databasePath()));
        assertEquals("Alpha", alpha.displayName());
        assertEquals("Beta", beta.displayName());
        assertEquals(WorkspaceStatus.ACTIVE, alpha.status());
        assertEquals(List.of(alpha, beta), firstStore.listWorkspaces());

        firstLock.close();
        SqliteStateStore reopenedStore = open(config);
        assertEquals(List.of(alpha, beta), reopenedStore.listWorkspaces());
        assertEquals("wal", journalMode(config.databasePath()));
    }

    @Test
    void archivesIdempotentlyAndRejectsUnknownWorkspace(@TempDir Path tempDirectory) {
        SqliteStateStore store = open(config(tempDirectory, 5000));
        WorkspaceDescriptor workspace = store.registerWorkspace("Workspace",
                tempDirectory.resolve("workspace"));

        store.archiveWorkspace(workspace.workspaceId());
        store.archiveWorkspace(workspace.workspaceId());

        WorkspaceDescriptor archived = store.listWorkspaces().getFirst();
        assertEquals(workspace.workspaceId(), archived.workspaceId());
        assertEquals(WorkspaceStatus.ARCHIVED, archived.status());

        AgentException exception = assertThrows(AgentException.class,
                () -> store.archiveWorkspace(WorkspaceId.random()));
        assertEquals(ErrorCode.UNKNOWN_WORKSPACE, exception.errorCode());
    }

    @Test
    void duplicateRootBecomesStorageErrorAndDoesNotPoisonLaterOperations(
            @TempDir Path tempDirectory) {
        SqliteStateStore store = open(config(tempDirectory, 5000));
        Path repeatedRoot = tempDirectory.resolve("same-root");
        store.registerWorkspace("First", repeatedRoot);

        AgentException exception = assertThrows(AgentException.class,
                () -> store.registerWorkspace("Duplicate", repeatedRoot));
        assertEquals(ErrorCode.STORAGE_ERROR, exception.errorCode());

        WorkspaceDescriptor second = store.registerWorkspace("Second",
                tempDirectory.resolve("other-root"));
        assertEquals(List.of("First", "Second"), store.listWorkspaces().stream()
                .map(WorkspaceDescriptor::displayName)
                .toList());
        assertEquals(WorkspaceStatus.ACTIVE, second.status());
    }

    private static AgentConfig config(Path dataDirectory, int busyTimeoutMillis) {
        return new AgentConfigLoader().load(Map.of(
                "apiKey", "test-key",
                "dataDirectory", dataDirectory.toString(),
                "databaseBusyTimeout", Integer.toString(busyTimeoutMillis)), Map.of());
    }

    private static SqliteStateStore open(AgentConfig config) {
        DataDirectoryLock lock = DataDirectoryLock.acquire(config.dataDirectory());
        return SqliteStateStore.open(lock, config.databasePath(),
                config.databaseBusyTimeout());
    }

    private static String journalMode(Path databasePath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1).toLowerCase();
        }
    }
}
