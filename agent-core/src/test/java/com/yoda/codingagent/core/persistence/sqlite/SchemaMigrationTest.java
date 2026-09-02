package com.yoda.codingagent.core.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.persistence.ModelStepStatus;
import com.yoda.codingagent.core.persistence.ToolExecutionStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SchemaMigrationTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void createsSevenStateTablesAndSecondMigrationPreservesData(@TempDir Path tempDirectory)
            throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDirectory.resolve("schema.db"));
        migrate(dataSource);

        try (Connection connection = openConnection(dataSource)) {
            execute(connection, """
                    INSERT INTO workspaces
                        (id, display_name, root_path, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, "workspace-1", "Workspace 1", "/tmp/workspace-1", "ACTIVE", NOW, NOW);
        }

        migrate(dataSource);

        try (Connection connection = openConnection(dataSource)) {
            assertEquals(Set.of("flyway_schema_history", "workspaces", "sessions", "turns",
                    "model_steps", "tool_calls", "messages", "turn_digests"),
                    applicationTables(connection));
            assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM workspaces"));
            assertEquals(1, queryInt(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version = '1' AND success = 1
                    """));
            assertEquals(1, queryInt(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version = '2' AND success = 1
                    """));
        }
    }

    @Test
    void upgradesVersionOneTurnsWithRestrictedAccessDefault(@TempDir Path tempDirectory)
            throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDirectory.resolve("upgrade-v1.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("1").load().migrate();
        try (Connection connection = openConnection(dataSource)) {
            insertWorkspace(connection, "workspace-v1", "/tmp/workspace-v1");
            insertSession(connection, "session-v1", "workspace-v1");
            insertTurn(connection, "turn-v1", "session-v1", 1);
        }

        migrate(dataSource);

        try (Connection connection = openConnection(dataSource);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT command_access_mode FROM turns WHERE id = 'turn-v1'");
             ResultSet resultSet = statement.executeQuery()) {
            assertEquals(true, resultSet.next());
            assertEquals("RESTRICTED", resultSet.getString(1));
        }
    }

    @Test
    void rejectsUnknownLifecycleStates(@TempDir Path tempDirectory) throws Exception {
        SQLiteDataSource dataSource = migratedDataSource(tempDirectory.resolve("states.db"));

        try (Connection connection = openConnection(dataSource)) {
            assertRejected(connection, """
                    INSERT INTO workspaces
                        (id, display_name, root_path, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, "bad-workspace", "Bad", "/tmp/bad-workspace", "BROKEN", NOW, NOW);

            insertWorkspace(connection, "workspace-1", "/tmp/workspace-1");
            assertRejected(connection, """
                    INSERT INTO sessions
                        (id, workspace_id, status, limits_json, created_at, updated_at, closed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, "bad-session", "workspace-1", "BUSY", "{}", NOW, NOW, null);

            insertSession(connection, "session-1", "workspace-1");
            assertRejected(connection, """
                    INSERT INTO turns
                        (id, session_id, turn_no, thinking_enabled, status,
                         created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'RUNNING', ?, ?)
                    """, "bad-thinking", "session-1", 1, 2, NOW, NOW);
            assertRejected(connection, """
                    INSERT INTO turns
                        (id, session_id, turn_no, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'RUNNING', ?, ?)
                    """, "missing-thinking", "session-1", 1, NOW, NOW);
            assertRejected(connection, """
                    INSERT INTO turns
                        (id, session_id, turn_no, thinking_enabled, status, termination_reason,
                         created_at, updated_at, finished_at)
                    VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?)
                    """, "bad-turn", "session-1", 1, "STOPPED", null, NOW, NOW, null);
            assertRejected(connection, """
                    INSERT INTO turns
                        (id, session_id, turn_no, thinking_enabled, command_access_mode,
                         status, created_at, updated_at)
                    VALUES (?, ?, ?, 0, ?, 'RUNNING', ?, ?)
                    """, "bad-access", "session-1", 1, "UNSAFE", NOW, NOW);

            insertTurn(connection, "turn-1", "session-1", 1);
            assertRejected(connection, """
                    INSERT INTO model_steps
                        (id, turn_id, step_no, status, visible_text,
                         context_estimated_tokens, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "bad-step", "turn-1", 1, "READY", "", 0, NOW, NOW);

            insertStep(connection, "step-1", "turn-1", 1);
            assertRejected(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "bad-call", "step-1", "call-bad", 0, "read_file", "{}",
                    "READY", NOW, NOW);
        }
    }

    @Test
    void rejectsInvalidJsonMessageShapesAndCrossSessionOwnership(@TempDir Path tempDirectory)
            throws Exception {
        SQLiteDataSource dataSource = migratedDataSource(tempDirectory.resolve("relations.db"));

        try (Connection connection = openConnection(dataSource)) {
            insertWorkspace(connection, "workspace-1", "/tmp/workspace-1");
            insertWorkspace(connection, "workspace-2", "/tmp/workspace-2");
            assertRejected(connection, """
                    INSERT INTO sessions
                        (id, workspace_id, status, limits_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, "bad-json-session", "workspace-1", "OPEN", "not-json", NOW, NOW);

            insertSession(connection, "session-1", "workspace-1");
            insertSession(connection, "session-2", "workspace-2");
            insertTurn(connection, "turn-1", "session-1", 1);
            insertTurn(connection, "turn-2", "session-2", 1);
            insertStep(connection, "step-1", "turn-1", 1);
            insertStep(connection, "step-2", "turn-2", 1);
            insertPendingCall(connection, "tool-1", "step-1", "call-1", 0);
            insertPendingCall(connection, "tool-2", "step-2", "call-2", 0);

            insertSystemMessage(connection, "message-system-1", "session-1");
            insertSystemMessage(connection, "message-system-2", "session-2");

            assertRejected(connection, """
                    INSERT INTO messages
                        (id, session_id, sequence_no, role, kind, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, "bad-system", "session-1", 2, "SYSTEM", "SYSTEM_PROMPT",
                    "duplicate system prompt", NOW);

            assertRejected(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, sequence_no, role, kind, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, "cross-session-user", "session-1", "turn-2", 2,
                    "USER", "USER_TEXT", "wrong session", NOW);

            assertRejected(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, sequence_no,
                         role, kind, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "cross-turn-step", "session-2", "turn-2", "step-1", 2,
                    "ASSISTANT", "ASSISTANT_TEXT", "wrong turn", NOW);

            assertRejected(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, tool_call_id, sequence_no,
                         role, kind, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "cross-step-call", "session-1", "turn-1", "step-1", "tool-2", 2,
                    "TOOL", "TOOL_RESULT", "wrong step", NOW);
        }
    }

    @Test
    void enforcesToolCallLifecycleFieldsAndUniqueness(@TempDir Path tempDirectory)
            throws Exception {
        SQLiteDataSource dataSource = migratedDataSource(tempDirectory.resolve("tools.db"));

        try (Connection connection = openConnection(dataSource)) {
            insertWorkspace(connection, "workspace-1", "/tmp/workspace-1");
            insertSession(connection, "session-1", "workspace-1");
            insertTurn(connection, "turn-1", "session-1", 1);
            insertStep(connection, "step-1", "turn-1", 1);
            insertPendingCall(connection, "tool-1", "step-1", "call-1", 0);

            assertRejected(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, result_output, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "tool-pending-result", "step-1", "call-2", 1, "read_file", "{}",
                    "PENDING", "must be null", NOW, NOW);

            assertRejected(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, result_output, created_at, updated_at,
                         started_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "tool-success-no-duration", "step-1", "call-3", 2, "read_file", "{}",
                    "SUCCESS", "ok", NOW, NOW, NOW, NOW);

            assertRejected(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "tool-duplicate-call", "step-1", "call-1", 3, "read_file", "{}",
                    "PENDING", NOW, NOW);

            assertRejected(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "tool-invalid-json", "step-1", "call-4", 4, "read_file", "{",
                    "PENDING", NOW, NOW);
        }
    }

    @Test
    void acceptsEveryLifecycleStateAndMessageShape(@TempDir Path tempDirectory) throws Exception {
        SQLiteDataSource dataSource = migratedDataSource(tempDirectory.resolve("contract.db"));

        try (Connection connection = openConnection(dataSource)) {
            int ordinal = 0;
            for (WorkspaceStatus status : WorkspaceStatus.values()) {
                execute(connection, """
                        INSERT INTO workspaces
                            (id, display_name, root_path, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, "workspace-status-" + ordinal, status.name(),
                        "/tmp/workspace-status-" + ordinal, status.name(), NOW, NOW);
                ordinal++;
            }

            insertWorkspace(connection, "workspace-main", "/tmp/workspace-main");
            for (SessionStatus status : SessionStatus.values()) {
                Long closedAt = status == SessionStatus.CLOSED ? NOW : null;
                execute(connection, """
                        INSERT INTO sessions
                            (id, workspace_id, status, limits_json,
                             created_at, updated_at, closed_at)
                        VALUES (?, 'workspace-main', ?, '{}', ?, ?, ?)
                        """, "session-status-" + status.name(), status.name(), NOW, NOW, closedAt);
            }

            insertSession(connection, "session-main", "workspace-main");
            int turnNo = 1;
            for (TurnStatus status : TurnStatus.values()) {
                boolean terminal = switch (status) {
                    case INTERRUPTED, COMPLETED, FAILED, CANCELLED, LIMIT_REACHED -> true;
                    default -> false;
                };
                String reason = terminal && status != TurnStatus.COMPLETED ? status.name() : null;
                Long finishedAt = terminal ? NOW : null;
                execute(connection, """
                        INSERT INTO turns
                            (id, session_id, turn_no, thinking_enabled, status,
                             termination_reason,
                             created_at, updated_at, finished_at)
                        VALUES (?, 'session-main', ?, 0, ?, ?, ?, ?, ?)
                        """, "turn-status-" + status.name(), turnNo++, status.name(), reason,
                        NOW, NOW, finishedAt);
            }

            insertTurn(connection, "turn-main", "session-main", turnNo);
            int stepNo = 1;
            for (ModelStepStatus status : ModelStepStatus.values()) {
                execute(connection, """
                        INSERT INTO model_steps
                            (id, turn_id, step_no, status, visible_text,
                             context_estimated_tokens, created_at, updated_at)
                        VALUES (?, 'turn-main', ?, ?, '', 0, ?, ?)
                        """, "step-status-" + status.name(), stepNo++, status.name(), NOW, NOW);
            }

            insertStep(connection, "step-main", "turn-main", stepNo);
            int callOrdinal = 0;
            for (ToolExecutionStatus status : ToolExecutionStatus.values()) {
                insertCallWithStatus(connection, status, callOrdinal++);
            }

            insertSystemMessage(connection, "message-system", "session-main");
            execute(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, sequence_no, role, kind, content, created_at)
                    VALUES ('message-user', 'session-main', 'turn-main', 2,
                            'USER', 'USER_TEXT', 'user text', ?)
                    """, NOW);
            execute(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, sequence_no,
                         role, kind, content, created_at)
                    VALUES ('message-assistant', 'session-main', 'turn-main', 'step-main', 3,
                            'ASSISTANT', 'ASSISTANT_TEXT', 'assistant text', ?)
                    """, NOW);
            execute(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, sequence_no,
                         role, kind, content, created_at)
                    VALUES ('message-tool-calls', 'session-main', 'turn-main', 'step-main', 4,
                            'ASSISTANT', 'ASSISTANT_TOOL_CALLS', '', ?)
                    """, NOW);
            execute(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, tool_call_id, sequence_no,
                         role, kind, content, created_at)
                    VALUES ('message-tool-result', 'session-main', 'turn-main', 'step-main',
                            'tool-status-SUCCESS', 5, 'TOOL', 'TOOL_RESULT', 'ok', ?)
                    """, NOW);

            assertEquals(5, queryInt(connection,
                    "SELECT COUNT(*) FROM messages WHERE session_id = 'session-main'"));
            assertEquals("ok", queryString(connection, "PRAGMA integrity_check"));
            assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    private static SQLiteDataSource migratedDataSource(Path databasePath) {
        SQLiteDataSource dataSource = dataSource(databasePath);
        migrate(dataSource);
        return dataSource;
    }

    private static SQLiteDataSource dataSource(Path databasePath) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath());
        return dataSource;
    }

    private static void migrate(SQLiteDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static Connection openConnection(SQLiteDataSource dataSource) throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static Set<String> applicationTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                """);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void insertWorkspace(Connection connection, String id, String rootPath)
            throws SQLException {
        execute(connection, """
                INSERT INTO workspaces
                    (id, display_name, root_path, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, id, id, rootPath, NOW, NOW);
    }

    private static void insertSession(Connection connection, String id, String workspaceId)
            throws SQLException {
        execute(connection, """
                INSERT INTO sessions
                    (id, workspace_id, status, limits_json, created_at, updated_at)
                VALUES (?, ?, 'OPEN', '{}', ?, ?)
                """, id, workspaceId, NOW, NOW);
    }

    private static void insertTurn(Connection connection, String id, String sessionId, int turnNo)
            throws SQLException {
        execute(connection, """
                INSERT INTO turns
                    (id, session_id, turn_no, thinking_enabled, status,
                     created_at, updated_at)
                VALUES (?, ?, ?, 0, 'RUNNING', ?, ?)
                """, id, sessionId, turnNo, NOW, NOW);
    }

    private static void insertStep(Connection connection, String id, String turnId, int stepNo)
            throws SQLException {
        execute(connection, """
                INSERT INTO model_steps
                    (id, turn_id, step_no, status, visible_text,
                     context_estimated_tokens, created_at, updated_at)
                VALUES (?, ?, ?, 'STAGED', '', 0, ?, ?)
                """, id, turnId, stepNo, NOW, NOW);
    }

    private static void insertPendingCall(Connection connection, String id, String stepId,
                                          String callId, int ordinal) throws SQLException {
        execute(connection, """
                INSERT INTO tool_calls
                    (id, model_step_id, call_id, ordinal, name, arguments_json,
                     execution_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'read_file', '{}', 'PENDING', ?, ?)
                """, id, stepId, callId, ordinal, NOW, NOW);
    }

    private static void insertCallWithStatus(Connection connection, ToolExecutionStatus status,
                                             int ordinal) throws SQLException {
        String resultOutput = null;
        String errorCode = null;
        Integer truncated = 0;
        Long duration = null;
        Long startedAt = null;
        Long completedAt = null;
        switch (status) {
            case PENDING -> { }
            case EXECUTING -> startedAt = NOW;
            case SUCCESS -> {
                resultOutput = "ok";
                duration = 1L;
                startedAt = NOW;
                completedAt = NOW;
            }
            case FAILURE, DENIED, TIMED_OUT, CANCELLED -> {
                resultOutput = "failed";
                errorCode = status.name();
                duration = 1L;
                startedAt = NOW;
                completedAt = NOW;
            }
            case UNKNOWN -> {
                startedAt = NOW;
                completedAt = NOW;
            }
        }
        execute(connection, """
                INSERT INTO tool_calls
                    (id, model_step_id, call_id, ordinal, name, arguments_json,
                     execution_status, result_output, result_error_code, result_truncated,
                     duration_ms, created_at, updated_at, started_at, completed_at)
                VALUES (?, 'step-main', ?, ?, 'read_file', '{}', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "tool-status-" + status.name(), "call-status-" + status.name(), ordinal,
                status.name(), resultOutput, errorCode, truncated, duration, NOW, NOW,
                startedAt, completedAt);
    }

    private static void insertSystemMessage(Connection connection, String id, String sessionId)
            throws SQLException {
        execute(connection, """
                INSERT INTO messages
                    (id, session_id, sequence_no, role, kind, content, created_at)
                VALUES (?, ?, 1, 'SYSTEM', 'SYSTEM_PROMPT', 'system prompt', ?)
                """, id, sessionId, NOW);
    }

    private static void assertRejected(Connection connection, String sql, Object... values) {
        assertThrows(SQLException.class, () -> execute(connection, sql, values));
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }
}
