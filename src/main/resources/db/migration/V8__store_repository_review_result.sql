ALTER TABLE repository_work_item
    ADD COLUMN final_sha256 TEXT REFERENCES workspace_snapshot(sha256),
    ADD COLUMN model_summary TEXT;
