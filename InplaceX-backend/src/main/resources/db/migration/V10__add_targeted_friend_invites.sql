ALTER TABLE private_duel_invites
    ADD COLUMN target_player_id VARCHAR(64);

CREATE INDEX idx_private_invites_target_waiting
    ON private_duel_invites(target_player_id, status, expires_at);
