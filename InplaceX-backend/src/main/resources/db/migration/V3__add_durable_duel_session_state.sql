CREATE TABLE duel_session_states (
    session_id VARCHAR(64) PRIMARY KEY REFERENCES duel_sessions(id),
    version BIGINT NOT NULL CHECK (version >= 0),
    event_cursor BIGINT NOT NULL CHECK (event_cursor >= 0),
    first_retained_event_seq BIGINT NOT NULL CHECK (first_retained_event_seq >= 0),
    snapshot_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    fingerprint VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    event_cursor BIGINT NOT NULL CHECK (event_cursor > 0),
    result_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, actor_id, client_command_id)
);

INSERT INTO duel_session_states(session_id, version, event_cursor, first_retained_event_seq, snapshot_json)
SELECT id, version, 0, 0, config_json FROM duel_sessions;

CREATE INDEX idx_duel_session_events_reconnect ON duel_session_events(session_id, event_seq);
