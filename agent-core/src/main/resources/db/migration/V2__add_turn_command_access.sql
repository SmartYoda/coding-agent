ALTER TABLE turns ADD COLUMN command_access_mode TEXT NOT NULL DEFAULT 'RESTRICTED'
    CHECK (command_access_mode IN ('RESTRICTED', 'ASK', 'FULL_ACCESS'));
