#!/usr/bin/env bash
set -euo pipefail

frontend=${1:?usage: package.sh <frontend dir with dist and dist-admin> <out dir>}
out=${2:?usage: package.sh <frontend dir with dist and dist-admin> <out dir>}
root=$(git rev-parse --show-toplevel)
admin="$root/backend/apps/backoffice/src/main/resources/static/admin"

[[ -f $frontend/dist/index.html && -f $frontend/dist-admin/admin.html ]] \
  || { echo "$frontend must hold dist/ and dist-admin/ from the frontend-build job" >&2; exit 1; }

rm -rf "$admin" "$out"
mkdir -p "$admin" "$out/web"
cp -R "$frontend/dist-admin/." "$admin/"

(cd "$root/backend" && ./gradlew --no-daemon :apps:public:bootJar :apps:backoffice:bootJar)

only_jar() {
  local jars=("$root/backend/apps/$1/build/libs/"*.jar)
  [[ ${#jars[@]} == 1 && -f ${jars[0]} ]] || { echo "apps/$1 built ${#jars[@]} jars, expected one" >&2; exit 1; }
  cp "${jars[0]}" "$out/$1.jar"
}
only_jar public
only_jar backoffice
cp -R "$frontend/dist/." "$out/web/"

ls -la "$out" "$out/web"
