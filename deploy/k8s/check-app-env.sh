#!/usr/bin/env bash
# Checks an app.env before it becomes the env Secret: KEY=value lines from column 0 and # comments only,
# every key matches ^H2_[A-Z0-9_]+$ (the only names the app reads, so a misspelt or unprefixed key fails
# here), no line ends in a space or tab (kubectl keeps it in the value), the database owner's and both app
# roles' passwords have 32+ characters of base64 or base64url (letters, digits, + / = _ -) and differ (ADR 0004:
# no space or quote, so they pass through env, JDBC and libpq unchanged), and the three Redis passwords (default
# user, h2_public, h2_backoffice) have 32+ letters and digits only and differ (they go into the Redis ACL line
# as is, where a space or a symbol could add rules, and their hash
# is public in the pod annotations). Usage: check-app-env.sh <app.env> [--no-redis-password] (with redis.auth.existingSecret).
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

# Each password is its own: one leaked app password must not open the owner or the other app.
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
