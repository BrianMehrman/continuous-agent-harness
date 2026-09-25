#!/bin/sh
set -eu
export SQL_PASSWORD="$TEMPORAL_DB_PASSWORD"
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal --db temporal setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal --db temporal update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned
export SQL_PASSWORD="$TEMPORAL_VISIBILITY_PASSWORD"
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal_visibility --db temporal_visibility setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal_visibility --db temporal_visibility update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned
