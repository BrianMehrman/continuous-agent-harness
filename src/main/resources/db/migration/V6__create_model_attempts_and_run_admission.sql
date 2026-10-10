CREATE TABLE model_attempt (
    run_id TEXT NOT NULL REFERENCES run_request(run_id),
    invocation_id TEXT NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    input_sha256 TEXT NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('IN_FLIGHT', 'RECORDED', 'UNKNOWN')),
    owner_token TEXT,
    request_blob_id TEXT NOT NULL REFERENCES artifact_blob(sha256),
    response_blob_id TEXT REFERENCES artifact_blob(sha256),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, invocation_id, attempt),
    CHECK ((status = 'IN_FLIGHT' AND owner_token IS NOT NULL AND response_blob_id IS NULL AND ended_at IS NULL)
        OR (status = 'RECORDED' AND owner_token IS NULL AND response_blob_id IS NOT NULL AND ended_at IS NOT NULL)
        OR (status = 'UNKNOWN' AND owner_token IS NULL AND response_blob_id IS NULL AND ended_at IS NOT NULL))
);

CREATE TABLE run_admission (
    slot_id INTEGER PRIMARY KEY CHECK (slot_id = 1),
    run_id TEXT NOT NULL UNIQUE REFERENCES run_request(run_id),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
