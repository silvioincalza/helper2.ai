#!/usr/bin/env bash
# What a change touches (ADR 0033): which CI jobs run on a pull request, and which images CD builds on a push. The one
# place that maps paths to components; ci.yml and cd.yml both call it.
#
#   changes.sh <base> <head>   git diff --name-only --no-renames -z <base> <head>, then the map below. --no-renames: a
#                              moved file counts on both sides (moved out of apps/public still builds the public image)
#   changes.sh --all           everything (a push to main runs all of CI)
#   changes.sh --paths         the paths on stdin, one per line
#   changes.sh --self-test     checks the map against fixed cases (the CI job changes runs it first)
#
# Prints key=true|false lines for $GITHUB_OUTPUT. CI jobs: frontend, backend, chart, monitoring, postgres, diagrams.
# Images: web, public, backoffice. A base that is empty, all zeros or not in the clone, or a failing diff, gives
# everything: a wrong "nothing changed" would skip a check or ship an old image, a wrong "everything" only costs time.
set -euo pipefail

KEYS="frontend backend chart monitoring postgres diagrams web public backoffice"

# The map, first match wins. Unknown = everything.
#   docs/eval/**               backend job (its tests read the evaluation sets) and diagrams
#   frontend/**                frontend job; web image and back office image: backoffice.Dockerfile builds the admin
#                              web app from the same frontend/ (one package, one lock file, one tsconfig over src)
#   deploy/web.Dockerfile, deploy/web/**                       web image
#   backend/apps/public/**, deploy/Dockerfile                  backend job; public image
#   backend/apps/backoffice/**, deploy/backoffice.Dockerfile   backend job; back office image
#   (the image components come before *.md: a .md file inside one can be part of the image)
#   *.md, docs/**, LICENSE     diagrams job only
#   .claude/**, .idea/**       nothing (assistant and editor settings; .claude/*.md counts as docs)
#   deploy/postgres/**         postgres tag check and chart; no app image
#   deploy/monitoring/**       monitoring job and chart (the chart ships the rule file)
#   deploy/helm/**, deploy/k8s/**, deploy/grafana/**           chart only; no app image
#   everything else            everything: api/, backend/libs/**, backend/apps/common-web/**, backend/build-logic/**,
#                              the Gradle files, settings, version catalog and wrapper, .github/**, scripts/, ...
classify() {
  case $1 in
    docs/eval/*) echo "backend diagrams" ;;
    frontend/*) echo "frontend web backoffice" ;;
    deploy/web.Dockerfile | deploy/web/*) echo "web" ;;
    backend/apps/public/* | deploy/Dockerfile) echo "backend public" ;;
    backend/apps/backoffice/* | deploy/backoffice.Dockerfile) echo "backend backoffice" ;;
    *.md | docs/* | LICENSE) echo "diagrams" ;;
    .claude/* | .idea/*) echo "" ;;
    deploy/postgres/*) echo "postgres chart" ;;
    deploy/monitoring/*) echo "monitoring chart" ;;
    deploy/helm/* | deploy/k8s/* | deploy/grafana/*) echo "chart" ;;
    *) echo "$KEYS" ;;
  esac
}

print() {
  local on=" $1 " k
  for k in $KEYS; do
    if [[ $on == *" $k "* ]]; then echo "$k=true"; else echo "$k=false"; fi
  done
}

# Paths separated by NUL bytes: a file name may hold spaces or newlines.
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
  # shellcheck disable=SC2064 # the name is known now
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

# Each case: the paths (comma-separated) and the keys that must be true; all others must be false.
self_test() {
  local fails=0 paths want got expect all
  all="frontend backend chart monitoring postgres diagrams web public backoffice"
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
backend/libs/search/src/main/kotlin/X.kt|ALL
backend/apps/common-web/build.gradle.kts|ALL
backend/build-logic/src/main/kotlin/helper2.spring-app.gradle.kts|ALL
backend/gradle/libs.versions.toml|ALL
backend/gradle/wrapper/gradle-wrapper.properties|ALL
backend/settings.gradle.kts|ALL
backend/build.gradle.kts|ALL
backend/gradlew|ALL
api/public.yaml|ALL
.github/workflows/ci.yml|ALL
.github/scripts/changes.sh|ALL
compose.yaml|ALL
.grype.yaml|ALL
deploy/helm/helper2/values.yaml|chart
deploy/helm/helper2/templates/app.yaml|chart
deploy/k8s/Makefile|chart
deploy/k8s/cosign.pub|chart
deploy/grafana/helper2.json|chart
deploy/monitoring/helper2-rules.yaml|monitoring chart
deploy/postgres/Dockerfile|postgres chart
docs/adr/README.md|diagrams
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
  # No paths run nothing; a base we cannot diff runs everything.
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
  [[ $(print "$all" | trues) == "$($0 --all | trues)" ]] || {
    echo "FAIL: --all must give everything" >&2
    fails=$((fails + 1))
  }
  # Moves and odd file names, through a real git diff in a scratch repository.
  local repo got
  repo=$(mktemp -d)
  (
    cd "$repo" || exit 1
    g() { git -c user.name=t -c user.email=t@example.org -c commit.gpgsign=false "$@" > /dev/null; }
    move() { # <from> <to> <want>
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
    odd() { # <path> <want>
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
  rm -rf "$repo"
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
