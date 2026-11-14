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

results() { jq -r 'to_entries[] | "\(.key): \(.value.result)"'; }

problems() {
  jq -r --argjson selected_by "$SELECTED_BY" --argjson pull_request_only "$PULL_REQUEST_ONLY" --argjson pr "$1" '
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
       | $selected_by[$job] as $part
       | if $part == null then "\($job): ci-ok.sh has no rule for this job"
         else
           ($changes.outputs[$part] == "true" and ($pr or ($pull_request_only | index($job) == null))) as $selected
           | if $selected and $result != "success" then "\($job): \($result), must be success (\($part) is selected)"
             elif ($selected | not) and $result != "skipped" then "\($job): \($result), must be skipped (\($part) is not selected)"
             else empty end
         end)'
}

verdict() {
  local needs=$1 pr=$2 found
  results <<< "$needs"
  found=$(problems "$pr" <<< "$needs")
  [[ -z $found ]] && return 0
  echo "::error::${found//$'\n'/$'\n'::error::}"
  return 1
}

needs_of() {
  local parts=" $1 " overrides=$2
  jq -n --argjson selected_by "$SELECTED_BY" --arg parts "$parts" --argjson overrides "$overrides" '
    ($selected_by | [to_entries[].value] | unique) as $all
    | ($all | map(. as $part | {key: $part, value: (if ($parts | contains(" \($part) ")) then "true" else "false" end)}) | from_entries) as $outputs
    | {changes: {result: "success", outputs: $outputs}}
      + ($selected_by | with_entries(.value = {result: (if $outputs[.value] == "true" then "success" else "skipped" end), outputs: {}}))
      + $overrides'
}

self_test() {
  local fails=0 what pr parts overrides want got
  while IFS='|' read -r what pr parts overrides want; do
    got=ok
    [[ -z $(problems "$pr" <<< "$(needs_of "$parts" "$overrides")") ]] || got=fail
    if [[ $got == "$want" ]]; then
      echo "ok   [$what] -> $got"
    else
      echo "FAIL [$what]: got $got, want $want" >&2
      fails=$((fails + 1))
    fi
  done << 'EOF'
nothing selected, every job skipped|true||{"postgres-tag":{"result":"skipped"}}|ok
diagrams selected and green|true|diagrams|{}|ok
everything selected and green|true|frontend backend chart monitoring postgres diagrams|{}|ok
a selected job failed|true|diagrams|{"diagrams":{"result":"failure"}}|fail
a selected job was cancelled|true|backend|{"backend-check":{"result":"cancelled"}}|fail
a selected job was skipped|true|backend|{"backend-build":{"result":"skipped"}}|fail
a selected matrix job failed|true|frontend|{"frontend-test":{"result":"failure"}}|fail
a job ran that was not selected|true|diagrams|{"chart":{"result":"success"}}|fail
the changes job failed|true||{"changes":{"result":"failure","outputs":{}}}|fail
the changes job was cancelled|true||{"changes":{"result":"cancelled","outputs":{}}}|fail
the changes job gave no outputs and the rest was skipped|true||{"changes":{"result":"success","outputs":{}}}|fail
a job with no rule|true|diagrams|{"package":{"result":"success"}}|fail
a push: postgres selected, postgres-tag skipped|false|postgres chart|{"postgres-tag":{"result":"skipped"}}|ok
a push: postgres-tag ran|false|postgres chart|{}|fail
a pull request: postgres selected, postgres-tag skipped|true|postgres chart|{"postgres-tag":{"result":"skipped"}}|fail
a push: everything else selected and green|false|frontend backend chart monitoring diagrams|{}|ok
a push: a selected job skipped|false|frontend backend chart monitoring diagrams|{"monitoring":{"result":"skipped"}}|fail
EOF
  [[ $(problems true <<< '{}' | wc -l) -eq 1 ]] || {
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
  "") verdict "${NEEDS:?NEEDS is the needs context as JSON}" "${PR:?PR is true on a pull request}" ;;
  *)
    echo "usage: NEEDS=<json> PR=<true|false> ci-ok.sh | ci-ok.sh --self-test" >&2
    exit 2
    ;;
esac
