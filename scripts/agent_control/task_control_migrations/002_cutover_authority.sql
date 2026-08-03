ALTER TABLE task_control.projects
    ADD COLUMN IF NOT EXISTS state_version bigint NOT NULL DEFAULT 1
        CHECK (state_version > 0);

CREATE TABLE IF NOT EXISTS task_control.project_sessions (
    session_id text PRIMARY KEY,
    project_id text NOT NULL REFERENCES task_control.projects(project_id) ON DELETE CASCADE,
    run_id text NOT NULL,
    owner_id text NOT NULL,
    state text NOT NULL CHECK (state IN ('active', 'committed', 'aborted', 'expired')),
    base_state_version bigint NOT NULL CHECK (base_state_version > 0),
    result_state_version bigint CHECK (result_state_version IS NULL OR result_state_version > 0),
    acquired_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    finished_at timestamptz,
    finish_reason text,
    source_digest text,
    result_digest text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (expires_at > acquired_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS project_sessions_one_active_per_project_idx
    ON task_control.project_sessions (project_id)
    WHERE state = 'active';
CREATE INDEX IF NOT EXISTS project_sessions_expiry_idx
    ON task_control.project_sessions (expires_at)
    WHERE state = 'active';

CREATE TABLE IF NOT EXISTS task_control.integration_candidates (
    project_id text NOT NULL REFERENCES task_control.projects(project_id) ON DELETE CASCADE,
    task_id text NOT NULL,
    candidate_id text NOT NULL,
    state text NOT NULL CHECK (state IN (
        'draft', 'ready', 'integrating', 'merged', 'needs_human', 'rejected', 'archived'
    )),
    base_branch text NOT NULL DEFAULT 'develop',
    base_sha text NOT NULL,
    work_branch text NOT NULL,
    head_sha text NOT NULL,
    pull_request_number bigint,
    pull_request_url text,
    latest_base_sha text,
    merge_commit_sha text,
    changed_paths jsonb NOT NULL DEFAULT '[]'::jsonb,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, candidate_id),
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS integration_candidates_ready_idx
    ON task_control.integration_candidates (project_id, created_at, candidate_id)
    WHERE state IN ('ready', 'integrating');

CREATE TABLE IF NOT EXISTS task_control.project_state_commits (
    idempotency_key text PRIMARY KEY,
    project_id text NOT NULL REFERENCES task_control.projects(project_id) ON DELETE CASCADE,
    session_id text NOT NULL REFERENCES task_control.project_sessions(session_id) ON DELETE RESTRICT,
    from_state_version bigint NOT NULL,
    to_state_version bigint NOT NULL,
    source_digest text NOT NULL,
    result_digest text NOT NULL,
    actor text NOT NULL,
    committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (to_state_version = from_state_version + 1)
);

CREATE OR REPLACE FUNCTION task_control.assert_runtime_authority(
    expected_mode text,
    expected_source text,
    expected_cutover boolean
) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    actual task_control.runtime_configuration%ROWTYPE;
BEGIN
    SELECT * INTO actual
    FROM task_control.runtime_configuration
    WHERE singleton;
    IF NOT FOUND
       OR actual.mode IS DISTINCT FROM expected_mode
       OR actual.source_of_truth IS DISTINCT FROM expected_source
       OR actual.cutover_enabled IS DISTINCT FROM expected_cutover THEN
        RAISE EXCEPTION 'task control runtime authority mismatch';
    END IF;
END;
$$;
