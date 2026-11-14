#!/usr/bin/env bash
set -euo pipefail

FILES=(backend/build.gradle.kts frontend/package.json frontend/package-lock.json deploy/helm/helper2/Chart.yaml api/public.yaml api/admin.yaml api/common.yaml)

current() {
  sed -n 's/^version = "\(.*\)"$/\1/p' backend/build.gradle.kts
}

bump() {
  local to=$1 from file
  [[ $to =~ ^[0-9]{4}\.[1-9][0-9]*\.(0|[1-9][0-9]*)$ ]] || { echo "the version must be YYYY.N.P, got '$to'" >&2; return 1; }
  from=$(current)
  [[ -n $from ]] || { echo "backend/build.gradle.kts names no version" >&2; return 1; }
  [[ $from != "$to" ]] || { echo "the files carry $to already" >&2; return 1; }
  sed -i.bak "s/^version = \"${from//./\\.}\"\$/version = \"$to\"/" backend/build.gradle.kts
  sed -i.bak "s/^version: ${from//./\\.}\$/version: $to/" deploy/helm/helper2/Chart.yaml
  sed -i.bak "s/^  version: ${from//./\\.}\$/  version: $to/" api/public.yaml api/admin.yaml api/common.yaml
  sed -i.bak "4s/^  \"version\": \"${from//./\\.}\",\$/  \"version\": \"$to\",/" frontend/package.json
  sed -i.bak -e "3s/^  \"version\": \"${from//./\\.}\",\$/  \"version\": \"$to\",/" \
    -e "9s/^      \"version\": \"${from//./\\.}\",\$/      \"version\": \"$to\",/" frontend/package-lock.json
  for file in "${FILES[@]}"; do rm -f "$file.bak"; done
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

case ${1:-} in
  bump) (($# == 2)) || { echo "usage: bump-version.sh bump <YYYY.N.P>" >&2; exit 2; }; bump "$2" ;;
  only-version-files) (($# == 3)) || { echo "usage: bump-version.sh only-version-files <base> <head>" >&2; exit 2; }; only_version_files "$2" "$3" ;;
  *)
    echo "usage: bump-version.sh bump <YYYY.N.P> | only-version-files <base> <head>" >&2
    exit 2
    ;;
esac
