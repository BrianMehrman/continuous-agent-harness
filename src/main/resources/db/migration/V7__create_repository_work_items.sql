ALTER TABLE workspace_snapshot DROP CONSTRAINT workspace_snapshot_byte_count_check;
ALTER TABLE workspace_snapshot DROP CONSTRAINT workspace_snapshot_file_count_check;
ALTER TABLE workspace_snapshot ADD CONSTRAINT workspace_snapshot_byte_count_check
    CHECK (byte_count BETWEEN 0 AND 8388608);
ALTER TABLE workspace_snapshot ADD CONSTRAINT workspace_snapshot_file_count_check
    CHECK (file_count BETWEEN 0 AND 512);

ALTER TABLE run_request ADD COLUMN kind TEXT NOT NULL DEFAULT 'BENCHMARK'
    CHECK (kind IN ('BENCHMARK', 'REPOSITORY'));

CREATE TABLE repository_work_item (
    run_id TEXT PRIMARY KEY REFERENCES run_request(run_id),
    title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 160),
    instructions_blob_id TEXT NOT NULL REFERENCES artifact_blob(sha256),
    profile_revision TEXT NOT NULL REFERENCES model_profile_revision(id),
    base_commit TEXT NOT NULL CHECK (base_commit ~ '^[0-9a-f]{40}$'),
    seed_sha256 TEXT NOT NULL REFERENCES workspace_snapshot(sha256),
    cancelled_at TIMESTAMPTZ,
    review_blob_id TEXT REFERENCES artifact_blob(sha256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX repository_work_item_created ON repository_work_item(created_at,run_id);
