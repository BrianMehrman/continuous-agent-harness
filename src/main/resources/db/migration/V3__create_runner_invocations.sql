CREATE TABLE artifact_blob (
    sha256 text PRIMARY KEY CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    media_type text NOT NULL,
    content bytea NOT NULL,
    size_bytes integer NOT NULL CHECK (size_bytes BETWEEN 0 AND 16777216 AND size_bytes = octet_length(content))
);
CREATE TABLE runner_invocation (
    invocation_id text PRIMARY KEY,
    run_id text NOT NULL,
    input_sha256 text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    snapshot_sha256 text NOT NULL,
    operation text NOT NULL CHECK (operation IN ('BUILD','TEST')),
    image_id text NOT NULL CHECK (image_id ~ '^sha256:[0-9a-f]{64}$'),
    deadline_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','STAGING','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','UNCERTAIN')),
    requested_cancel boolean NOT NULL DEFAULT false,
    attempt integer NOT NULL DEFAULT 1 CHECK (attempt BETWEEN 1 AND 2),
    uncertain boolean NOT NULL DEFAULT false,
    container_name text NOT NULL UNIQUE,
    lease_owner text,
    lease_until timestamptz,
    checked_at timestamptz NOT NULL DEFAULT '-infinity',
    cleaned boolean NOT NULL DEFAULT false,
    result_json jsonb,
    reports_blob_id text REFERENCES artifact_blob(sha256),
    FOREIGN KEY (run_id,snapshot_sha256) REFERENCES run_snapshot(run_id,sha256)
);
CREATE INDEX runner_pending ON runner_invocation(checked_at) WHERE NOT cleaned;
