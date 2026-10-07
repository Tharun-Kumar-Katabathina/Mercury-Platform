#!/usr/bin/env bash
# Backs up every Mercury database (pg_dump, custom format: compressed, restorable table by table).
#   scripts/backup-databases.sh [backup-dir] [compose project]       default: ./backups, project mercury-platform
# Needs the platform's Postgres container to be running and POSTGRES_USER / POSTGRES_PASSWORD from .env.
set -euo pipefail
cd "$(dirname "$0")/.."
dir="${1:-backups}/$(date +%Y%m%d-%H%M%S)"
project="${2:-${COMPOSE_PROJECT_NAME:-mercury-platform}}"
container="${project}-postgres-1"
user="${POSTGRES_USER:-mercury}"
mkdir -p "$dir"
for db in mercury_product mercury_inventory mercury_order mercury_notification mercury_user mercury_recommendation; do
  docker exec -e PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}" "$container" pg_dump -U "$user" -Fc "$db" > "$dir/$db.dump"
  echo "  $db  $(du -h "$dir/$db.dump" | cut -f1)"
done
echo "backup written to $dir"
