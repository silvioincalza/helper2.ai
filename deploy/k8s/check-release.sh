#!/usr/bin/env bash
set -euo pipefail

RELEASE=helper2
ROLLOUT_TIMEOUT=${ROLLOUT_TIMEOUT:-120s}
ROLLBACK_TIMEOUT=${ROLLBACK_TIMEOUT:-12m}
HTTP_TRIES=${HTTP_TRIES:-5}
HTTP_PAUSE=${HTTP_PAUSE:-3}
VERSION_TRIES=${VERSION_TRIES:-10}
VERSION_PAUSE=${VERSION_PAUSE:-6}
SMOKE_API_PATHS=${SMOKE_API_PATHS:-/api/v1/sources /api/v1/search?q=mcp}
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")

unhealthy() {
  echo "not healthy: $*" >&2
  exit 1
}

failed() {
  if [[ -n ${GITHUB_ACTIONS:-} ]]; then echo "::error::$*" >&2; else echo "$*" >&2; fi
  exit 1
}

deployed_revision() {
  helm -n "$1" history "$RELEASE" -o json 2> /dev/null \
    | jq -r '[.[] | select(.status == "deployed") | .revision] | max // empty' || true
}

current_revision() { helm -n "$1" status "$RELEASE" -o json 2> /dev/null | jq -r .version || true; }

release_is_deployed() {
  local ns=$1 release
  release=$(helm -n "$ns" status "$RELEASE" -o json) || unhealthy "no release $RELEASE in $ns"
  status=$(jq -r .info.status <<< "$release")
  revision=$(jq -r .version <<< "$release")
  [[ $status == deployed ]] || unhealthy "release $RELEASE in $ns is $status (revision $revision)"
  echo "release $RELEASE in $ns: deployed, revision $revision"
}

workloads_are_rolled_out() {
  local ns=$1 workloads workload
  workloads=$(kubectl -n "$ns" get deployments,statefulsets -l "app.kubernetes.io/part-of=$RELEASE" -o name) \
    || unhealthy "the workloads of $ns could not be listed"
  [[ -n $workloads ]] || unhealthy "$ns has no Deployment or StatefulSet of $RELEASE"
  for workload in $workloads; do
    kubectl -n "$ns" rollout status "$workload" --timeout="$ROLLOUT_TIMEOUT" \
      || unhealthy "$workload in $ns is not rolled out after $ROLLOUT_TIMEOUT"
  done
}

migrate_job_of_the_release() {
  helm -n "$1" get manifest "$RELEASE" | grep -oE "$RELEASE-migrate-[0-9]+" | head -n 1
}

migrate_job_completed() {
  local ns=$1 name job
  name=$(migrate_job_of_the_release "$ns") || unhealthy "the manifest of $RELEASE in $ns names no migrate Job"
  job=$(kubectl -n "$ns" get job "$name" --ignore-not-found -o json) \
    || unhealthy "job $name in $ns could not be read"
  if [[ -z $job ]]; then
    echo "job $name is gone: a finished migrate Job is deleted after a day"
    return
  fi
  jq -e '[.status.conditions[]? | select(.type == "Complete" and .status == "True")] | length > 0' <<< "$job" > /dev/null \
    || unhealthy "job $name in $ns did not complete"
  echo "job $name: complete"
}

site_answers() {
  local url=$1 try code
  for ((try = 1; try <= HTTP_TRIES; try++)); do
    code=$(curl -sS -o /dev/null --max-time 10 -w '%{http_code}' "$url" || true)
    echo "$url -> ${code:-no answer} (try $try of $HTTP_TRIES)"
    [[ $code == 200 ]] && return
    ((try == HTTP_TRIES)) || sleep "$HTTP_PAUSE"
  done
  unhealthy "$url did not answer 200"
}

fetch() {
  local url=$1 out=$2 try code
  shift 2
  for ((try = 1; try <= HTTP_TRIES; try++)); do
    code=$(curl -sS -o "$out" --max-time 20 -w '%{http_code}' "$@" "$url" || true)
    echo "$url -> ${code:-no answer} (try $try of $HTTP_TRIES)"
    [[ $code == 200 ]] && return
    ((try == HTTP_TRIES)) || sleep "$HTTP_PAUSE"
  done
  unhealthy "$url did not answer 200"
}

names_version() {
  local base=$1 version=$2 dir=$3 script pattern
  shift 3
  fetch "$base/" "$dir/page.html" "$@"
  pattern="[\"'\`]${version//./\\.}[\"'\`]"
  while read -r script; do
    fetch "$base$script" "$dir/script.js" "$@"
    if grep -qE "$pattern" "$dir/script.js"; then
      echo "$base serves version $version ($script)"
      return 0
    fi
  done < <(grep -oE '/assets/[A-Za-z0-9._-]+\.js' "$dir/page.html" | sort -u)
  return 1
}

serves_version() {
  local base=$1 version=$2 dir=$3 try
  shift 3
  for ((try = 1; try <= VERSION_TRIES; try++)); do
    names_version "$base" "$version" "$dir" "$@" && return
    echo "$base does not serve version $version yet (try $try of $VERSION_TRIES)"
    ((try == VERSION_TRIES)) || sleep "$VERSION_PAUSE"
  done
  unhealthy "$base does not serve version $version: none of the scripts of its page names it"
}

smoke() {
  local base=$1 version=$2 dir path
  shift 2
  dir=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$dir'" RETURN
  if [[ $version == - ]]; then
    fetch "$base/" "$dir/page.html" "$@"
  else
    serves_version "$base" "$version" "$dir" "$@"
  fi
  for path in $SMOKE_API_PATHS; do fetch "$base$path" "$dir/answer" "$@"; done
  echo "$base passed the smoke test"
}

check() {
  local ns=$1 url status revision
  shift
  release_is_deployed "$ns"
  workloads_are_rolled_out "$ns"
  migrate_job_completed "$ns"
  for url in "$@"; do site_answers "$url"; done
  echo "release $RELEASE in $ns is healthy"
}

check_or_roll_back_to() {
  local before=$1 ns=$2 now
  shift 2
  if (check "$ns" "$@"); then return; fi
  now=$(current_revision "$ns")
  [[ $before != off ]] \
    || failed "NOT ROLLED BACK: rollback switched off for this deploy. $RELEASE in $ns is not healthy at revision ${now:-unknown} and stays as it is"
  [[ $before != none ]] \
    || failed "NOT ROLLED BACK: $RELEASE in $ns is not healthy and no revision was deployed before this one (a first install). The release stays as it is"
  [[ $before != "$now" ]] \
    || failed "NOT ROLLED BACK: $RELEASE in $ns is not healthy at revision $now, the one that was deployed before: there is no upgrade to undo"
  echo "rolling $RELEASE in $ns back from revision ${now:-unknown} to revision $before, the one deployed before this upgrade"
  helm -n "$ns" rollback "$RELEASE" "$before" --wait --wait-for-jobs --timeout "$ROLLBACK_TIMEOUT" \
    || failed "ROLLBACK FAILED: $RELEASE in $ns was not healthy at revision ${now:-unknown} and the rollback to revision $before failed. Nothing more is tried: check the pods and helm -n $ns history $RELEASE"
  if (check "$ns" "$@"); then
    failed "ROLLED BACK: $RELEASE in $ns was not healthy at revision ${now:-unknown}. It runs revision $before again, which is healthy. The deploy failed"
  fi
  failed "ROLLED BACK, STILL NOT HEALTHY: $RELEASE in $ns was not healthy at revision ${now:-unknown} and is not healthy after the rollback to revision $before either. Nothing more is tried: the fault may be outside the release (the edge, the database)"
}

fake_cluster() {
  local bin=$1
  cat > "$bin/helm" << 'SH'
#!/usr/bin/env bash
case " $* " in
  *" history "*)
    [[ -n ${FAKE_HISTORY:-} ]] || exit 1
    tr ',' '\n' <<< "$FAKE_HISTORY" | jq -R 'split(":") | {revision: (.[0] | tonumber), status: .[1]}' | jq -s .
    ;;
  *" rollback "*)
    echo "$*" >> "$FAKE_DIR/rollbacks"
    [[ ${FAKE_ROLLBACK:-works} == works ]] || exit 1
    echo "$5" > "$FAKE_DIR/rolled-back"
    ;;
  *" get manifest "*)
    restored=7
    [[ -e $FAKE_DIR/rolled-back ]] && restored=$(< "$FAKE_DIR/rolled-back")
    printf 'kind: Job\nmetadata:\n  name: helper2-migrate-%s\n' "$restored"
    ;;
  *" status "*)
    [[ -n ${FAKE_RELEASE:-} ]] || exit 1
    version=7
    [[ -e $FAKE_DIR/rolled-back ]] && version=8
    printf '{"version": %s, "info": {"status": "%s"}}\n' "$version" "$FAKE_RELEASE"
    ;;
  *) echo "fake helm: unexpected $*" >&2; exit 2 ;;
esac
SH
  cat > "$bin/kubectl" << 'SH'
#!/usr/bin/env bash
restored=7
state=${FAKE_JOB:-}
if [[ -e $FAKE_DIR/rolled-back ]]; then
  restored=$(< "$FAKE_DIR/rolled-back")
  state=${FAKE_JOB_AFTER_ROLLBACK:-$state}
fi
case " $* " in
  *" get deployments,statefulsets "*) printf '%s\n' ${FAKE_WORKLOADS:-} ;;
  *" rollout status "*) [[ " ${FAKE_STUCK:-} " != *" $5 "* ]] ;;
  *" get job helper2-migrate-$restored "*)
    case $state in
      complete) echo '{"status": {"conditions": [{"type": "Complete", "status": "True"}]}}' ;;
      failed) echo '{"status": {"conditions": [{"type": "Failed", "status": "True"}]}}' ;;
      running) echo '{"status": {"active": 1}}' ;;
      gone) ;;
    esac ;;
  *) echo "fake kubectl: unexpected $*" >&2; exit 2 ;;
esac
SH
  cat > "$bin/curl" << 'SH'
#!/usr/bin/env bash
url=${*: -1}
out=/dev/null
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  [[ ${args[i]} == -o ]] && out=${args[i + 1]}
  [[ ${args[i]} == -H ]] && echo "${args[i + 1]}" >> "$FAKE_DIR/headers"
done
case $url in
  */assets/*) printf '%s' "${FAKE_SCRIPT-var v=\"2026.11.2\";}" > "$out" ;;
  */) printf '%s' "${FAKE_PAGE-<script type=\"module\" src=\"/assets/vendor-1.js\"></script><script type=\"module\" src=\"/assets/index-a1.js\"></script>}" > "$out" ;;
  *) printf '{}' > "$out" ;;
esac
count=$FAKE_DIR/tries
echo x >> "$count"
down=${FAKE_DOWN:-}
[[ -e $FAKE_DIR/rolled-back ]] && down=${FAKE_DOWN_AFTER_ROLLBACK:-}
if [[ " $down " == *" $url "* ]]; then printf 503; exit 0; fi
if [[ -n ${FAKE_UP_AT_TRY:-} ]] && (($(wc -l < "$count") < FAKE_UP_AT_TRY)); then exit 7; fi
printf 200
SH
  chmod +x "$bin/helm" "$bin/kubectl" "$bin/curl"
}

self_test() {
  local dir fails=0 want what settings mode rollbacks says got problem
  dir=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$dir'" EXIT
  fake_cluster "$dir"
  while IFS='|' read -r want what settings mode rollbacks says; do
    [[ -z $want ]] && continue
    rm -f "$dir/rolled-back" "$dir/rollbacks"
    : > "$dir/tries"
    # shellcheck disable=SC2086
    if env PATH="$dir:$PATH" FAKE_DIR="$dir" HTTP_PAUSE=0 GITHUB_ACTIONS= FAKE_RELEASE=deployed FAKE_JOB=complete \
      FAKE_WORKLOADS="deployment.apps/helper2 deployment.apps/helper2-web statefulset.apps/postgres" \
      $settings "$SELF" $mode helper2-test https://site.test/ https://site.test/api > "$dir/out" 2>&1; then
      got=ok
    else
      got=fail
    fi
    problem=""
    [[ $got == "$want" ]] || problem="got $got, want $want"
    [[ $(cat "$dir/rollbacks" 2> /dev/null) == "$rollbacks" ]] \
      || problem="$problem; helm rollback calls: '$(cat "$dir/rollbacks" 2> /dev/null)', want '$rollbacks'"
    grep -qF -- "$says" "$dir/out" || problem="$problem; the output does not say '$says'"
    if [[ -z $problem ]]; then
      echo "ok   [$what] -> $got"
    else
      echo "FAIL [$what]: $problem" >&2
      sed 's/^/     /' "$dir/out" >&2
      fails=$((fails + 1))
    fi
  done << 'CASES'
ok|deployed, rolled out, migrated, the site answers||||is healthy
ok|the site answers at the third try|FAKE_UP_AT_TRY=3|||is healthy
ok|the migrate Job is deleted after its day|FAKE_JOB=gone|||is gone
fail|no release|FAKE_RELEASE=|||no release
fail|the release failed|FAKE_RELEASE=failed|||is failed
fail|the release is pending|FAKE_RELEASE=pending-upgrade|||is pending-upgrade
fail|no workload|FAKE_WORKLOADS=|||has no Deployment
fail|a Deployment is not rolled out|FAKE_STUCK=deployment.apps/helper2-web|||deployment.apps/helper2-web in helper2-test is not rolled out
fail|a StatefulSet is not rolled out|FAKE_STUCK=statefulset.apps/postgres|||statefulset.apps/postgres in helper2-test is not rolled out
fail|the migrate Job failed|FAKE_JOB=failed|||did not complete
fail|the migrate Job still runs|FAKE_JOB=running|||did not complete
fail|the page answers 503|FAKE_DOWN=https://site.test/|||https://site.test/ did not answer 200
fail|the API answers 503|FAKE_DOWN=https://site.test/api|||https://site.test/api did not answer 200
fail|the site never answers|FAKE_UP_AT_TRY=99|||did not answer 200
fail|a plain check never rolls back|FAKE_DOWN=https://site.test/|||not healthy
ok|deploy: healthy, no rollback||--or-roll-back-to 6||is healthy
fail|deploy: the page answers 503, rolled back to the revision deployed before, healthy again|FAKE_DOWN=https://site.test/|--or-roll-back-to 6|-n helper2-test rollback helper2 6 --wait --wait-for-jobs --timeout 12m|ROLLED BACK: helper2 in helper2-test was not healthy at revision 7. It runs revision 6 again, which is healthy. The deploy failed
fail|deploy: the rollback goes to the recorded revision, not to the one before the current|FAKE_DOWN=https://site.test/api|--or-roll-back-to 4|-n helper2-test rollback helper2 4 --wait --wait-for-jobs --timeout 12m|It runs revision 4 again
fail|deploy: a first install is not rolled back|FAKE_DOWN=https://site.test/|--or-roll-back-to none||NOT ROLLED BACK: helper2 in helper2-test is not healthy and no revision was deployed before this one
fail|deploy: no upgrade happened, nothing to undo|FAKE_DOWN=https://site.test/|--or-roll-back-to 7||NOT ROLLED BACK: helper2 in helper2-test is not healthy at revision 7
fail|deploy: the rollback is switched off, the release stays|FAKE_DOWN=https://site.test/|--or-roll-back-to off||NOT ROLLED BACK: rollback switched off for this deploy. helper2 in helper2-test is not healthy at revision 7 and stays as it is
ok|deploy: the rollback is switched off and the release is healthy||--or-roll-back-to off||is healthy
fail|deploy: the rollback itself fails, once|FAKE_DOWN=https://site.test/ FAKE_ROLLBACK=fails|--or-roll-back-to 6|-n helper2-test rollback helper2 6 --wait --wait-for-jobs --timeout 12m|ROLLBACK FAILED: helper2 in helper2-test was not healthy at revision 7 and the rollback to revision 6 failed
fail|deploy: not healthy after the rollback either, rolled back once|FAKE_DOWN=https://site.test/ FAKE_DOWN_AFTER_ROLLBACK=https://site.test/|--or-roll-back-to 6|-n helper2-test rollback helper2 6 --wait --wait-for-jobs --timeout 12m|ROLLED BACK, STILL NOT HEALTHY
fail|deploy: after the rollback the migrate Job of the restored revision is read, and it failed|FAKE_DOWN=https://site.test/ FAKE_JOB_AFTER_ROLLBACK=failed|--or-roll-back-to 6|-n helper2-test rollback helper2 6 --wait --wait-for-jobs --timeout 12m|job helper2-migrate-6 in helper2-test did not complete
fail|deploy: after the rollback the migrate Job of the restored revision is complete|FAKE_DOWN=https://site.test/|--or-roll-back-to 6|-n helper2-test rollback helper2 6 --wait --wait-for-jobs --timeout 12m|job helper2-migrate-6: complete
CASES
  while IFS='|' read -r want settings version says what; do
    [[ -z $what ]] && continue
    : > "$dir/tries"
    : > "$dir/headers"
    rm -f "$dir/rolled-back"
    # shellcheck disable=SC2086
    if env PATH="$dir:$PATH" FAKE_DIR="$dir" HTTP_PAUSE=0 GITHUB_ACTIONS= $settings \
      "$SELF" smoke https://site.test "$version" -H 'X-Canary: abc' > "$dir/out" 2>&1; then
      got=ok
    else
      got=fail
    fi
    problem=""
    [[ $got == "$want" ]] || problem="got $got, want $want"
    grep -qF -- "$says" "$dir/out" || problem="$problem; the output does not say '$says'"
    if [[ $got == ok ]] && grep -qvx 'X-Canary: abc' "$dir/headers"; then problem="$problem; a request went without the canary header"; fi
    if [[ -z $problem ]]; then
      echo "ok   [$what] -> $got"
    else
      echo "FAIL [$what]: $problem" >&2
      sed 's/^/     /' "$dir/out" >&2
      fails=$((fails + 1))
    fi
  done << 'CASES'
ok||2026.11.2|serves version 2026.11.2 (/assets/index-a1.js)|smoke: the canary serves the new version and answers
ok|FAKE_SCRIPT=x='2026.11.2'|2026.11.2|passed the smoke test|smoke: the version in single quotes
ok||-|passed the smoke test|smoke: no version to check (the release that runs)
fail|FAKE_SCRIPT=v="2026.11.1"|2026.11.2|does not serve version 2026.11.2|smoke: the page still serves the old version
fail|FAKE_SCRIPT=v="2026.11.21"|2026.11.2|does not serve version 2026.11.2|smoke: a longer version is not the version
fail|FAKE_PAGE=<html></html>|2026.11.2|does not serve version 2026.11.2|smoke: a page with no script
fail|FAKE_DOWN=https://site.test/api/v1/search?q=mcp|2026.11.2|did not answer 200|smoke: the search answers 503
fail|FAKE_DOWN=https://site.test/|-|did not answer 200|smoke: the page answers 503 and no version is checked
CASES
  while IFS='|' read -r want settings what; do
    [[ -z $what ]] && continue
    # shellcheck disable=SC2086
    got=$(env PATH="$dir:$PATH" FAKE_DIR="$dir" $settings "$SELF" deployed-revision helper2-test)
    if [[ $got == "$want" ]]; then
      echo "ok   [$what] -> '$got'"
    else
      echo "FAIL [$what]: got '$got', want '$want'" >&2
      fails=$((fails + 1))
    fi
  done << 'CASES'
6|FAKE_HISTORY=4:superseded,5:superseded,6:deployed|before an upgrade: the deployed revision
4|FAKE_HISTORY=4:deployed,5:failed|before an upgrade: a failed revision is never the one to go back to
|FAKE_HISTORY=1:failed|before an upgrade: nothing was ever deployed
|FAKE_HISTORY=|before a first install: no release
CASES
  if ((fails > 0)); then
    echo "check-release.sh: $fails case(s) failed" >&2
    return 1
  fi
  echo "check-release.sh: all cases pass"
}

usage() {
  echo "usage: check-release.sh <namespace> <url>..." >&2
  echo "       check-release.sh deployed-revision <namespace>" >&2
  echo "       check-release.sh --or-roll-back-to <revision deployed before the upgrade | none | off> <namespace> <url>..." >&2
  echo "       check-release.sh smoke <base-url> <version | -> [curl option]..." >&2
  echo "       check-release.sh --self-test" >&2
  exit 2
}

case ${1:-} in
  --self-test) self_test ;;
  deployed-revision)
    (($# == 2)) || usage
    deployed_revision "$2"
    ;;
  smoke)
    (($# >= 3)) || usage
    shift
    smoke "$@"
    ;;
  --or-roll-back-to)
    (($# >= 4)) || usage
    [[ $2 =~ ^([0-9]+|none|off)$ ]] || usage
    shift
    check_or_roll_back_to "$@"
    ;;
  -* | "") usage ;;
  *)
    (($# >= 2)) || usage
    check "$@"
    ;;
esac
