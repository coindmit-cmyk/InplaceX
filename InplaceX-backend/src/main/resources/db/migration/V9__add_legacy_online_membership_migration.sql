CREATE TABLE legacy_online_session_migrations (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id) ON DELETE CASCADE,
    platform_player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    legacy_player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    command_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    migrated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, platform_player_id),
    UNIQUE (session_id, legacy_player_id)
);
