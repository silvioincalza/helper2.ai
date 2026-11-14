#!/usr/bin/env bash
set -euo pipefail

PEOPLE="feature fix docs chore ci refactor test release replay"
MACHINES="dependabot release-bump release-merge"

allowed() {
  local name=$1 prefix
  [[ $name =~ ^[a-z-]+/[A-Za-z0-9._/-]+$ ]] || return 1
  prefix=${name%%/*}
  [[ " $PEOPLE $MACHINES " == *" $prefix "* ]]
}

verdict() {
  local name=$1
  if allowed "$name"; then
    echo "branch $name: ok"
    return 0
  fi
  echo "::error::branch '$name' must be <prefix>/<name> with one of these prefixes: ${PEOPLE// /, }"
  return 1
}

self_test() {
  local fails=0 name want got
  while IFS='|' read -r name want; do
    got=fail
    allowed "$name" && got=ok
    if [[ $got == "$want" ]]; then
      echo "ok   [$name] -> $got"
    else
      echo "FAIL [$name]: got $got, want $want" >&2
      fails=$((fails + 1))
    fi
  done << 'EOF_CASES'
feature/results-timeline|ok
fix/review-findings|ok
docs/adr-0046-run-report|ok
chore/bump-gradle|ok
ci/branch-name-prefixes|ok
refactor/catalog-ports|ok
test/search-contract|ok
release/2026.11|ok
replay/07-catalog-core|ok
dependabot/npm_and_yarn/frontend/vite-8.3.6|ok
release-bump/2026.11.4|ok
release-merge/v2026.11.4|ok
main|fail
feature|fail
feature/|fail
/feature|fail
Feature/results|fail
feat/results|fail
hotfix/login|fail
worktree/05-foundation-305608|fail
feature/a b|fail
fix/x;echo|fail
EOF_CASES
  if [[ $fails -gt 0 ]]; then
    echo "branch-name.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "branch-name.sh: all cases pass"
}

case ${1:-} in
  --self-test) self_test ;;
  "")
    echo "usage: branch-name.sh <branch> | --self-test" >&2
    exit 2
    ;;
  *) verdict "$1" ;;
esac
