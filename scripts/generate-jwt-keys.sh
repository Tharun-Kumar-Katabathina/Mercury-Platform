#!/usr/bin/env bash
# Generates the RS256 key pair that signs (user-service) and verifies (every service) access tokens.
#   scripts/generate-jwt-keys.sh [directory]        default: .secrets  (git-ignored)
# The private key never leaves the user-service; the services only get the public key.
set -euo pipefail
dir="${1:-$(dirname "$0")/../.secrets}"
mkdir -p "$dir"
chmod 700 "$dir"
if [[ -s "$dir/jwt-private.pem" && -s "$dir/jwt-public.pem" ]]; then
  echo "keys already exist in $dir (delete them to rotate)"
  exit 0
fi
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/jwt-private.pem" 2>/dev/null
openssl pkey -in "$dir/jwt-private.pem" -pubout -out "$dir/jwt-public.pem"
chmod 644 "$dir/jwt-public.pem"       # the public half is not secret
chmod 640 "$dir/jwt-private.pem"      # readable by the group a container runs as; the directory is 700
echo "wrote $dir/jwt-private.pem and $dir/jwt-public.pem"
