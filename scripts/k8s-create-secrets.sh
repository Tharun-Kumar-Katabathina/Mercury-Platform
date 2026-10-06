#!/usr/bin/env bash
# Writes infrastructure/kubernetes/base/secrets.env with a random database password (never committed).
set -euo pipefail
target="$(dirname "$0")/../infrastructure/kubernetes/base/secrets.env"
[[ -e $target && ${1:-} != --force ]] && { echo "$target exists (use --force to replace)"; exit 0; }
umask 077
printf 'POSTGRES_USER=mercury\nPOSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 24)" > "$target"
echo "wrote $target"
