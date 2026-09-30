ALTER TABLE players ADD COLUMN account_kind VARCHAR(16) NOT NULL DEFAULT 'guest';

CREATE TABLE player_profiles (
    player_id VARCHAR(64) PRIMARY KEY REFERENCES players(id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    locale VARCHAR(16),
    region_hint VARCHAR(16),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO player_profiles(player_id, revision)
SELECT id, 0 FROM players;

CREATE TABLE guest_installations (
    installation_hash VARCHAR(64) PRIMARY KEY,
    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    platform VARCHAR(16) NOT NULL,
    app_version VARCHAR(64),
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE refresh_token_families (
    id VARCHAR(64) PRIMARY KEY,
    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    family_id VARCHAR(64) NOT NULL REFERENCES refresh_token_families(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE save_commands (
    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    command_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_id, command_id)
);

CREATE INDEX idx_guest_installations_player ON guest_installations(player_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
