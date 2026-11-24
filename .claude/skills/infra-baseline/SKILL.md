---
name: infra-baseline
description: Use before changing anything under deploy/, a Helm chart, a workflow that deploys, a secret, a config key, or before proposing any infrastructure or hosting choice. Holds the infrastructure baseline that is not reopened, the generic-deploy rule and the SA_ prefix rule.
---

# Infrastructure baseline

- `deploy/` — Helm charts for staging and prod.
- Config and secrets: `SA_` prefix; secrets live in OpenBao, never in the repo.
- The deploy stays generic: no dependency on a specific cluster or infra repo.
- Infra baseline (do not reopen without a concrete reason): Kubernetes, Helm charts for staging
  and prod, own image registry, OpenBao for secrets, ingress-nginx, GitHub as identity
  provider, servers in the EU, admin back office only over VPN.
- An assistant never deploys to prod and never changes it. When a prod deploy waits for approval, give the human in
  the loop the direct approval link.
