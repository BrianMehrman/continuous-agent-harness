-- Read mounted secrets inside PostgreSQL; never embed credential values in this file.
DO $$
DECLARE secret_name text;
BEGIN
    FOREACH secret_name IN ARRAY ARRAY['harness_db_password', 'temporal_db_password', 'temporal_visibility_password'] LOOP
        IF btrim(pg_read_file('/run/secrets/' || secret_name), E' \t\r\n') = '' THEN
            RAISE EXCEPTION 'Database secret file is empty: %', secret_name;
        END IF;
    END LOOP;
END $$;
SELECT rtrim(pg_read_file('/run/secrets/harness_db_password'), E'\r\n') AS app_password,
       rtrim(pg_read_file('/run/secrets/temporal_db_password'), E'\r\n') AS temporal_password,
       rtrim(pg_read_file('/run/secrets/temporal_visibility_password'), E'\r\n') AS visibility_password
\gset
CREATE ROLE harness LOGIN PASSWORD :'app_password';
CREATE ROLE temporal LOGIN PASSWORD :'temporal_password';
CREATE ROLE temporal_visibility LOGIN PASSWORD :'visibility_password';
CREATE DATABASE harness OWNER harness;
CREATE DATABASE temporal OWNER temporal;
CREATE DATABASE temporal_visibility OWNER temporal_visibility;
REVOKE CONNECT ON DATABASE harness FROM PUBLIC;
REVOKE CONNECT ON DATABASE temporal FROM PUBLIC;
REVOKE CONNECT ON DATABASE temporal_visibility FROM PUBLIC;
GRANT CONNECT ON DATABASE harness TO harness;
GRANT CONNECT ON DATABASE temporal TO temporal;
GRANT CONNECT ON DATABASE temporal_visibility TO temporal_visibility;
