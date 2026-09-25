\getenv app_password HARNESS_DB_PASSWORD
\getenv temporal_password TEMPORAL_DB_PASSWORD
\getenv visibility_password TEMPORAL_VISIBILITY_PASSWORD
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
