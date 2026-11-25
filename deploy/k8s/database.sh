#!/usr/bin/env bash
set -euo pipefail

POLL=${POLL:-10}
TRIES=${TRIES:-180}
DUMP_NAME='^(searchavo|helper2)-[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{6}Z\.sql\.gz$'
READER=pg-restore

namespace_ok() { [[ $1 =~ ^searchavo-(staging|prod)$ ]]; }
source_ok() { [[ $1 =~ ^(helper2|searchavo)-(staging|prod)$ ]]; }

job_state() {
  kubectl -n "$1" get job "$2" -o json | jq -r '
    [.status.conditions[]? | select(.status == "True") | .type] as $c
    | if ($c | index("Complete")) then "complete" elif ($c | index("Failed")) then "failed" else "running" end'
}

dump() {
  local ns=$1 version=$2 job state name log start
  namespace_ok "$ns" || {
    echo "database.sh: $ns is not searchavo-staging or searchavo-prod" >&2
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
  log=$(kubectl -n "$ns" logs "job/$job" -c dump) || {
    echo "database.sh: the log of $job cannot be read: no dump for $version, the release stops here" >&2
    return 1
  }
  name=$(sed -n 's|^dump: /backup/||p' <<< "$log" | tail -n 1)
  if [[ -z $name ]]; then
    # A pg-backup CronJob whose dump step prints no dump line still lists /backup: its dump is the newest file
    # named no earlier than the job's start.
    start=$(kubectl -n "$ns" get job "$job" -o jsonpath='{.status.startTime}') || start=
    start=${start//:/}
    [[ -z $start ]] || name=$(grep -oE 'searchavo-[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{6}Z\.sql\.gz' <<< "$log" | sort -u |
      awk -v from="searchavo-$start" '$0 >= from' | tail -n 1)
  fi
  [[ $name =~ $DUMP_NAME ]] || {
    echo "database.sh: the dump job $job names no dump in its log" >&2
    return 1
  }
  echo "$name"
}

apps_stopped() {
  # Called in an || list, so errexit is off here: every read that fails returns 1 itself, as a running app does.
  local ns=$1 prefix=${1%-*} app running jobs
  for app in $prefix $prefix-backoffice $prefix-canary; do
    running=$(kubectl -n "$ns" get pods -l "app=$app" -o name) || {
      echo "database.sh: the pods of $app in $ns cannot be read" >&2
      return 1
    }
    [[ -z $running ]] || {
      echo "database.sh: $app still runs in $ns ($running)" >&2
      return 1
    }
  done
  jobs=$(kubectl -n "$ns" get jobs -o json) || {
    echo "database.sh: the jobs in $ns cannot be read" >&2
    return 1
  }
  running=$(jq -r --arg prefix "$prefix" '.items[] | select(.metadata.name | test("^\($prefix)-(canary-)?migrate-")) | select((.status.active // 0) > 0) | .metadata.name' <<< "$jobs") || {
    echo "database.sh: the jobs in $ns do not read as a job list" >&2
    return 1
  }
  [[ -z $running ]] || {
    echo "database.sh: the migrate job $running runs in $ns" >&2
    return 1
  }
}

restore() {
  local ns=$1 file=$2 typed=$3 image
  namespace_ok "$ns" || {
    echo "database.sh: $ns is not searchavo-staging or searchavo-prod" >&2
    return 1
  }
  [[ $typed == "$ns" ]] || {
    echo "database.sh: the namespace typed ('$typed') is not $ns" >&2
    return 1
  }
  [[ $file =~ $DUMP_NAME ]] || {
    echo "database.sh: $file is not a dump name (searchavo- or helper2-<UTC date>T<UTC time>Z.sql.gz)" >&2
    return 1
  }
  apps_stopped "$ns" || {
    echo "stop them first: kubectl -n $ns scale deployment/searchavo deployment/searchavo-backoffice --replicas=0, and make uncanary" >&2
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
    kubectl -n "$ns" exec -i postgres-0 -- psql -U searchavo -d searchavo -v ON_ERROR_STOP=1 -q
  echo "$ns runs $file. The apps stay stopped: bring back the release that runs on this schema (make ENV=${ns#searchavo-} rollback, or a deploy of that release)"
}

# The exact row count of every table in public, one "table|count" line each, sorted.
row_counts() {
  local ns=$1 db=${1%-*} sql
  sql=$(kubectl -n "$ns" exec postgres-0 -- psql -U "$db" -d "$db" -v ON_ERROR_STOP=1 -Atc \
    "select string_agg(format('select %L, count(*) from public.%I', table_name, table_name), ' union all ' order by table_name) from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'")
  [[ -n $sql ]] || {
    echo "database.sh: $ns has no table in public" >&2
    return 1
  }
  kubectl -n "$ns" exec postgres-0 -- psql -U "$db" -d "$db" -v ON_ERROR_STOP=1 -At -F '|' -c "$sql" | sort
}

# The whole database of FROM as SQL for the searchavo database: one transaction that replaces the public schema.
# The dump is the one pg-backup writes (pg_dump --no-owner); the default privileges it grants for FROM's own role
# are granted for searchavo. COMMIT is written only after pg_dump finished, so a cut stream changes nothing.
move_stream() {
  local from=$1 db=${1%-*}
  printf 'BEGIN;\nDROP SCHEMA public CASCADE;\nCREATE SCHEMA public AUTHORIZATION pg_database_owner;\nGRANT USAGE ON SCHEMA public TO PUBLIC;\n'
  kubectl -n "$from" exec postgres-0 -- pg_dump -U "$db" -d "$db" --no-owner |
    sed -E "s/^(ALTER DEFAULT PRIVILEGES FOR ROLE) $db /\1 searchavo /" || return 1
  printf 'COMMIT;\n'
}

move() {
  local from=$1 to=$2 typed=$3 before after
  source_ok "$from" || {
    echo "database.sh: $from is not helper2- or searchavo-staging or -prod" >&2
    return 1
  }
  namespace_ok "$to" || {
    echo "database.sh: $to is not searchavo-staging or searchavo-prod" >&2
    return 1
  }
  [[ $from != "$to" ]] || {
    echo "database.sh: $from is the namespace it moves to" >&2
    return 1
  }
  [[ ${from##*-} == "${to##*-}" ]] || {
    echo "database.sh: a move stays in its environment: $from cannot move to $to" >&2
    return 1
  }
  [[ $typed == "$to" ]] || {
    echo "database.sh: the namespace typed ('$typed') is not $to" >&2
    return 1
  }
  apps_stopped "$from" || {
    echo "stop them first, so that nothing writes while the database is copied: kubectl -n $from scale deployment/${from%-*} deployment/${from%-*}-backoffice --replicas=0" >&2
    return 1
  }
  apps_stopped "$to" || {
    echo "stop them first: kubectl -n $to scale deployment/searchavo deployment/searchavo-backoffice --replicas=0, and make uncanary" >&2
    return 1
  }
  before=$(row_counts "$from") || {
    echo "database.sh: the tables of $from cannot be counted: nothing was changed" >&2
    return 1
  }
  echo "moving the database of $from into $to: the whole database of $to is replaced, in one transaction"
  move_stream "$from" | kubectl -n "$to" exec -i postgres-0 -- psql -U searchavo -d searchavo -v ON_ERROR_STOP=1 -q || {
    echo "database.sh: the copy did not finish: $to rolled back and keeps its database" >&2
    return 1
  }
  after=$(row_counts "$to") || {
    echo "database.sh: the tables of $to cannot be counted after the move" >&2
    return 1
  }
  [[ $before == "$after" ]] || {
    echo "database.sh: the row counts of $to differ from $from: keep the apps of $to stopped and check" >&2
    diff <(echo "$before") <(echo "$after") >&2 || true
    return 1
  }
  echo "$to runs the database of $from ($(wc -l <<< "$after" | tr -d ' ') tables, every row count equal). $from is unchanged, its apps stay stopped."
  echo "Bring up $to with a deploy of the release that runs on this schema"
}

fake_cluster() {
  cat > "$1/kubectl" << 'SH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DIR/calls"
case " $* " in
  *" get job pg-backup-release-"*" jsonpath="*) printf '%s' "${FAKE_START-2026-11-23T01:30:00Z}" ;;
  *" get job pg-backup-release-"*)
    [[ -z ${FAKE_NO_JOB:-} || -e $FAKE_DIR/created ]] || exit 1
    printf '{"status": {"conditions": [{"type": "%s", "status": "True"}]}}\n' "${FAKE_DUMP_JOB:-Complete}" ;;
  *" logs job/"*) printf '%s\n' "${FAKE_DUMP_LOG-dump: /backup/searchavo-2026-11-23T013000Z.sql.gz}" ;;
  *" get pods -l app="*)
    [[ -z ${FAKE_PODS_FAIL:-} ]] || exit 1
    all="$*"
    app=${all##*app=}
    app=${app%% *}
    [[ " ${FAKE_RUNNING:-} " != *" $app "* ]] || echo "pod/$app-1"
    if [[ $app == searchavo && -n ${FAKE_STARTS_AT_SECOND_CHECK:-} ]] && [[ -e $FAKE_DIR/checked ]]; then echo "pod/searchavo-2"; fi
    [[ $app != searchavo-canary ]] || touch "$FAKE_DIR/checked"
    ;;
  *" get jobs -o json "*)
    [[ -z ${FAKE_JOBS_FAIL:-} ]] || exit 1
    printf '{"items": [%s]}\n' "${FAKE_JOBS:-}" ;;
  *" get statefulset postgres "*) echo harbor/postgres:18 ;;
  *" exec pg-restore -- sh -ec test -f "*) [[ ${FAKE_DUMP:-whole} == whole ]] ;;
  *" exec pg-restore -- sh -ec printf "*) echo "-- the dump" ;;
  *" exec -i postgres-0 "*) cat > "$FAKE_DIR/loaded" ;;
  *" exec postgres-0 -- pg_dump "*)
    printf 'CREATE TABLE public.resource (id bigint);\nALTER DEFAULT PRIVILEGES FOR ROLE helper2 IN SCHEMA public GRANT SELECT ON TABLES TO h2_backoffice;\n'
    [[ -z ${FAKE_PGDUMP_FAIL:-} ]] || exit 1
    echo '-- PostgreSQL database dump complete' ;;
  *" exec postgres-0 -- psql "*"string_agg"*) [[ -z ${FAKE_COUNT_FAIL:-} ]] || exit 1; echo "select 'resource', count(*) from public.resource" ;;
  *" -n searchavo-"*" exec postgres-0 -- psql "*) printf 'resource|%s\n' "${FAKE_TARGET_ROWS:-3}" ;;
  *" exec postgres-0 -- psql "*) echo 'resource|3' ;;
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
      untouched) [[ $got == fail && ! -e $dir/loaded ]] && ! grep -q 'exec -i postgres-0' "$dir/calls" 2> /dev/null && got=untouched ;;
      named) [[ $got == ok && ${out##*$'\n'} == searchavo-2026-11-23T013000Z.sql.gz ]] && got=named ;;
      made) [[ $got == ok && -e $dir/created && ${out##*$'\n'} == searchavo-2026-11-23T013000Z.sql.gz ]] && got=made ;;
      kept) [[ $got == ok && ! -e $dir/created ]] && ! grep -q ' delete job' "$dir/calls" && got=kept ;;
      moved) [[ $got == ok ]] && head -n 2 "$dir/loaded" | grep -q '^DROP SCHEMA public CASCADE;' &&
        grep -q '^ALTER DEFAULT PRIVILEGES FOR ROLE searchavo IN SCHEMA public' "$dir/loaded" &&
        ! grep -q 'FOR ROLE helper2' "$dir/loaded" && [[ $(tail -n 1 "$dir/loaded") == 'COMMIT;' ]] && got=moved ;;
      rolledback) [[ $got == fail && -e $dir/loaded ]] && ! grep -q '^COMMIT;' "$dir/loaded" && got=rolledback ;;
    esac
    if [[ $got == "$want" ]]; then
      echo "ok   $name"
    else
      echo "FAIL $name: got $got, want $want: $out" >&2
      fails=$((fails + 1))
    fi
  done << 'EOF_CASES'
restore with the apps stopped and a whole dump|loaded|NONE=1|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses another namespace|untouched|NONE=1|restore default searchavo-2026-11-23T013000Z.sql.gz default
restore refuses a namespace typed wrong|untouched|NONE=1|restore searchavo-prod searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses a name that is not a dump|untouched|NONE=1|restore searchavo-staging ../etc/passwd searchavo-staging
restore refuses a running public app|untouched|FAKE_RUNNING=searchavo|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses a running back office|untouched|FAKE_RUNNING=searchavo-backoffice|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses a canary|untouched|FAKE_RUNNING=searchavo-canary|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses a running migrate job|untouched|FAKE_JOBS={"metadata":{"name":"searchavo-migrate-9"},"status":{"active":1}}|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses an app that started while the dump was read|untouched|FAKE_STARTS_AT_SECOND_CHECK=1|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses a dump that does not read back in full|untouched|FAKE_DUMP=cut|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses when the pods cannot be read|untouched|FAKE_PODS_FAIL=1|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
restore refuses when the jobs cannot be read|untouched|FAKE_JOBS_FAIL=1|restore searchavo-staging searchavo-2026-11-23T013000Z.sql.gz searchavo-staging
dump names the dump of the release|named|NONE=1|dump searchavo-prod 2026.11.12
dump runs pg-backup once when the release has no dump yet|made|FAKE_NO_JOB=1|dump searchavo-prod 2026.11.12
dump of a rerun keeps the dump of the earlier run, taken before its canary|kept|NONE=1|dump searchavo-prod 2026.11.12
dump fails with a failed job|fail|FAKE_DUMP_JOB=Failed|dump searchavo-prod 2026.11.12
dump fails with a job that does not finish|fail|FAKE_DUMP_JOB=Suspended|dump searchavo-prod 2026.11.12
dump fails when the log names no dump|fail|FAKE_DUMP_LOG=done|dump searchavo-prod 2026.11.12
dump of a CronJob with no dump line names the newest file from its start|named|FAKE_DUMP_LOG=-rw-r--r--_70_9_searchavo-2026-11-22T013000Z.sql.gz_-rw-r--r--_70_9_searchavo-2026-11-23T013000Z.sql.gz|dump searchavo-prod 2026.11.12
dump of a CronJob with no dump line fails when no file is from its start|fail|FAKE_DUMP_LOG=-rw-r--r--_70_9_searchavo-2026-11-22T013000Z.sql.gz|dump searchavo-prod 2026.11.12
dump refuses a version that is not YYYY.N.P|fail|NONE=1|dump searchavo-prod 2026.11
move helper2 staging into searchavo staging, both stopped|moved|NONE=1|move helper2-staging searchavo-staging searchavo-staging
move refuses a target that is not searchavo|untouched|NONE=1|move helper2-staging helper2-prod helper2-prod
move refuses a source that is not a known namespace|untouched|NONE=1|move default searchavo-staging searchavo-staging
move refuses the same namespace|untouched|NONE=1|move searchavo-staging searchavo-staging searchavo-staging
move refuses staging into prod|untouched|NONE=1|move helper2-staging searchavo-prod searchavo-prod
move refuses a namespace typed wrong|untouched|NONE=1|move helper2-prod searchavo-prod searchavo-staging
move refuses a source app that still writes|untouched|FAKE_RUNNING=helper2|move helper2-staging searchavo-staging searchavo-staging
move refuses a source back office that still writes|untouched|FAKE_RUNNING=helper2-backoffice|move helper2-staging searchavo-staging searchavo-staging
move refuses a running target app|untouched|FAKE_RUNNING=searchavo|move helper2-staging searchavo-staging searchavo-staging
move refuses a source migrate job|untouched|FAKE_JOBS={"metadata":{"name":"helper2-migrate-9"},"status":{"active":1}}|move helper2-staging searchavo-staging searchavo-staging
move refuses when the source tables cannot be counted|untouched|FAKE_COUNT_FAIL=1|move helper2-staging searchavo-staging searchavo-staging
move of a dump that stops halfway never commits|rolledback|FAKE_PGDUMP_FAIL=1|move helper2-staging searchavo-staging searchavo-staging
move fails when a row count differs after the copy|fail|FAKE_TARGET_ROWS=2|move helper2-staging searchavo-staging searchavo-staging
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
  echo "       database.sh move <from namespace> <to namespace> <the to namespace again>" >&2
  echo "       database.sh --self-test" >&2
  exit 2
}

case ${1:-} in
  --self-test) self_test ;;
  dump) [[ $# == 3 ]] || usage && dump "$2" "$3" ;;
  restore) [[ $# == 4 ]] || usage && restore "$2" "$3" "$4" ;;
  move) [[ $# == 4 ]] || usage && move "$2" "$3" "$4" ;;
  *) usage ;;
esac
