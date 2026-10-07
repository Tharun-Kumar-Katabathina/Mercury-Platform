#!/usr/bin/env bash
# Restores ONE database from a backup made by scripts/backup-databases.sh into an empty database.
#   scripts/restore-database.sh <dump-file> <database> [compose project]
# It refuses to restore over a database that has tables: restore into a fresh one (CREATE DATABASE mercury_order_restored)
# and compare, or drop the broken one first, on purpose.
set -euo pipefail
dump="${1:?dump file}"; db="${2:?target database}"
project="${3:-${COMPOSE_PROJECT_NAME:-mercury-platform}}"
container="${project}-postgres-1"
user="${POSTGRES_USER:-mercury}"
export_pw=(-e PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}")
docker exec "${export_pw[@]}" "$container" psql -U "$user" -d postgres -Atc "select 1 from pg_database where datname='$db'" | grep -q 1 \
  || docker exec "${export_pw[@]}" "$container" psql -U "$user" -d postgres -c "CREATE DATABASE $db OWNER $user"
tables=$(docker exec "${export_pw[@]}" "$container" psql -U "$user" -d "$db" -Atc "select count(*) from information_schema.tables where table_schema='public'")
[[ $tables == 0 ]] || { echo "$db already has $tables tables; refusing to restore over it" >&2; exit 1; }
docker exec -i "${export_pw[@]}" "$container" pg_restore -U "$user" -d "$db" --no-owner < "$dump"
echo "restored $dump into $db ($(docker exec "${export_pw[@]}" "$container" psql -U "$user" -d "$db" -Atc "select count(*) from information_schema.tables where table_schema='public'") tables)"
