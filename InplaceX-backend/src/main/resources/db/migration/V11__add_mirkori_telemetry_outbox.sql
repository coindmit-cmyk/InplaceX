CREATE TABLE mirkori_gameplay_sessions (
    duel_session_id VARCHAR(64) NOT NULL,
    game_profile_id VARCHAR(64) NOT NULL,
    platform_session_id VARCHAR(63) NOT NULL UNIQUE,
    next_sequence BIGINT NOT NULL CHECK (next_sequence > 0),
    last_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('open', 'ended')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (duel_session_id, game_profile_id)
);

CREATE TABLE mirkori_telemetry_outbox (
    event_id VARCHAR(63) PRIMARY KEY,
    fact_type VARCHAR(16) NOT NULL CHECK (fact_type IN ('achievement', 'gameplay')),
    game_profile_id VARCHAR(64) NOT NULL,
    achievement_id VARCHAR(128),
    platform_session_id VARCHAR(63),
    sequence_number BIGINT,
    gameplay_event_type VARCHAR(16),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'processing', 'delivered', 'dead')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token VARCHAR(64),
    claimed_until TIMESTAMP WITH TIME ZONE,
    platform_decision VARCHAR(64),
    platform_session_status VARCHAR(32),
    trusted_duration_seconds BIGINT,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (
        (fact_type = 'achievement' AND achievement_id IS NOT NULL AND platform_session_id IS NULL AND sequence_number IS NULL AND gameplay_event_type IS NULL) OR
        (fact_type = 'gameplay' AND achievement_id IS NULL AND platform_session_id IS NOT NULL AND sequence_number IS NOT NULL AND sequence_number >= 0 AND gameplay_event_type IN ('start', 'heartbeat', 'end'))
    ),
    CHECK (
        (status = 'processing' AND claim_token IS NOT NULL AND claimed_until IS NOT NULL) OR
        (status <> 'processing' AND claim_token IS NULL AND claimed_until IS NULL)
    ),
    CHECK (
        fact_type <> 'gameplay' OR
        (sequence_number = 0 AND gameplay_event_type = 'start') OR
        (sequence_number > 0 AND gameplay_event_type IN ('heartbeat', 'end'))
    )
);

CREATE UNIQUE INDEX idx_mirkori_gameplay_sequence
    ON mirkori_telemetry_outbox(platform_session_id, sequence_number);
CREATE INDEX idx_mirkori_telemetry_due
    ON mirkori_telemetry_outbox(status, next_attempt_at, created_at);
CREATE INDEX idx_mirkori_gameplay_open
    ON mirkori_gameplay_sessions(status, last_event_at);
