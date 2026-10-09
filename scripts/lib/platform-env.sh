#!/usr/bin/env bash
# Prints KEY=VALUE lines with every secret the platform needs, remembered per compose project so that a kept stack
# (whose database volume fixed the password at first start) keeps working across runs. Also makes sure the JWT key
# pair exists. Used by the check, chaos and performance scripts, which run throw-away stacks.
#   eval "$(scripts/lib/platform-env.sh mercury-verify)"
set -euo pipefail
project="${1:?compose project name}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
state="/tmp/${project}.env"
if [[ ! -s $state ]]; then
  ( umask 077
    {
      echo "POSTGRES_PASSWORD=$(openssl rand -hex 12)"
      echo "ADMIN_EMAIL=admin@mercury.local"
      echo "ADMIN_PASSWORD=$(openssl rand -hex 10)"
      echo "ORDER_SERVICE_SECRET=$(openssl rand -hex 20)"
      echo "PRODUCT_SERVICE_SECRET=$(openssl rand -hex 20)"
      echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 8)"
    } > "$state" )
fi
"$root/scripts/generate-jwt-keys.sh" >/dev/null
# the group that owns the private key (the user-service container joins it, see docker-compose.yml)
{ cat "$state"; echo "JWT_PRIVATE_KEY_GID=$("$root/scripts/lib/file-gid.sh" "$root/.secrets/jwt-private.pem")"; } | sed 's/^/export /'
