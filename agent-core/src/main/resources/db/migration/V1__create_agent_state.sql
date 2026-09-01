CREATE TABLE workspaces (
    id TEXT PRIMARY KEY
        CHECK (length(trim(id)) > 0),
    display_name TEXT NOT NULL
        CHECK (length(trim(display_name)) > 0),
    root_path TEXT NOT NULL UNIQUE
        CHECK (length(trim(root_path)) > 0),
    status TEXT NOT NULL
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'UNAVAILABLE')),
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL
        CHECK (updated_at >= created_at)
);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY
        CHECK (length(trim(id)) > 0),
    workspace_id TEXT NOT NULL,
    status TEXT NOT NULL
        CHECK (status IN ('OPEN', 'CLOSED')),
    limits_json TEXT NOT NULL
        CHECK (json_valid(limits_json)),
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL
        CHECK (updated_at >= created_at),
    closed_at INTEGER,
    CONSTRAINT fk_sessions_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_sessions_closed_at
        CHECK (
            (status = 'OPEN' AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at >= created_at)
        )
);

CREATE TABLE turns (
    id TEXT PRIMARY KEY
        CHECK (length(trim(id)) > 0),
    session_id TEXT NOT NULL,
    turn_no INTEGER NOT NULL
        CHECK (turn_no > 0),
    thinking_enabled INTEGER NOT NULL
        CHECK (thinking_enabled IN (0, 1)),
    status TEXT NOT NULL
        CHECK (status IN (
            'CREATED', 'RUNNING', 'STREAMING_MODEL', 'EXECUTING_TOOL',
            'INTERRUPTED', 'COMPLETED', 'FAILED', 'CANCELLED', 'LIMIT_REACHED'
        )),
    termination_reason TEXT,
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL
        CHECK (updated_at >= created_at),
    finished_at INTEGER,
    CONSTRAINT uq_turns_id_session UNIQUE (id, session_id),
    CONSTRAINT uq_turns_session_number UNIQUE (session_id, turn_no),
    CONSTRAINT fk_turns_session
        FOREIGN KEY (session_id) REFERENCES sessions (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_turns_terminal_fields
        CHECK (
            (
                status IN ('CREATED', 'RUNNING', 'STREAMING_MODEL', 'EXECUTING_TOOL')
                AND termination_reason IS NULL
                AND finished_at IS NULL
            )
            OR (
                status = 'COMPLETED'
                AND termination_reason IS NULL
                AND finished_at IS NOT NULL
                AND finished_at >= created_at
            )
            OR (
                status IN ('INTERRUPTED', 'FAILED', 'CANCELLED', 'LIMIT_REACHED')
                AND termination_reason IS NOT NULL
                AND length(trim(termination_reason)) > 0
                AND finished_at IS NOT NULL
                AND finished_at >= created_at
            )
        )
);

CREATE TABLE model_steps (
    id TEXT PRIMARY KEY
        CHECK (length(trim(id)) > 0),
    turn_id TEXT NOT NULL,
    step_no INTEGER NOT NULL
        CHECK (step_no > 0),
    status TEXT NOT NULL
        CHECK (status IN ('STAGED', 'COMMITTED', 'ABORTED')),
    response_id TEXT
        CHECK (response_id IS NULL OR length(trim(response_id)) > 0),
    visible_text TEXT NOT NULL DEFAULT '',
    prompt_tokens INTEGER
        CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    completion_tokens INTEGER
        CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    context_estimated_tokens INTEGER NOT NULL
        CHECK (context_estimated_tokens >= 0),
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL
        CHECK (updated_at >= created_at),
    CONSTRAINT uq_model_steps_id_turn UNIQUE (id, turn_id),
    CONSTRAINT uq_model_steps_turn_number UNIQUE (turn_id, step_no),
    CONSTRAINT fk_model_steps_turn
        FOREIGN KEY (turn_id) REFERENCES turns (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE tool_calls (
    id TEXT PRIMARY KEY
        CHECK (length(trim(id)) > 0),
    model_step_id TEXT NOT NULL,
    call_id TEXT NOT NULL
        CHECK (length(trim(call_id)) > 0),
    ordinal INTEGER NOT NULL
        CHECK (ordinal >= 0),
    name TEXT NOT NULL
        CHECK (length(trim(name)) > 0),
    arguments_json TEXT NOT NULL
        CHECK (json_valid(arguments_json)),
    execution_status TEXT NOT NULL
        CHECK (execution_status IN (
            'PENDING', 'EXECUTING', 'SUCCESS', 'FAILURE',
            'DENIED', 'TIMED_OUT', 'CANCELLED', 'UNKNOWN'
        )),
    result_output TEXT,
    result_error_code TEXT
        CHECK (result_error_code IS NULL OR length(trim(result_error_code)) > 0),
    result_truncated INTEGER NOT NULL DEFAULT 0
        CHECK (result_truncated IN (0, 1)),
    duration_ms INTEGER
        CHECK (duration_ms IS NULL OR duration_ms >= 0),
    result_metadata_json TEXT
        CHECK (result_metadata_json IS NULL OR json_valid(result_metadata_json)),
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL
        CHECK (updated_at >= created_at),
    started_at INTEGER,
    completed_at INTEGER,
    CONSTRAINT uq_tool_calls_id_step UNIQUE (id, model_step_id),
    CONSTRAINT uq_tool_calls_step_call UNIQUE (model_step_id, call_id),
    CONSTRAINT uq_tool_calls_step_ordinal UNIQUE (model_step_id, ordinal),
    CONSTRAINT fk_tool_calls_model_step
        FOREIGN KEY (model_step_id) REFERENCES model_steps (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_tool_calls_lifecycle
        CHECK (
            (
                execution_status = 'PENDING'
                AND started_at IS NULL
                AND completed_at IS NULL
                AND result_output IS NULL
                AND result_error_code IS NULL
                AND result_truncated = 0
                AND duration_ms IS NULL
                AND result_metadata_json IS NULL
            )
            OR (
                execution_status = 'EXECUTING'
                AND started_at IS NOT NULL
                AND completed_at IS NULL
                AND result_output IS NULL
                AND result_error_code IS NULL
                AND result_truncated = 0
                AND duration_ms IS NULL
                AND result_metadata_json IS NULL
            )
            OR (
                execution_status = 'SUCCESS'
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND result_output IS NOT NULL
                AND result_error_code IS NULL
                AND duration_ms IS NOT NULL
            )
            OR (
                execution_status IN ('FAILURE', 'DENIED', 'TIMED_OUT', 'CANCELLED')
                AND completed_at IS NOT NULL
                AND result_output IS NOT NULL
                AND result_error_code IS NOT NULL
                AND duration_ms IS NOT NULL
            )
            OR (
                execution_status = 'UNKNOWN'
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND result_output IS NULL
                AND result_error_code IS NULL
                AND result_truncated = 0
                AND duration_ms IS NULL
                AND result_metadata_json IS NULL
            )
        ),
    CONSTRAINT ck_tool_calls_execution_times
        CHECK (
            (started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)
            AND (started_at IS NULL OR completed_at IS NULL OR completed_at >= started_at)
        )
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY
        CHECK (length(trim(id)) > 0),
    session_id TEXT NOT NULL,
    turn_id TEXT,
    model_step_id TEXT,
    tool_call_id TEXT,
    sequence_no INTEGER NOT NULL
        CHECK (sequence_no > 0),
    role TEXT NOT NULL
        CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    kind TEXT NOT NULL
        CHECK (kind IN (
            'SYSTEM_PROMPT', 'USER_TEXT', 'ASSISTANT_TEXT',
            'ASSISTANT_TOOL_CALLS', 'TOOL_RESULT'
        )),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    CONSTRAINT uq_messages_session_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT fk_messages_session
        FOREIGN KEY (session_id) REFERENCES sessions (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_messages_turn_session
        FOREIGN KEY (turn_id, session_id) REFERENCES turns (id, session_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_messages_step_turn
        FOREIGN KEY (model_step_id, turn_id) REFERENCES model_steps (id, turn_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_messages_call_step
        FOREIGN KEY (tool_call_id, model_step_id) REFERENCES tool_calls (id, model_step_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_messages_shape
        CHECK (
            (
                role = 'SYSTEM'
                AND kind = 'SYSTEM_PROMPT'
                AND sequence_no = 1
                AND turn_id IS NULL
                AND model_step_id IS NULL
                AND tool_call_id IS NULL
                AND length(trim(content)) > 0
            )
            OR (
                role = 'USER'
                AND kind = 'USER_TEXT'
                AND sequence_no > 1
                AND turn_id IS NOT NULL
                AND model_step_id IS NULL
                AND tool_call_id IS NULL
                AND length(trim(content)) > 0
            )
            OR (
                role = 'ASSISTANT'
                AND kind = 'ASSISTANT_TEXT'
                AND sequence_no > 1
                AND turn_id IS NOT NULL
                AND model_step_id IS NOT NULL
                AND tool_call_id IS NULL
                AND length(trim(content)) > 0
            )
            OR (
                role = 'ASSISTANT'
                AND kind = 'ASSISTANT_TOOL_CALLS'
                AND sequence_no > 1
                AND turn_id IS NOT NULL
                AND model_step_id IS NOT NULL
                AND tool_call_id IS NULL
            )
            OR (
                role = 'TOOL'
                AND kind = 'TOOL_RESULT'
                AND sequence_no > 1
                AND turn_id IS NOT NULL
                AND model_step_id IS NOT NULL
                AND tool_call_id IS NOT NULL
            )
        )
);

CREATE TABLE turn_digests (
    turn_id TEXT PRIMARY KEY,
    digest_json TEXT NOT NULL
        CHECK (json_valid(digest_json)),
    created_at INTEGER NOT NULL
        CHECK (created_at >= 0),
    CONSTRAINT fk_turn_digests_turn
        FOREIGN KEY (turn_id) REFERENCES turns (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE INDEX idx_sessions_workspace_status
    ON sessions (workspace_id, status);

CREATE INDEX idx_turns_session_status
    ON turns (session_id, status);

CREATE INDEX idx_model_steps_turn_status
    ON model_steps (turn_id, status);

CREATE INDEX idx_tool_calls_step_status
    ON tool_calls (model_step_id, execution_status);
