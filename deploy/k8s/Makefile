# helper2 on the incalza.me cluster, ENV=staging (helper2.incalza.me) or ENV=prod (helper2.ai).
# Run from the workstation with the mesh (WireGuard) up.
#
# Required environment — the platform repo's .env / .secrets.env carry the first five:
#   KUBECONFIG  R2_ACCESS_KEY_ID  R2_SECRET_ACCESS_KEY  TF_STATE_BUCKET  CF_ACCOUNT_ID  BAO_ADDR
#   set -a; . <incalza-me-infra>/.env; . <incalza-me-infra>/.secrets.env; set +a
#   export KUBECONFIG=<incalza-me-infra>/.secrets/kubeconfig
#
#   make ENV=staging bootstrap   once per env: namespace + tunnel + DNS (+ www redirect in prod)
#   make image                   build linux/amd64 image, push to Harbor (robot from OpenBao helper2/kv ci/harbor)
#   make ENV=staging diff        what the next deploy would change
#   make ENV=staging deploy      helm upgrade --install (chart ../helm/helper2) with TAG, wait, roll back on failure
#   make ENV=staging rollback    previous helm revision
# Promote to prod = the same TAG: make ENV=prod TAG=<tag already on staging> deploy

SHELL    := /bin/bash
ROOT     := $(abspath ../..)
TF       := terraform -chdir=terraform
REGISTRY := harbor-i.infra.incalza.me
IMAGE    := $(REGISTRY)/helper2/apps/helper2
CHART    := ../helm/helper2
# The Postgres + pgvector image tag lives in one place: postgres.image.tag in the chart's values.yaml.
PG_REPO  := $(REGISTRY)/helper2/apps/postgres
PG_TAG   := $(shell awk '/^postgres:/ {p=1; next} p && /^[^ \#]/ {exit} p && $$1 == "tag:" {print $$2; exit}' $(CHART)/values.yaml)
# The image is built from `git archive HEAD`: committed code only, so the tag (the commit) is exactly
# what runs — uncommitted work in a shared checkout never leaks into an image.
TAG      ?= $(shell git -C $(ROOT) rev-parse --short=12 HEAD)
ENGINE   ?= podman
ENV      ?= staging
NS       := helper2-$(ENV)
HOST     := $(if $(filter prod,$(ENV)),helper2.ai,helper2.incalza.me)

ifeq ($(filter $(ENV),staging prod),)
$(error ENV must be staging or prod)
endif

export AWS_ACCESS_KEY_ID      = $(R2_ACCESS_KEY_ID)
export AWS_SECRET_ACCESS_KEY  = $(R2_SECRET_ACCESS_KEY)
export TF_VAR_kubeconfig_path = $(KUBECONFIG)
export TF_VAR_environment     = $(ENV)

.PHONY: help init plan bootstrap credentials backup-credentials image postgres-image chart-check check-env deploy diff rollback status

help: ## this list
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

# One state per environment (helper2-staging.tfstate, helper2-prod.tfstate); -reconfigure switches.
init: ## terraform init against the state of ENV
	@test -n "$(TF_STATE_BUCKET)" || { echo "TF_STATE_BUCKET is not set"; exit 1; }
	@test -n "$(CF_ACCOUNT_ID)"   || { echo "CF_ACCOUNT_ID is not set"; exit 1; }
	@printf 'bucket = "%s"\nkey = "$(NS).tfstate"\nregion = "auto"\nendpoints = { s3 = "https://%s.r2.cloudflarestorage.com" }\n' \
		"$(TF_STATE_BUCKET)" "$(CF_ACCOUNT_ID)" > terraform/backend.hcl
	$(TF) init -input=false -reconfigure -backend-config=backend.hcl

plan: init ## what bootstrap would change
	$(TF) plan -input=false

bootstrap: init ## namespace + tunnel + DNS + tunnel token secret
	$(TF) apply -input=false

# What the CI deploy job of ENV reads (helper2/kv ci/<env>): the deployer's kubeconfig and app.env.
# Needs a BAO_TOKEN with helper2-write: `export BAO_TOKEN=$(bao login -method=oidc -path=oidc -token-only role=helper2-operator)`.
# Run after bootstrap, and again after an app.env change or a token rotation.
API_SERVER ?= https://10.0.0.40:6443
credentials: check-env ## write ENV's deployer kubeconfig + app.env to OpenBao for CI
	@set -euo pipefail; \
	kc=$$(mktemp); trap 'rm -f $$kc' EXIT; \
	sec=$$(kubectl -n $(NS) get secret deployer-token -o json); \
	jq -r '.data["ca.crt"]' <<<"$$sec" | base64 -d > $$kc.ca; trap 'rm -f $$kc $$kc.ca' EXIT; \
	KUBECONFIG=$$kc kubectl config set-cluster incalzame --server=$(API_SERVER) \
		--certificate-authority=$$kc.ca --embed-certs=true >/dev/null; \
	KUBECONFIG=$$kc kubectl config set-credentials deployer \
		--token="$$(jq -r .data.token <<<"$$sec" | base64 -d)" >/dev/null; \
	KUBECONFIG=$$kc kubectl config set-context ci --cluster=incalzame --user=deployer --namespace=$(NS) >/dev/null; \
	KUBECONFIG=$$kc kubectl config use-context ci >/dev/null; \
	KUBECONFIG=$$kc kubectl auth can-i create deployments -n $(NS) | grep -qx yes; \
	bao kv put -mount=helper2/kv ci/$(ENV) kubeconfig=@$$kc app_env=@$(ENV)/app.env >/dev/null; \
	echo "wrote helper2/kv ci/$(ENV) (kubeconfig of $(NS)/deployer, $(ENV)/app.env)"

# The off-site backup's R2 token (backup.offsite), from helper2/kv backup/r2 (access_key_id,
# secret_access_key) into the Secret pg-backup-offsite. From the workstation, with the admin
# kubeconfig and a helper2-operator BAO_TOKEN: CI never reads this token. Rerun after a rotation.
backup-credentials: ## write ENV's off-site backup Secret (pg-backup-offsite) from OpenBao
	@set -euo pipefail; \
	r2=$$(bao kv get -mount=helper2/kv -format=json backup/r2); \
	kubectl -n $(NS) create secret generic pg-backup-offsite --dry-run=client -o yaml \
		--from-env-file=<(jq -r '.data.data | "access_key_id=\(.access_key_id)\nsecret_access_key=\(.secret_access_key)"' <<<"$$r2") \
		| kubectl apply -f - >/dev/null; \
	echo "wrote $(NS)/pg-backup-offsite from helper2/kv backup/r2"

# Shell prelude of the image targets: the Harbor robot from OpenBao (helper2/kv ci/harbor) in a curl
# config (never in argv), `harbor_api <repository> <tag> [curl args]` on Harbor's artifact API (status
# 200 = the tag exists, 404 = it does not), and `harbor_login` for the image engine.
define HARBOR
work=$$(mktemp -d); trap 'rm -rf "$$work"' EXIT; \
creds=$$(bao kv get -mount=helper2/kv -format=json ci/harbor); \
user=$$(jq -r .data.data.harbor_user <<<"$$creds"); pass=$$(jq -r .data.data.harbor_password <<<"$$creds"); \
printf 'user = "%s:%s"\n' "$$user" "$$pass" > "$$work/curl.cfg"; \
harbor_api() { curl -sS -K "$$work/curl.cfg" "https://$(REGISTRY)/api/v2.0/projects/helper2/repositories/$${1//\//%252F}/artifacts/$$2" "$${@:3}"; }; \
harbor_login() { printf '%s' "$$pass" | $(ENGINE) login $(REGISTRY) --username "$$user" --password-stdin >/dev/null; }
endef

# Tags are immutable: one commit, one image. A tag already in Harbor is never rebuilt, so a rerun of CD
# deploys the image staging tested, not a fresh build of the same commit.
image: ## build the linux/amd64 image of TAG (deploy/Dockerfile) and push it to Harbor, unless it is there
	@set -euo pipefail; $(HARBOR); \
	code=$$(harbor_api apps/helper2 $(TAG) -o /dev/null -w "%{http_code}"); case $$code in \
		200) echo "$(IMAGE):$(TAG) is in Harbor already, not rebuilt"; exit 0;; \
		404) ;; \
		*) echo "Harbor answered HTTP $$code for $(IMAGE):$(TAG)"; exit 1;; \
	esac; \
	mkdir "$$work/src"; git -C $(ROOT) archive $(TAG) | tar -x -C "$$work/src"; \
	harbor_login; \
	$(ENGINE) build --platform linux/amd64 -f "$$work/src/deploy/Dockerfile" -t $(IMAGE):$(TAG) "$$work/src"; \
	$(ENGINE) push $(IMAGE):$(TAG); \
	echo "pushed $(IMAGE):$(TAG)"

# Built only when postgres.image.tag is not in Harbor yet (the first run after a bump), otherwise a
# no-op, so CD runs it on every push. Prints the digest to pin as postgres.image.digest.
postgres-image: ## build Postgres + pgvector (deploy/postgres) and push it to Harbor, unless its tag is there
	@set -euo pipefail; \
	test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }; \
	$(HARBOR); \
	code=$$(harbor_api apps/postgres $(PG_TAG) -o /dev/null -w "%{http_code}"); case $$code in \
		200) echo "$(PG_REPO):$(PG_TAG) is in Harbor already, not rebuilt (a new image needs a new postgres.image.tag)";; \
		404) harbor_login; \
			$(ENGINE) build --platform linux/amd64 -t $(PG_REPO):$(PG_TAG) $(ROOT)/deploy/postgres; \
			$(ENGINE) push $(PG_REPO):$(PG_TAG); \
			echo "pushed $(PG_REPO):$(PG_TAG)";; \
		*) echo "Harbor answered HTTP $$code for $(PG_REPO):$(PG_TAG)"; exit 1;; \
	esac; \
	echo "$(PG_REPO):$(PG_TAG) digest: $$(harbor_api apps/postgres $(PG_TAG) -f | jq -r .digest)"

HELM_ARGS = helper2 $(CHART) -n $(NS) -f $(CHART)/values-$(ENV).yaml \
	--set image.repository=$(IMAGE) --set image.tag=$(TAG) --set-file envFile=$(ENV)/app.env

# CI runs this on every pull request; no cluster needed. Each environment's values (and prod with the
# off-site backup on) with a fixture app.env, linted and checked against the Kubernetes schemas, and
# Redis rendered with AOF (no RDB snapshots) on its own PersistentVolumeClaim (ADR 0005).
chart-check: ## helm lint + helm template | kubeconform, for staging and prod; Redis AOF + PVC; an envFile key without H2_ fails
	@set -euo pipefail; \
	env=$$(mktemp); bad=$$(mktemp); trap 'rm -f "$$env" "$$bad"' EXIT; \
	printf 'H2_DATABASE_PASSWORD=ci\nH2_REDIS_PASSWORD=00000000000000000000000000000000\n' > "$$env"; \
	echo "== envFile with a key outside ^H2_[A-Z0-9_]+$$ must not render"; \
	{ cat "$$env"; echo 'NEBIUS_API_KEY=stale'; } > "$$bad"; \
	if out=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --set-file envFile="$$bad" 2>&1); then \
		echo "chart rendered an envFile with the key NEBIUS_API_KEY"; exit 1; \
	fi; \
	grep -q 'NEBIUS_API_KEY' <<<"$$out" || { echo "$$out"; echo "render failed without naming the key"; exit 1; }; \
	offsite=(--set backup.offsite.enabled=true --set backup.offsite.endpoint=https://example.r2.cloudflarestorage.com --set backup.offsite.bucket=ci); \
	for v in "staging" "prod" "prod $${offsite[*]}"; do \
		set -- $$v; e=$$1; shift; \
		echo "== $$e $$*"; \
		args=(-f $(CHART)/values-$$e.yaml --set image.tag=000000000000 --set-file envFile=$$env "$$@"); \
		helm lint --strict $(CHART) "$${args[@]}"; \
		helm template helper2 $(CHART) -n helper2-$$e "$${args[@]}" | kubeconform -strict -summary; \
		redis=$$(helm template helper2 $(CHART) -n helper2-$$e "$${args[@]}" --show-only templates/redis.yaml); \
		grep -qF -- '--save "" --appendonly yes --appendfsync everysec' <<<"$$redis" \
			|| { echo "Redis must run with AOF and no RDB snapshots (--save \"\" --appendonly yes --appendfsync everysec)"; exit 1; }; \
		grep -q '^  volumeClaimTemplates:' <<<"$$redis" || { echo "Redis must keep /data on a PersistentVolumeClaim"; exit 1; }; \
	done

check-env:
	@test -f $(ENV)/app.env || { echo "$(ENV)/app.env missing: copy app.env.example and fill it"; exit 1; }

# Revision 1 exists in both envs (adopted from kustomize on 2026-10-26), so a failed upgrade rolls
# back to the previous revision. Never `helm uninstall`: it would delete the Postgres StatefulSet
# (its data PVC stays, it is not owned by the release).
# --force-conflicts: Helm is the only owner. Fields last written by kubectl (kustomize era, or a manual
# `kubectl edit`) would otherwise block server-side apply, e.g. the image on the first new tag.
# 12m: the app's replicas are replaced one after another, each only once the previous one is ready.
deploy: check-env ## helm upgrade ENV to TAG (default: current commit), wait, roll back on failure
	helm upgrade --install $(HELM_ARGS) --server-side=true --force-conflicts --wait --timeout 12m --rollback-on-failure --history-max 10

# kubectl diff exits 1 when there are changes (the usual case before a deploy) and >1 on an error
# (auth, API, invalid manifest): only 1 is tolerated. The chart is rendered first, so a render error
# cannot pass as a diff either.
diff: check-env ## what deploy would change in ENV (Secret values are masked by kubectl)
	@set -uo pipefail; \
	out=$$(mktemp); trap 'rm -f "$$out"' EXIT; \
	helm template $(HELM_ARGS) > "$$out" || exit; \
	kubectl diff -n $(NS) -f "$$out"; rc=$$?; \
	if [ $$rc -gt 1 ]; then echo "kubectl diff failed (exit $$rc)"; exit $$rc; fi

rollback: ## back to the previous helm revision of ENV
	helm -n $(NS) rollback helper2 --wait --timeout 12m
	helm -n $(NS) history helper2 --max 5

status: ## ENV's pods (READY = the readiness probe) and the public site
	kubectl -n $(NS) get pods -o wide
	@# Actuator health is on the management port (8081), which the Service and the tunnel never route.
	curl -s -o /dev/null -w "https://$(HOST)/ -> %{http_code}\n" https://$(HOST)/
