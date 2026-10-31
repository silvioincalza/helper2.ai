# Deploy on Kubernetes

The Helm chart [`../helm/helper2`](../helm/helper2) runs Helper2.ai on any Kubernetes cluster: the app,
Postgres with pgvector and Redis, one release `helper2` per namespace. The app is exposed through an
Ingress or a Cloudflare tunnel. A nightly database backup is optional.

## Requirements

- Kubernetes 1.27 or later (the backup CronJob uses `timeZone`) and Helm 3.
- A container registry that the cluster can pull from. The chart sets no `imagePullSecrets`: use a
  public registry, or attach a pull secret to the namespace's `default` ServiceAccount.
- A storage class for `ReadWriteOnce` volumes. Empty `storageClassName` = the cluster default.
- An Ingress controller (or a Cloudflare tunnel, see [Options](#options)).
- A CNI that enforces NetworkPolicy. Without one, the policies are created but do nothing.
- A Nebius Token Factory API key for the AI guide. Without it, search works and the guide shows plain
  search results instead.

## 1. Build and push the images

From the repository root:

```bash
REGISTRY=ghcr.io/<owner>
TAG=$(git rev-parse --short=12 HEAD)
PG_TAG=18.6-pgvector0.8.6    # postgres.image.tag in the chart's values.yaml

podman build --platform linux/amd64 -f deploy/Dockerfile -t $REGISTRY/helper2:$TAG .
podman build --platform linux/amd64 -t $REGISTRY/helper2-postgres:$PG_TAG deploy/postgres
podman push $REGISTRY/helper2:$TAG
podman push $REGISTRY/helper2-postgres:$PG_TAG
```

Docker works the same way. Tags are never rebuilt or moved: one commit, one image.

## 2. Configure

**`app.env`** (never commit it): copy [`app.env.example`](app.env.example) and fill in the values. It must
contain `H2_DATABASE_PASSWORD` and `H2_REDIS_PASSWORD` (`openssl rand -hex 32`; letters and digits only).
The chart turns the file into the Secret `helper2-env`. A change restarts the app only.

**`my-values.yaml`**, for an Ingress with TLS:

```yaml
app:
  environment: prod            # tag on metrics and spans
image:
  repository: ghcr.io/<owner>/helper2
postgres:
  image:
    repository: ghcr.io/<owner>/helper2-postgres
ingress:
  enabled: true
  className: nginx
  host: helper2.example.com
  tlsSecretName: helper2-tls   # e.g. issued by cert-manager through ingress.annotations
  controllerNamespace: ingress-nginx
```

Every value and its default is described in [`values.yaml`](../helm/helper2/values.yaml).

## 3. Install

```bash
kubectl create namespace helper2
helm upgrade --install helper2 deploy/helm/helper2 -n helper2 \
  -f my-values.yaml --set image.tag=$TAG --set-file envFile=app.env \
  --wait --timeout 12m
```

Check that it works:

```bash
kubectl -n helper2 get pods
kubectl -n helper2 port-forward deploy/helper2 8081 &
curl -s localhost:8081/actuator/health/readiness    # {"status":"UP"}
```

Then open `https://<host>`. The Scout starts about 20 seconds after startup and fills the catalog over
the next hours.

To upgrade, build a new tag and run the same `helm upgrade --install` command with it.

## Options

| What | Values |
|---|---|
| Cloudflare tunnel instead of an Ingress | `cloudflared.enabled: true`; a Secret `cloudflared-token` (key `token`) with the tunnel token. Route the public hostnames to `http://helper2.<namespace>.svc.cluster.local:80`. |
| Traces (OTLP/HTTP) | `app.tracing.export: true`, `app.tracing.endpoint`, `networkPolicy.egress.otlp.namespace` of the collector |
| Metrics scraping | `networkPolicy.metricsNamespace` of the scraper. The pods carry `prometheus.io/*` annotations (port 8081). |
| Replicas | `app.replicas` (default 3) |
| Nightly backup | `backup.enabled: true`, see [Backups](#backups) |
| DNS for the egress policy | `networkPolicy.egress.dns` (default: CoreDNS in `kube-system`) |

The network policy lets only the edge (the Ingress controller's namespace or cloudflared) reach the app,
and only on port 8080. The app trusts `X-Forwarded-For` from that edge for its per-client rate limits.

## Operations

The app runs `app.replicas` replicas, replaced one at a time on upgrade, with a PodDisruptionBudget when
there is more than one replica. The Scout and the embedding indexer run on one replica at a time (lock
table `shedlock` in Postgres); rate limits and sign-in sessions are in Redis. What is still per replica:
[docs/roadmap.md](../../docs/roadmap.md), "More than one replica".

**Never `helm uninstall`:** it deletes the Postgres and Redis StatefulSets and the Secret. Their PVCs
(`data-postgres-0`, and `data-redis-0` with Redis's append-only file) are not owned by the release and stay; the backup PVC has `helm.sh/resource-policy: keep`.
To go back, use `helm -n helper2 rollback helper2`.

## Backups

With `backup.enabled: true`, the CronJob `pg-backup` (02:30 in `backup.timeZone`) writes
`pg_dump --no-owner | gzip` to `/backup/helper2-<date>.sql.gz` on the PVC `pg-backup` (7 days, kept on
`helm uninstall`). Use a storage class that outlives a node (`backup.storageClassName`).

**Off-site copy** (`backup.offsite`, off by default): after the dump, rclone copies every dump the bucket
lacks to S3-compatible storage (for example Cloudflare R2). It never deletes there; retention is the
bucket's lifecycle rule. To turn it on:

1. Create a bucket with a lifecycle rule (for example, delete after 30 days) and an access key with read
   and write access to that bucket only.
2. `kubectl -n helper2 create secret generic pg-backup-offsite --from-literal=access_key_id=<id> --from-literal=secret_access_key=<secret>`
3. Set `backup.offsite.enabled: true`, `endpoint` and `bucket`, then upgrade. Check:
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

# b) or from the off-site bucket, on a workstation with rclone
RCLONE_CONFIG_S3_TYPE=s3 RCLONE_CONFIG_S3_PROVIDER=Other RCLONE_CONFIG_S3_ENDPOINT=<endpoint> \
RCLONE_CONFIG_S3_ACCESS_KEY_ID=<id> RCLONE_CONFIG_S3_SECRET_ACCESS_KEY=<secret> \
  rclone cat s3:<bucket>/<prefix>/$F \
  | kubectl -n $NS exec -i postgres-0 -- sh -c 'gunzip -c | psql -U helper2 -d helper2 -v ON_ERROR_STOP=1 -q'

kubectl -n $NS scale deployment/helper2 --replicas=3   # your app.replicas
```

**Reset** to an empty database on the single V1 schema (staging first, prod in one maintenance window):
[RESET-DATABASE.md](RESET-DATABASE.md).

## The hosted helper2.ai

helper2.ai runs on this chart with [`values-staging.yaml`](../helm/helper2/values-staging.yaml) and
[`values-prod.yaml`](../helm/helper2/values-prod.yaml), deployed by GitHub Actions: [CI.md](CI.md).
Staging's `app.env` also sets `H2_GUIDE_CACHE_TTL=PT1S`, `H2_GUIDE_CLIENT_DAILY_QUESTIONS=60` and
`H2_GUIDE_DAILY_MODEL_CALLS=150` for the nightly guide evaluation
([ADR 0030](../../docs/adr/quality/0030-guide-evaluation.md)); prod keeps the defaults.
