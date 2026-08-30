package com.yoda.codingagent.core.persistence.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.SessionConfig;
import com.yoda.codingagent.core.api.SessionDescriptor;
import com.yoda.codingagent.core.api.SessionContextSummary;
import com.yoda.codingagent.core.api.SessionId;
import com.yoda.codingagent.core.api.SessionStatus;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.TurnStatus;
import com.yoda.codingagent.core.api.WorkspaceDescriptor;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.api.WorkspaceStatus;
import com.yoda.codingagent.core.context.CanonicalHistory;
import com.yoda.codingagent.core.context.TurnDigest;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.model.Message;
import com.yoda.codingagent.core.model.ModelResponse;
import com.yoda.codingagent.core.persistence.ModelStepStatus;
import com.yoda.codingagent.core.persistence.StateStore;
import com.yoda.codingagent.core.persistence.ToolExecutionStatus;
import com.yoda.codingagent.core.tool.ToolCall;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.tool.ToolStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteDataSource;

public final class SqliteStateStore implements StateStore {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_DIGESTS_LOADED = 512;

    private final SQLiteDataSource dataSource;
    private final DataDirectoryLock dataDirectoryLock;
    private final Path databasePath;
    private final int busyTimeoutMillis;
    private RecoverySummary startupRecoverySummary = new RecoverySummary(0, 0, 0, 0);

    private SqliteStateStore(SQLiteDataSource dataSource, DataDirectoryLock dataDirectoryLock,
                             Path databasePath,
                             int busyTimeoutMillis) {
        this.dataSource = dataSource;
        this.dataDirectoryLock = dataDirectoryLock;
        this.databasePath = databasePath;
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    public static SqliteStateStore open(DataDirectoryLock dataDirectoryLock,
                                        Path requestedDatabasePath,
                                        Duration busyTimeout) {
        Path databasePath = Objects.requireNonNull(requestedDatabasePath, "databasePath")
                .toAbsolutePath().normalize();
        if (dataDirectoryLock == null) {
            throw new AgentException(ErrorCode.STORAGE_ERROR,
                    "data directory lock is required");
        }
        dataDirectoryLock.requireHeldFor(databasePath);
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        long busyMillis = busyTimeout.toMillis();
        if (busyMillis < 1 || busyMillis > 60_000) {
            throw new IllegalArgumentException("busyTimeout must be between 1 and 60000 ms");
        }
        try {
            Files.createDirectories(Objects.requireNonNull(databasePath.getParent(),
                    "databasePath parent"));
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + databasePath);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations(MIGRATION_LOCATION)
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();

            int busyTimeoutMillis = Math.toIntExact(busyMillis);
            SqliteStateStore store = new SqliteStateStore(dataSource, dataDirectoryLock,
                    databasePath,
                    busyTimeoutMillis);
            store.initializeDatabase();
            store.startupRecoverySummary = store.recoverInterruptedTurns();
            return store;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof AgentException agentException) {
                throw agentException;
            }
            throw storageFailure("cannot initialize state database at " + databasePath,
                    exception);
        }
    }

    public RecoverySummary startupRecoverySummary() {
        return startupRecoverySummary;
    }

    @Override
    public WorkspaceDescriptor registerWorkspace(String displayName, Path root) {
        String normalizedName = requireText(displayName, "displayName");
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        WorkspaceDescriptor workspace = new WorkspaceDescriptor(WorkspaceId.random(),
                normalizedName, normalizedRoot, WorkspaceStatus.ACTIVE);
        long now = Instant.now().toEpochMilli();

        withTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workspaces
                        (id, display_name, root_path, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, workspace.workspaceId().value().toString());
                statement.setString(2, workspace.displayName());
                statement.setString(3, workspace.root().toString());
                statement.setString(4, workspace.status().name());
                statement.setLong(5, now);
                statement.setLong(6, now);
                requireOneRow(statement.executeUpdate(), "register workspace");
            }
            return null;
        });
        return workspace;
    }

    @Override
    public List<WorkspaceDescriptor> listWorkspaces() {
        return withConnection(connection -> {
            List<WorkspaceDescriptor> workspaces = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, display_name, root_path, status
                    FROM workspaces
                    ORDER BY created_at, id
                    """);
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    workspaces.add(mapWorkspace(resultSet));
                }
            }
            return List.copyOf(workspaces);
        });
    }

    @Override
    public void archiveWorkspace(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        withTransaction(connection -> {
            WorkspaceStatus status = findWorkspaceStatus(connection, workspaceId);
            if (status == null) {
                throw new AgentException(ErrorCode.UNKNOWN_WORKSPACE,
                        "workspace does not exist");
            }
            if (status == WorkspaceStatus.ARCHIVED) {
                return null;
            }
            if (hasOpenSession(connection, workspaceId)) {
                throw new AgentException(ErrorCode.WORKSPACE_IN_USE,
                        "workspace has an open session");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE workspaces
                    SET status = ?, updated_at = ?
                    WHERE id = ?
                    """)) {
                statement.setString(1, WorkspaceStatus.ARCHIVED.name());
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setString(3, workspaceId.value().toString());
                requireOneRow(statement.executeUpdate(), "archive workspace");
            }
            return null;
        });
    }

    @Override
    public void markWorkspaceUnavailable(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        withTransaction(connection -> {
            WorkspaceStatus status = findWorkspaceStatus(connection, workspaceId);
            if (status == null) {
                throw new AgentException(ErrorCode.UNKNOWN_WORKSPACE,
                        "workspace does not exist");
            }
            if (status != WorkspaceStatus.ACTIVE) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE workspaces
                    SET status = ?, updated_at = ?
                    WHERE id = ? AND status = ?
                    """)) {
                statement.setString(1, WorkspaceStatus.UNAVAILABLE.name());
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setString(3, workspaceId.value().toString());
                statement.setString(4, WorkspaceStatus.ACTIVE.name());
                requireOneRow(statement.executeUpdate(), "mark workspace unavailable");
            }
            return null;
        });
    }

    @Override
    public SessionDescriptor createSessionWithSystemMessage(SessionConfig config,
                                                             String systemPrompt) {
        Objects.requireNonNull(config, "config");
        String prompt = requireNonBlank(systemPrompt, "systemPrompt");
        String limitsJson = serializeLimits(config.limits());
        SessionId sessionId = SessionId.random();
        long now = Instant.now().toEpochMilli();
        SessionDescriptor descriptor = new SessionDescriptor(sessionId, config.workspaceId(),
                SessionStatus.OPEN, Instant.ofEpochMilli(now), Instant.ofEpochMilli(now));

        withTransaction(connection -> {
            WorkspaceStatus workspaceStatus = findWorkspaceStatus(connection,
                    config.workspaceId());
            if (workspaceStatus == null) {
                throw new AgentException(ErrorCode.UNKNOWN_WORKSPACE,
                        "workspace does not exist");
            }
            if (workspaceStatus == WorkspaceStatus.ARCHIVED) {
                throw new AgentException(ErrorCode.WORKSPACE_ARCHIVED,
                        "workspace is archived");
            }
            if (workspaceStatus == WorkspaceStatus.UNAVAILABLE) {
                throw new AgentException(ErrorCode.WORKSPACE_UNAVAILABLE,
                        "workspace is unavailable");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sessions
                        (id, workspace_id, status, limits_json,
                         created_at, updated_at, closed_at)
                    VALUES (?, ?, ?, ?, ?, ?, NULL)
                    """)) {
                statement.setString(1, sessionId.value().toString());
                statement.setString(2, config.workspaceId().value().toString());
                statement.setString(3, SessionStatus.OPEN.name());
                statement.setString(4, limitsJson);
                statement.setLong(5, now);
                statement.setLong(6, now);
                requireOneRow(statement.executeUpdate(), "create session");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO messages
                        (id, session_id, turn_id, model_step_id, tool_call_id,
                         sequence_no, role, kind, content, created_at)
                    VALUES (?, ?, NULL, NULL, NULL, 1,
                            'SYSTEM', 'SYSTEM_PROMPT', ?, ?)
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, sessionId.value().toString());
                statement.setString(3, prompt);
                statement.setLong(4, now);
                requireOneRow(statement.executeUpdate(), "create system message");
            }
            return null;
        });
        return descriptor;
    }

    @Override
    public List<SessionDescriptor> listSessions(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return withConnection(connection -> {
            if (findWorkspaceStatus(connection, workspaceId) == null) {
                throw new AgentException(ErrorCode.UNKNOWN_WORKSPACE,
                        "workspace does not exist");
            }
            List<SessionDescriptor> sessions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, workspace_id, status, created_at, updated_at
                    FROM sessions
                    WHERE workspace_id = ?
                    ORDER BY created_at, id
                    """)) {
                statement.setString(1, workspaceId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        sessions.add(mapSessionDescriptor(resultSet));
                    }
                }
            }
            return List.copyOf(sessions);
        });
    }

    @Override
    public StoredSession loadSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, workspace_id, status, limits_json, created_at, updated_at
                    FROM sessions
                    WHERE id = ?
                    """)) {
                statement.setString(1, sessionId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new AgentException(ErrorCode.UNKNOWN_SESSION,
                                "session does not exist");
                    }
                    SessionDescriptor descriptor = mapSessionDescriptor(resultSet);
                    return new StoredSession(descriptor,
                            deserializeLimits(resultSet.getString("limits_json")));
                }
            }
        });
    }

    @Override
    public SessionContextSummary loadSessionContextSummary(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.workspace_id, s.limits_json,
                           (SELECT COUNT(*) FROM turns t
                            WHERE t.session_id = s.id AND t.status = 'COMPLETED')
                               AS completed_turn_count,
                           (SELECT COUNT(*) FROM turn_digests d
                            JOIN turns t ON t.id = d.turn_id
                            WHERE t.session_id = s.id AND t.status = 'COMPLETED')
                               AS digest_count
                    FROM sessions s
                    WHERE s.id = ?
                    """)) {
                statement.setString(1, sessionId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new AgentException(ErrorCode.UNKNOWN_SESSION,
                                "session does not exist");
                    }
                    try {
                        return new SessionContextSummary(sessionId,
                                new WorkspaceId(UUID.fromString(
                                        resultSet.getString("workspace_id"))),
                                deserializeLimits(resultSet.getString("limits_json")),
                                resultSet.getInt("completed_turn_count"),
                                resultSet.getInt("digest_count"));
                    } catch (IllegalArgumentException exception) {
                        throw storageFailure(
                                "state database contains an invalid session context summary",
                                exception);
                    }
                }
            }
        });
    }

    @Override
    public void closeSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        withTransaction(connection -> {
            SessionStatus status = findSessionStatus(connection, sessionId);
            if (status == null) {
                throw new AgentException(ErrorCode.UNKNOWN_SESSION,
                        "session does not exist");
            }
            if (status == SessionStatus.CLOSED) {
                return null;
            }
            if (hasActiveTurn(connection, sessionId)) {
                throw new AgentException(ErrorCode.SESSION_BUSY,
                        "session has an active turn");
            }
            long now = Instant.now().toEpochMilli();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE sessions
                    SET status = ?, updated_at = ?, closed_at = ?
                    WHERE id = ? AND status = ?
                    """)) {
                statement.setString(1, SessionStatus.CLOSED.name());
                statement.setLong(2, now);
                statement.setLong(3, now);
                statement.setString(4, sessionId.value().toString());
                statement.setString(5, SessionStatus.OPEN.name());
                requireOneRow(statement.executeUpdate(), "close session");
            }
            return null;
        });
    }

    @Override
    public CanonicalHistory loadCanonicalHistory(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return withConnection(connection -> {
            WorkspaceId workspaceId = findSessionWorkspace(connection, sessionId);
            RunLimits limits = findSessionLimits(connection, sessionId);
            int totalCompletedTurnCount;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM turns
                    WHERE session_id = ? AND status = 'COMPLETED'
                    """)) {
                statement.setString(1, sessionId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    totalCompletedTurnCount = resultSet.getInt(1);
                }
            }
            int recentFullTurns = limits.recentFullTurns();
            Map<String, List<ToolCall>> toolCallsByStep = loadCommittedToolCalls(
                    connection, sessionId, recentFullTurns);
            List<TurnDigest> digests = loadTurnDigests(
                    connection, sessionId, MAX_DIGESTS_LOADED);
            List<Message> messages = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.turn_id, m.model_step_id, m.role, m.kind, m.content,
                           tc.call_id AS protocol_call_id,
                           tc.execution_status AS tool_execution_status,
                           tc.result_output AS tool_result_output,
                           tc.result_error_code AS tool_result_error_code,
                           tc.result_truncated AS tool_result_truncated,
                           tc.duration_ms AS tool_duration_ms,
                           tc.result_metadata_json AS tool_result_metadata_json
                    FROM messages m
                    LEFT JOIN turns t ON t.id = m.turn_id
                    LEFT JOIN model_steps ms ON ms.id = m.model_step_id
                    LEFT JOIN tool_calls tc ON tc.id = m.tool_call_id
                    WHERE m.session_id = ?
                      AND (
                        m.turn_id IS NULL
                        OR (
                          m.turn_id IN (
                            SELECT recent.id
                            FROM turns recent
                            WHERE recent.session_id = ? AND recent.status = 'COMPLETED'
                            ORDER BY recent.turn_no DESC
                            LIMIT ?
                          )
                          AND t.status = 'COMPLETED'
                          AND (m.model_step_id IS NULL OR ms.status = 'COMMITTED')
                        )
                      )
                    ORDER BY m.sequence_no
                    """)) {
                statement.setString(1, sessionId.value().toString());
                statement.setString(2, sessionId.value().toString());
                statement.setInt(3, recentFullTurns);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        messages.add(mapMessage(resultSet, toolCallsByStep));
                    }
                }
            }
            try {
                return new CanonicalHistory(sessionId, workspaceId, messages, digests,
                        totalCompletedTurnCount);
            } catch (IllegalArgumentException exception) {
                throw storageFailure("state database contains invalid canonical history",
                        exception);
            }
        });
    }

    @Override
    public void beginTurn(TurnId turnId, SessionId sessionId, Instant startedAt,
                          String userInput) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(startedAt, "startedAt");
        String input = requireNonBlank(userInput, "userInput");
        withTransaction(connection -> {
            SessionStatus sessionStatus = findSessionStatus(connection, sessionId);
            if (sessionStatus == null) {
                throw new AgentException(ErrorCode.UNKNOWN_SESSION,
                        "session does not exist");
            }
            if (sessionStatus == SessionStatus.CLOSED) {
                throw new AgentException(ErrorCode.SESSION_CLOSED, "session is closed");
            }
            if (hasActiveTurn(connection, sessionId)) {
                throw new AgentException(ErrorCode.SESSION_BUSY,
                        "session has an active turn");
            }
            int turnNo = nextSequence(connection, "turns", "turn_no",
                    "session_id", sessionId.value().toString());
            int messageSequence = nextSequence(connection, "messages", "sequence_no",
                    "session_id", sessionId.value().toString());
            long now = startedAt.toEpochMilli();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO turns
                        (id, session_id, turn_no, status, termination_reason,
                         created_at, updated_at, finished_at)
                    VALUES (?, ?, ?, 'RUNNING', NULL, ?, ?, NULL)
                    """)) {
                statement.setString(1, turnId.value().toString());
                statement.setString(2, sessionId.value().toString());
                statement.setInt(3, turnNo);
                statement.setLong(4, now);
                statement.setLong(5, now);
                requireOneRow(statement.executeUpdate(), "begin turn");
            }
            insertMessage(connection, sessionId, turnId, null, null,
                    messageSequence, "USER", "USER_TEXT", input, now);
            return null;
        });
    }

    @Override
    public void markTurnStreaming(TurnId turnId, int stepNo) {
        if (stepNo < 1) {
            throw new IllegalArgumentException("stepNo must be positive");
        }
        transitionTurn(Objects.requireNonNull(turnId, "turnId"),
                "RUNNING", "STREAMING_MODEL");
    }

    @Override
    public StagedModelStep stageToolStep(TurnId turnId, int stepNo, ModelResponse response,
                                         int contextEstimatedTokens) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(response, "response");
        if (response.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("tool step requires tool calls");
        }
        if (contextEstimatedTokens < 0 || stepNo < 1) {
            throw new IllegalArgumentException("invalid model step metadata");
        }
        UUID stepId = UUID.randomUUID();
        long now = Instant.now().toEpochMilli();
        withTransaction(connection -> {
            insertModelStep(connection, stepId, turnId, stepNo, response, contextEstimatedTokens,
                    ModelStepStatus.STAGED, now);
            int ordinal = 0;
            for (ToolCall call : response.toolCalls()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tool_calls
                            (id, model_step_id, call_id, ordinal, name, arguments_json,
                             execution_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        """)) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, stepId.toString());
                    statement.setString(3, call.callId());
                    statement.setInt(4, ordinal++);
                    statement.setString(5, call.name());
                    statement.setString(6, serializeArguments(call));
                    statement.setLong(7, now);
                    statement.setLong(8, now);
                    requireOneRow(statement.executeUpdate(), "stage tool call");
                }
            }
            updateTurnStatus(connection, turnId, "STREAMING_MODEL", "EXECUTING_TOOL");
            return null;
        });
        return new StagedModelStep(stepId, turnId, stepNo);
    }

    @Override
    public void markToolExecuting(StagedModelStep step, ToolCall call) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(call, "call");
        withTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_calls
                    SET execution_status = 'EXECUTING', started_at = ?, updated_at = ?
                    WHERE model_step_id = ? AND call_id = ?
                      AND execution_status = 'PENDING'
                    """)) {
                long now = Instant.now().toEpochMilli();
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.setString(3, step.stepId().toString());
                statement.setString(4, call.callId());
                requireOneRow(statement.executeUpdate(), "mark tool executing");
            }
            return null;
        });
    }

    @Override
    public void recordToolResult(StagedModelStep step, ToolCall call, ToolResult result) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(result, "result");
        ToolExecutionStatus executionStatus = executionStatus(result.status());
        withTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_calls
                    SET execution_status = ?, result_output = ?, result_error_code = ?,
                        result_truncated = ?, duration_ms = ?, result_metadata_json = ?,
                        completed_at = ?, updated_at = ?
                    WHERE model_step_id = ? AND call_id = ?
                      AND execution_status = 'EXECUTING'
                    """)) {
                long now = Instant.now().toEpochMilli();
                statement.setString(1, executionStatus.name());
                statement.setString(2, result.output());
                if (result.errorCode() == null) {
                    statement.setNull(3, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(3, result.errorCode().name());
                }
                statement.setInt(4, result.truncated() ? 1 : 0);
                statement.setLong(5, result.duration().toMillis());
                statement.setString(6, serializeMetadata(result.metadata()));
                statement.setLong(7, now);
                statement.setLong(8, now);
                statement.setString(9, step.stepId().toString());
                statement.setString(10, call.callId());
                requireOneRow(statement.executeUpdate(), "record tool result");
            }
            return null;
        });
    }

    @Override
    public void commitToolStep(StagedModelStep step) {
        Objects.requireNonNull(step, "step");
        withTransaction(connection -> {
            ToolStepData data = loadToolStepData(connection, step);
            int sequence = nextMessageSequenceForTurn(connection, step.turnId());
            long now = Instant.now().toEpochMilli();
            insertMessage(connection, data.sessionId(), step.turnId(), step.stepId(), null,
                    sequence++, "ASSISTANT", "ASSISTANT_TOOL_CALLS", data.visibleText(), now);
            for (StoredToolResult result : data.results()) {
                insertMessage(connection, data.sessionId(), step.turnId(), step.stepId(),
                        result.internalId(), sequence++, "TOOL", "TOOL_RESULT",
                        result.output(), now);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE model_steps
                    SET status = 'COMMITTED', updated_at = ?
                    WHERE id = ? AND turn_id = ? AND status = 'STAGED'
                    """)) {
                statement.setLong(1, now);
                statement.setString(2, step.stepId().toString());
                statement.setString(3, step.turnId().value().toString());
                requireOneRow(statement.executeUpdate(), "commit tool step");
            }
            updateTurnStatus(connection, step.turnId(), "EXECUTING_TOOL", "RUNNING");
            return null;
        });
    }

    @Override
    public void completeTurn(TurnId turnId, int stepNo, ModelResponse response,
                             int contextEstimatedTokens, TurnDigest digest,
                             Instant finishedAt) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (!response.toolCalls().isEmpty() || response.visibleText().isBlank()
                || !digest.turnId().equals(turnId) || stepNo < 1) {
            throw new IllegalArgumentException("invalid final model step");
        }
        UUID stepId = UUID.randomUUID();
        long now = finishedAt.toEpochMilli();
        withTransaction(connection -> {
            insertModelStep(connection, stepId, turnId, stepNo, response, contextEstimatedTokens,
                    ModelStepStatus.COMMITTED, now);
            SessionId sessionId = findTurnSession(connection, turnId);
            int sequence = nextMessageSequenceForTurn(connection, turnId);
            insertMessage(connection, sessionId, turnId, stepId, null,
                    sequence, "ASSISTANT", "ASSISTANT_TEXT", response.visibleText(), now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO turn_digests (turn_id, digest_json, created_at)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, turnId.value().toString());
                statement.setString(2, serializeDigest(digest));
                statement.setLong(3, now);
                requireOneRow(statement.executeUpdate(), "insert turn digest");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE turns
                    SET status = 'COMPLETED', termination_reason = NULL,
                        updated_at = ?, finished_at = ?
                    WHERE id = ? AND status = 'STREAMING_MODEL'
                    """)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.setString(3, turnId.value().toString());
                requireOneRow(statement.executeUpdate(), "complete turn");
            }
            return null;
        });
    }

    @Override
    public void failTurn(TurnId turnId, TurnStatus status, ErrorCode reason,
                         Instant finishedAt) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (status != TurnStatus.FAILED && status != TurnStatus.CANCELLED
                && status != TurnStatus.LIMIT_REACHED && status != TurnStatus.INTERRUPTED) {
            throw new IllegalArgumentException("invalid unsuccessful terminal status");
        }
        withTransaction(connection -> {
            long now = finishedAt.toEpochMilli();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_calls
                    SET execution_status = 'UNKNOWN', completed_at = ?, updated_at = ?
                    WHERE execution_status = 'EXECUTING'
                      AND model_step_id IN (
                        SELECT id FROM model_steps
                        WHERE turn_id = ? AND status = 'STAGED'
                      )
                    """)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                statement.setString(3, turnId.value().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_calls
                    SET execution_status = 'CANCELLED', result_output = ?,
                        result_error_code = 'CANCELLED', result_truncated = 0,
                        duration_ms = 0, result_metadata_json = '{}',
                        completed_at = ?, updated_at = ?
                    WHERE execution_status = 'PENDING'
                      AND model_step_id IN (
                        SELECT id FROM model_steps
                        WHERE turn_id = ? AND status = 'STAGED'
                      )
                    """)) {
                statement.setString(1,
                        "Tool call cancelled because the turn stopped before execution.");
                statement.setLong(2, now);
                statement.setLong(3, now);
                statement.setString(4, turnId.value().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE model_steps SET status = 'ABORTED', updated_at = ?
                    WHERE turn_id = ? AND status = 'STAGED'
                    """)) {
                statement.setLong(1, now);
                statement.setString(2, turnId.value().toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE turns
                    SET status = ?, termination_reason = ?, updated_at = ?, finished_at = ?
                    WHERE id = ?
                      AND status IN ('CREATED', 'RUNNING', 'STREAMING_MODEL', 'EXECUTING_TOOL')
                    """)) {
                statement.setString(1, status.name());
                statement.setString(2, reason.name());
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.setString(5, turnId.value().toString());
                requireOneRow(statement.executeUpdate(), "fail turn");
            }
            return null;
        });
    }

    @Override
    public RecoverySummary recoverInterruptedTurns() {
        return withTransaction(connection -> {
            long now = Instant.now().toEpochMilli();
            int unknownCalls;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_calls
                    SET execution_status = 'UNKNOWN', completed_at = ?, updated_at = ?
                    WHERE execution_status = 'EXECUTING'
                      AND model_step_id IN (
                        SELECT ms.id FROM model_steps ms
                        JOIN turns t ON t.id = ms.turn_id
                        WHERE ms.status = 'STAGED'
                          AND t.status IN ('CREATED', 'RUNNING',
                                           'STREAMING_MODEL', 'EXECUTING_TOOL')
                      )
                    """)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                unknownCalls = statement.executeUpdate();
            }
            int cancelledCalls;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tool_calls
                    SET execution_status = 'CANCELLED', result_output = ?,
                        result_error_code = 'CANCELLED', result_truncated = 0,
                        duration_ms = 0, result_metadata_json = '{}',
                        completed_at = ?, updated_at = ?
                    WHERE execution_status = 'PENDING'
                      AND model_step_id IN (
                        SELECT ms.id FROM model_steps ms
                        JOIN turns t ON t.id = ms.turn_id
                        WHERE ms.status = 'STAGED'
                          AND t.status IN ('CREATED', 'RUNNING',
                                           'STREAMING_MODEL', 'EXECUTING_TOOL')
                      )
                    """)) {
                statement.setString(1,
                        "Tool call cancelled during startup recovery; it was not executed.");
                statement.setLong(2, now);
                statement.setLong(3, now);
                cancelledCalls = statement.executeUpdate();
            }
            int abortedSteps;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE model_steps
                    SET status = 'ABORTED', updated_at = ?
                    WHERE status = 'STAGED'
                      AND turn_id IN (
                        SELECT id FROM turns
                        WHERE status IN ('CREATED', 'RUNNING',
                                         'STREAMING_MODEL', 'EXECUTING_TOOL')
                      )
                    """)) {
                statement.setLong(1, now);
                abortedSteps = statement.executeUpdate();
            }
            int interruptedTurns;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE turns
                    SET status = 'INTERRUPTED', termination_reason = 'INTERNAL_ERROR',
                        updated_at = ?, finished_at = ?
                    WHERE status IN ('CREATED', 'RUNNING',
                                     'STREAMING_MODEL', 'EXECUTING_TOOL')
                    """)) {
                statement.setLong(1, now);
                statement.setLong(2, now);
                interruptedTurns = statement.executeUpdate();
            }
            return new RecoverySummary(interruptedTurns, abortedSteps,
                    unknownCalls, cancelledCalls);
        });
    }

    private void initializeDatabase() {
        withConnection(connection -> {
            String journalMode;
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                if (!resultSet.next()) {
                    throw new SQLException("journal_mode did not return a value");
                }
                journalMode = resultSet.getString(1);
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw new SQLException("database did not enter WAL mode");
            }
            return null;
        });
    }

    private WorkspaceStatus findWorkspaceStatus(Connection connection, WorkspaceId workspaceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM workspaces WHERE id = ?")) {
            statement.setString(1, workspaceId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                try {
                    return WorkspaceStatus.valueOf(resultSet.getString(1));
                } catch (IllegalArgumentException exception) {
                    throw storageFailure("state database contains an invalid workspace status",
                            exception);
                }
            }
        }
    }

    private void transitionTurn(TurnId turnId, String expectedStatus, String targetStatus) {
        withTransaction(connection -> {
            updateTurnStatus(connection, turnId, expectedStatus, targetStatus);
            return null;
        });
    }

    private static void updateTurnStatus(Connection connection, TurnId turnId,
                                         String expectedStatus, String targetStatus)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE turns SET status = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """)) {
            statement.setString(1, targetStatus);
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.setString(3, turnId.value().toString());
            statement.setString(4, expectedStatus);
            requireOneRow(statement.executeUpdate(), "transition turn");
        }
    }

    private static int nextSequence(Connection connection, String table, String sequenceColumn,
                                    String ownerColumn, String ownerId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(" + sequenceColumn + "), 0) + 1 FROM "
                + table + " WHERE " + ownerColumn + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("cannot allocate sequence");
                }
                return resultSet.getInt(1);
            }
        }
    }

    private static int nextMessageSequenceForTurn(Connection connection, TurnId turnId)
            throws SQLException {
        SessionId sessionId = findTurnSession(connection, turnId);
        return nextSequence(connection, "messages", "sequence_no", "session_id",
                sessionId.value().toString());
    }

    private static SessionId findTurnSession(Connection connection, TurnId turnId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT session_id FROM turns WHERE id = ?")) {
            statement.setString(1, turnId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("turn does not exist");
                }
                try {
                    return new SessionId(UUID.fromString(resultSet.getString(1)));
                } catch (IllegalArgumentException exception) {
                    throw storageFailure("state database contains an invalid turn session",
                            exception);
                }
            }
        }
    }

    private static void insertMessage(Connection connection, SessionId sessionId, TurnId turnId,
                                      Object stepId, Object toolCallId, int sequence,
                                      String role, String kind, String content, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO messages
                    (id, session_id, turn_id, model_step_id, tool_call_id,
                     sequence_no, role, kind, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, sessionId.value().toString());
            if (turnId == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, turnId.value().toString());
            }
            if (stepId == null) {
                statement.setNull(4, java.sql.Types.VARCHAR);
            } else {
                statement.setString(4, stepId.toString());
            }
            if (toolCallId == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, toolCallId.toString());
            }
            statement.setInt(6, sequence);
            statement.setString(7, role);
            statement.setString(8, kind);
            statement.setString(9, content);
            statement.setLong(10, now);
            requireOneRow(statement.executeUpdate(), "insert message");
        }
    }

    private static void insertModelStep(Connection connection, UUID stepId, TurnId turnId,
                                        int stepNo,
                                        ModelResponse response, int contextEstimatedTokens,
                                        ModelStepStatus status, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO model_steps
                    (id, turn_id, step_no, status, response_id, visible_text,
                     prompt_tokens, completion_tokens, context_estimated_tokens,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, stepId.toString());
            statement.setString(2, turnId.value().toString());
            statement.setInt(3, stepNo);
            statement.setString(4, status.name());
            if (response.providerResponseId() == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, response.providerResponseId());
            }
            statement.setString(6, response.visibleText());
            if (response.usage() == null) {
                statement.setNull(7, java.sql.Types.INTEGER);
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setLong(7, response.usage().inputTokens());
                statement.setLong(8, response.usage().outputTokens());
            }
            statement.setInt(9, contextEstimatedTokens);
            statement.setLong(10, now);
            statement.setLong(11, now);
            requireOneRow(statement.executeUpdate(), "insert model step");
        }
    }

    private ToolStepData loadToolStepData(Connection connection, StagedModelStep step)
            throws SQLException {
        SessionId sessionId;
        String visibleText;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.session_id, ms.visible_text
                FROM model_steps ms JOIN turns t ON t.id = ms.turn_id
                WHERE ms.id = ? AND ms.turn_id = ? AND ms.status = 'STAGED'
                """)) {
            statement.setString(1, step.stepId().toString());
            statement.setString(2, step.turnId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("staged model step does not exist");
                }
                sessionId = new SessionId(UUID.fromString(resultSet.getString("session_id")));
                visibleText = resultSet.getString("visible_text");
            }
        }
        List<StoredToolResult> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, call_id, execution_status, result_output
                FROM tool_calls
                WHERE model_step_id = ?
                ORDER BY ordinal
                """)) {
            statement.setString(1, step.stepId().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ToolExecutionStatus status = ToolExecutionStatus.valueOf(
                            resultSet.getString("execution_status"));
                    if (status != ToolExecutionStatus.SUCCESS
                            && status != ToolExecutionStatus.FAILURE
                            && status != ToolExecutionStatus.DENIED
                            && status != ToolExecutionStatus.TIMED_OUT
                            && status != ToolExecutionStatus.CANCELLED) {
                        throw new SQLException("tool step contains unfinished calls");
                    }
                    results.add(new StoredToolResult(
                            UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("call_id"),
                            resultSet.getString("result_output")));
                }
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains invalid staged tool results", exception);
        }
        if (results.isEmpty()) {
            throw new SQLException("tool step has no calls");
        }
        return new ToolStepData(sessionId, visibleText, List.copyOf(results));
    }

    private static ToolExecutionStatus executionStatus(ToolStatus status) {
        return switch (status) {
            case SUCCESS -> ToolExecutionStatus.SUCCESS;
            case FAILURE -> ToolExecutionStatus.FAILURE;
            case DENIED -> ToolExecutionStatus.DENIED;
            case TIMED_OUT -> ToolExecutionStatus.TIMED_OUT;
            case CANCELLED -> ToolExecutionStatus.CANCELLED;
        };
    }

    private static ToolStatus modelVisibleStatus(ToolExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> ToolStatus.SUCCESS;
            case FAILURE -> ToolStatus.FAILURE;
            case DENIED -> ToolStatus.DENIED;
            case TIMED_OUT -> ToolStatus.TIMED_OUT;
            case CANCELLED -> ToolStatus.CANCELLED;
            case PENDING, EXECUTING, UNKNOWN -> throw new IllegalArgumentException(
                    "unfinished tool call cannot enter canonical history");
        };
    }

    private boolean hasOpenSession(Connection connection, WorkspaceId workspaceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM sessions
                WHERE workspace_id = ? AND status = 'OPEN'
                LIMIT 1
                """)) {
            statement.setString(1, workspaceId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private SessionStatus findSessionStatus(Connection connection, SessionId sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM sessions WHERE id = ?")) {
            statement.setString(1, sessionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                try {
                    return SessionStatus.valueOf(resultSet.getString(1));
                } catch (IllegalArgumentException exception) {
                    throw storageFailure("state database contains an invalid session status",
                            exception);
                }
            }
        }
    }

    private boolean hasActiveTurn(Connection connection, SessionId sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM turns
                WHERE session_id = ?
                  AND status IN ('CREATED', 'RUNNING', 'STREAMING_MODEL', 'EXECUTING_TOOL')
                LIMIT 1
                """)) {
            statement.setString(1, sessionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private WorkspaceId findSessionWorkspace(Connection connection, SessionId sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT workspace_id FROM sessions WHERE id = ?")) {
            statement.setString(1, sessionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AgentException(ErrorCode.UNKNOWN_SESSION,
                            "session does not exist");
                }
                try {
                    return new WorkspaceId(UUID.fromString(resultSet.getString(1)));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw storageFailure("state database contains an invalid session workspace",
                            exception);
                }
            }
        }
    }

    private RunLimits findSessionLimits(Connection connection, SessionId sessionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT limits_json FROM sessions WHERE id = ?")) {
            statement.setString(1, sessionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AgentException(ErrorCode.UNKNOWN_SESSION,
                            "session does not exist");
                }
                return deserializeLimits(resultSet.getString(1));
            }
        }
    }

    private Map<String, List<ToolCall>> loadCommittedToolCalls(
            Connection connection, SessionId sessionId, int recentFullTurns)
            throws SQLException {
        Map<String, List<ToolCall>> callsByStep = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tc.model_step_id, tc.call_id, tc.name, tc.arguments_json
                FROM tool_calls tc
                JOIN model_steps ms ON ms.id = tc.model_step_id
                JOIN turns t ON t.id = ms.turn_id
                WHERE t.session_id = ?
                  AND t.status = 'COMPLETED'
                  AND ms.status = 'COMMITTED'
                  AND t.id IN (
                    SELECT recent.id
                    FROM turns recent
                    WHERE recent.session_id = ? AND recent.status = 'COMPLETED'
                    ORDER BY recent.turn_no DESC
                    LIMIT ?
                  )
                ORDER BY t.turn_no, ms.step_no, tc.ordinal
                """)) {
            statement.setString(1, sessionId.value().toString());
            statement.setString(2, sessionId.value().toString());
            statement.setInt(3, recentFullTurns);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String stepId = resultSet.getString("model_step_id");
                    callsByStep.computeIfAbsent(stepId, ignored -> new ArrayList<>())
                            .add(new ToolCall(resultSet.getString("call_id"),
                                    resultSet.getString("name"),
                                    parseArguments(resultSet.getString("arguments_json"))));
                }
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains invalid tool call history", exception);
        }
        callsByStep.replaceAll((ignored, calls) -> List.copyOf(calls));
        return Map.copyOf(callsByStep);
    }

    private List<TurnDigest> loadTurnDigests(Connection connection, SessionId sessionId,
                                             int maximumDigests) throws SQLException {
        List<TurnDigest> newestFirst = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.turn_id, d.digest_json
                FROM turn_digests d
                JOIN turns t ON t.id = d.turn_id
                WHERE t.session_id = ? AND t.status = 'COMPLETED'
                ORDER BY t.turn_no DESC
                LIMIT ?
                """)) {
            statement.setString(1, sessionId.value().toString());
            statement.setInt(2, maximumDigests);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    TurnId turnId = new TurnId(UUID.fromString(resultSet.getString("turn_id")));
                    TurnDigest digest = deserializeDigest(turnId,
                            resultSet.getString("digest_json"));
                    newestFirst.add(digest);
                }
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains an invalid turn digest", exception);
        }
        java.util.Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private Message mapMessage(ResultSet resultSet,
                               Map<String, List<ToolCall>> toolCallsByStep)
            throws SQLException {
        String role = resultSet.getString("role");
        String kind = resultSet.getString("kind");
        String content = resultSet.getString("content");
        try {
            if ("SYSTEM".equals(role) && "SYSTEM_PROMPT".equals(kind)) {
                return new Message.SystemMessage(content);
            }
            TurnId turnId = new TurnId(UUID.fromString(resultSet.getString("turn_id")));
            if ("USER".equals(role) && "USER_TEXT".equals(kind)) {
                return new Message.UserMessage(turnId, content);
            }
            if ("ASSISTANT".equals(role) && "ASSISTANT_TEXT".equals(kind)) {
                return new Message.AssistantMessage(turnId, content);
            }
            if ("ASSISTANT".equals(role) && "ASSISTANT_TOOL_CALLS".equals(kind)) {
                String stepId = resultSet.getString("model_step_id");
                List<ToolCall> calls = toolCallsByStep.get(stepId);
                if (calls == null || calls.isEmpty()) {
                    throw new IllegalArgumentException("assistant tool group has no calls");
                }
                return new Message.AssistantToolCallsMessage(turnId, content, calls);
            }
            if ("TOOL".equals(role) && "TOOL_RESULT".equals(kind)) {
                ToolExecutionStatus executionStatus = ToolExecutionStatus.valueOf(
                        resultSet.getString("tool_execution_status"));
                ToolStatus toolStatus = modelVisibleStatus(executionStatus);
                String output = resultSet.getString("tool_result_output");
                if (!Objects.equals(content, output)) {
                    throw new IllegalArgumentException(
                            "tool message content does not match its result");
                }
                String errorName = resultSet.getString("tool_result_error_code");
                ErrorCode errorCode = errorName == null ? null : ErrorCode.valueOf(errorName);
                long durationMillis = resultSet.getLong("tool_duration_ms");
                if (resultSet.wasNull()) {
                    throw new IllegalArgumentException("tool result duration is missing");
                }
                int truncatedValue = resultSet.getInt("tool_result_truncated");
                if (truncatedValue != 0 && truncatedValue != 1) {
                    throw new IllegalArgumentException("tool result truncated flag is invalid");
                }
                ToolResult result = new ToolResult(toolStatus, output, errorCode,
                        truncatedValue == 1,
                        Duration.ofMillis(durationMillis),
                        deserializeMetadata(resultSet.getString("tool_result_metadata_json")));
                return new Message.ToolResultMessage(turnId,
                        resultSet.getString("protocol_call_id"), result);
            }
            throw new IllegalArgumentException("unknown message role and kind");
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains an invalid message", exception);
        }
    }

    private static ObjectNode parseArguments(String argumentsJson) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(argumentsJson);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalArgumentException("tool arguments must be an object");
            }
            return object;
        } catch (JsonProcessingException exception) {
            throw storageFailure("state database contains invalid tool arguments", exception);
        }
    }

    private WorkspaceDescriptor mapWorkspace(ResultSet resultSet) throws SQLException {
        try {
            return new WorkspaceDescriptor(
                    new WorkspaceId(UUID.fromString(resultSet.getString("id"))),
                    resultSet.getString("display_name"),
                    Path.of(resultSet.getString("root_path")),
                    WorkspaceStatus.valueOf(resultSet.getString("status")));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains an invalid workspace", exception);
        }
    }

    private SessionDescriptor mapSessionDescriptor(ResultSet resultSet) throws SQLException {
        try {
            return new SessionDescriptor(
                    new SessionId(UUID.fromString(resultSet.getString("id"))),
                    new WorkspaceId(UUID.fromString(resultSet.getString("workspace_id"))),
                    SessionStatus.valueOf(resultSet.getString("status")),
                    Instant.ofEpochMilli(resultSet.getLong("created_at")),
                    Instant.ofEpochMilli(resultSet.getLong("updated_at")));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains an invalid session", exception);
        }
    }

    private <T> T withConnection(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            configureConnection(connection);
            return operation.execute(connection);
        } catch (SQLException exception) {
            throw storageFailure("state database operation failed at " + databasePath, exception);
        }
    }

    private synchronized <T> T withTransaction(SqlOperation<T> operation) {
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        });
    }

    private void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + busyTimeoutMillis);
        }
        if (queryPragmaInt(connection, "foreign_keys") != 1) {
            throw new SQLException("foreign key enforcement is disabled");
        }
        if (queryPragmaInt(connection, "busy_timeout") != busyTimeoutMillis) {
            throw new SQLException("busy timeout was not applied");
        }
    }

    private static int queryPragmaInt(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            if (!resultSet.next()) {
                throw new SQLException("pragma did not return a value");
            }
            return resultSet.getInt(1);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void requireOneRow(int affectedRows, String operation) throws SQLException {
        if (affectedRows != 1) {
            throw new SQLException(operation + " affected " + affectedRows + " rows");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String serializeLimits(RunLimits limits) {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("maxSteps", limits.maxSteps());
        json.put("turnTimeoutMs", limits.turnTimeout().toMillis());
        json.put("modelTimeoutMs", limits.modelTimeout().toMillis());
        json.put("commandTimeoutMs", limits.commandTimeout().toMillis());
        json.put("maxToolOutputChars", limits.maxToolOutputChars());
        json.put("maxInputTokens", limits.maxInputTokens());
        json.put("reservedOutputTokens", limits.reservedOutputTokens());
        json.put("recentFullTurns", limits.recentFullTurns());
        try {
            return OBJECT_MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw storageFailure("cannot serialize session limits", exception);
        }
    }

    private static String serializeArguments(ToolCall call) {
        try {
            return OBJECT_MAPPER.writeValueAsString(call.arguments());
        } catch (JsonProcessingException exception) {
            throw storageFailure("cannot serialize tool arguments", exception);
        }
    }

    private static String serializeMetadata(Map<String, String> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw storageFailure("cannot serialize tool result metadata", exception);
        }
    }

    private static Map<String, String> deserializeMetadata(String metadataJson) {
        if (metadataJson == null) {
            return Map.of();
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(metadataJson);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("tool result metadata must be an object");
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            json.properties().forEach(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw new IllegalArgumentException(
                            "tool result metadata values must be strings");
                }
                metadata.put(entry.getKey(), entry.getValue().textValue());
            });
            return Map.copyOf(metadata);
        } catch (JsonProcessingException exception) {
            throw storageFailure("state database contains invalid tool result metadata",
                    exception);
        }
    }

    private static String serializeDigest(TurnDigest digest) {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("userGoal", digest.userGoal());
        json.put("status", digest.status().name());
        json.put("finalSummary", digest.finalSummary());
        addStrings(json, "filesRead", digest.filesRead());
        addStrings(json, "filesModified", digest.filesModified());
        addStrings(json, "commands", digest.commands());
        addStrings(json, "importantErrors", digest.importantErrors());
        addStrings(json, "openItems", digest.openItems());
        try {
            return OBJECT_MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw storageFailure("cannot serialize turn digest", exception);
        }
    }

    private static void addStrings(ObjectNode json, String field, List<String> values) {
        var array = json.putArray(field);
        values.forEach(array::add);
    }

    private static RunLimits deserializeLimits(String limitsJson) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(limitsJson);
            requireIntegralFields(json, "maxSteps", "turnTimeoutMs", "modelTimeoutMs",
                    "commandTimeoutMs", "maxToolOutputChars", "maxInputTokens",
                    "reservedOutputTokens", "recentFullTurns");
            return new RunLimits(
                    json.get("maxSteps").intValue(),
                    java.time.Duration.ofMillis(json.get("turnTimeoutMs").longValue()),
                    java.time.Duration.ofMillis(json.get("modelTimeoutMs").longValue()),
                    java.time.Duration.ofMillis(json.get("commandTimeoutMs").longValue()),
                    json.get("maxToolOutputChars").intValue(),
                    json.get("maxInputTokens").intValue(),
                    json.get("reservedOutputTokens").intValue(),
                    json.get("recentFullTurns").intValue());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains invalid session limits", exception);
        }
    }

    private static TurnDigest deserializeDigest(TurnId turnId, String digestJson) {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(digestJson);
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException("turn digest must be an object");
            }
            return new TurnDigest(turnId,
                    requiredText(json, "userGoal"),
                    TurnStatus.valueOf(requiredText(json, "status")),
                    requiredText(json, "finalSummary"),
                    stringList(json, "filesRead"),
                    stringList(json, "filesModified"),
                    stringList(json, "commands"),
                    stringList(json, "importantErrors"),
                    stringList(json, "openItems"));
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            throw storageFailure("state database contains an invalid turn digest", exception);
        }
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("invalid turn digest field: " + field);
        }
        return value.textValue();
    }

    private static List<String> stringList(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("invalid turn digest field: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("invalid turn digest list: " + field);
            }
            result.add(element.textValue());
        }
        return List.copyOf(result);
    }

    private static void requireIntegralFields(JsonNode json, String... names) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("session limits must be an object");
        }
        for (String name : names) {
            if (!json.has(name) || !json.get(name).isIntegralNumber()) {
                throw new IllegalArgumentException("invalid session limit: " + name);
            }
        }
    }

    private static AgentException storageFailure(String safeMessage, Throwable cause) {
        return new AgentException(ErrorCode.STORAGE_ERROR, safeMessage, cause);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record StoredToolResult(UUID internalId, String callId, String output) { }

    private record ToolStepData(
            SessionId sessionId,
            String visibleText,
            List<StoredToolResult> results
    ) { }
}
