ALTER TABLE task_control.integration_candidates
    ADD COLUMN IF NOT EXISTS owner_session_id text
        REFERENCES task_control.project_sessions(session_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS integration_candidates_owner_session_idx
    ON task_control.integration_candidates (owner_session_id)
    WHERE owner_session_id IS NOT NULL;
