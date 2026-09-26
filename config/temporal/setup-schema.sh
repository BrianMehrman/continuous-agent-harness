#!/bin/sh
set -eu
SQL_PASSWORD=$(cat /run/secrets/temporal_db_password)
: "${SQL_PASSWORD:?Temporal database secret is empty}"
export SQL_PASSWORD
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal --db temporal setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal --db temporal update-schema -d /etc/temporal/schema/postgresql/v12/temporal/versioned
SQL_PASSWORD=$(cat /run/secrets/temporal_visibility_password)
: "${SQL_PASSWORD:?Visibility database secret is empty}"
export SQL_PASSWORD
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal_visibility --db temporal_visibility setup-schema -v 0.0
temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal_visibility --db temporal_visibility update-schema -d /etc/temporal/schema/postgresql/v12/visibility/versioned
