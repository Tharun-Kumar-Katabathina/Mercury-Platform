#!/usr/bin/env bash
# Prints the numeric id of the group that owns a file (GNU and busybox `stat -c`, BSD and macOS `stat -f`).
#   scripts/lib/file-gid.sh .secrets/jwt-private.pem
set -euo pipefail
file="${1:?file}"
stat -c %g "$file" 2>/dev/null || stat -f %g "$file"
