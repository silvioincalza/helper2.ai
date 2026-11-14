# Deploy on Kubernetes

The Helm chart [`../helm/helper2`](../helm/helper2) runs Helper2.ai on any Kubernetes cluster: the app,
Postgres with pgvector and Redis, one release `helper2` per namespace. The chart creates no Ingress: you
route your hosts to its Services with your own Ingress. A nightly database backup is optional.

## Requirements

- Kubernetes 1.27 or later (the backup CronJob uses `timeZone`) and Helm 4: CI checks the chart with Helm 4, and
  the Makefile uses its flags (`--server-side`, `--rollback-on-failure`).
- A container registry that the cluster can pull from. Every pod runs as the ServiceAccount `helper2`, which the
  chart creates: a pull secret on the namespace's `default` ServiceAccount reaches no pod. For a private registry,
  create the pull secret in the namespace before the install and name it in `imagePullSecrets`
  (see [step 2](#2-configure)). Empty (the default) = a public registry, or nodes that hold the credential.
- A storage class for `ReadWriteOnce` volumes. Empty `storageClassName` = the cluster default.
- An Ingress controller, and an Ingress of your own for the app (see [step 3](#3-install)).
- A CNI that enforces NetworkPolicy. Without one, the policies are created but do nothing.
- A Nebius Token Factory API key for the AI guide. Without it, search works and the guide shows plain
  search results instead.

## 1. Build and push the images

From the repository root:

```bash
REGISTRY=ghcr.io/<owner>
TAG=$(git rev-parse --short=12 HEAD)
PG_TAG=18.6-pgvector0.8.6-7  # postgres.image.tag in the chart's values.yaml

podman build --platform linux/amd64 -f deploy/web.Dockerfile -t $REGISTRY/helper2-web:$TAG .
podman build --platform linux/amd64 -f deploy/Dockerfile -t $REGISTRY/helper2:$TAG .
podman build --platform linux/amd64 -f deploy/backoffice.Dockerfile -t $REGISTRY/helper2-backoffice:$TAG .
podman build --platform linux/amd64 -t $REGISTRY/helper2-postgres:$PG_TAG deploy/postgres
podman push $REGISTRY/helper2-web:$TAG
podman push $REGISTRY/helper2:$TAG
podman push $REGISTRY/helper2-backoffice:$TAG
podman push $REGISTRY/helper2-postgres:$PG_TAG
```

Three images, one tag ([ADR 0043](../../docs/adr/operations/0043-runtime-topology-helm-chart-three-deployables.md)): `helper2-web` holds the
public web app's files (nginx) and the HTML shell, `helper2` is the public app (search, guide, the pages, sign-in
for users), `helper2-backoffice` the back office (admin, Scout, collectors, enrichment). Each public pod copies the
shell from `helper2-web` of the same tag when it starts, so build and push all three. Both apps use the same
database, Redis and app.env; the web pods use none of them. Docker works the same way. Tags are never rebuilt or
moved: one commit, one image.

## 2. Configure

**`app.env`** (never commit it): copy [`app.env.example`](app.env.example) and fill in the values. When the
example gains keys later, `scripts/env-align.sh deploy/k8s/app.env.example app.env` appends only the ones app.env
does not have yet, with their comments; it never changes a key that is there (`--dry-run` lists them first). It must
contain six different passwords of 32+ characters (`openssl rand -hex 32`;
[ADR 0004](../../docs/adr/foundation/0004-persistence-and-the-schema.md)). The three database ones may also hold base64 characters
(`+ / = _ -`); the three Redis ones only letters and digits: `H2_DATABASE_PASSWORD` (the owner's: Postgres, the
backup and the migrate Job only), `H2_DATABASE_PUBLIC_PASSWORD` and `H2_DATABASE_BACKOFFICE_PASSWORD` (each app's
own role), `H2_REDIS_PASSWORD` (Redis' default user, the admin), and `H2_REDIS_PUBLIC_PASSWORD` and
`H2_REDIS_BACKOFFICE_PASSWORD` (each app's own Redis user). With `redis.auth.existingSecret` (or
`redis.auth.enabled=false`), Redis does not read app.env: check it with `--no-redis-password`. `make env-secret`
picks the flag from the rendered chart.
It goes into two Secrets that the chart does not create, so no value is kept in Helm's release history: the
passwords into `helper2-credentials`, which no workload loads whole, and the rest into `helper2-app-env`, which
both apps load. Check it, then create or update both:

```bash
deploy/k8s/check-app-env.sh app.env
kubectl create namespace helper2
pw='^H2_(DATABASE|REDIS)(_PUBLIC|_BACKOFFICE)?_PASSWORD='
kubectl -n helper2 create secret generic helper2-credentials --from-env-file=<(grep -E "$pw" app.env) \
  --dry-run=client -o yaml | kubectl -n helper2 apply --server-side --field-manager=helper2-env-secret -f -
kubectl -n helper2 create secret generic helper2-app-env --from-env-file=<(grep -vE "$pw" app.env) \
  --dry-run=client -o yaml | kubectl -n helper2 apply --server-side --field-manager=helper2-env-secret -f -
```

The chart cannot see the Secret change. Pass a new `envSecret.revision` (for example a hash of app.env) to
restart the app, and a new `envSecret.redisPasswordRevision` to restart Redis after a new Redis password.

**Redis password from your own Secret** (`redis.auth.existingSecret`): the chart cannot see that Secret change.
To rotate: update the Secret, raise `redis.auth.revision` by one, deploy. Redis and the app restart and read the
new password.

**`my-values.yaml`**:

```yaml
app:
  environment: prod            # tag on metrics and spans
image:
  repository: ghcr.io/<owner>/helper2
web:
  image:
    repository: ghcr.io/<owner>/helper2-web          # always tagged image.tag, like the public app
backoffice:
  image:
    repository: ghcr.io/<owner>/helper2-backoffice   # its tag is image.tag unless you set backoffice.image.tag
  adminBaseUrl: https://admin.example.com           # the admin host: the sign-in callback is pinned to it
postgres:
  image:
    repository: ghcr.io/<owner>/helper2-postgres
imagePullSecrets:              # private registry only: Secrets of type kubernetes.io/dockerconfigjson in the namespace
  - name: registry-pull
networkPolicy:
  ingressController:           # the pods that may reach the app
    namespace: ingress-nginx
    podLabels: { app.kubernetes.io/name: ingress-nginx }
```

Every value and its default is described in [`values.yaml`](../helm/helper2/values.yaml).

## 3. Install

```bash
helm upgrade --install helper2 deploy/helm/helper2 -n helper2 \
  -f my-values.yaml --set image.tag=$TAG \
  --set envSecret.revision=$(sha256sum < app.env | cut -c1-16) \
  --set envSecret.redisPasswordRevision=$(grep -E '^H2_REDIS_(PUBLIC_|BACKOFFICE_)?PASSWORD=' app.env | sha256sum | cut -c1-16) \
  --wait --wait-for-jobs --timeout 12m --history-max 3
```

Check that it works:

```bash
kubectl -n helper2 get pods
kubectl -n helper2 port-forward deploy/helper2 8081 &
curl -s localhost:8081/actuator/health/readiness    # {"status":"UP"}
```

Route your host to two Services, both on port 80: exactly these three paths to `helper2-web`, and everything
else to `helper2`.

| Path | Path type | Service |
|---|---|---|
| `/assets/` | `Prefix` | `helper2-web` |
| `/theme.js` | `Exact` | `helper2-web` |
| `/favicon.svg` | `Exact` | `helper2-web` |
| `/` | `Prefix` | `helper2` |

The public app serves no web files, and the web pods answer 404 for anything else. A new file in
`frontend/public` needs a new route here and in `deploy/web/nginx.conf` (the image build stops until both are
there). For example:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: { name: helper2, namespace: helper2 }
spec:
  ingressClassName: nginx
  tls: [{ hosts: [helper2.example.com], secretName: helper2-tls }]   # e.g. issued by cert-manager
  rules:
    - host: helper2.example.com
      http:
        paths:
          - { path: /assets/, pathType: Prefix, backend: { service: { name: helper2-web, port: { number: 80 } } } }
          - { path: /theme.js, pathType: Exact, backend: { service: { name: helper2-web, port: { number: 80 } } } }
          - { path: /favicon.svg, pathType: Exact, backend: { service: { name: helper2-web, port: { number: 80 } } } }
          - { path: /, pathType: Prefix, backend: { service: { name: helper2, port: { number: 80 } } } }
```

The back office (`/admin`) is its own Deployment, `helper2-backoffice`, behind the Service `helper2-admin`,
port 80 ([ADR 0036](../../docs/adr/identity/0036-identity-and-the-admin-host.md), [ADR 0043](../../docs/adr/operations/0043-runtime-topology-helm-chart-three-deployables.md)).
Route it from a host that only your own network can reach (for example an Ingress with a source allow-list),
never from the public host. The public app has no admin code and answers 404 on `/admin`. Sign in to the
back office on that host: its GitHub App (or OAuth app) needs the callback
`https://<admin host>/login/oauth2/code/github`.

Then open `https://<host>`. Each release runs the Job `helper2-migrate-<revision>` from the public image: it
migrates the database as the owner and opens both apps' logins. Neither app migrates: each one connects as its own
role and waits at startup, up to 4 minutes, until the Job is done. `--wait-for-jobs` makes the release fail when
the Job does. The
Scout runs in the back office: it starts about 20 seconds after startup and fills the catalog over the next
hours.

To upgrade, build a new tag and run the same `helm upgrade --install` command with it.

## Options

| What | Values |
|---|---|
| Traces (OTLP/HTTP) | `app.tracing.export: true`, `app.tracing.endpoint`, `networkPolicy.egress.otlp.namespace` of the collector |
| Metrics scraping | `networkPolicy.metricsNamespace` of the scraper. The pods carry `prometheus.io/*` annotations (port 8081). |
| Replicas | `app.replicas` (public app, default 3), `backoffice.replicas` (default 1), `web.replicas` (default 2) |
| Scout | `backoffice.scoutEnabled`, `backoffice.scoutCron`, `backoffice.scoutMaxEnrichments` |
| Nightly backup | `backup.enabled: true`, see [Backups](#backups) |
| DNS for the egress policy | `networkPolicy.egress.dns` (default: CoreDNS in `kube-system`) |

The network policy lets only the Ingress controller (`networkPolicy.ingressController`) reach the web pods and
the two apps, on port 8080. Without it, no visitor reaches the app. The app trusts `X-Forwarded-For` from
that edge for its per-client rate limits.

## Operations

The web pods run `web.replicas` replicas, the public app `app.replicas` and the back office
`backoffice.replicas`, each replaced one at a time on upgrade, with a PodDisruptionBudget when there is more than one replica. Every scheduled job runs
in one of the two apps: the Scout, the collectors, the embedding indexer and the catalog jobs in the back
office, the guide, account and search retention in the public app. Each runs on one replica at a time (lock
table `shedlock` in Postgres); rate limits and sign-in sessions are in Redis. What is still per replica:
[F1.10](https://github.com/silvioincalza/helper2.ai/issues/29).

**Never `helm uninstall`:** it deletes the Postgres and Redis StatefulSets and the Secret. Their PVCs
(`data-postgres-0`, and `data-redis-0` with Redis's append-only file) are not owned by the release and stay; the backup PVC has `helm.sh/resource-policy: keep`.
To go back, use `make ENV=<env> rollback`: it picks the last revision Helm marked deployed (a bare
`helm rollback` takes the one just before the current, which after an interrupted rollback is the failed upgrade).
`make deploy` goes back by itself when the release is not healthy after the upgrade
([`check-release.sh`](check-release.sh)): to the revision that was deployed before it, then it fails. The schema
stays as the new release migrated it.

## Backups

With `backup.enabled: true`, the CronJob `pg-backup` (02:30 in `backup.timeZone`) writes
`pg_dump --no-owner | gzip` to `/backup/helper2-<date>.sql.gz` on the PVC `pg-backup` (7 days, kept on
`helm uninstall`). Use a storage class that outlives a node (`backup.storageClassName`). A failed `pg_dump`
fails the Job; the dump replaces the old one only after `gzip -t` and a size check, and old dumps are deleted
after that. `Helper2BackupFailed` fires on a failed Job, `Helper2BackupStale` when no run has succeeded for
36 hours (the stale alert needs one successful run first: check the first night by hand).

**Off-site copy** (`backup.offsite`, off by default): after the dump, rclone encrypts tonight's dump
(`rclone crypt`, with the password in the Secret) and copies it to S3-compatible storage (for example
Cloudflare R2) as `<prefix>/helper2-<date>.sql.gz.bin`. The storage provider never gets the password. rclone never
deletes there; retention is the bucket's lifecycle rule. It copies only a dump younger than `backup.offsite.maxAge`
(24 hours), so a dump the lifecycle rule deleted is not uploaded again: rerun a failed upload the same day. To turn
it on:

1. Create a bucket with a lifecycle rule that deletes after `backup.retentionDays` (7 days, the period the Privacy
   page states) and an access key with read and write access to that bucket only.
2. Create the Secret `pg-backup-offsite` from an env file with five keys: `access_key_id`, `secret_access_key`,
   `endpoint` (the S3 endpoint URL), `bucket` and `crypt_password` (32 or more random letters and digits):
   `kubectl -n helper2 create secret generic pg-backup-offsite --from-env-file=<file>`, then delete the file.
   Keep a second copy of `crypt_password` outside the cluster: without it no off-site dump can be read.
   The hosted helper2.ai keeps the five fields in OpenBao (`helper2/kv`, `backup/r2`) and writes the Secret with
   `make ENV=prod backup-credentials`.
3. Set `backup.offsite.enabled: true`, then upgrade. The Secret must exist first: without it the upload container
   cannot start and the Job fails. Check:
   `kubectl -n helper2 create job --from=cronjob/pg-backup pg-backup-now`, then
   `kubectl -n helper2 logs job/pg-backup-now -c upload`.

**Restore** (replaces the whole database; the app is down in the meantime). The dump is plain SQL with
`CREATE EXTENSION vector` and Flyway's history, so the app starts on it as usual:

```bash
NS=helper2; F=helper2-2026-10-27.sql.gz
kubectl -n $NS scale deployment/helper2 --replicas=0
kubectl -n $NS exec postgres-0 -- psql -U helper2 -d helper2 -v ON_ERROR_STOP=1 \
  -c 'DROP SCHEMA public CASCADE' -c 'CREATE SCHEMA public'

# a) from the PVC, through a throwaway pod that mounts it read-only (it needs no network)
IMG=$(kubectl -n $NS get statefulset postgres -o jsonpath='{.spec.template.spec.containers[0].image}')
kubectl -n $NS apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata: { name: pg-restore }
spec:
  securityContext: { runAsNonRoot: true, runAsUser: 70, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: shell
      image: $IMG
      command: [sleep, "3600"]
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: [ALL] } }
      volumeMounts: [{ name: backup, mountPath: /backup, readOnly: true }]
  volumes: [{ name: backup, persistentVolumeClaim: { claimName: pg-backup } }]
EOF
kubectl -n $NS wait --for=condition=Ready pod/pg-restore
kubectl -n $NS exec pg-restore -- cat /backup/$F \
  | kubectl -n $NS exec -i postgres-0 -- sh -c 'gunzip -c | psql -U helper2 -d helper2 -v ON_ERROR_STOP=1 -q'
kubectl -n $NS delete pod pg-restore

# b) or from the off-site bucket, on a workstation with rclone (it decrypts <prefix>/$F.bin)
printf 'crypt_password: '; read -rs CRYPT; echo
RCLONE_CONFIG_S3_TYPE=s3 RCLONE_CONFIG_S3_PROVIDER=Other RCLONE_CONFIG_S3_ENDPOINT=<endpoint> \
RCLONE_CONFIG_S3_ACCESS_KEY_ID=<id> RCLONE_CONFIG_S3_SECRET_ACCESS_KEY=<secret> \
RCLONE_CONFIG_SEALED_TYPE=crypt RCLONE_CONFIG_SEALED_REMOTE=s3:<bucket>/<prefix> \
RCLONE_CONFIG_SEALED_FILENAME_ENCRYPTION=off RCLONE_CONFIG_SEALED_DIRECTORY_NAME_ENCRYPTION=false \
RCLONE_CONFIG_SEALED_PASSWORD=$(printf %s "$CRYPT" | rclone obscure -) \
  rclone cat sealed:$F \
  | kubectl -n $NS exec -i postgres-0 -- sh -c 'gunzip -c | psql -U helper2 -d helper2 -v ON_ERROR_STOP=1 -q'

kubectl -n $NS scale deployment/helper2 --replicas=3   # your app.replicas
```

## The hosted helper2.ai

helper2.ai runs on this chart with [`values-staging.yaml`](../helm/helper2/values-staging.yaml) and
[`values-prod.yaml`](../helm/helper2/values-prod.yaml), deployed by GitHub Actions: [CI.md](CI.md).
For the nightly guide evaluation, `values-staging.yaml` keeps guide answers one second (`app.guideCacheTtl`) and caps
guide model calls at 600 a day, the prod cap (`app.dailyLimits.guideModelCalls`: a run, a rerun and checks by hand)
([ADR 0046](../../docs/adr/operations/0046-evaluation-guide-and-search.md)); prod keeps the defaults.

The hosted images are signed with a key in OpenBao Transit, and a deploy runs them only after it checked each
signature, pinned by digest (`image.digest`, `web.image.digest`, `backoffice.image.digest`, `postgres.image.digest`;
[ADR 0044](../../docs/adr/operations/0044-ci-cd-and-supply-chain.md)). `make diff`, `env-secret` and
`deploy` check the signatures themselves first (`make verified`, into `<env>/digests.env`) and take the digests only
from that check. By hand, from the workstation (cosign installed, a token that reads `ci/harbor`):

```bash
make ENV=staging TAG=<tag> deploy
```

Without a digest the chart names each image by tag, so another cluster needs no signature.
