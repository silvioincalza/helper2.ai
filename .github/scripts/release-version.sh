#!/usr/bin/env bash
set -euo pipefail

VERSION='^v([0-9]{4})\.([1-9][0-9]*)\.(0|[1-9][0-9]*)$'
RELEASE='^v[0-9]+\.[0-9]+\.[0-9]+$'
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")

year_of() {
  date -u -d "@$1" +%Y 2> /dev/null || date -u -r "$1" +%Y
}

highest_release_below() {
  local year=$1 below=$2
  { grep -E "^v$year\.[0-9]+\.[0-9]+$" || true; } \
    | awk -F. -v below="$below" '$2 + 0 < below + 0 && $2 + 0 > max { max = $2 + 0 } END { print max + 0 }'
}

check() {
  local tag=$1 from=$2 to=${3:-$2} year n p tags highest
  [[ $from =~ ^[0-9]+$ && $to =~ ^[0-9]+$ ]] || { echo "the dates must be unix times, got '$from' and '$to'" >&2; return 1; }
  [[ $tag =~ $VERSION ]] || { echo "$tag is not vYYYY.N.P (for example v2026.12.0, or v2026.12.1 for its first patch)" >&2; return 1; }
  year=${BASH_REMATCH[1]} n=${BASH_REMATCH[2]} p=${BASH_REMATCH[3]}
  if ((year < $(year_of "$from") || year > $(year_of "$to"))); then
    echo "$tag names $year, but the release was made in $(year_of "$from")$([[ $(year_of "$from") == "$(year_of "$to")" ]] || echo " to $(year_of "$to")") (UTC)" >&2
    return 1
  fi
  tags=$(sed 's#^used/##' | { grep -E "$RELEASE" || true; })
  if ((p == 0)); then
    highest=$(highest_release_below "$year" "$n" <<< "$tags")
    ((n == highest + 1)) || { echo "$tag must be release $((highest + 1)) of $year, the one after the highest below it" >&2; return 1; }
  else
    grep -qFx "v$year.$n.$((p - 1))" <<< "$tags" \
      || { echo "$tag is a patch of $year.$n: v$year.$n.$((p - 1)) must be released first" >&2; return 1; }
  fi
  echo "${tag#v}"
}

known_versions() {
  git tag -l 'v*' 'used/v*' | sed 's#^used/##'
  git for-each-ref --format='%(refname:lstrip=-1)' refs/heads/release/ refs/remotes/origin/release/ \
    | sed -n 's/^\([0-9]\{4\}\.[1-9][0-9]*\)$/v\1.0/p'
}

next() {
  local year highest
  year=$(year_of "${1:-$(date +%s)}")
  highest=$(known_versions | highest_release_below "$year" 999999)
  echo "$year.$((highest + 1)).0"
}

next_patch() {
  local line=$1 last
  [[ $line =~ ^[0-9]{4}\.[1-9][0-9]*$ ]] || { echo "the release line must be YYYY.N, got '$line'" >&2; return 1; }
  last=$(git tag -l "v$line.*" "used/v$line.*" | sed 's#^used/##' | { grep -E "$VERSION" || true; } | sort -V | tail -1)
  [[ -n $last ]] || { echo "$line has no release yet: release $line.0 first" >&2; return 1; }
  echo "$line.$((${last##*.} + 1))"
}

unix_of() {
  date -u -d "$1" +%s 2> /dev/null || date -u -j -f '%Y-%m-%d %H:%M' "$1" +%s
}

self_test() {
  local fails=0 want tag when tags got from to
  while IFS='|' read -r want tag when tags; do
    [[ -z $want || $want == \#* ]] && continue
    from=$(unix_of "${when%% to *}")
    to=$(unix_of "${when##* to }")
    if tr ',' '\n' <<< "$tags" | check "$tag" "$from" "$to" > /dev/null 2>&1; then got=ok; else got=fail; fi
    if [[ $got != "$want" ]]; then
      echo "FAIL [$tag at $when after $tags]: got $got, want $want" >&2
      fails=$((fails + 1))
    fi
  done << 'CASES'
# the first release of the line after v2026.11.1, which reads as release 11, patch 1
ok|v2026.12.0|2026-11-20 10:00|v2.0.0,v2026.11.1
ok|v2026.13.0|2026-12-02 10:00|v2026.11.1,v2026.12.0,v2026.12.1
ok|v2027.1.0|2027-01-02 09:00|v2026.11.1,v2026.14.3
ok|v2026.1.0|2026-03-01 09:00|v2.0.0
ok|v2026.1.0|2026-03-01 09:00|
# patches on a release branch
ok|v2026.12.1|2026-11-20 10:00|v2026.11.1,v2026.12.0
ok|v2026.12.2|2026-12-20 10:00|v2026.12.0,v2026.12.1,v2026.13.0
ok|v2026.11.2|2026-11-20 10:00|v2026.11.1
# a run started again after a later release: the releases below it still decide
ok|v2026.12.0|2026-11-20 10:00|v2026.11.1,v2026.12.0,v2026.13.0
fail|v2026.13.0|2026-11-20 10:00|v2026.11.1
fail|v2026.12.0|2026-11-20 10:00|v2026.10.4
fail|v2026.12.2|2026-11-20 10:00|v2026.12.0
fail|v2026.12.1|2026-11-20 10:00|v2026.11.1
fail|v2026.12.0|2027-01-02 09:00|v2026.11.1
fail|v2027.1.0|2026-12-31 22:00|v2026.11.1
fail|v2026.0.0|2026-11-20 10:00|
fail|v2026.012.0|2026-11-20 10:00|v2026.11.1
fail|v2026.12.01|2026-11-20 10:00|v2026.12.0
fail|v2026.12|2026-11-20 10:00|v2026.11.1
fail|v2.0.1|2026-11-20 10:00|v2.0.0
fail|2026.12.0|2026-11-20 10:00|v2026.11.1
fail|v2026.12.0-rc1|2026-11-20 10:00|v2026.11.1
# a version used up on prod (used/v...) counts as taken
ok|v2026.11.3|2026-11-20 10:00|v2026.11.1,used/v2026.11.2
ok|v2026.12.1|2026-11-20 10:00|v2026.11.1,used/v2026.12.0
ok|v2026.13.0|2026-11-20 10:00|v2026.11.1,used/v2026.12.0
fail|v2026.11.3|2026-11-20 10:00|v2026.11.1,used/v2026.11.1
fail|v2026.11.3|2026-11-20 10:00|v2026.11.1,xused/v2026.11.2
# prepared on 31 December, released on 2 January: either year is right
ok|v2026.12.0|2026-12-31 20:00 to 2027-01-02 09:00|v2026.11.1
ok|v2027.1.0|2026-12-31 20:00 to 2027-01-02 09:00|v2026.11.1
fail|v2025.9.0|2026-12-31 20:00 to 2027-01-02 09:00|v2025.8.0
CASES
  local repo got_next
  repo=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$repo'" RETURN
  (
    cd "$repo" && git init -q && git -c user.name=t -c user.email=t@e -c commit.gpgsign=false commit -q --allow-empty -m a
  ) > /dev/null 2>&1
  expect_next() {
    local want=$1 what=$2
    shift 2
    got_next=$(cd "$repo" && "$SELF" "$@" 2> /dev/null || echo fail)
    if [[ $got_next != "$want" ]]; then
      echo "FAIL [$what]: got $got_next, want $want" >&2
      fails=$((fails + 1))
    fi
  }
  expect_next 2026.1.0 "next with no tag" next 1794132000
  expect_next fail "next patch of a line with no release" next-patch 2026.11
  (cd "$repo" && git tag v2026.11.1 && git tag v2.0.0) > /dev/null 2>&1
  expect_next 2026.12.0 "next after v2026.11.1" next 1794132000
  expect_next 2026.11.2 "next patch of 2026.11" next-patch 2026.11
  (cd "$repo" && git branch release/2026.12) > /dev/null 2>&1
  expect_next 2026.13.0 "next after a prepared release/2026.12 with no tag yet" next 1794132000
  expect_next 2027.1.0 "next in a new year" next 1798848000
  (cd "$repo" && git tag used/v2026.11.2 && git tag used/v2026.12.0) > /dev/null 2>&1
  expect_next 2026.11.3 "next patch after a used-up patch" next-patch 2026.11
  expect_next 2026.12.1 "next patch after a used-up first release" next-patch 2026.12
  expect_next 2026.13.0 "next after a used-up release/2026.12" next 1794132000
  expect_next fail "next patch of a malformed line" next-patch 2026.11.1
  if ((fails > 0)); then
    echo "$fails case(s) failed" >&2
    return 1
  fi
  echo "release-version: all cases pass"
}

usage() {
  echo "usage: release-version.sh check <tag> <unix-time> [<latest-unix-time>] | next [unix-time] | next-patch <YYYY.N> | --self-test" >&2
  exit 2
}

case ${1:-} in
  check) (($# == 3 || $# == 4)) || usage; check "${@:2}" ;;
  next) next "${2:-}" ;;
  next-patch) (($# == 2)) || usage; next_patch "$2" ;;
  --self-test) self_test ;;
  *) usage ;;
esac
