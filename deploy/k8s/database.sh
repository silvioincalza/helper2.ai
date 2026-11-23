#!/usr/bin/env bash
set -euo pipefail

POLL=${POLL:-10}
TRIES=${TRIES:-180}
DUMP_NAME='^helper2-[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{6}Z\.sql\.gz$'
APPS='helper2 helper2-backoffice helper2-canary'
READER=pg-restore

namespace_ok() { [[ $1 =~ ^helper2-(staging|prod)$ ]]; }

job_state() {
  kubectl -n "$1" get job "$2" -o json | jq -r '
    [.status.conditions[]? | select(.status == "True") | .type] as $c
    | if ($c | index("Complete")) then "complete" elif ($c | index("Failed")) then "failed" else "running" end'
}

dump() {
  local ns=$1 version=$2 job state name
  namespace_ok "$ns" || {
    echo "database.sh: $ns is not helper2-staging or helper2-prod" >&2
    return 1
  }
  [[ $version =~ ^[0-9]{4}\.[1-9][0-9]*\.(0|[1-9][0-9]*)$ ]] || {
    echo "database.sh: version must be YYYY.N.P, got '$version'" >&2
    return 1
  }
  job=pg-backup-release-${version//./-}
  state=none
  if kubectl -n "$ns" get job "$job" -o name > /dev/null 2>&1; then
    state=$(job_state "$ns" "$job")
  fi
  if [[ $state == complete ]]; then
    echo "database.sh: $job finished in an earlier run: its dump, taken before that run's canary, stays the way back" >&2
  else
    kubectl -n "$ns" delete job "$job" --ignore-not-found --wait > /dev/null
    kubectl -n "$ns" create job --from=cronjob/pg-backup "$job" > /dev/null
  fi
  for ((i = 0; i < TRIES; i++)); do
    state=$(job_state "$ns" "$job")
    [[ $state == running ]] || break
    sleep "$POLL"
  done
  [[ $state == complete ]] || {
    echo "database.sh: the dump job $job is $state: no dump for $version, the release stops here" >&2
    return 1
  }
  name=$(kubectl -n "$ns" logs "job/$job" -c dump | sed -n 's|^dump: /backup/||p' | tail -n 1)
  [[ $name =~ $DUMP_NAME ]] || {
    echo "database.sh: the dump job $job names no dump in its log" >&2
    return 1
  }
  echo "$name"
}

apps_stopped() {
  local ns=$1 app running
  for app in $APPS; do
    running=$(kubectl -n "$ns" get pods -l "app=$app" -o name)
    [[ -z $running ]] || {
      echo "database.sh: $app still runs in $ns ($running)" >&2
      return 1
    }
  done
  running=$(kubectl -n "$ns" get jobs -o json | jq -r '.items[] | select(.metadata.name | test("^helper2-(canary-)?migrate-")) | select((.status.active // 0) > 0) | .metadata.name')
  [[ -z $running ]] || {
    echo "database.sh: the migrate job $running runs in $ns" >&2
    return 1
  }
}

restore() {
  local ns=$1 file=$2 typed=$3 image
  namespace_ok "$ns" || {
    echo "database.sh: $ns is not helper2-staging or helper2-prod" >&2
    return 1
  }
  [[ $typed == "$ns" ]] || {
    echo "database.sh: the namespace typed ('$typed') is not $ns" >&2
    return 1
  }
  [[ $file =~ $DUMP_NAME ]] || {
    echo "database.sh: $file is not a dump name (helper2-<UTC date>T<UTC time>Z.sql.gz)" >&2
    return 1
  }
  apps_stopped "$ns" || {
    echo "stop them first: kubectl -n $ns scale deployment/helper2 deployment/helper2-backoffice --replicas=0, and make uncanary" >&2
    return 1
  }
  image=$(kubectl -n "$ns" get statefulset postgres -o jsonpath='{.spec.template.spec.containers[0].image}')
  trap 'kubectl -n "$ns" delete pod "$READER" --ignore-not-found --wait=false > /dev/null' RETURN
  kubectl -n "$ns" apply -f - > /dev/null << EOF
apiVersion: v1
kind: Pod
metadata: { name: $READER, labels: { app: $READER } }
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: { runAsNonRoot: true, runAsUser: 70, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: shell
      image: $image
      command: [sleep, "3600"]
      securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: [ALL] } }
      volumeMounts: [{ name: backup, mountPath: /backup, readOnly: true }]
  volumes: [{ name: backup, persistentVolumeClaim: { claimName: pg-backup } }]
EOF
  kubectl -n "$ns" wait --for=condition=Ready "pod/$READER" --timeout=5m > /dev/null
  kubectl -n "$ns" exec "$READER" -- sh -ec "test -f /backup/$file; gzip -t /backup/$file; gzip -dc /backup/$file | tail -n 5 | grep -q '^-- PostgreSQL database dump complete'" ||
    {
      echo "database.sh: /backup/$file is missing or does not read back in full: nothing was changed" >&2
      return 1
    }
  apps_stopped "$ns" || {
    echo "database.sh: an app started while the dump was read: nothing was changed" >&2
    return 1
  }
  echo "restoring $ns from $file: the whole database is replaced, in one transaction"
  kubectl -n "$ns" exec "$READER" -- sh -ec "printf 'BEGIN;\nDROP SCHEMA public CASCADE;\nCREATE SCHEMA public AUTHORIZATION pg_database_owner;\nGRANT USAGE ON SCHEMA public TO PUBLIC;\n'; gzip -dc /backup/$file; printf 'COMMIT;\n'" |
    kubectl -n "$ns" exec -i postgres-0 -- psql -U helper2 -d helper2 -v ON_ERROR_STOP=1 -q
  echo "$ns runs $file. The apps stay stopped: bring back the release that runs on this schema (make ENV=${ns#helper2-} rollback, or a deploy of that release)"
}

fake_cluster() {
  cat > "$1/kubectl" << 'SH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DIR/calls"
case " $* " in
  *" get job pg-backup-release-"*)
    [[ -z ${FAKE_NO_JOB:-} || -e $FAKE_DIR/created ]] || exit 1
    printf '{"status": {"conditions": [{"type": "%s", "status": "True"}]}}\n' "${FAKE_DUMP_JOB:-Complete}" ;;
  *" logs job/"*) printf '%s\n' "${FAKE_DUMP_LOG-dump: /backup/helper2-2026-11-23T013000Z.sql.gz}" ;;
  *" get pods -l app="*)
    all="$*"
    app=${all##*app=}
    app=${app%% *}
    [[ " ${FAKE_RUNNING:-} " != *" $app "* ]] || echo "pod/$app-1"
    if [[ $app == helper2 && -n ${FAKE_STARTS_AT_SECOND_CHECK:-} ]] && [[ -e $FAKE_DIR/checked ]]; then echo "pod/helper2-2"; fi
    [[ $app != helper2-canary ]] || touch "$FAKE_DIR/checked"
    ;;
  *" get jobs -o json "*) printf '{"items": [%s]}\n' "${FAKE_JOBS:-}" ;;
  *" get statefulset postgres "*) echo harbor/postgres:18 ;;
  *" exec pg-restore -- sh -ec test -f "*) [[ ${FAKE_DUMP:-whole} == whole ]] ;;
  *" exec pg-restore -- sh -ec printf "*) echo "-- the dump" ;;
  *" exec -i postgres-0 "*) cat > "$FAKE_DIR/loaded" ;;
  *" create job "*) touch "$FAKE_DIR/created" ;;
  *" delete "* | *" apply "* | *" wait "*) cat > /dev/null 2>&1 || true ;;
  *) echo "fake kubectl: unexpected $*" >&2; exit 2 ;;
esac
SH
  chmod +x "$1/kubectl"
}

self_test() {
  local fails=0 dir name want env out
  dir=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$dir'" EXIT
  mkdir "$dir/bin"
  fake_cluster "$dir/bin"
  while IFS='|' read -r name want env args; do
    rm -f "$dir/calls" "$dir/loaded" "$dir/checked" "$dir/created"
    # shellcheck disable=SC2086
    if out=$(env -i PATH="$dir/bin:$PATH" FAKE_DIR="$dir" POLL=0 TRIES=3 $env "$0" $args 2>&1 < /dev/null); then got=ok; else got=fail; fi
    case $want in
      loaded) [[ $got == ok && -s $dir/loaded ]] && ! grep -q -- --single-transaction "$dir/calls" &&
        grep 'exec pg-restore -- sh -ec printf' "$dir/calls" | grep -q "^.*'BEGIN;.*DROP SCHEMA public CASCADE;.*gzip -dc /backup/[^;]*; printf 'COMMIT;.n'\$" && got=loaded ;;
      untouched) [[ $got == fail && ! -e $dir/loaded ]] && ! grep -q 'postgres-0' "$dir/calls" 2> /dev/null && got=untouched ;;
      named) [[ $got == ok && ${out##*$'\n'} == helper2-2026-11-23T013000Z.sql.gz ]] && got=named ;;
      made) [[ $got == ok && -e $dir/created && ${out##*$'\n'} == helper2-2026-11-23T013000Z.sql.gz ]] && got=made ;;
      kept) [[ $got == ok && ! -e $dir/created ]] && ! grep -q ' delete job' "$dir/calls" && got=kept ;;
    esac
    if [[ $got == "$want" ]]; then
      echo "ok   $name"
    else
      echo "FAIL $name: got $got, want $want: $out" >&2
      fails=$((fails + 1))
    fi
  done << 'EOF_CASES'
restore with the apps stopped and a whole dump|loaded|NONE=1|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses another namespace|untouched|NONE=1|restore default helper2-2026-11-23T013000Z.sql.gz default
restore refuses a namespace typed wrong|untouched|NONE=1|restore helper2-prod helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses a name that is not a dump|untouched|NONE=1|restore helper2-staging ../etc/passwd helper2-staging
restore refuses a running public app|untouched|FAKE_RUNNING=helper2|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses a running back office|untouched|FAKE_RUNNING=helper2-backoffice|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses a canary|untouched|FAKE_RUNNING=helper2-canary|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses a running migrate job|untouched|FAKE_JOBS={"metadata":{"name":"helper2-migrate-9"},"status":{"active":1}}|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses an app that started while the dump was read|untouched|FAKE_STARTS_AT_SECOND_CHECK=1|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
restore refuses a dump that does not read back in full|untouched|FAKE_DUMP=cut|restore helper2-staging helper2-2026-11-23T013000Z.sql.gz helper2-staging
dump names the dump of the release|named|NONE=1|dump helper2-prod 2026.11.12
dump runs pg-backup once when the release has no dump yet|made|FAKE_NO_JOB=1|dump helper2-prod 2026.11.12
dump of a rerun keeps the dump of the earlier run, taken before its canary|kept|NONE=1|dump helper2-prod 2026.11.12
dump fails with a failed job|fail|FAKE_DUMP_JOB=Failed|dump helper2-prod 2026.11.12
dump fails with a job that does not finish|fail|FAKE_DUMP_JOB=Suspended|dump helper2-prod 2026.11.12
dump fails when the log names no dump|fail|FAKE_DUMP_LOG=done|dump helper2-prod 2026.11.12
dump refuses a version that is not YYYY.N.P|fail|NONE=1|dump helper2-prod 2026.11
EOF_CASES
  if [[ $fails -gt 0 ]]; then
    echo "database.sh: $fails case(s) failed" >&2
    exit 1
  fi
  echo "database.sh: all cases pass"
}

usage() {
  echo "usage: database.sh dump <namespace> <version>" >&2
  echo "       database.sh restore <namespace> <dump> <the namespace again>" >&2
  echo "       database.sh --self-test" >&2
  exit 2
}

case ${1:-} in
  --self-test) self_test ;;
  dump) [[ $# == 3 ]] || usage && dump "$2" "$3" ;;
  restore) [[ $# == 4 ]] || usage && restore "$2" "$3" "$4" ;;
  *) usage ;;
esac
