#!/usr/bin/env bash
# Writes infrastructure/kubernetes/base/secrets.env (random values) and the JWT key pair under .secrets/ (never committed).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/infrastructure/kubernetes/base/secrets.env"
if [[ -e $target && ${1:-} != --force ]]; then
  echo "$target exists (use --force to replace)"
else
  umask 077
  cat > "$target" <<ENV
POSTGRES_USER=mercury
POSTGRES_PASSWORD=$(openssl rand -hex 24)
ADMIN_EMAIL=admin@mercury.example
ADMIN_PASSWORD=$(openssl rand -hex 16)
ORDER_SERVICE_SECRET=$(openssl rand -hex 24)
PRODUCT_SERVICE_SECRET=$(openssl rand -hex 24)
ENV
  echo "wrote $target"
fi
"$root/scripts/generate-jwt-keys.sh" >/dev/null
# kustomize only reads files below the kustomization directory
cp "$root/.secrets/jwt-private.pem" "$root/.secrets/jwt-public.pem" "$root/infrastructure/kubernetes/base/"
chmod 600 "$root/infrastructure/kubernetes/base/jwt-private.pem"
echo "copied the JWT key pair next to the kustomization"
