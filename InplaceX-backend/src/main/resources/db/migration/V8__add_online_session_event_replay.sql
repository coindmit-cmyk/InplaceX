ALTER TABLE duel_events ADD COLUMN session_revision BIGINT CHECK (session_revision IS NULL OR session_revision > 0);
CREATE INDEX idx_duel_events_session_cursor ON duel_events(session_id, id);
