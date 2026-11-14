#!/usr/bin/env bash
set -euo pipefail
secret=${1:?usage: check-env-overrides.sh <env Secret name>}
awk -v ref="secretRef: { name: $secret }" '
  function check() {
    if (n > 0 && (db < n || rd < n)) {
      printf "%s %s: %d container(s) load %s, %d set H2_DATABASE_PASSWORD, %d set H2_REDIS_PASSWORD\n", kind, name, n, ref, db, rd > "/dev/stderr"
      bad = 1
    }
  }
  /^---/ { check(); n = db = rd = 0; kind = name = ""; next }
  /^kind: / && kind == "" { kind = $2 }
  /^  name: / && name == "" { name = $2 }
  { n += gsub(ref, "&") }
  /name: H2_DATABASE_PASSWORD([ ,}]|$)/ { db++ }
  /name: H2_REDIS_PASSWORD([ ,}]|$)/ { rd++ }
  END { check(); exit bad }
'
