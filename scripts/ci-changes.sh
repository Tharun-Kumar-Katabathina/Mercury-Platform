#!/usr/bin/env bash
# Decides how much of CI a change needs, so a pull request that touches one service does not rebuild and test all seven.
#
#   ci-changes.sh <base-sha> <head-sha>     -> prints key=value lines for $GITHUB_OUTPUT
#   ci-changes.sh                           -> everything (pushes, manual runs: no base to compare with)
#
# The rule is deliberately conservative: anything changed outside services/<name>/ (workflows, compose files, scripts,
# infrastructure, build files at the root) can affect every service, so it selects everything. Documentation alone
# (*.md, docs/) selects nothing but the static checks and scans, which always run.
#
#   services  JSON array of the services whose unit tests and image run
#   images    the same services with their ports, for the image matrix
#   stack     true when the real-stack integration tests must run (Order's suite starts Product, Inventory and Notification)
#   code      true when anything but documentation changed (the platform smoke runs)
set -euo pipefail

ALL=(product-service inventory-service order-service notification-service user-service recommendation-service api-gateway)
declare -A PORT=([product-service]=8081 [inventory-service]=8082 [order-service]=8083 [notification-service]=8084
                 [user-service]=8085 [recommendation-service]=8086 [api-gateway]=8090)
STACK=(product-service inventory-service order-service notification-service)

emit() {   # emit <code> <stack> <service>...
  local code=$1 stack=$2 services="" images="" s
  shift 2
  for s in "$@"; do
    services+="${services:+,}\"$s\""
    images+="${images:+,}{\"service\":\"$s\",\"port\":${PORT[$s]}}"
  done
  echo "services=[$services]"
  echo "images=[$images]"
  echo "stack=$stack"
  echo "code=$code"
}

if [[ $# -lt 2 || -z $1 || -z $2 ]]; then
  emit true true "${ALL[@]}"
  exit 0
fi

mapfile -t files < <(git diff --name-only "$1" "$2")

code=false
everything=false
declare -A touched=()
for f in "${files[@]}"; do
  [[ $f == *.md || $f == docs/* ]] && continue            # documentation only
  code=true
  if [[ $f =~ ^services/([^/]+)/ && -n ${PORT[${BASH_REMATCH[1]}]:-} ]]; then
    touched[${BASH_REMATCH[1]}]=1
  else
    everything=true                                       # shared file: it may affect any service
  fi
done

if [[ $everything == true ]]; then
  emit true true "${ALL[@]}"
  exit 0
fi

selected=()
stack=false
for s in "${ALL[@]}"; do
  [[ -n ${touched[$s]:-} ]] || continue
  selected+=("$s")
  for t in "${STACK[@]}"; do [[ $s == "$t" ]] && stack=true; done
done
emit "$code" "$stack" "${selected[@]}"
