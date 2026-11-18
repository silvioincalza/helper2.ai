#!/usr/bin/env bash
set -euo pipefail

FILES=(backend/build.gradle.kts frontend/package.json frontend/package-lock.json deploy/helm/helper2/Chart.yaml api/public.yaml api/admin.yaml api/common.yaml)
VERSION_RE='^[0-9]{4}\.[1-9][0-9]*\.(0|[1-9][0-9]*)$'

version_in() {
  sed -n 's/^version = "\(.*\)"$/\1/p' "$1"
}

current() {
  version_in backend/build.gradle.kts
}

rewrite() {
  local from=$1 to=$2 root=$3 file
  sed -i.bak "s/^version = \"${from//./\\.}\"\$/version = \"$to\"/" "$root/backend/build.gradle.kts"
  sed -i.bak "s/^version: ${from//./\\.}\$/version: $to/" "$root/deploy/helm/helper2/Chart.yaml"
  sed -i.bak "s/^  version: ${from//./\\.}\$/  version: $to/" "$root/api/public.yaml" "$root/api/admin.yaml" "$root/api/common.yaml"
  sed -i.bak "4s/^  \"version\": \"${from//./\\.}\",\$/  \"version\": \"$to\",/" "$root/frontend/package.json"
  sed -i.bak -e "3s/^  \"version\": \"${from//./\\.}\",\$/  \"version\": \"$to\",/" \
    -e "9s/^      \"version\": \"${from//./\\.}\",\$/      \"version\": \"$to\",/" "$root/frontend/package-lock.json"
  for file in "${FILES[@]}"; do rm -f "$root/$file.bak"; done
}

bump() {
  local to=$1 from file
  [[ $to =~ $VERSION_RE ]] || { echo "the version must be YYYY.N.P, got '$to'" >&2; return 1; }
  from=$(current)
  [[ -n $from ]] || { echo "backend/build.gradle.kts names no version" >&2; return 1; }
  [[ $from != "$to" ]] || { echo "the files carry $to already" >&2; return 1; }
  rewrite "$from" "$to" .
  for file in "${FILES[@]}"; do
    want=1
    [[ $file == frontend/package-lock.json ]] && want=2
    got=$(grep -cF "$to" "$file" || true)
    ((got >= want)) || { echo "$file: the version was not changed to $to" >&2; return 1; }
  done
  changed=$(git diff --numstat -- "${FILES[@]}" | awk '{added += $1; removed += $2} END {print added + 0 " " removed + 0}')
  [[ $changed == "8 8" ]] || { echo "the bump must change 8 lines, it changed: $changed (added removed)" >&2; return 1; }
  echo "$from -> $to in ${FILES[*]}"
}

only_version_files() {
  local base=$1 head=$2 file
  while read -r file; do
    [[ " ${FILES[*]} " == *" $file "* ]] || { echo "$file"; return 1; }
  done < <(git diff --name-only "$base" "$head")
}

version_only() {
  local base=$1 head=$2 from to file tree
  only_version_files "$base" "$head" > /dev/null || return 1
  from=$(git show "$base:backend/build.gradle.kts" 2> /dev/null | sed -n 's/^version = "\(.*\)"$/\1/p')
  to=$(git show "$head:backend/build.gradle.kts" 2> /dev/null | sed -n 's/^version = "\(.*\)"$/\1/p')
  [[ $from =~ $VERSION_RE && $to =~ $VERSION_RE && $from != "$to" ]] || return 1
  tree=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tree'" RETURN
  for file in "${FILES[@]}"; do
    mkdir -p "$tree/$(dirname "$file")"
    git show "$base:$file" > "$tree/$file" 2> /dev/null || return 1
  done
  rewrite "$from" "$to" "$tree"
  for file in "${FILES[@]}"; do
    git show "$head:$file" 2> /dev/null | cmp -s - "$tree/$file" || return 1
  done
  echo "$from -> $to"
}

self_test() {
  local repo fails=0
  repo=$(mktemp -d)
  (
    cd "$repo" || exit 1
    g() { git -c user.name=t -c user.email=t@example.org -c commit.gpgsign=false "$@" > /dev/null; }
    check() {
      local name=$1 want=$2 got=fail
      version_only HEAD~1 HEAD > /dev/null && got=pass
      if [[ $got != "$want" ]]; then
        echo "FAIL [$name]: got $got, want $want" >&2
        return 1
      fi
      echo "ok   [$name] -> $got"
    }
    mkdir -p backend frontend deploy/helm/helper2 api docs
    printf 'plugins {}\n\ngroup = "x"\n\nversion = "2026.1.0"\n' > backend/build.gradle.kts
    printf '{\n  "name": "f",\n  "private": true,\n  "version": "2026.1.0",\n  "type": "module"\n}\n' > frontend/package.json
    printf '{\n  "name": "f",\n  "version": "2026.1.0",\n  "lockfileVersion": 3,\n  "requires": true,\n  "packages": {\n    "": {\n      "name": "f",\n      "version": "2026.1.0",\n      "dependencies": {}\n    }\n  }\n}\n' > frontend/package-lock.json
    printf 'apiVersion: v2\nname: h\nversion: 2026.1.0\n' > deploy/helm/helper2/Chart.yaml
    for f in public admin common; do printf 'openapi: 3.1.0\ninfo:\n  title: %s\n  version: 2026.1.0\n' "$f" > "api/$f.yaml"; done
    echo d > docs/x.md
    g init -q . && g add -A && g commit -qm init
    f=0
    bump 2026.1.1 > /dev/null && g commit -qam bump
    check "the bump alone" pass || f=$((f + 1))
    sed -i.bak 's/"type": "module"/"type": "commonjs"/' frontend/package.json && rm frontend/package.json.bak && g commit -qam other
    check "another line of a version file" fail || f=$((f + 1))
    bump 2026.1.2 > /dev/null
    echo e >> docs/x.md && g commit -qam both
    check "the bump and another file" fail || f=$((f + 1))
    sed -i.bak 's/^version = "2026.1.2"$/version = "2026.1.3"/' backend/build.gradle.kts && rm backend/build.gradle.kts.bak && g commit -qam half
    check "one version file only" fail || f=$((f + 1))
    echo f >> docs/x.md && g commit -qam docs
    check "no version change" fail || f=$((f + 1))
    exit "$f"
  ) || fails=$?
  rm -rf "$repo"
  if ((fails > 0)); then
    echo "bump-version.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "bump-version.sh: all cases pass"
}

case ${1:-} in
  bump) (($# == 2)) || { echo "usage: bump-version.sh bump <YYYY.N.P>" >&2; exit 2; }; bump "$2" ;;
  only-version-files) (($# == 3)) || { echo "usage: bump-version.sh only-version-files <base> <head>" >&2; exit 2; }; only_version_files "$2" "$3" ;;
  version-only) (($# == 3)) || { echo "usage: bump-version.sh version-only <base> <head>" >&2; exit 2; }; version_only "$2" "$3" ;;
  --self-test) self_test ;;
  *)
    echo "usage: bump-version.sh bump <YYYY.N.P> | only-version-files <base> <head> | version-only <base> <head> | --self-test" >&2
    exit 2
    ;;
esac
