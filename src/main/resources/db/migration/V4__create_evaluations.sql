CREATE TABLE evaluation (
    invocation_id text PRIMARY KEY,
    run_id text NOT NULL,
    snapshot_sha256 text NOT NULL,
    input_sha256 text NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    image_id text NOT NULL CHECK (image_id ~ '^sha256:[0-9a-f]{64}$'),
    evaluator_version text NOT NULL,
    seed bigint NOT NULL,
    deadline_at timestamptz NOT NULL,
    build_deadline_at timestamptz NOT NULL,
    test_deadline_at timestamptz,
    artifact_sha256 text REFERENCES artifact_blob(sha256),
    result_json jsonb,
    diagnostics jsonb NOT NULL DEFAULT '{}',
    FOREIGN KEY (run_id,snapshot_sha256) REFERENCES run_snapshot(run_id,sha256)
);
CREATE TABLE evaluation_runtime (
    session_id text PRIMARY KEY CHECK (session_id ~ '^[0-9a-f]{64}$'),
    invocation_id text NOT NULL REFERENCES evaluation(invocation_id),
    case_name text NOT NULL,
    image_id text NOT NULL,
    artifact_sha256 text NOT NULL REFERENCES artifact_blob(sha256),
    deadline_at timestamptz NOT NULL,
    state text NOT NULL DEFAULT 'REQUESTED' CHECK (state IN ('REQUESTED','READY','CLOSING','CLOSED','FAILED')),
    failure text,
    checked_at timestamptz NOT NULL DEFAULT '-infinity',
    UNIQUE (invocation_id,case_name)
);
CREATE TABLE evaluation_command (
    command_id text PRIMARY KEY CHECK (command_id ~ '^[0-9a-f]{64}$'),
    session_id text NOT NULL REFERENCES evaluation_runtime(session_id),
    ordinal integer NOT NULL,
    arguments jsonb NOT NULL,
    deadline_at timestamptz NOT NULL,
    state text NOT NULL DEFAULT 'REQUESTED' CHECK (state IN ('REQUESTED','RUNNING','COMPLETE')),
    result_json jsonb,
    cleaned boolean NOT NULL DEFAULT false,
    UNIQUE (session_id,ordinal)
);
CREATE INDEX evaluation_runtime_pending ON evaluation_runtime(checked_at) WHERE state <> 'CLOSED';
