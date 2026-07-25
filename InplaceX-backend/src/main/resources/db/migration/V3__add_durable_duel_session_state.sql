ALTER TABLE duel_events ADD COLUMN event_seq BIGINT;

UPDATE duel_events SET event_seq = id WHERE event_seq IS NULL;

ALTER TABLE duel_events ALTER COLUMN event_seq SET NOT NULL;

CREATE UNIQUE INDEX idx_legacy_duel_events_session_sequence
ON duel_events(session_id, event_seq);

CREATE TABLE duel_session_states (
    session_id VARCHAR(64) PRIMARY KEY REFERENCES duel_sessions(id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    event_cursor BIGINT NOT NULL CHECK (event_cursor >= 0),
    first_retained_event_seq BIGINT NOT NULL CHECK (first_retained_event_seq > 0),
    public_state_available BOOLEAN NOT NULL,
    snapshot_event_seq BIGINT,
    snapshot_json TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (snapshot_event_seq IS NULL OR snapshot_event_seq >= 0),
    CHECK (snapshot_event_seq IS NULL OR snapshot_event_seq <= event_cursor),
    CHECK (
        (public_state_available = TRUE AND snapshot_event_seq IS NOT NULL AND snapshot_json IS NOT NULL)
        OR
        (public_state_available = FALSE AND snapshot_event_seq IS NULL AND snapshot_json IS NULL)
    )
);

CREATE TABLE duel_session_snapshots (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id),
    event_seq BIGINT NOT NULL CHECK (event_seq >= 0),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, event_seq)
);

CREATE TABLE duel_session_events (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id),
    event_seq BIGINT NOT NULL CHECK (event_seq > 0),
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, event_seq)
);

CREATE TABLE duel_command_receipts (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id),
    actor_id VARCHAR(64) NOT NULL,
    client_command_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    event_seq BIGINT NOT NULL CHECK (event_seq > 0),
    result_type VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, actor_id, client_command_id),
    UNIQUE (session_id, revision),
    UNIQUE (session_id, event_seq)
);

INSERT INTO duel_session_states(
    session_id,
    revision,
    event_cursor,
    first_retained_event_seq,
    public_state_available,
    snapshot_event_seq,
    snapshot_json
)
SELECT
    sessions.id,
    sessions.version,
    COALESCE(
        (SELECT MAX(events.event_seq) FROM duel_events events WHERE events.session_id = sessions.id),
        0
    ),
    COALESCE(
        (SELECT MAX(events.event_seq) FROM duel_events events WHERE events.session_id = sessions.id),
        0
    ) + 1,
    FALSE,
    NULL,
    NULL
FROM duel_sessions sessions;

CREATE INDEX idx_duel_session_events_reconnect
ON duel_session_events(session_id, event_seq);

CREATE INDEX idx_duel_session_snapshots_reconnect
ON duel_session_snapshots(session_id, event_seq);
