#!/usr/bin/env bash
set -euo pipefail

plumbing() {
  local base=$1 head=$2
  [[ $base != main || $head == release-merge/* ]]
}

refs_from_github() {
  gh pr view "$1" -R "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is owner/name}" --json baseRefName,headRefName \
    --jq '"\(.baseRefName)|\(.headRefName)"'
}

filter() {
  local refs_of=$1 line refs
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%$'\r'}
    if [[ $line =~ ^\*\ .*/pull/([0-9]+)$ ]]; then
      refs=$("$refs_of" "${BASH_REMATCH[1]}")
      [[ $refs == *"|"* ]] || {
        echo "release-notes.sh: no base and head for pull request ${BASH_REMATCH[1]}" >&2
        return 1
      }
      if plumbing "${refs%%|*}" "${refs#*|}"; then
        continue
      fi
    fi
    printf '%s\n' "$line"
  done
}

refs_from_cases() {
  case $1 in
    1002) echo "main|fix/guide-quotes" ;;
    1016) echo "main|fix/list-follow-codex-findings" ;;
    1015) echo "release/2026.11|chore/main-into-release-2026.11-v10" ;;
    1017) echo "release/2026.11|chore/1016-into-release-2026.11" ;;
    1019) echo "release/2026.11|release-bump/2026.11.10" ;;
    1026) echo "main|release-merge/v2026.11.10" ;;
    1040) echo "main|chore/release-merge-notes" ;;
    1099) echo "" ;;
    *) return 1 ;;
  esac
}

self_test() {
  local fails=0 got want
  want=$(
    cat << 'EOF_WANT'
A migration drops the old table.

## What's Changed
* fix(guide): quotes by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1002
* fix(scout): a cap is a cap by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1016
* chore: notes of release-merge by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1040
* a line that names /pull/1015 in its text and ends elsewhere

## New Contributors
* @someone made their first contribution in https://github.com/silvioincalza/searchavo.ai/pull/1002

**Full Changelog**: https://github.com/silvioincalza/searchavo.ai/compare/v2026.11.9...v2026.11.10
EOF_WANT
  )
  got=$(
    filter refs_from_cases << 'EOF_NOTES'
A migration drops the old table.

## What's Changed
* fix(guide): quotes by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1002
* chore(release): main into release/2026.11 for v2026.11.10 by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1015
* fix(scout): a cap is a cap by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1016
* chore(release): #1016 into release/2026.11 for v2026.11.10 by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1017
* chore(release): version 2026.11.10 by @github-actions[bot] in https://github.com/silvioincalza/searchavo.ai/pull/1019
* chore(release): v2026.11.10 back into main by @github-actions[bot] in https://github.com/silvioincalza/searchavo.ai/pull/1026
* chore: notes of release-merge by @silvioincalza in https://github.com/silvioincalza/searchavo.ai/pull/1040
* a line that names /pull/1015 in its text and ends elsewhere

## New Contributors
* @someone made their first contribution in https://github.com/silvioincalza/searchavo.ai/pull/1002
* @github-actions[bot] made their first contribution in https://github.com/silvioincalza/searchavo.ai/pull/1019

**Full Changelog**: https://github.com/silvioincalza/searchavo.ai/compare/v2026.11.9...v2026.11.10
EOF_NOTES
  )
  if [[ $got == "$want" ]]; then
    echo "ok   plumbing pull requests left out, the rest unchanged"
  else
    echo "FAIL the notes differ:" >&2
    diff <(echo "$want") <(echo "$got") >&2 || true
    fails=$((fails + 1))
  fi

  got=$(printf '* x in https://github.com/o/r/pull/1002\r\n* y in https://github.com/o/r/pull/1019\r\n' | filter refs_from_cases)
  if [[ $got == "* x in https://github.com/o/r/pull/1002" ]]; then
    echo "ok   lines that end in a carriage return"
  else
    echo "FAIL carriage returns: got [$got]" >&2
    fails=$((fails + 1))
  fi

  for number in 1099 4242; do
    if echo "* x in https://github.com/o/r/pull/$number" | filter refs_from_cases > /dev/null 2>&1; then
      echo "FAIL pull request $number has no base and head, and the notes were made" >&2
      fails=$((fails + 1))
    else
      echo "ok   pull request $number without base and head stops the notes"
    fi
  done

  if [[ $fails -gt 0 ]]; then
    echo "release-notes.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "release-notes.sh: all cases pass"
}

case ${1:-} in
  --self-test) self_test ;;
  "") filter refs_from_github ;;
  *)
    echo "usage: <GitHub's generated notes> | GITHUB_REPOSITORY=<owner/name> release-notes.sh | release-notes.sh --self-test" >&2
    exit 2
    ;;
esac
