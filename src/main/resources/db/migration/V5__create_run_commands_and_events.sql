CREATE TABLE run_request (
    run_id TEXT PRIMARY KEY CHECK (run_id ~ '^run-[0-9a-f]{64}$'),
    command_id TEXT NOT NULL UNIQUE,
    input_sha256 TEXT NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    spec_json JSONB NOT NULL CHECK (jsonb_typeof(spec_json) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE run_command (
    command_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES run_request(run_id),
    kind TEXT NOT NULL CHECK (kind IN ('START','CANCEL')),
    payload_sha256 TEXT NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload_json JSONB NOT NULL CHECK (jsonb_typeof(payload_json) = 'object'),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    outcome_json JSONB,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code TEXT,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);
CREATE UNIQUE INDEX run_command_one_start_per_run ON run_command(run_id) WHERE kind='START';
CREATE INDEX run_command_ready ON run_command(next_attempt_at,accepted_at)
    WHERE delivered_at IS NULL;

CREATE TABLE run_event (
    run_id TEXT NOT NULL REFERENCES run_request(run_id),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_id TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    payload_blob_id TEXT NOT NULL REFERENCES artifact_blob(sha256),
    PRIMARY KEY (run_id,sequence)
);

CREATE TABLE run_projection (
    run_id TEXT PRIMARY KEY REFERENCES run_request(run_id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    status TEXT NOT NULL,
    snapshot_sha256 TEXT REFERENCES workspace_snapshot(sha256),
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    stale BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminal_result_blob_id TEXT REFERENCES artifact_blob(sha256)
);
