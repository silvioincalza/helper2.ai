#!/usr/bin/env bash
set -euo pipefail

COPILOT="copilot-pull-request-reviewer[bot]"

reviewed() {
  local head=$1 owner=$2
  jq -e --arg head "$head" --arg copilot "$COPILOT" --arg owner "$owner" '
    any(.[]; .commit_id == $head
             and (.user.login == $copilot or .user.login == $owner)
             and (.state | IN("COMMENTED", "APPROVED", "CHANGES_REQUESTED")))' > /dev/null
}

verdict() {
  local reviews=$1 head=$2 owner=$3
  if reviewed "$head" "$owner" <<< "$reviews"; then
    echo "head $head: reviewed"
    return 0
  fi
  echo "::error::head $head has no review by Copilot or $owner yet. Wait for Copilot, request its review again, or submit your own review."
  return 1
}

reviews_of() {
  gh api --paginate --slurp "repos/$1/pulls/$2/reviews" | jq 'add // []'
}

self_test() {
  local fails=0 what reviews want got
  while IFS='|' read -r what reviews want; do
    got=fail
    reviewed head1 silvio <<< "$reviews" && got=ok
    if [[ $got == "$want" ]]; then
      echo "ok   [$what] -> $got"
    else
      echo "FAIL [$what]: got $got, want $want" >&2
      fails=$((fails + 1))
    fi
  done << 'EOF_CASES'
no review|[]|fail
Copilot on the head|[{"commit_id":"head1","user":{"login":"copilot-pull-request-reviewer[bot]"},"state":"COMMENTED"}]|ok
the owner on the head|[{"commit_id":"head1","user":{"login":"silvio"},"state":"COMMENTED"}]|ok
the owner approved the head|[{"commit_id":"head1","user":{"login":"silvio"},"state":"APPROVED"}]|ok
Copilot on an older commit|[{"commit_id":"old","user":{"login":"copilot-pull-request-reviewer[bot]"},"state":"COMMENTED"}]|fail
someone else on the head|[{"commit_id":"head1","user":{"login":"chatgpt-codex-connector[bot]"},"state":"COMMENTED"}]|fail
a pending review by the owner|[{"commit_id":"head1","user":{"login":"silvio"},"state":"PENDING"}]|fail
a dismissed review by Copilot|[{"commit_id":"head1","user":{"login":"copilot-pull-request-reviewer[bot]"},"state":"DISMISSED"}]|fail
an old Copilot review and a new owner review|[{"commit_id":"old","user":{"login":"copilot-pull-request-reviewer[bot]"},"state":"COMMENTED"},{"commit_id":"head1","user":{"login":"silvio"},"state":"COMMENTED"}]|ok
EOF_CASES
  if [[ $fails -gt 0 ]]; then
    echo "review-done.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "review-done.sh: all cases pass"
}

case ${1:-} in
  --self-test) self_test ;;
  "")
    verdict "$(reviews_of "${REPO:?REPO is owner/name}" "${PR:?PR is the pull request number}")" \
      "${HEAD:?HEAD is the head commit of the pull request}" "${OWNER:?OWNER is the repository owner}"
    ;;
  *)
    echo "usage: REPO=<owner/name> PR=<n> HEAD=<sha> OWNER=<login> review-done.sh | review-done.sh --self-test" >&2
    exit 2
    ;;
esac
