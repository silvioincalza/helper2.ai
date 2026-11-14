#!/usr/bin/env bash
set -euo pipefail

KEYS="frontend backend chart monitoring postgres diagrams web public backoffice"
ROOT=${CHANGES_ROOT:-.}

needs() {
  case $1 in
    frontend) echo "frontend/package.json" ;;
    backend) echo "backend/gradlew" ;;
    chart) echo "deploy/k8s/Makefile" ;;
    monitoring) echo "deploy/monitoring/helper2-rules.yaml" ;;
    postgres) echo "deploy/postgres/Dockerfile deploy/helm/helper2/values.yaml" ;;
    diagrams) echo "docs/diagrams/render.sh" ;;
    web) echo "deploy/web.Dockerfile" ;;
    public) echo "deploy/Dockerfile" ;;
    backoffice) echo "deploy/backoffice.Dockerfile" ;;
  esac
}

present() {
  local file
  for file in $(needs "$1"); do
    [[ -f $ROOT/$file ]] || return 1
  done
}

classify() {
  case $1 in
    docs/eval/*) echo "backend diagrams" ;;
    frontend/*) echo "frontend web backoffice" ;;
    deploy/web.Dockerfile | deploy/web/*) echo "web" ;;
    backend/apps/public/* | deploy/Dockerfile) echo "backend public" ;;
    backend/apps/backoffice/* | deploy/backoffice.Dockerfile) echo "backend backoffice" ;;
    docs/design/system/tokens.json) echo "frontend diagrams" ;;
    backend/loadtest/*) echo "backend" ;;
    *.md | docs/* | LICENSE) echo "diagrams" ;;
    .claude/* | .idea/*) echo "" ;;
    deploy/postgres/*) echo "postgres chart" ;;
    deploy/monitoring/*) echo "monitoring chart" ;;
    deploy/restore/*) echo "backend" ;;
    deploy/helm/* | deploy/k8s/* | deploy/grafana/*) echo "chart" ;;
    *) echo "$KEYS" ;;
  esac
}

print() {
  local on=" $1 " k
  for k in $KEYS; do
    if [[ $on == *" $k "* ]] && present "$k"; then echo "$k=true"; else echo "$k=false"; fi
  done
}

from_paths_z() {
  local on="" path
  while IFS= read -r -d '' path || [[ -n $path ]]; do
    [[ -z $path ]] && continue
    on="$on $(classify "$path")"
  done
  print "$on"
}

from_paths() { tr '\n' '\0' | from_paths_z; }

from_git() {
  local base=$1 head=$2 paths
  if [[ -z $base || $base =~ ^0+$ ]] || ! git cat-file -e "$base^{commit}" 2> /dev/null; then
    echo "changes: no usable base '$base', so everything" >&2
    print "$KEYS"
    return
  fi
  local list
  list=$(mktemp)
  # shellcheck disable=SC2064
  trap "rm -f '$list'" RETURN
  if ! git diff --name-only --no-renames -z "$base" "$head" > "$list"; then
    echo "changes: git diff $base $head failed, so everything" >&2
    print "$KEYS"
    return
  fi
  tr '\0' '\n' < "$list" | sed 's/^/changed: /' >&2
  from_paths_z < "$list"
}

trues() { awk -F= '$2 == "true" {print $1}' | tr '\n' ' '; }

plant() {
  local k file
  for k in "${@:2}"; do
    for file in $(needs "$k"); do
      mkdir -p "$1/$(dirname "$file")"
      touch "$1/$file"
    done
  done
}

self_test() {
  local fails=0 paths want got expect all whole
  all="frontend backend chart monitoring postgres diagrams web public backoffice"
  whole=$(mktemp -d)
  # shellcheck disable=SC2086
  plant "$whole" $all
  ROOT=$whole
  while IFS='|' read -r paths want; do
    want=${want//ALL/$all}
    got=$(tr ',' '\n' <<< "$paths" | from_paths | trues)
    expect=$(print "$want" | trues)
    if [[ $got != "$expect" ]]; then
      echo "FAIL [$paths]: got '$got', want '$expect'" >&2
      fails=$((fails + 1))
    else
      echo "ok   [$paths] -> ${got:-nothing}"
    fi
  done << 'EOF'
frontend/src/main.tsx|frontend web backoffice
frontend/src/admin/AdminShell.tsx|frontend web backoffice
frontend/package-lock.json|frontend web backoffice
deploy/web/nginx.conf|web
deploy/web.Dockerfile|web
backend/apps/public/src/main/java/ai/helper2/PublicApplication.java|backend public
deploy/Dockerfile|backend public
backend/apps/backoffice/build.gradle.kts|backend backoffice
deploy/backoffice.Dockerfile|backend backoffice
backend/loadtest/src/gatling/java/ai/helper2/load/SearchSimulation.java|backend
backend/loadtest/build.gradle.kts|backend
backend/libs/search/src/main/kotlin/X.kt|ALL
backend/libs/web-contract/build.gradle.kts|ALL
backend/libs/web-contract/src/main/java/ai/helper2/search/web/SearchController.java|ALL
backend/build-logic/src/main/kotlin/helper2.spring-app.gradle.kts|ALL
backend/gradle/libs.versions.toml|ALL
backend/gradle/wrapper/gradle-wrapper.properties|ALL
backend/settings.gradle.kts|ALL
backend/build.gradle.kts|ALL
backend/gradlew|ALL
api/public.yaml|ALL
.github/workflows/ci.yml|ALL
.github/scripts/changes.sh|ALL
.github/scripts/release-version.sh|ALL
.github/scripts/released-migrations.sh|ALL
backend/libs/schema/src/main/resources/db/migration/V1__schema.sql|ALL
compose.yaml|ALL
.grype.yaml|ALL
deploy/helm/helper2/values.yaml|chart
deploy/helm/helper2/templates/app.yaml|chart
deploy/k8s/Makefile|chart
deploy/k8s/cosign.pub|chart
deploy/grafana/helper2.json|chart
deploy/grafana/helper2-backoffice.json|chart
deploy/monitoring/helper2-rules.yaml|monitoring chart
deploy/restore/restore-v2-dump.sh|backend
deploy/postgres/Dockerfile|postgres chart
docs/adr/README.md|diagrams
docs/design/system/tokens.json|frontend diagrams
docs/diagrams/flow-0033-ci-cd.d2|diagrams
deploy/k8s/README.md|diagrams
frontend/README.md|frontend web backoffice
backend/apps/public/src/main/resources/x.md|backend public
backend/apps/backoffice/README.md|backend backoffice
deploy/web/README.md|web
README.md,LICENSE|diagrams
docs/eval/search-queries.json|backend diagrams
.claude/agents/architect.md|diagrams
.claude/settings.json|
frontend/src/main.tsx,backend/apps/public/x.kt|frontend backend web public backoffice
deploy/k8s/Makefile,docs/roadmap.md|chart diagrams
EOF
  [[ -z $(from_paths < /dev/null | trues) ]] || {
    echo "FAIL: no paths must run nothing" >&2
    fails=$((fails + 1))
  }
  local base
  for base in "" 0000000000000000000000000000000000000000 ffffffffffffffffffffffffffffffffffffffff; do
    [[ $(from_git "$base" HEAD 2> /dev/null | trues) == "$(print "$all" | trues)" ]] || {
      echo "FAIL: base '$base' must give everything" >&2
      fails=$((fails + 1))
    }
  done
  [[ $(print "$all" | trues) == "$(CHANGES_ROOT=$whole $0 --all | trues)" ]] || {
    echo "FAIL: --all must give everything" >&2
    fails=$((fails + 1))
  }
  local tree
  tree=$(mktemp -d)
  while IFS='|' read -r have paths want; do
    rm -rf "$tree" && mkdir -p "$tree"
    # shellcheck disable=SC2086
    plant "$tree" $have
    got=$(tr ',' '\n' <<< "$paths" | CHANGES_ROOT=$tree $0 --paths | trues)
    [[ $paths == ALL ]] && got=$(CHANGES_ROOT=$tree $0 --all | trues)
    expect=$(tr ' ' '\n' <<< "$want" | sed '/^$/d' | tr '\n' ' ')
    if [[ $got != "$expect" ]]; then
      echo "FAIL [tree with '$have', $paths]: got '$got', want '$expect'" >&2
      fails=$((fails + 1))
    else
      echo "ok   [tree with '${have:-nothing}', $paths] -> ${got:-nothing}"
    fi
  done << 'EOF'
|ALL|
|.github/workflows/ci.yml|
|docs/adr/README.md|
diagrams|ALL|diagrams
diagrams|.github/workflows/ci.yml|diagrams
diagrams|docs/adr/README.md|diagrams
diagrams|backend/libs/search/src/main/kotlin/X.kt|diagrams
diagrams|backend/apps/public/src/main/java/ai/helper2/PublicApplication.java|
diagrams|frontend/src/main.tsx|
diagrams backend|backend/gradlew|backend diagrams
diagrams backend|backend/apps/public/src/main/java/ai/helper2/PublicApplication.java|backend
diagrams backend|frontend/src/main.tsx|
diagrams backend|README.md|diagrams
diagrams backend frontend|frontend/src/main.tsx|frontend
diagrams backend frontend|api/public.yaml|frontend backend diagrams
diagrams backend monitoring|deploy/monitoring/helper2-rules.yaml|monitoring
diagrams backend public|deploy/Dockerfile|backend public
EOF
  rm -rf "$tree"
  local repo got
  repo=$(mktemp -d)
  (
    cd "$repo" || exit 1
    g() { git -c user.name=t -c user.email=t@example.org -c commit.gpgsign=false "$@" > /dev/null; }
    move() {
      mkdir -p "$(dirname "$1")" "$(dirname "$2")"
      echo "$1" > "$1"
      g add -A && g commit -qm a
      g mv "$1" "$2" && g commit -qm b
      got=$(from_git HEAD~1 HEAD 2> /dev/null | trues)
      want=$(print "$3" | trues)
      if [[ $got != "$want" ]]; then
        echo "FAIL [move $1 -> $2]: got '$got', want '$want'" >&2
        return 1
      fi
      echo "ok   [move $1 -> $2] -> $got"
    }
    odd() {
      mkdir -p "$(dirname "$1")"
      echo x > "$1"
      g add -A && g commit -qm c
      got=$(from_git HEAD~1 HEAD 2> /dev/null | trues)
      want=$(print "$2" | trues)
      if [[ $got != "$want" ]]; then
        echo "FAIL [new file $(printf '%q' "$1")]: got '$got', want '$want'" >&2
        return 1
      fi
      echo "ok   [new file $(printf '%q' "$1")] -> $got"
    }
    g init -q . && echo r > README.md && g add -A && g commit -qm init
    f=0
    move backend/apps/public/src/Gone.java docs/Gone.java "backend public diagrams" || f=$((f + 1))
    move docs/notes/Page.tsx frontend/src/Page.tsx "frontend web backoffice diagrams" || f=$((f + 1))
    move backend/apps/public/src/notes.md docs/notes.md "backend public diagrams" || f=$((f + 1))
    move backend/apps/public/src/Moved.java backend/apps/backoffice/src/Moved.java "backend public backoffice" || f=$((f + 1))
    odd $'frontend/src/a b\nc.tsx' "frontend web backoffice" || f=$((f + 1))
    odd $'docs/x\nbackend/apps/public/y.java' "diagrams" || f=$((f + 1))
    exit "$f"
  ) || fails=$((fails + $?))
  rm -rf "$repo" "$whole"
  if [[ $fails -gt 0 ]]; then
    echo "changes.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "changes.sh: all cases pass"
}

case ${1:-} in
  --all) print "$KEYS" ;;
  --paths) from_paths ;;
  --self-test) self_test ;;
  -* | "")
    echo "usage: changes.sh <base> <head> | --all | --paths | --self-test" >&2
    exit 2
    ;;
  *) from_git "$1" "${2:?usage: changes.sh <base> <head>}" ;;
esac
