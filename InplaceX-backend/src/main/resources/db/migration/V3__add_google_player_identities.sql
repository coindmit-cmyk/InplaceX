CREATE TABLE player_identities (
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider, provider_subject),
    UNIQUE (provider, player_id)
);

CREATE TABLE google_auth_challenges (
    nonce_hash VARCHAR(64) PRIMARY KEY,
    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_player_identities_player ON player_identities(player_id);
CREATE INDEX idx_google_auth_challenges_player ON google_auth_challenges(player_id);
