# 0027 — Runtime topology: Helm chart, three replicas on prod, Cloudflare tunnel, per-workload network policies, Redis auth, backups

**Status.** Accepted. **Builds on:** [0001](../foundation/0001-stack-and-runtime.md), [0004](../foundation/0004-persistence-and-provenance.md), [0005](../foundation/0005-redis-disposable-cache.md), [0007](../foundation/0007-configuration-and-secrets.md), [0018](../scout/0018-scout-runner.md), [0023](../edge/0023-identity-and-admin.md), [0024](../edge/0024-http-edge-protection.md)

## Context
The hosted Helper2 runs on one Kubernetes cluster, with a staging environment and
prod (`helper2.ai`). The chart must also run on any other cluster. The cluster has no public ingress. A deploy must not interrupt the site, and one node
going down must not take the app with it. The app talks to many public application programming interfaces (APIs), so a compromised pod must not reach the private
network. User data (bookmarks, reports, reviews) cannot be rebuilt by the Scout, so prod needs backups.

## Decision
1. **Split of ownership.** Terraform (`deploy/k8s/terraform`) owns identity and edge: the namespace
   (`helper2-<env>`, Pod Security `restricted`), the Cloudflare tunnel, Domain Name System (DNS) records, the tunnel-token Secret, the
   `deployer` ServiceAccount, and in prod the `www` → apex 301 redirect. The Helm chart `deploy/helm/helper2`
   owns the workloads: one release `helper2` per namespace, values `values-staging.yaml` / `values-prod.yaml`.
2. **Workloads.** `helper2` Deployment, `postgres` StatefulSet
   (5 Gi `local-path` volume), `redis` StatefulSet (1 Gi `local-path` volume for its append-only file),
   `cloudflared` Deployment, and in prod the `pg-backup`
   CronJob. Both stateful workloads run one replica on a ReadWriteOnce volume from `volumeClaimTemplates`, with
   `fsGroup` so the non-root user can write it. Every container runs as non-root with a read-only root file system, no capabilities and the
   `RuntimeDefault` seccomp profile. Every pod runs as the ServiceAccount `helper2`, which has no RoleBinding,
   with `automountServiceAccountToken: false`: no workload calls the Kubernetes API, so no pod holds a token.
3. **App replicas.** `app.replicas`: 3 on prod, 1 on staging. `RollingUpdate` with `maxUnavailable: 0` and
   `maxSurge: 1`: one new pod at a time, and an old pod goes only when the new one is ready. Topology spread over
   `kubernetes.io/hostname` (`ScheduleAnyway`). Above 1 replica a PodDisruptionBudget keeps `replicas - 1` available
   during node drains. `make deploy` and `make rollback` wait up to 12 min (`helm --wait`). Replicas share state only
   through Postgres and Redis: the Scout and indexer locks and the end time of the last scheduled Scout run
   ([0018](../scout/0018-scout-runner.md)), rate limits
   ([0024](../edge/0024-http-edge-protection.md)) and sign-in sessions ([0023](../edge/0023-identity-and-admin.md)). The `deployer`
   Role may manage PodDisruptionBudgets (`rbac.tf`, applied by `make ENV=<env> bootstrap`).
4. **Edge.** Cloudflare terminates Transport Layer Security (TLS) and sends traffic through a remotely managed tunnel. `cloudflared`
   dials out (`--protocol http2`, Transmission Control Protocol (TCP) port 7844, because outbound User Datagram
   Protocol (UDP) traffic is blocked by the host firewall) and
   forwards to `http://helper2.<ns>.svc.cluster.local:80`. Inside the cluster the traffic is plain Hypertext Transfer Protocol (HTTP).
   Other clusters can use an Ingress instead (`ingress.enabled`); the network policy then lets only the
   Ingress controller's namespace reach the app. Platform settings (registry, storage classes, tunnel,
   tracing, scraper) are values, set for the hosted environments in `values-<env>.yaml`
   ([deploy/k8s/README.md](../../../deploy/k8s/README.md)).
5. **Ports.** The app serves the site and API on 8080 (Service `helper2`), the back office on 8082 (Service
   `helper2-admin`, `app.adminPort`, [0035](../edge/0035-admin-host.md)) and Actuator on 8081 (`H2_MANAGEMENT_PORT`).
   Probes and the Prometheus scrape use 8081. The tunnel never reaches 8081 or 8082.
   Startup probe: every 5 s, up to `app.startupSeconds` (300 s); then readiness every 5 s and liveness
   every 10 s.
6. **Network policy.** One policy per workload and direction, each naming only the peers that workload needs.
   Ingress is denied by default, then allowed per workload:
   - app (`ingress-app`): `cloudflared` on 8080; the Ingress controller's pods (`ingress.controllerNamespace`,
     `controllerPodLabels`, also when the Ingresses live outside the chart) on 8080 and 8082; namespace
     `observability` on 8081;
   - Postgres (`ingress-postgres`): the app and `pg-backup` on 5432;
   - Redis (`ingress-redis`): the app on 6379;
   - `cloudflared` and `pg-backup`: nothing.

   Egress is denied by default, then allowed per workload:
   - app: DNS, Postgres 5432, Redis 6379, the OpenTelemetry collector (`observability`, 4318), and the
     internet on 443 only, with the private ranges excluded;
   - `cloudflared`: DNS, the app on 8080, the internet on 7844;
   - `pg-backup`: DNS, Postgres, and 443 only when `backup.offsite` is on;
   - Postgres and Redis: nothing.
7. **Redis auth.** `redis.auth.enabled: true`. The password is `H2_REDIS_PASSWORD` from `helper2-env` (or
   `existingSecret`); it reaches `redis-server` as `requirepass` on standard input, never in the command line.
   Redis is a single-replica StatefulSet with an append-only file on its own PersistentVolumeClaim
   ([0005](../foundation/0005-redis-disposable-cache.md)): a Redis restart keeps sessions, rate-limit windows and cached
   answers. The volume holds only cache data and is not backed up.
8. **Backups (prod).** `pg-backup` runs at 02:30 Europe/Zurich: `pg_dump --no-owner | gzip` to the Network File System (NFS)
   volume `pg-backup` (kept on `helm uninstall`), 7 days kept. With `backup.offsite.enabled`, rclone then
   copies new dumps to Cloudflare R2 (it never deletes there; the bucket's lifecycle rule sets retention).
9. **Per environment.** The chart runs the Scout once a day (`app.scoutEvery: P1D`, so each collector runs at
   most once a day; each replica checks every hour, `H2_SCOUT_TICK` default, and a restart does not add a run) with at most 200 enrichments per run (`app.scoutMaxEnrichments`); the daily enrichment cap
   (300) still applies. Staging has the Scout off and no backup.

## Consequences
- No inbound port is open on the cluster; Cloudflare is the only way in.
- A compromised app pod can reach only the internet on 443, its own database and cache, and the trace collector,
  and holds no Kubernetes API credential.
- A deploy or a node drain keeps the site up on prod, and one pod failing leaves two serving. On staging too, a
  deploy starts the new pod before the old one stops.
- Postgres connections: (replicas + 1 during a deploy) × `H2_DATABASE_POOL_SIZE` (10) = 40 on prod, under the default
  `max_connections` of 100.
- The Postgres and Redis volumes are not owned by the release, so the data outlives the release.

## Alternatives considered
- Ingress controller with a public Internet Protocol (IP) address — opens a port on the cluster and needs certificate management.
- One replica with `Recreate` — a short outage on every deploy and no failover.
- Redis as a Deployment with `Recreate` and a separate PersistentVolumeClaim — same single pod, but the claim is one
  more template to keep in step, and a StatefulSet already guarantees that two pods never share the volume.
- Horizontal Pod Autoscaler — the load is small and steady; a fixed count keeps the connection budget known.
- Managed PostgreSQL — monthly cost and data outside the owner's cluster for a small database.
- Kustomize — no release history, no `--rollback-on-failure` and no value checks; Helm has all three.

## Code
- `deploy/helm/helper2/values.yaml`, `values-staging.yaml`, `values-prod.yaml` — settings.
- `deploy/helm/helper2/templates/app.yaml`, `postgres.yaml`, `redis.yaml`, `cloudflared.yaml`, `backup.yaml`,
  `networkpolicy.yaml`, `serviceaccount.yaml`, `secret.yaml`, `_helpers.tpl` — workloads and policies.
- `deploy/k8s/terraform/main.tf`, `rbac.tf` — namespace, tunnel, DNS, redirect, deployer.
- `deploy/k8s/Makefile`, `deploy/k8s/README.md` — deploy, rollback, backup and restore steps.

## Diagrams
[flow](../../diagrams/flow-0027-runtime-topology.svg) · [sequence](../../diagrams/sequence-0027-runtime-topology.svg)
The topology itself is in the [infrastructure](../../diagrams/infrastructure.svg) diagram.

## Roadmap
- Check that the DNS pods carry `k8s-app=kube-dns` in `kube-system`: [roadmap](../../roadmap.md#1-before-the-hackathon-deadline-30-oct-2026-1800-zurich).
- Test a restore, add a restore-check job, turn on the R2 copy, encrypt dumps: [roadmap](../../roadmap.md#3-operations).
- Postgres and Redis exporters; narrow `cloudflared` egress to Cloudflare ranges: [roadmap](../../roadmap.md#3-operations).
- What stays per replica; `preStop` delay for rolling updates; Scout lock after a crash; Redis memory:
  [roadmap](../../roadmap.md#4-more-than-one-replica).
- Cutover to the platform's ingress-nginx (DNS switch), then remove `cloudflared` from the chart:
  [roadmap](../../roadmap.md#domain-and-identity).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
