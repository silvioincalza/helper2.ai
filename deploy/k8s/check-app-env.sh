#!/usr/bin/env bash
# Checks an app.env before it becomes the env Secret: KEY=value lines from column 0 and # comments only,
# every key matches ^H2_[A-Z0-9_]+$ (the only names the app reads, so a misspelt or unprefixed key fails
# here), no line ends in a space or tab (kubectl keeps it in the value), H2_DATABASE_PASSWORD is set, and
# H2_REDIS_PASSWORD has 32+ letters and digits (it goes into the Redis config as is, and its hash is
# public in the pod annotations). Usage: check-app-env.sh <app.env> [--no-redis-password] (with redis.auth.existingSecret).
set -euo pipefail

file=${1:?usage: check-app-env.sh <app.env> [--no-redis-password]}
redis=${2:-}
fail() { echo "$file: $*" >&2; exit 1; }

declare -A seen
n=0
while IFS= read -r line || [ -n "$line" ]; do
  n=$((n + 1))
  l=${line%$'\r'}
  [[ $l =~ ^[[:space:]]*$ ]] && continue
  [[ $l =~ ^[[:space:]]*# ]] && continue
  [[ $l =~ [[:space:]]$ ]] && fail "line $n ends in a space or tab, which would become part of the value"
  [[ $l =~ ^[[:space:]] ]] && fail "line $n starts with a space or tab"
  [[ $l == *=* ]] || fail "line $n is not KEY=value"
  key=${l%%=*}
  [[ $key =~ ^H2_[A-Z0-9_]+$ ]] || fail "line $n: key \"$key\" must match ^H2_[A-Z0-9_]+\$"
  [ -z "${seen[$key]:-}" ] || fail "line $n: $key is set twice"
  seen[$key]=${l#*=}
done < "$file"

[ -n "${seen[H2_DATABASE_PASSWORD]:-}" ] || fail "H2_DATABASE_PASSWORD is missing"
if [ "$redis" != "--no-redis-password" ]; then
  [[ ${seen[H2_REDIS_PASSWORD]:-} =~ ^[A-Za-z0-9]{32,}$ ]] \
    || fail "H2_REDIS_PASSWORD needs 32+ letters and digits (openssl rand -hex 32)"
fi
