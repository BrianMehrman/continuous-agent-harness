CREATE TABLE workspace_snapshot (
    sha256 TEXT PRIMARY KEY CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    files_json JSONB NOT NULL CHECK (jsonb_typeof(files_json) = 'object'),
    byte_count INTEGER NOT NULL CHECK (byte_count BETWEEN 0 AND 1048576),
    file_count INTEGER NOT NULL CHECK (file_count BETWEEN 0 AND 64)
);

CREATE TABLE run_workspace (
    run_id TEXT PRIMARY KEY,
    benchmark_version TEXT NOT NULL,
    seed_sha256 TEXT NOT NULL REFERENCES workspace_snapshot(sha256)
);

CREATE TABLE run_snapshot (
    run_id TEXT NOT NULL REFERENCES run_workspace(run_id),
    sha256 TEXT NOT NULL REFERENCES workspace_snapshot(sha256),
    PRIMARY KEY (run_id, sha256)
);

CREATE TABLE workspace_write_receipt (
    run_id TEXT NOT NULL,
    invocation_id TEXT NOT NULL,
    input_sha256 TEXT NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    parent_sha256 TEXT NOT NULL,
    result_sha256 TEXT NOT NULL,
    PRIMARY KEY (run_id, invocation_id),
    FOREIGN KEY (run_id, parent_sha256) REFERENCES run_snapshot(run_id, sha256),
    FOREIGN KEY (run_id, result_sha256) REFERENCES run_snapshot(run_id, sha256)
);
