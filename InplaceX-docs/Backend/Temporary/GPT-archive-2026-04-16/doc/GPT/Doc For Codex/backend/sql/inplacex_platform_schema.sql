-- InplaceX platform schema draft
-- date: 2026-04-16

create extension if not exists pgcrypto;

do $$ begin
    create type identity_provider as enum ('guest','google','facebook','apple','email');
exception when duplicate_object then null; end $$;

do $$ begin
    create type reward_session_status as enum ('created','shown','completedClient','verifiedProvider','granted','rejected','expired');
exception when duplicate_object then null; end $$;

do $$ begin
    create type room_type as enum ('privateDuel','privateRace','partyRace','tournamentRoom');
exception when duplicate_object then null; end $$;

do $$ begin
    create type room_status as enum ('waiting','ready','inProgress','finished','cancelled');
exception when duplicate_object then null; end $$;

do $$ begin
    create type match_status as enum ('created','running','finished','cancelled');
exception when duplicate_object then null; end $$;

create table if not exists players (
    id uuid primary key,
    display_name text,
    locale text,
    region_code text,
    status text not null default 'active',
    created_at timestamptz not null default now()
);

create table if not exists player_identities (
    id uuid primary key,
    player_id uuid not null references players(id) on delete cascade,
    provider identity_provider not null,
    provider_subject text not null,
    linked_at timestamptz not null default now(),
    unique (provider, provider_subject)
);

create table if not exists refresh_sessions (
    id uuid primary key,
    player_id uuid not null references players(id) on delete cascade,
    refresh_token_hash text not null,
    installation_id text,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create table if not exists player_devices (
    id uuid primary key,
    player_id uuid not null references players(id) on delete cascade,
    installation_id text not null,
    platform text not null,
    app_version text,
    last_seen_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    unique (installation_id)
);

create table if not exists player_progress (
    id uuid primary key,
    player_id uuid not null references players(id) on delete cascade,
    game_slug text not null,
    cloud_revision bigint not null default 0,
    state_json jsonb not null default '{}'::jsonb,
    soft_currency bigint not null default 0,
    hint_balance integer not null default 0,
    updated_at timestamptz not null default now(),
    unique (player_id, game_slug)
);

create table if not exists ad_reward_sessions (
    id uuid primary key,
    player_id uuid not null references players(id) on delete cascade,
    placement text not null,
    provider text not null,
    state reward_session_status not null default 'created',
    reward_kind text not null,
    reward_amount integer not null,
    provider_session_id text,
    provider_transaction_id text,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    granted_at timestamptz
);

create unique index if not exists uq_ad_reward_provider_tx
    on ad_reward_sessions(provider, provider_transaction_id)
    where provider_transaction_id is not null;

create table if not exists economy_ledger (
    id uuid primary key,
    player_id uuid not null references players(id) on delete cascade,
    game_slug text not null,
    reason text not null,
    delta_soft_currency bigint not null default 0,
    delta_hint_balance integer not null default 0,
    source_ref_type text,
    source_ref_id uuid,
    created_at timestamptz not null default now()
);

create table if not exists rooms (
    id uuid primary key,
    game_slug text not null,
    room_type room_type not null,
    status room_status not null default 'waiting',
    owner_player_id uuid not null references players(id) on delete restrict,
    invite_code text,
    config_json jsonb not null,
    created_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz
);

create table if not exists room_members (
    id uuid primary key,
    room_id uuid not null references rooms(id) on delete cascade,
    player_id uuid not null references players(id) on delete cascade,
    seat_no integer,
    member_status text not null default 'joined',
    joined_at timestamptz not null default now(),
    unique (room_id, player_id)
);

create table if not exists matches (
    id uuid primary key,
    room_id uuid not null references rooms(id) on delete cascade,
    mode text not null,
    status match_status not null default 'created',
    code_length integer not null,
    allow_duplicates boolean not null,
    attempt_limit integer,
    turn_time_limit_sec integer,
    secret_source text not null,
    seed text,
    winner_player_id uuid references players(id) on delete set null,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now()
);

create table if not exists match_participants (
    id uuid primary key,
    match_id uuid not null references matches(id) on delete cascade,
    player_id uuid not null references players(id) on delete cascade,
    participant_status text not null default 'active',
    attempts_used integer not null default 0,
    elapsed_ms bigint not null default 0,
    secret_value_enc bytea,
    secret_sha256 text,
    finished_at timestamptz,
    unique (match_id, player_id)
);

create table if not exists match_turns (
    id uuid primary key,
    match_id uuid not null references matches(id) on delete cascade,
    player_id uuid not null references players(id) on delete cascade,
    client_turn_id uuid not null,
    turn_index integer not null,
    guess text not null,
    score integer not null,
    created_at timestamptz not null default now(),
    unique (match_id, player_id, client_turn_id)
);

create index if not exists idx_match_turns_match_created
    on match_turns(match_id, created_at);

create table if not exists processed_idempotency_keys (
    id uuid primary key,
    idempotency_key text not null,
    scope text not null,
    player_id uuid references players(id) on delete set null,
    response_json jsonb,
    created_at timestamptz not null default now(),
    unique (scope, idempotency_key)
);
