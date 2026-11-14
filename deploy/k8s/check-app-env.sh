#!/usr/bin/env bash
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

distinct() {
  local key other
  for key in "$@"; do
    for other in "$@"; do
      [ "$key" = "$other" ] || [ "${seen[$key]}" != "${seen[$other]}" ] || fail "$key and $other must differ"
    done
  done
}

for key in H2_DATABASE_PASSWORD H2_DATABASE_PUBLIC_PASSWORD H2_DATABASE_BACKOFFICE_PASSWORD; do
  [[ ${seen[$key]:-} =~ ^[A-Za-z0-9+/=_-]{32,}$ ]] || fail "$key needs 32+ characters of base64 (openssl rand -hex 32)"
done
distinct H2_DATABASE_PASSWORD H2_DATABASE_PUBLIC_PASSWORD H2_DATABASE_BACKOFFICE_PASSWORD
if [ "$redis" != "--no-redis-password" ]; then
  for key in H2_REDIS_PASSWORD H2_REDIS_PUBLIC_PASSWORD H2_REDIS_BACKOFFICE_PASSWORD; do
    [[ ${seen[$key]:-} =~ ^[A-Za-z0-9]{32,}$ ]] || fail "$key needs 32+ letters and digits (openssl rand -hex 32)"
  done
  distinct H2_REDIS_PASSWORD H2_REDIS_PUBLIC_PASSWORD H2_REDIS_BACKOFFICE_PASSWORD
fi
