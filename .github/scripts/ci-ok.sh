#!/usr/bin/env bash
set -euo pipefail

SELECTED_BY='{
  "frontend-build": "frontend",
  "frontend-check": "frontend",
  "frontend-test": "frontend",
  "backend-build": "backend",
  "backend-check": "backend",
  "chart": "chart",
  "monitoring": "monitoring",
  "postgres-tag": "postgres",
  "diagrams": "diagrams"
}'
PULL_REQUEST_ONLY='["postgres-tag"]'
FULL_RUN_ONLY='{"package": ["frontend", "backend"]}'

results() { jq -r 'to_entries[] | "\(.key): \(.value.result)"'; }

problems() {
  jq -r --argjson selected_by "$SELECTED_BY" --argjson pull_request_only "$PULL_REQUEST_ONLY" \
    --argjson full_run_only "$FULL_RUN_ONLY" --argjson pr "$1" --argjson full "$2" '
    .changes as $changes
    | (if $changes == null then "changes: not in the needs of ci-ok"
       elif $changes.result != "success" then "changes: \($changes.result), must be success"
       else ($selected_by | [to_entries[].value] | unique[]
             | select($changes.outputs[.] != "true" and $changes.outputs[.] != "false")
             | "changes: no true or false for \(.)")
       end),
      (to_entries[]
       | select(.key != "changes")
       | .key as $job
       | .value.result as $result
       | $full_run_only[$job] as $parts
       | ($selected_by[$job] // (($parts // []) | join(" and "))) as $part
       | if $part == "" then "\($job): ci-ok.sh has no rule for this job"
         else
           (if $parts != null then $full and ($parts | all($changes.outputs[.] == "true"))
            else $changes.outputs[$part] == "true" and ($pr or ($pull_request_only | index($job) == null)) end) as $selected
           | if $selected and $result != "success" then "\($job): \($result), must be success (\($part) is selected)"
             elif ($selected | not) and $result != "skipped" then "\($job): \($result), must be skipped (\($part) is not selected)"
             else empty end
         end)'
}

verdict() {
  local needs=$1 pr=$2 full=$3 found
  results <<< "$needs"
  found=$(problems "$pr" "$full" <<< "$needs")
  [[ -z $found ]] && return 0
  echo "::error::${found//$'\n'/$'\n'::error::}"
  return 1
}

needs_of() {
  local parts=" $1 " overrides=$2 full=$3
  jq -n --argjson selected_by "$SELECTED_BY" --argjson full_run_only "$FULL_RUN_ONLY" --arg parts "$parts" \
    --argjson overrides "$overrides" --argjson full "$full" '
    ($selected_by | [to_entries[].value] | unique) as $all
    | ($all | map(. as $part | {key: $part, value: (if ($parts | contains(" \($part) ")) then "true" else "false" end)}) | from_entries) as $outputs
    | {changes: {result: "success", outputs: $outputs}}
      + ($selected_by | with_entries(.value = {result: (if $outputs[.value] == "true" then "success" else "skipped" end), outputs: {}}))
      + ($full_run_only | with_entries(.value = {result: (if $full and (.value | all($outputs[.] == "true")) then "success" else "skipped" end), outputs: {}}))
      + $overrides'
}

self_test() {
  local fails=0 what run pr full parts overrides want got
  while IFS='|' read -r what run parts overrides want; do
    pr=false full=false
    [[ $run == pr ]] && pr=true
    [[ $run == full ]] && full=true
    got=ok
    [[ -z $(problems "$pr" "$full" <<< "$(needs_of "$parts" "$overrides" "$full")") ]] || got=fail
    if [[ $got == "$want" ]]; then
      echo "ok   [$what] -> $got"
    else
      echo "FAIL [$what]: got $got, want $want" >&2
      fails=$((fails + 1))
    fi
  done << 'EOF'
nothing selected, every job skipped|pr||{"postgres-tag":{"result":"skipped"}}|ok
diagrams selected and green|pr|diagrams|{}|ok
everything selected and green|pr|frontend backend chart monitoring postgres diagrams|{}|ok
a selected job failed|pr|diagrams|{"diagrams":{"result":"failure"}}|fail
a selected job was cancelled|pr|backend|{"backend-check":{"result":"cancelled"}}|fail
a selected job was skipped|pr|backend|{"backend-build":{"result":"skipped"}}|fail
a selected matrix job failed|pr|frontend|{"frontend-test":{"result":"failure"}}|fail
a job ran that was not selected|pr|diagrams|{"chart":{"result":"success"}}|fail
the changes job failed|pr||{"changes":{"result":"failure","outputs":{}}}|fail
the changes job was cancelled|pr||{"changes":{"result":"cancelled","outputs":{}}}|fail
the changes job gave no outputs and the rest was skipped|pr||{"changes":{"result":"success","outputs":{}}}|fail
a job with no rule|pr|diagrams|{"deploy":{"result":"success"}}|fail
a push: postgres selected, postgres-tag skipped|push|postgres chart|{"postgres-tag":{"result":"skipped"}}|ok
a push: postgres-tag ran|push|postgres chart|{}|fail
a pull request: postgres selected, postgres-tag skipped|pr|postgres chart|{"postgres-tag":{"result":"skipped"}}|fail
a push: everything else selected and green|push|frontend backend chart monitoring diagrams|{}|ok
a push: a selected job skipped|push|frontend backend chart monitoring diagrams|{"monitoring":{"result":"skipped"}}|fail
a full run: package ran and is green|full|frontend backend chart monitoring postgres diagrams|{"postgres-tag":{"result":"skipped"}}|ok
a full run: package skipped|full|frontend backend chart monitoring postgres diagrams|{"postgres-tag":{"result":"skipped"},"package":{"result":"skipped"}}|fail
a full run: package failed|full|frontend backend chart monitoring postgres diagrams|{"postgres-tag":{"result":"skipped"},"package":{"result":"failure"}}|fail
a full run of a tree with no frontend: package skipped|full|backend diagrams|{}|ok
a full run of a tree with no frontend: package ran|full|backend diagrams|{"package":{"result":"success"}}|fail
a pull request: package ran|pr|frontend backend|{"package":{"result":"success"}}|fail
a push that is not a full run: package ran|push|frontend backend|{"package":{"result":"success"}}|fail
EOF
  [[ $(problems true false <<< '{}' | wc -l) -eq 1 ]] || {
    echo "FAIL: needs without the changes job must be one problem" >&2
    fails=$((fails + 1))
  }
  if [[ $fails -gt 0 ]]; then
    echo "ci-ok.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "ci-ok.sh: all cases pass"
}

case ${1:-} in
  --self-test) self_test ;;
  "") verdict "${NEEDS:?NEEDS is the needs context as JSON}" "${PR:?PR is true on a pull request}" "${FULL:?FULL is true when cd.yml runs every job}" ;;
  *)
    echo "usage: NEEDS=<json> PR=<true|false> FULL=<true|false> ci-ok.sh | ci-ok.sh --self-test" >&2
    exit 2
    ;;
esac
