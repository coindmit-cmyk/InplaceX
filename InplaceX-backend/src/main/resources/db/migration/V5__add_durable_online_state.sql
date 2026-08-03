ALTER TABLE matchmaking_tickets ADD COLUMN command_id VARCHAR(128);
ALTER TABLE matchmaking_tickets ADD COLUMN rules_json TEXT;
ALTER TABLE matchmaking_tickets ADD COLUMN session_id VARCHAR(64) REFERENCES duel_sessions(id);
ALTER TABLE matchmaking_tickets ADD COLUMN matched_with_bot BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE matchmaking_tickets ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE duel_sessions ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE duel_sessions ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE duel_sessions ADD COLUMN winner_player_id VARCHAR(64) REFERENCES players(id);
ALTER TABLE duel_sessions ADD COLUMN state_iv BYTEA;
ALTER TABLE duel_sessions ADD COLUMN state_ciphertext BYTEA;
ALTER TABLE duel_sessions ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE duel_participants (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id) ON DELETE CASCADE,
    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    slot VARCHAR(16) NOT NULL,
    participant_type VARCHAR(16) NOT NULL,
    bot_profile_json TEXT,
    connected BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (session_id, slot),
    UNIQUE (session_id, player_id)
);

CREATE TABLE duel_secrets (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id) ON DELETE CASCADE,
    slot VARCHAR(16) NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    secret_iv BYTEA NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, slot),
    FOREIGN KEY (session_id, slot) REFERENCES duel_participants(session_id, slot) ON DELETE CASCADE
);

CREATE TABLE duel_turns (
    session_id VARCHAR(64) NOT NULL REFERENCES duel_sessions(id) ON DELETE CASCADE,
    turn_number INTEGER NOT NULL CHECK (turn_number > 0),
    actor_slot VARCHAR(16) NOT NULL,
    guess VARCHAR(16) NOT NULL,
    exact_matches INTEGER NOT NULL CHECK (exact_matches >= 0),
    solved BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, turn_number),
    FOREIGN KEY (session_id, actor_slot) REFERENCES duel_participants(session_id, slot)
);

CREATE TABLE private_duel_invites (
    invite_code VARCHAR(16) PRIMARY KEY,
    owner_player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    guest_player_id VARCHAR(64) REFERENCES players(id),
    create_command_id VARCHAR(128) NOT NULL,
    accept_command_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    rules_json TEXT NOT NULL,
    session_id VARCHAR(64) REFERENCES duel_sessions(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE online_command_results (
    operation VARCHAR(32) NOT NULL,
    actor_key VARCHAR(128) NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation, actor_key, command_id)
);

CREATE INDEX idx_matchmaking_waiting ON matchmaking_tickets(status, mode, created_at);
CREATE INDEX idx_duel_sessions_expiry ON duel_sessions(expires_at);
CREATE INDEX idx_private_invites_expiry ON private_duel_invites(status, expires_at);
CREATE INDEX idx_online_command_expiry ON online_command_results(expires_at);
