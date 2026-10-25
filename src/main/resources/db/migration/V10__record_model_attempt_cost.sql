ALTER TABLE model_attempt
    ADD COLUMN cost_micros BIGINT CHECK (cost_micros >= 0),
    ADD COLUMN cost_estimated BOOLEAN,
    ADD COLUMN input_tokens BIGINT,
    ADD COLUMN output_tokens BIGINT,
    ADD COLUMN cache_write_tokens BIGINT,
    ADD COLUMN cache_read_tokens BIGINT;
