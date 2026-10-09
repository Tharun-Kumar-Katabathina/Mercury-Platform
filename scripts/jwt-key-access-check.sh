#!/usr/bin/env bash
# Who can read the JWT private key inside the containers, checked on a real Linux file system.
#
#   scripts/jwt-key-access-check.sh
#
# The key is mode 640 and owned by whoever ran scripts/init-secrets.sh. A compose file secret keeps that owner, group and
# mode, so the user-service (uid/gid 10001) can only read it when the container also gets the key's group (group_add in
# docker-compose.yml). Docker Desktop on macOS maps ownership and hides a failure that every Linux host shows, which is how
# the platform came to fail on the CI runner only. So the readability is tested on a Docker volume, where the permissions
# are the kernel's, with the identities compose gives the containers.
#
# Needs Docker and python3. Creates and removes one throw-away volume and holds no real key.
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE="${JWT_CHECK_IMAGE:-nginx:1.27-alpine}"      # only its busybox tools matter; CI's static checks pull it anyway
OWNER_UID=1001                                      # the host user who generated the key (the runner)
KEY_GID=4242                                        # the group that owns it on the host: nothing special about the number
failures=0

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pass() { echo "  ok:   $*"; }
fail() { echo "  FAIL: $*" >&2; failures=$((failures + 1)); }

# ---- 1. the key the repository's script generates -----------------------------------------------------------------
step "1. the generated private key is not readable by everyone"
tmp="$(mktemp -d)"
volume="mercury-jwt-check-$$"
trap 'rm -rf "$tmp"; docker volume rm -f "$volume" >/dev/null 2>&1 || true' EXIT
scripts/generate-jwt-keys.sh "$tmp" >/dev/null
mode="$(stat -c %a "$tmp/jwt-private.pem" 2>/dev/null || stat -f %Lp "$tmp/jwt-private.pem")"
if [[ $mode == 640 || $mode == 600 || $mode == 440 || $mode == 400 ]]; then pass "jwt-private.pem is mode $mode"; else fail "jwt-private.pem is mode $mode"; fi
if (( 8#$mode & 8#007 )); then fail "others can access the private key (mode $mode)"; else pass "no access for others"; fi

# ---- 2. what the compose file gives the container --------------------------------------------------------------------
step "2. docker-compose.yml: only the user-service joins the key's group, as a non-root user"
POSTGRES_PASSWORD=ci JWT_PRIVATE_KEY_GID="$KEY_GID" docker compose -f docker-compose.yml --profile platform config --format json > "$tmp/compose.json"
if python3 - "$KEY_GID" "$tmp/compose.json" <<'PY'; then pass "group_add, secrets and user are as intended"; else fail "docker-compose.yml does not give the key's group to the user-service alone"; fi
import json, sys
gid = sys.argv[1]
services = json.load(open(sys.argv[2]))["services"]
joined = sorted(name for name, s in services.items() if s.get("group_add"))
assert joined == ["user-service"], f"services with group_add: {joined}"
assert [str(g) for g in services["user-service"]["group_add"]] == [gid], services["user-service"]["group_add"]
holders = sorted(name for name, s in services.items() if any(x.get("source") == "jwt_private" for x in s.get("secrets", [])))
assert holders == ["user-service"], f"services that get the private key: {holders}"
assert "user" not in services["user-service"], f"user override: {services['user-service'].get('user')}"
assert services["user-service"].get("read_only") is True and services["user-service"].get("cap_drop") == ["ALL"]
PY
if grep -q '^USER 10001:10001$' infrastructure/docker/Dockerfile.service; then pass "the image runs as 10001:10001"; else fail "the Dockerfile no longer sets USER 10001:10001"; fi

# ---- 3. who can read it, on a Linux file system ------------------------------------------------------------------
step "3. who can read a mode 640 file owned by $OWNER_UID:$KEY_GID (Docker volume, real Linux permissions)"
docker volume create "$volume" >/dev/null
docker run --rm -v "$volume":/keys --entrypoint touch "$IMAGE" /keys/jwt-private.pem
docker run --rm -v "$volume":/keys --entrypoint chown "$IMAGE" "$OWNER_UID:$KEY_GID" /keys/jwt-private.pem
docker run --rm -v "$volume":/keys --entrypoint chmod "$IMAGE" 640 /keys/jwt-private.pem

# $1 = who, the rest = extra `docker run` options. Same restrictions as the services: read-only root, no capabilities.
can_read() {
  local who=$1; shift
  docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges:true --user "$who" "$@" \
    -v "$volume":/run/secrets:ro --entrypoint cat "$IMAGE" /run/secrets/jwt-private.pem >/dev/null 2>&1
}
if can_read 10001:10001 --group-add "$KEY_GID"; then pass "10001:10001 with the key's group (the user-service as configured) can read it"; else fail "the user-service identity cannot read the key"; fi
if can_read 10001:10001; then fail "10001:10001 reads it without the group (the permissions are not what this test assumes)"; else pass "10001:10001 without the group cannot (the failure on the CI runner)"; fi
if can_read 10001:10001 --group-add 10001; then fail "the default group_add (10001) grants access"; else pass "the default group_add (10001, no JWT_PRIVATE_KEY_GID) adds no access"; fi
if can_read 10002:10002; then fail "another service identity (10002) can read the key"; else pass "another service identity (10002:10002) cannot"; fi
if can_read 65534:65534; then fail "nobody can read the key"; else pass "nobody (65534) cannot"; fi
if can_read 10002:10002 --group-add 10001; then fail "a stranger in the service's own group can read the key"; else pass "a stranger in the service's own group (10001) cannot"; fi
who="$(docker run --rm --user 10001:10001 --group-add "$KEY_GID" --entrypoint id "$IMAGE" -u)"
if [[ $who == 10001 ]]; then pass "the container process is uid 10001, not root"; else fail "the container process is uid $who"; fi

echo
if (( failures )); then
  echo "JWT KEY ACCESS CHECK FAIL: $failures check(s) failed" >&2
  exit 1
fi
echo "JWT KEY ACCESS CHECK PASS"
