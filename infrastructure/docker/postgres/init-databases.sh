#!/bin/bash
# Runs once, when the Postgres data volume is first created (docker-entrypoint-initdb.d).
# One database per service; the services never share tables.
set -euo pipefail
for db in mercury_product mercury_inventory mercury_order mercury_notification mercury_recommendation mercury_user; do
  echo "creating database $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE $db OWNER $POSTGRES_USER"
done
