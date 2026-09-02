CREATE SCHEMA IF NOT EXISTS task_control;

CREATE TABLE IF NOT EXISTS task_control.schema_migrations (
    version integer PRIMARY KEY,
    name text NOT NULL,
    checksum text NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS task_control.runtime_configuration (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    mode text NOT NULL DEFAULT 'shadow' CHECK (mode IN ('shadow', 'cutover')),
    source_of_truth text NOT NULL DEFAULT 'json_git' CHECK (source_of_truth IN ('json_git', 'postgres')),
    cutover_enabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (
        (mode = 'shadow' AND source_of_truth = 'json_git' AND cutover_enabled = false)
        OR
        (mode = 'cutover' AND source_of_truth = 'postgres' AND cutover_enabled = true)
    )
);

INSERT INTO task_control.runtime_configuration (singleton)
VALUES (true)
ON CONFLICT (singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS task_control.projects (
    project_id text PRIMARY KEY CHECK (project_id ~ '^[a-z0-9][a-z0-9._-]{1,127}$'),
    repository text,
    base_branch text NOT NULL DEFAULT 'develop',
    enabled boolean NOT NULL DEFAULT true,
    source_revision text,
    source_digest text,
    queue_envelope jsonb NOT NULL DEFAULT '{"schema_version":1}'::jsonb,
    history_envelope jsonb NOT NULL DEFAULT '{"schema_version":1}'::jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_shadow_sync_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS task_control.shadow_runs (
    run_id text PRIMARY KEY,
    state text NOT NULL CHECK (state IN ('running', 'succeeded', 'failed')),
    source_kind text NOT NULL DEFAULT 'json_git',
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    project_count integer NOT NULL DEFAULT 0 CHECK (project_count >= 0),
    task_count integer NOT NULL DEFAULT 0 CHECK (task_count >= 0),
    source_digest text,
    error jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS task_control.tasks (
    project_id text NOT NULL REFERENCES task_control.projects(project_id) ON DELETE CASCADE,
    task_id text NOT NULL,
    title text NOT NULL,
    status text NOT NULL,
    priority text,
    complexity text,
    task_type text,
    worker_ready boolean NOT NULL DEFAULT false,
    terminal boolean NOT NULL DEFAULT false,
    source_kind text NOT NULL CHECK (source_kind IN ('queue', 'history', 'native')),
    source_digest text NOT NULL,
    source_revision text,
    source_updated_at timestamptz,
    payload jsonb NOT NULL,
    row_version bigint NOT NULL DEFAULT 1 CHECK (row_version > 0),
    shadow_present boolean NOT NULL DEFAULT true,
    last_seen_run_id text REFERENCES task_control.shadow_runs(run_id) ON DELETE SET NULL,
    imported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, task_id)
);

CREATE INDEX IF NOT EXISTS tasks_status_idx
    ON task_control.tasks (status, project_id);
CREATE INDEX IF NOT EXISTS tasks_worker_ready_idx
    ON task_control.tasks (project_id, priority, task_id)
    WHERE worker_ready AND NOT terminal AND shadow_present;
CREATE INDEX IF NOT EXISTS tasks_payload_gin_idx
    ON task_control.tasks USING gin (payload);

CREATE TABLE IF NOT EXISTS task_control.task_source_records (
    project_id text NOT NULL,
    task_id text NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('queue', 'history')),
    source_ordinal integer NOT NULL CHECK (source_ordinal >= 0),
    source_digest text NOT NULL,
    payload jsonb NOT NULL,
    source_present boolean NOT NULL DEFAULT true,
    last_seen_run_id text REFERENCES task_control.shadow_runs(run_id) ON DELETE SET NULL,
    imported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, task_id, source_kind),
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS task_source_records_present_idx
    ON task_control.task_source_records (project_id, source_kind, task_id)
    WHERE source_present;

CREATE TABLE IF NOT EXISTS task_control.task_dependencies (
    project_id text NOT NULL,
    task_id text NOT NULL,
    dependency_task_id text NOT NULL,
    dependency_kind text NOT NULL DEFAULT 'blocked_by',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, task_id, dependency_task_id, dependency_kind),
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_control.task_events (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key text NOT NULL UNIQUE,
    project_id text NOT NULL,
    task_id text NOT NULL,
    event_type text NOT NULL,
    from_status text,
    to_status text,
    actor text NOT NULL,
    attempt_id text,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS task_events_task_time_idx
    ON task_control.task_events (project_id, task_id, occurred_at, event_id);

CREATE TABLE IF NOT EXISTS task_control.task_attempts (
    project_id text NOT NULL,
    task_id text NOT NULL,
    attempt_id text NOT NULL,
    stage text NOT NULL,
    status text NOT NULL CHECK (status IN ('planned', 'running', 'succeeded', 'failed', 'cancelled')),
    model text,
    reasoning_effort text,
    skills jsonb NOT NULL DEFAULT '[]'::jsonb,
    accepted boolean,
    result_digest text,
    started_at timestamptz,
    finished_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, task_id, attempt_id),
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_control.task_leases (
    lease_id text PRIMARY KEY,
    project_id text NOT NULL,
    task_id text NOT NULL,
    owner_id text NOT NULL,
    state text NOT NULL CHECK (state IN ('active', 'released', 'expired')),
    acquired_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    released_at timestamptz,
    release_reason text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE,
    CHECK (expires_at > acquired_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS task_leases_one_active_per_task_idx
    ON task_control.task_leases (project_id, task_id)
    WHERE state = 'active';
CREATE INDEX IF NOT EXISTS task_leases_expiry_idx
    ON task_control.task_leases (expires_at)
    WHERE state = 'active';

CREATE TABLE IF NOT EXISTS task_control.resource_usage (
    usage_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key text NOT NULL UNIQUE,
    project_id text NOT NULL,
    task_id text NOT NULL,
    attempt_id text,
    stage text NOT NULL,
    model text,
    input_tokens bigint NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens bigint NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cached_input_tokens bigint NOT NULL DEFAULT 0 CHECK (cached_input_tokens >= 0),
    reasoning_tokens bigint NOT NULL DEFAULT 0 CHECK (reasoning_tokens >= 0),
    tool_tokens bigint NOT NULL DEFAULT 0 CHECK (tool_tokens >= 0),
    effective_tokens bigint NOT NULL DEFAULT 0 CHECK (effective_tokens >= 0),
    cost_usd numeric(18, 8) CHECK (cost_usd IS NULL OR cost_usd >= 0),
    wall_time_ms bigint CHECK (wall_time_ms IS NULL OR wall_time_ms >= 0),
    cpu_time_ms bigint CHECK (cpu_time_ms IS NULL OR cpu_time_ms >= 0),
    max_rss_kb bigint CHECK (max_rss_kb IS NULL OR max_rss_kb >= 0),
    outcome text,
    accepted boolean,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (project_id, task_id)
        REFERENCES task_control.tasks(project_id, task_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS resource_usage_task_stage_idx
    ON task_control.resource_usage (project_id, task_id, stage, recorded_at);

CREATE TABLE IF NOT EXISTS task_control.shadow_run_tasks (
    run_id text NOT NULL REFERENCES task_control.shadow_runs(run_id) ON DELETE CASCADE,
    project_id text NOT NULL,
    task_id text NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('queue', 'history')),
    source_digest text NOT NULL,
    PRIMARY KEY (run_id, project_id, task_id, source_kind)
);

CREATE TABLE IF NOT EXISTS task_control.backup_records (
    backup_id text PRIMARY KEY,
    created_at timestamptz NOT NULL,
    database_name text NOT NULL,
    schema_name text NOT NULL DEFAULT 'task_control',
    dump_format text NOT NULL DEFAULT 'custom',
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    verified_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS task_control.outbox_events (
    outbox_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic text NOT NULL,
    aggregate_key text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error text
);

CREATE INDEX IF NOT EXISTS outbox_unpublished_idx
    ON task_control.outbox_events (created_at, outbox_id)
    WHERE published_at IS NULL;
