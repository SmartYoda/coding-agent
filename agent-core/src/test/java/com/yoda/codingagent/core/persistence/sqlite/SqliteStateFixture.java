package com.yoda.codingagent.core.persistence.sqlite;

import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.TurnId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class SqliteStateFixture {

    private final Path databasePath;

    public SqliteStateFixture(Path databasePath) {
        this.databasePath = databasePath;
    }

    void insertCompletedToolTurn(SessionId sessionId, TurnId turnId) throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            long now = Instant.now().toEpochMilli();
            int turnNo = nextInt(connection,
                    "SELECT COALESCE(MAX(turn_no), 0) + 1 FROM turns WHERE session_id = ?",
                    sessionId.value().toString());
            int sequence = nextInt(connection,
                    "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM messages WHERE session_id = ?",
                    sessionId.value().toString());
            String toolStepId = UUID.randomUUID().toString();
            String finalStepId = UUID.randomUUID().toString();
            String toolCallId = UUID.randomUUID().toString();

            execute(connection, """
                    INSERT INTO turns
                        (id, session_id, turn_no, status, termination_reason,
                         created_at, updated_at, finished_at)
                    VALUES (?, ?, ?, 'COMPLETED', NULL, ?, ?, ?)
                    """, turnId.value().toString(), sessionId.value().toString(), turnNo,
                    now, now, now);
            execute(connection, """
                    INSERT INTO model_steps
                        (id, turn_id, step_no, status, response_id, visible_text,
                         prompt_tokens, completion_tokens, context_estimated_tokens,
                         created_at, updated_at)
                    VALUES (?, ?, 1, 'COMMITTED', 'response-tool', '', 10, 4, 9, ?, ?)
                    """, toolStepId, turnId.value().toString(), now, now);
            execute(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, result_output, result_error_code, result_truncated,
                         duration_ms, result_metadata_json, created_at, updated_at,
                         started_at, completed_at)
                    VALUES (?, ?, 'call-read', 0, 'read_file', '{"path":"a.txt"}',
                            'SUCCESS', 'file-content', NULL, 0, 5, '{}', ?, ?, ?, ?)
                    """, toolCallId, toolStepId, now, now, now, now);
            execute(connection, """
                    INSERT INTO model_steps
                        (id, turn_id, step_no, status, response_id, visible_text,
                         prompt_tokens, completion_tokens, context_estimated_tokens,
                         created_at, updated_at)
                    VALUES (?, ?, 2, 'COMMITTED', 'response-final', 'done', 20, 2, 18, ?, ?)
                    """, finalStepId, turnId.value().toString(), now, now);

            insertMessage(connection, sessionId, turnId, null, null, sequence++,
                    "USER", "USER_TEXT", "read a.txt", now);
            insertMessage(connection, sessionId, turnId, toolStepId, null, sequence++,
                    "ASSISTANT", "ASSISTANT_TOOL_CALLS", "", now);
            insertMessage(connection, sessionId, turnId, toolStepId, toolCallId, sequence++,
                    "TOOL", "TOOL_RESULT", "file-content", now);
            insertMessage(connection, sessionId, turnId, finalStepId, null, sequence,
                    "ASSISTANT", "ASSISTANT_TEXT", "done", now);
            connection.commit();
        }
    }

    void insertUnknownMessage(SessionId sessionId) throws Exception {
        try (Connection connection = openConnection();
                Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA ignore_check_constraints = ON");
            int sequence = nextInt(connection,
                    "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM messages WHERE session_id = ?",
                    sessionId.value().toString());
            execute(connection, """
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, tool_call_id,
                         sequence_no, role, kind, content, created_at)
                    VALUES (?, ?, NULL, NULL, NULL, ?, 'ALIEN', 'UNKNOWN', 'bad', ?)
                    """, UUID.randomUUID().toString(), sessionId.value().toString(), sequence,
                    Instant.now().toEpochMilli());
        }
    }

    void insertDigest(TurnId turnId) throws Exception {
        try (Connection connection = openConnection()) {
            execute(connection, """
                    INSERT INTO turn_digests (turn_id, digest_json, created_at)
                    VALUES (?, ?, ?)
                    """, turnId.value().toString(), """
                    {"userGoal":"read a.txt","status":"COMPLETED","finalSummary":"done",
                     "filesRead":["a.txt"],"filesModified":[],"commands":[],
                     "importantErrors":[],"openItems":[]}
                    """, Instant.now().toEpochMilli());
        }
    }

    void insertRecoverableToolTurn(SessionId sessionId, TurnId turnId) throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            long now = Instant.now().toEpochMilli();
            int turnNo = nextInt(connection,
                    "SELECT COALESCE(MAX(turn_no), 0) + 1 FROM turns WHERE session_id = ?",
                    sessionId.value().toString());
            int sequence = nextInt(connection,
                    "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM messages WHERE session_id = ?",
                    sessionId.value().toString());
            String stepId = UUID.randomUUID().toString();
            execute(connection, """
                    INSERT INTO turns
                        (id, session_id, turn_no, status, termination_reason,
                         created_at, updated_at, finished_at)
                    VALUES (?, ?, ?, 'EXECUTING_TOOL', NULL, ?, ?, NULL)
                    """, turnId.value().toString(), sessionId.value().toString(), turnNo,
                    now, now);
            execute(connection, """
                    INSERT INTO model_steps
                        (id, turn_id, step_no, status, visible_text,
                         context_estimated_tokens, created_at, updated_at)
                    VALUES (?, ?, 1, 'STAGED', '', 10, ?, ?)
                    """, stepId, turnId.value().toString(), now, now);
            execute(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, created_at, updated_at, started_at)
                    VALUES (?, ?, 'call-executing', 0, 'read_file', '{}',
                            'EXECUTING', ?, ?, ?)
                    """, UUID.randomUUID().toString(), stepId, now, now, now);
            execute(connection, """
                    INSERT INTO tool_calls
                        (id, model_step_id, call_id, ordinal, name, arguments_json,
                         execution_status, created_at, updated_at)
                    VALUES (?, ?, 'call-pending', 1, 'read_file', '{}',
                            'PENDING', ?, ?)
                    """, UUID.randomUUID().toString(), stepId, now, now);
            insertMessage(connection, sessionId, turnId, null, null, sequence,
                    "USER", "USER_TEXT", "recover me", now);
            connection.commit();
        }
    }

    public RecoveryState readRecoveryState(TurnId turnId) throws Exception {
        try (Connection connection = openConnection()) {
            String turnStatus = queryText(connection,
                    "SELECT status FROM turns WHERE id = ?", turnId.value().toString());
            String stepStatus = queryText(connection, """
                    SELECT status FROM model_steps WHERE turn_id = ? ORDER BY step_no LIMIT 1
                    """, turnId.value().toString());
            List<String> calls = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT execution_status FROM tool_calls
                    WHERE model_step_id IN (SELECT id FROM model_steps WHERE turn_id = ?)
                    ORDER BY ordinal
                    """)) {
                statement.setString(1, turnId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        calls.add(resultSet.getString(1));
                    }
                }
            }
            return new RecoveryState(turnStatus, stepStatus, List.copyOf(calls));
        }
    }

    public String readTurnStatus(TurnId turnId) throws Exception {
        try (Connection connection = openConnection()) {
            return queryText(connection,
                    "SELECT status FROM turns WHERE id = ?", turnId.value().toString());
        }
    }

    public UsageState readFinalStepUsage(TurnId turnId) throws Exception {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT prompt_tokens, completion_tokens
                        FROM model_steps
                        WHERE turn_id = ? AND status = 'COMMITTED'
                        ORDER BY step_no DESC
                        LIMIT 1
                        """)) {
            statement.setString(1, turnId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("completed model step not found");
                }
                return new UsageState(resultSet.getLong(1), resultSet.getLong(2));
            }
        }
    }

    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static int nextInt(Connection connection, String sql, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static String queryText(Connection connection, String sql, String id)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static void insertMessage(Connection connection, SessionId sessionId, TurnId turnId,
                                      String stepId, String toolCallId, int sequence,
                                      String role, String kind, String content, long now)
            throws Exception {
        execute(connection, """
                INSERT INTO messages
                    (id, session_id, turn_id, model_step_id, tool_call_id,
                     sequence_no, role, kind, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), sessionId.value().toString(),
                turnId.value().toString(), stepId, toolCallId, sequence, role, kind, content, now);
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    public record RecoveryState(
            String turnStatus,
            String stepStatus,
            List<String> toolStatuses
    ) { }

    public record UsageState(long promptTokens, long completionTokens) { }
}
