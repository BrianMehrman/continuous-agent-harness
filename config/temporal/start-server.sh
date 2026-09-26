#!/bin/sh
set -eu
POSTGRES_PWD=$(cat /run/secrets/temporal_db_password)
VISIBILITY_POSTGRES_PWD=$(cat /run/secrets/temporal_visibility_password)
: "${POSTGRES_PWD:?Temporal database secret is empty}"
: "${VISIBILITY_POSTGRES_PWD:?Visibility database secret is empty}"
export POSTGRES_PWD VISIBILITY_POSTGRES_PWD
exec /etc/temporal/entrypoint.sh "$@"
