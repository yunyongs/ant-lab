#!/bin/sh
# Runs once, on an empty data directory (postgres image rule), before any table exists: creates antlab_owner and
# antlab_app in $POSTGRES_DB. Existing volumes run the script by hand (db/README.md).
set -eu
{
    printf "SET antlab.expected_database = '%s';\n" "$POSTGRES_DB"
    cat /antlab-roles/antlab_owner_app.sql
} | psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
