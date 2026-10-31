# 0007 — Configuration and secrets: every external variable uses the H2_ prefix; secrets only in OpenBao (helper2/kv ci/<env> app_env); profiles

**Status.** Accepted (2026-10-27). **Builds on:** [0001](0001-stack-and-runtime.md)

## Context
The app needs database and Redis passwords, model and source application programming interface (API)
keys, and many tuning values. The same
names must work on a laptop, in the demo compose file and in Kubernetes. Unprefixed names (`REDIS_PORT`,
`DATABASE_URL`) clash with variables that Kubernetes or other tools set. Secrets must never live in Git
or in GitHub settings, and the deploy must not need a person to copy them.

## Decision
1. **One prefix.** Every variable the app reads from outside starts with `H2_`, profiles included:
   `spring.profiles.active: ${H2_PROFILES:}`. `application.yaml` maps each one to a property with a safe
   default (for example `H2_COOKIE_SECURE=true`, `H2_FORWARD_HEADERS_STRATEGY=none`,
   `H2_METRICS_PUBLIC=false`, `H2_SCOUT_ENABLED=false`). Search and rate-limit settings follow the same rule
   (`H2_SEARCH_STALE_FOR`, `H2_SEARCH_PER_SOURCE_LIMIT`, `H2_SEARCH_TRANSLATE_TIMEOUT_MS`, `H2_RATE_LIMIT_*`).
   The pod sets `enableServiceLinks: false`, so Kubernetes adds no service variables of its own.
2. **Profiles.** `llm-nebius` turns on the model provider (needs `H2_NEBIUS_API_KEY`); `oauth-github` turns
   on sign-in (needs `H2_GITHUB_OAUTH_CLIENT_ID` and `_SECRET`); `dev` starts compose and allows
   `http://localhost`; `demo` uses fixture sources and no Scout. Staging and prod run `llm-nebius` only.
3. **Local runs.** The app imports the repository-root `.env` (Git-ignored, template `.env.example`); real
   environment variables win. Tests replace the import, so a developer's keys never reach a test run.
4. **Secrets live in OpenBao.** Mount `helper2/kv`, path `ci/<env>`, fields `app_env` (the whole env file)
   and `kubeconfig` (the namespace's deployer). The owner writes them with `make ENV=<env> credentials`
   after a login with role `helper2-operator`. Other entries: `ci/harbor` (image push robot) and
   `backup/r2` (off-site backup token, written to the cluster from the workstation; continuous integration (CI) never reads it).
5. **The path to the pod.** The deploy job logs in to OpenBao with the job's GitHub OpenID Connect (OIDC)
   token ([0028](../operations/0028-ci-cd.md)), writes `app_env` to `<env>/app.env` (`umask 077`), and runs `make deploy`
   (`helm --set-file envFile=<env>/app.env`). The chart turns the file into the Secret `helper2-env` and
   fails when `H2_DATABASE_PASSWORD` is missing or `H2_REDIS_PASSWORD` is not 16+ letters and digits.
   The app reads it with `envFrom`; Postgres, Redis and the backup job read single keys. The job deletes
   the file and the kubeconfig at the end, even on failure.
6. **Restart on change.** The app pod carries `checksum/env` of the file, and Redis `checksum/auth` of its
   password, so a changed value restarts them on the next deploy.
7. **Values that are not secret** (database address, Redis host, profiles, Scout settings, tracing endpoint,
   management port 8081) are set in the chart's Deployment, per environment through `values-<env>.yaml`.

## Consequences
- One search for `H2_` finds every setting. A missing or misspelled prefix is easy to spot in review.
- No secret is stored in GitHub: the deploy reads OpenBao after a login that is bound to the
  repository and the job's environment.
- The Kubernetes Secret is still only base64 in the cluster; access to the namespace means access to it.
- The Cloudflare tunnel token is created by Terraform and kept in its state and in the Secret
  `cloudflared-token`, not in OpenBao.
- Renamed variables need the OpenBao entry rewritten by hand; an old name is silently ignored.
- A change in `app_env` takes effect only after `make credentials` and the next deploy.

## Alternatives considered
- GitHub Actions secrets — long-lived values in a second place, readable by any workflow change.
- External Secrets Operator or the OpenBao agent in the cluster — another controller to run for one app.
- Spring Cloud Config — a config server for one service.
- Plain Spring names (`SPRING_DATASOURCE_URL`) — long, tied to Spring's internal property names, and
  mixed with our own settings under no common prefix.

## Code
- `backend/src/main/resources/application.yaml` — every `H2_` variable, defaults, profiles.
- `.env.example`, `deploy/k8s/app.env.example` — templates.
- `deploy/k8s/Makefile` (`credentials`, `backup-credentials`, `deploy`) — writes and uses the secrets.
- `.github/actions/bao-login/action.yml`, `.github/workflows/deploy.yml` — OIDC login, `app_env` to file.
- `deploy/helm/helper2/templates/_helpers.tpl` (`helper2.env`, `helper2.redisPasswordFrom`),
  `templates/secret.yaml`, `templates/app.yaml` — Secret and pod environment.

## Diagrams
[flow](../../diagrams/flow-0007-configuration-and-secrets.svg) · [sequence](../../diagrams/sequence-0007-configuration-and-secrets.svg)

## Roadmap
- Rewrite `app_env` in OpenBao with the `H2_` names: [roadmap](../../roadmap.md#1-release-v100-before-the-hackathon-deadline-30-oct-2026-1800-zurich).
- Short-lived deployer token from the OpenBao Kubernetes secrets engine: [roadmap](../../roadmap.md#3-operations).
- Restart Redis on rotation when `redis.auth.existingSecret` is used: [roadmap](../../roadmap.md#3-operations).
- Production GitHub OAuth App and `H2_ADMINS`: [roadmap](../../roadmap.md#domain-and-identity).
