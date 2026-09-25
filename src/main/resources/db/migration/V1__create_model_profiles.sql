CREATE TABLE model_profile_revision (
    id TEXT PRIMARY KEY,
    profile_json JSONB NOT NULL CHECK (jsonb_typeof(profile_json) = 'object'),
    sha256 TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN model_profile_revision.profile_json IS
    'Immutable effective profile settings. Store credential references, never secret values.';
