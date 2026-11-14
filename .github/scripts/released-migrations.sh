#!/usr/bin/env bash
set -euo pipefail

CALVER='^v([0-9]{4})\.([1-9][0-9]*)\.(0|[1-9][0-9]*)$'
VERSIONED='^V[0-9][0-9._]*__.*\.sql$'
MIGRATIONS=backend/libs/schema/src/main/resources/db/migration
FIRST=V1__schema.sql
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")

releases_in() {
  local head=$1 tag
  for tag in $(git tag -l 'v*' | { grep -E "$CALVER" || true; } | sort -V); do
    if git merge-base --is-ancestor "$tag" "$head" || git merge-base --is-ancestor "$tag^" "$head" 2> /dev/null; then
      echo "$tag"
    fi
  done
}

versioned_in() { git ls-tree --name-only "$1:$MIGRATIONS/" 2> /dev/null | { grep -E "$VERSIONED" || true; }; }

blob() { git rev-parse --verify --quiet "$1:$MIGRATIONS/$2" || true; }

only_the_first_migration() {
  local head=$1 file bad=0
  for file in $(versioned_in "$head"); do
    [[ $file == "$FIRST" ]] && continue
    echo "::error file=$MIGRATIONS/$file::$file: until the first release tag the schema is $FIRST only, edited in place (ADR 0004 §5)"
    bad=1
  done
  ((bad == 0)) || return 1
  echo "no release tag vYYYY.N.P in this history: $FIRST is the whole schema and may change"
}

released_migrations_unchanged() {
  local head=$1 release=$2 file bad=0
  for file in $(versioned_in "$release"); do
    [[ $(blob "$head" "$file") == "$(blob "$release" "$file")" ]] && continue
    echo "::error file=$MIGRATIONS/$file::$file is not the file $release holds: a released migration never changes, Flyway refuses its new checksum on prod (ADR 0004 §5). Write a new versioned migration"
    bad=1
  done
  ((bad == 0)) || return 1
  echo "the versioned migrations of $release are unchanged"
}

check() {
  local head=${1:-HEAD} releases release bad=0
  git rev-parse --verify --quiet "$head^{commit}" > /dev/null || { echo "no commit $head" >&2; return 2; }
  [[ $(git rev-parse --is-shallow-repository) == false ]] \
    || { echo "a shallow clone hides the release tags: fetch the full history (fetch-depth: 0)" >&2; return 2; }
  releases=$(releases_in "$head")
  if [[ -z $releases ]]; then
    only_the_first_migration "$head"
    return
  fi
  for release in $releases; do
    released_migrations_unchanged "$head" "$release" || bad=1
  done
  return "$bad"
}

self_test() {
  local repo fails
  repo=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$repo'" EXIT
  fails=$(
    set -e
    mkdir "$repo/origin" && cd "$repo/origin"
    f=0
    g() { git -c user.name=t -c user.email=t@example.org -c commit.gpgsign=false -c tag.gpgSign=false "$@" > /dev/null 2>&1; }
    write() {
      mkdir -p "$MIGRATIONS"
      echo "$2" > "$MIGRATIONS/$1"
      g add -A && g commit -qm "$1 is $2"
    }
    remove() { g rm -q "$MIGRATIONS/$1" && g commit -qm "$1 is removed"; }
    expect() {
      local want=$1 what=$2 got=ok
      "$SELF" check > /dev/null 2>&1 || got=fail
      if [[ $got == "$want" ]]; then
        echo "ok   [$what] -> $got" >&2
      else
        echo "FAIL [$what]: got $got, want $want" >&2
        f=$((f + 1))
      fi
    }
    g init -q -b main .
    write README.md text
    expect ok "no migration yet"
    write "$FIRST" one
    write R__seed.sql one
    write "$FIRST" two
    expect ok "no release: V1 changes"
    g tag v2.0.0
    write "$FIRST" three
    expect ok "v2.0.0 is not a calendar release: V1 changes"
    write V2__more.sql one
    expect fail "no release: a V2"
    remove V2__more.sql
    expect ok "no release: the V2 is taken out again"
    g tag v2026.11.1
    expect ok "the release commit"
    write R__seed.sql two
    expect ok "released: a seed changes"
    g switch -qc v1-edited
    write "$FIRST" four
    expect fail "released: V1 changes"
    g switch -q main && g switch -qc v1-removed
    remove "$FIRST"
    expect fail "released: V1 is removed"
    g switch -q main
    write V2__more.sql one
    expect ok "released: a V2 is added"
    write V2__more.sql two
    expect ok "released: a V2 no release holds changes"
    g tag v2026.11.2
    write V3__more.sql one
    expect ok "second release: a V3 is added"
    write V2__more.sql three
    expect fail "second release: its V2 changes"
    write V2__more.sql two
    g switch -qc release/2026.11.3
    write version.txt 2026.11.3
    g tag v2026.12.0
    g switch -q main
    expect ok "a release tagged on its release branch, off main"
    write V3__more.sql two
    expect fail "a release tagged on its release branch, off main: its V3 changes on main"
    write V3__more.sql one
    g switch -qc fix-of-an-old-release v2026.11.1
    write V2__fix.sql one
    expect ok "a fix branch from an older release: the migrations of later releases are not in it"
    g switch -q main
    g switch -qc before-the-reset v2.0.0
    write "$FIRST" five
    expect ok "a history without the release (release/2.x): V1 changes"
    g clone -q --depth 1 "file://$repo/origin" "$repo/shallow" && cd "$repo/shallow"
    expect fail "a shallow clone"
    echo "$f"
  ) || fails=1
  if ((fails > 0)); then
    echo "released-migrations.sh: $fails case(s) failed" >&2
    return 1
  fi
  echo "released-migrations.sh: all cases pass"
}

case ${1:-} in
  check) check "${2:-}" ;;
  --self-test) self_test ;;
  *)
    echo "usage: released-migrations.sh check [commit] | --self-test" >&2
    exit 2
    ;;
esac
