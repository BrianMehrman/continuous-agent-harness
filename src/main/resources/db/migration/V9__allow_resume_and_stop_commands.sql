ALTER TABLE run_command DROP CONSTRAINT run_command_kind_check;
ALTER TABLE run_command ADD CONSTRAINT run_command_kind_check
    CHECK (kind IN ('START','CANCEL','RESUME','STOP'));
