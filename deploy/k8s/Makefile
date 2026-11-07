# helper2 on the incalza.me cluster, ENV=staging (helper2.incalza.me) or ENV=prod (helper2.ai).
# Run from the workstation with the mesh (WireGuard) up.
#
# Required environment: KUBECONFIG and BAO_ADDR, from the platform repo:
#   set -a; . <incalza-me-infra>/.env; . <incalza-me-infra>/.secrets.env; set +a
#   export KUBECONFIG=<incalza-me-infra>/.secrets/kubeconfig
# The platform repo (incalza-me-infra) owns the namespaces, the Ingresses, DNS and the deployer RBAC.
#
#   make image                   build the three linux/amd64 images (web, public app, back office; ADR 0069), push
#                                them to Harbor (robot from OpenBao helper2/kv ci/harbor)
#   make retag VERSION=X.Y.Z     add the release tag X.Y.Z to the three pushed images TAG (same digests, no rebuild)
#   make sbom                    CycloneDX SBOM of the pushed public image TAG (syft), into sbom.cdx.json
#   make backoffice-sbom         the same for the back office image TAG
#   make web-sbom                the same for the web image TAG
#   make postgres-sbom           the same for the Postgres image of postgres.image.tag
#   make ci-images FROM=<commit> BUILD="web public backoffice" PUSHED=<file>
#                                CD per component (ADR 0071): build the components in BUILD, give the others TAG on
#                                the signed image of FROM (same digest); the digests pushed go to PUSHED
#   make sign PUSHED=<file>      CD: sign exactly the digests this run pushed (OpenBao Transit), then check TAG's images
#   make sign-existing TAG=<tag> workstation only, with a confirmation: sign images pushed before signing
#   make verify-images           digest of each image of TAG and of the Postgres image, signature checked against
#                                cosign.pub; diff, env-secret and deploy run it themselves and deploy by digest
#   make ENV=staging diff        what the next deploy would change
#   make ENV=staging env-secret  write ENV's app.env into the Secrets helper2-credentials (the passwords) and
#                                helper2-app-env (the rest), never through Helm (ADR 0070)
#   make ENV=staging deploy      env-secret, then helm upgrade --install (chart ../helm/helper2) with TAG, wait
#                                for the migrate Job and the apps, roll back on failure
#   make ENV=staging rollback    last deployed helm revision (not a failed or interrupted one)
# Promote to prod = the same TAG: make ENV=prod TAG=<tag already on staging> deploy

SHELL    := /bin/bash
ROOT     := $(abspath ../..)
REGISTRY := harbor-i.infra.incalza.me
IMAGE    := $(REGISTRY)/helper2/apps/helper2
# The back office app (deploy/backoffice.Dockerfile), always built and deployed with the same TAG.
BO_IMAGE := $(REGISTRY)/helper2/apps/helper2-backoffice
# The web image (deploy/web.Dockerfile): the public web app's files, and the shell the public pod copies. Same TAG.
WEB_IMAGE := $(REGISTRY)/helper2/apps/helper2-web
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

.PHONY: help credentials backup-credentials image retag sbom backoffice-sbom web-sbom postgres-sbom postgres-image chart-check check-env release-ready deploy diff rollback status

help: ## this list
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

# What the CI deploy job of ENV reads (helper2/kv ci/<env>): the deployer's kubeconfig and app.env.
# Needs helper2-write: uses BAO_TOKEN when set, else logs in (OIDC, browser) as helper2-operator.
# BAO_ADDR must be a node over the mesh (the platform .env sets bao-01): the public bao.infra name
# sits behind Cloudflare Access, which answers the CLI with 403.
# Run once per env, and again after an app.env change or a token rotation.
# API_SERVER: the cluster's API over the mesh (not in this public repo). Default: the server of the current
# kubectl context, which is the mesh address with the platform's KUBECONFIG.
API_SERVER ?= $(shell kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null)
credentials: check-env ## write ENV's deployer kubeconfig + app.env to OpenBao for CI
	@set -euo pipefail; \
	test -n "$(API_SERVER)" || { echo "API_SERVER is not set (the platform .env sets it)"; exit 1; }; \
	if [ -z "$${BAO_TOKEN:-}" ]; then \
		BAO_TOKEN=$$(bao login -method=oidc -path=oidc -token-only role=helper2-operator); export BAO_TOKEN; \
	fi; \
	kc=$$(mktemp); trap 'rm -f $$kc' EXIT; \
	sec=$$(kubectl -n $(NS) get secret deployer-token -o json); \
	jq -r '.data["ca.crt"]' <<<"$$sec" | base64 -d > $$kc.ca; trap 'rm -f $$kc $$kc.ca' EXIT; \
	KUBECONFIG=$$kc kubectl config set-cluster incalzame --server=$(API_SERVER) \
		--certificate-authority=$$kc.ca --embed-certs=true >/dev/null; \
	KUBECONFIG=$$kc kubectl config set-credentials deployer \
		--token="$$(jq -r .data.token <<<"$$sec" | base64 -d)" >/dev/null; \
	KUBECONFIG=$$kc kubectl config set-context ci --cluster=incalzame --user=deployer --namespace=$(NS) >/dev/null; \
	KUBECONFIG=$$kc kubectl config use-context ci >/dev/null; \
	for v in "create deployments" "create jobs" "delete jobs" "get jobs" "list jobs" "watch jobs" "create secrets" "patch secrets/helper2-app-env" "patch secrets/helper2-credentials"; do \
		KUBECONFIG=$$kc kubectl auth can-i $$v -n $(NS) | grep -qx yes || { echo "deployer may not $$v"; exit 1; }; \
	done; \
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

# The Harbor robot's entry in OpenBao: ci/harbor (push, for CD); deploy.yml reads ci/harbor-pull (pull only) to verify.
HARBOR_KV ?= ci/harbor
# Shell prelude of the image targets: the Harbor robot from OpenBao (helper2/kv HARBOR_KV) in a curl
# config (never in argv), `harbor_api <repository> <tag> [curl args]` on Harbor's artifact API (status
# 200 = the tag exists, 404 = it does not), and `harbor_login` for the image engine.
define HARBOR
work=$$(mktemp -d); trap 'rm -rf "$$work"' EXIT; \
creds=$$(bao kv get -mount=helper2/kv -format=json $(HARBOR_KV)); \
user=$$(jq -r .data.data.harbor_user <<<"$$creds"); pass=$$(jq -r .data.data.harbor_password <<<"$$creds"); \
printf 'user = "%s:%s"\n' "$$user" "$$pass" > "$$work/curl.cfg"; \
harbor_api() { curl -sS -K "$$work/curl.cfg" "https://$(REGISTRY)/api/v2.0/projects/helper2/repositories/$${1//\//%252F}/artifacts/$$2" "$${@:3}"; }; \
harbor_login() { printf '%s' "$$pass" | $(ENGINE) login $(REGISTRY) --username "$$user" --password-stdin >/dev/null; }
endef

# Tags are immutable: one commit, one image per deployable. A tag already in Harbor is never rebuilt, so a rerun of CD
# deploys the images staging tested, not a fresh build of the same commit. The three images come from one
# `git archive`, built one after another.
IMAGES := apps/helper2-web=deploy/web.Dockerfile apps/helper2=deploy/Dockerfile apps/helper2-backoffice=deploy/backoffice.Dockerfile
image: ## build the linux/amd64 images of TAG (web, public app, back office) and push them to Harbor, unless they are there
	@set -euo pipefail; $(HARBOR); \
	for entry in $(IMAGES); do \
		repo=$${entry%%=*}; dockerfile=$${entry#*=}; image=$(REGISTRY)/helper2/$$repo; \
		code=$$(harbor_api $$repo $(TAG) -o /dev/null -w "%{http_code}"); case $$code in \
			200) echo "$$image:$(TAG) is in Harbor already, not rebuilt"; continue;; \
			404) ;; \
			*) echo "Harbor answered HTTP $$code for $$image:$(TAG)"; exit 1;; \
		esac; \
		if [ ! -d "$$work/src" ]; then \
			mkdir "$$work/src"; git -C $(ROOT) archive $(TAG) | tar -x -C "$$work/src"; harbor_login; \
		fi; \
		$(ENGINE) build --platform linux/amd64 -f "$$work/src/$$dockerfile" -t $$image:$(TAG) "$$work/src"; \
		$(ENGINE) push $(PUSH_DIGEST) $$image:$(TAG); $(call record_push,$$repo,$(TAG)) \
		echo "pushed $$image:$(TAG)"; \
	done

# A release names its image by version (ADR 0048): Harbor adds the tag VERSION to the image of the commit TAG, so both
# tags point at one digest and nothing is rebuilt. Run again: a no-op. VERSION already on another digest: a failure
# (Harbor's tag-immutability rule would refuse to move it anyway).
VERSION ?=
retag: ## add the release tag VERSION (X.Y.Z) to the three pushed images TAG in Harbor: same digests, no rebuild
	@set -euo pipefail; \
	[[ "$(VERSION)" =~ ^[0-9]+\.[0-9]+\.[0-9]+$$ ]] || { echo "VERSION must be X.Y.Z, got '$(VERSION)'"; exit 1; }; \
	$(HARBOR); \
	for repo in apps/helper2-web apps/helper2 apps/helper2-backoffice; do \
		image=$(REGISTRY)/helper2/$$repo; \
		want=$$(harbor_api $$repo $(TAG) -f | jq -r .digest) || { echo "$$image:$(TAG) is not in Harbor"; exit 1; }; \
		code=$$(harbor_api $$repo $(VERSION) -o "$$work/version.json" -w "%{http_code}"); case $$code in \
			200) have=$$(jq -r .digest "$$work/version.json"); \
				[[ $$have == "$$want" ]] || { echo "$$image:$(VERSION) is $$have, not the image of $(TAG) ($$want)"; exit 1; }; \
				echo "$$image:$(VERSION) is the image of $(TAG) already";; \
			404) jq -n --arg name "$(VERSION)" '{name: $$name}' \
				| harbor_api $$repo $(TAG)/tags -f -X POST -H 'Content-Type: application/json' --data-binary @- -o /dev/null; \
				echo "tagged $$image:$(TAG) as $(VERSION) ($$want)";; \
			*) echo "Harbor answered HTTP $$code for $$image:$(VERSION)"; exit 1;; \
		esac; \
	done

# CycloneDX SBOM of the pushed image of TAG, read from Harbor with the robot account (ADR 0046). Needs syft.
SBOM ?= sbom.cdx.json
sbom: ## CycloneDX SBOM of the image TAG, read from Harbor (needs syft), into SBOM (default sbom.cdx.json)
	@set -euo pipefail; $(HARBOR); \
	SYFT_REGISTRY_AUTH_AUTHORITY=$(REGISTRY) SYFT_REGISTRY_AUTH_USERNAME="$$user" SYFT_REGISTRY_AUTH_PASSWORD="$$pass" \
		syft registry:$(IMAGE):$(TAG) -o cyclonedx-json=$(SBOM); \
	echo "SBOM of $(IMAGE):$(TAG) in $(SBOM)"

# The same SBOM for the back office image of TAG (cd.yml scans it with the public app's).
backoffice-sbom: ## CycloneDX SBOM of the back office image TAG, read from Harbor, into SBOM
	@$(MAKE) --no-print-directory sbom IMAGE=$(BO_IMAGE)

# The same SBOM for the web image of TAG (cd.yml scans it with the apps').
web-sbom: ## CycloneDX SBOM of the web image TAG, read from Harbor, into SBOM
	@$(MAKE) --no-print-directory sbom IMAGE=$(WEB_IMAGE)

# The same SBOM for the Postgres image of postgres.image.tag (cd.yml scans it with the app's).
postgres-sbom: ## CycloneDX SBOM of the Postgres image postgres.image.tag, read from Harbor, into SBOM
	@test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }
	@$(MAKE) --no-print-directory sbom IMAGE=$(PG_REPO) TAG=$(PG_TAG)

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
			$(ENGINE) push $(PUSH_DIGEST) $(PG_REPO):$(PG_TAG); $(call record_push,apps/postgres,$(PG_TAG)) \
			echo "pushed $(PG_REPO):$(PG_TAG)";; \
		*) echo "Harbor answered HTTP $$code for $(PG_REPO):$(PG_TAG)"; exit 1;; \
	esac; \
	echo "$(PG_REPO):$(PG_TAG) digest: $$(harbor_api apps/postgres $(PG_TAG) -f | jq -r .digest)"

# The env Secrets (ADR 0007, 0070): app.env goes to the cluster with kubectl, never through Helm, so no value is
# kept in the release history. The passwords go to their own Secret, which no workload loads whole: each names
# only its own keys. Helm gets only hashes, which restart the apps (any change) and Redis (a new Redis password).
ENV_SECRET   := helper2-app-env
CREDS_SECRET := helper2-credentials
CREDENTIALS  := H2_DATABASE_PASSWORD|H2_DATABASE_PUBLIC_PASSWORD|H2_DATABASE_BACKOFFICE_PASSWORD|H2_REDIS_PASSWORD|H2_REDIS_PUBLIC_PASSWORD|H2_REDIS_BACKOFFICE_PASSWORD
# One release only (the first with ADR 0070): the owner's and Redis' default password stay in helper2-app-env
# too, so a failed upgrade that rolls back to the old chart (Postgres, Redis and pg-backup reading them from
# helper2-app-env) still finds them. Both app templates set these two names under env:, which wins over envFrom,
# so no app process sees them. In the release after, take CREDENTIALS instead of NOT_IN_APP_ENV below.
NOT_IN_APP_ENV := H2_DATABASE_PUBLIC_PASSWORD|H2_DATABASE_BACKOFFICE_PASSWORD|H2_REDIS_PUBLIC_PASSWORD|H2_REDIS_BACKOFFICE_PASSWORD
ENV_REV     = $(shell sha256sum < $(ENV)/app.env 2>/dev/null | cut -c1-16)
REDIS_REV   = $(shell grep -E '^H2_REDIS_(PUBLIC_|BACKOFFICE_)?PASSWORD=' $(ENV)/app.env 2>/dev/null | sha256sum | cut -c1-16)
HELM_ARGS = helper2 $(CHART) -n $(NS) -f $(CHART)/values-$(ENV).yaml \
	--set image.repository=$(IMAGE) --set image.tag=$(TAG) --set backoffice.image.repository=$(BO_IMAGE) \
	--set web.image.repository=$(WEB_IMAGE) \
	--set envSecret.name=$(ENV_SECRET) --set credentialsSecret.name=$(CREDS_SECRET) \
	--set envSecret.revision=$(ENV_REV) --set envSecret.redisPasswordRevision=$(REDIS_REV)

# CI runs this on every pull request; no cluster needed. Each environment's values (and prod with the
# off-site backup and the PrometheusRule on) with a fixture app.env, linted and checked against the Kubernetes
# schemas, and Redis rendered with AOF (no RDB snapshots) on its own PersistentVolumeClaim (ADR 0005). The
# PrometheusRule is checked against its CRD schema from the CRDs-catalog, pinned to a commit, and must hold the
# alerts of deploy/monitoring/helper2-rules.yaml (read through the chart's files/ symlink).
CRD_SCHEMAS := https://raw.githubusercontent.com/datreeio/CRDs-catalog/3eb90d47aba8d61919d4419dd38301536e32a94b/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json
chart-check: ## helm lint + helm template | kubeconform, for staging and prod; Redis AOF + PVC; preStop, drain and grace period; images pinned by digest; redis.auth.revision restarts; daily limits only where set; admin Service on the back office, ingress-nginx on 8080; the public pod's shell from the web image; PrometheusRule = rule file; the backup verifies its dump; the chart renders no Secret; app.env checks
	@set -euo pipefail; \
	env=$$(mktemp); bad=$$(mktemp); trap 'rm -f "$$env" "$$bad"' EXIT; \
	printf '%s\n' H2_DATABASE_PASSWORD=33333333333333333333333333333333 H2_DATABASE_PUBLIC_PASSWORD=44444444444444444444444444444444 \
		H2_DATABASE_BACKOFFICE_PASSWORD=55555555555555555555555555555555 \
		H2_REDIS_PASSWORD=00000000000000000000000000000000 H2_REDIS_PUBLIC_PASSWORD=11111111111111111111111111111111 \
		H2_REDIS_BACKOFFICE_PASSWORD=22222222222222222222222222222222 > "$$env"; \
	echo "== app.env: a key outside ^H2_[A-Z0-9_]+$$, a missing or shared database password or a short or shared Redis password fails"; \
	./check-app-env.sh "$$env"; \
	for b in 'NEBIUS_API_KEY=stale' 'H2_REDIS_PASSWORD=0000000000000000' 'H2_DATABASE_PASSWORD=33333333333333333333333333333333' 'H2_X=trailing ' ' H2_X=indented' 'H2_X'; do \
		{ cat "$$env"; echo "$$b"; } > "$$bad"; \
		if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed with $$b"; exit 1; fi; \
	done; \
	grep -v DATABASE "$$env" > "$$bad"; \
	if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed without H2_DATABASE_PASSWORD"; exit 1; fi; \
	for k in H2_DATABASE_PUBLIC_PASSWORD H2_REDIS_BACKOFFICE_PASSWORD; do \
		grep -v "^$$k=" "$$env" > "$$bad"; \
		if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed without $$k"; exit 1; fi; \
	done; \
	sed 's/^H2_DATABASE_PUBLIC_PASSWORD=.*/H2_DATABASE_PUBLIC_PASSWORD=33333333333333333333333333333333/' "$$env" > "$$bad"; \
	if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed with the owner's password on h2_public"; exit 1; fi; \
	sed 's/^H2_DATABASE_BACKOFFICE_PASSWORD=.*/H2_DATABASE_BACKOFFICE_PASSWORD=short-and-not-hex/' "$$env" > "$$bad"; \
	if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed with a short database password"; exit 1; fi; \
	sed 's|^H2_DATABASE_PASSWORD=.*|H2_DATABASE_PASSWORD=Ab+/Cd=_Ef-0123456789abcdefghijklmnopq|' "$$env" > "$$bad"; \
	./check-app-env.sh "$$bad" >/dev/null || { echo "check-app-env.sh refused a base64 owner password (openssl rand -base64 32)"; exit 1; }; \
	sed 's|^H2_DATABASE_PASSWORD=.*|H2_DATABASE_PASSWORD=Ab Cd0123456789abcdefghijklmnopqrstu|' "$$env" > "$$bad"; \
	if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed with a space in a database password"; exit 1; fi; \
	sed 's|^H2_REDIS_PUBLIC_PASSWORD=.*|H2_REDIS_PUBLIC_PASSWORD=Ab+/Cd0123456789abcdefghijklmnopqr|' "$$env" > "$$bad"; \
	if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed with a symbol in a Redis password"; exit 1; fi; \
	echo "== ADR 0070: no app holds the owner's or Redis' default password; only Postgres, pg-backup and the migrate Job get the owner's"; \
	for e in prod staging; do \
		r=(helm template helper2 $(CHART) -f $(CHART)/values-$$e.yaml --set image.tag=000000000000 --set backup.enabled=true); \
		for t in app backoffice; do \
			out=$$("$${r[@]}" --show-only templates/$$t.yaml); \
			if grep -qE 'key: H2_(DATABASE|REDIS)_PASSWORD \}' <<< "$$out"; then echo "$$e $$t: holds the owner's or Redis' default password"; exit 1; fi; \
			grep -qE 'name: H2_DATABASE_USER, value: h2_(public|backoffice) \}' <<< "$$out" || { echo "$$e $$t: not its own database role"; exit 1; }; \
			grep -qE 'name: H2_REDIS_USER, value: h2_(public|backoffice) \}' <<< "$$out" || { echo "$$e $$t: not its own Redis user"; exit 1; }; \
			if grep -q 'secretRef: { name: helper2-credentials }' <<< "$$out"; then echo "$$e $$t: loads the credentials Secret whole"; exit 1; fi; \
		done; \
		owners=$$("$${r[@]}" | grep -cE 'key: H2_DATABASE_PASSWORD \}' || true); \
		[ "$$owners" -eq 3 ] || { echo "$$e: the owner's password goes to $$owners places, want 3 (postgres, pg-backup, migrate)"; exit 1; }; \
		job=$$("$${r[@]}" --show-only templates/migrate.yaml); \
		grep -q 'args: \[migrate\]' <<< "$$job" || { echo "$$e: the migrate Job does not run the migrate step"; exit 1; }; \
		if grep -q 'envFrom' <<< "$$job"; then echo "$$e: the migrate Job loads a whole Secret"; exit 1; fi; \
		[ "$$("$${r[@]}" --show-only templates/redis.yaml | grep -cE '^ +user h2_(public|backoffice) on >[$$]H2_REDIS_(PUBLIC|BACKOFFICE)_PASSWORD ')" -eq 2 ] \
			|| { echo "$$e: Redis must define the two ACL users of files/redis-users.acl"; exit 1; }; \
		"$${r[@]}" --show-only templates/redis.yaml | grep -qF 'for v in "$$REDISCLI_AUTH" "$$H2_REDIS_PUBLIC_PASSWORD" "$$H2_REDIS_BACKOFFICE_PASSWORD"; do' \
			|| { echo "$$e: Redis must refuse a password that is empty or not letters and digits before it starts"; exit 1; }; \
		for a in "" "--set redis.auth.enabled=false"; do \
			"$${r[@]}" $$a | ./check-env-overrides.sh $(ENV_SECRET) \
				|| { echo "$$e $$a: a container loads $(ENV_SECRET) without overriding both passwords"; exit 1; }; \
		done; \
	done; \
	echo "== the chart renders no Secret: app.env never sits in Helm's release history"; \
	if helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 | grep -q '^kind: Secret$$'; then \
		echo "the chart renders a Secret"; exit 1; fi; \
	echo "== envSecret.revision restarts the app, envSecret.redisPasswordRevision restarts Redis"; \
	rev=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		--set envSecret.revision=abc --set envSecret.redisPasswordRevision=def --show-only templates/app.yaml --show-only templates/backoffice.yaml --show-only templates/redis.yaml); \
	[ "$$(grep -c 'checksum/env-revision: "abc"' <<< "$$rev")" -eq 2 ] || { echo "app and back office: no checksum/env-revision"; exit 1; }; \
	grep -q 'checksum/auth: "def"' <<< "$$rev" || { echo "redis: no checksum/auth"; exit 1; }; \
	offsite=(--set backup.offsite.enabled=true --set backup.offsite.endpoint=https://example.r2.cloudflarestorage.com --set backup.offsite.bucket=ci); \
	for v in "staging" "prod" "prod $${offsite[*]} --set monitoring.prometheusRule.enabled=true"; do \
		set -- $$v; e=$$1; shift; \
		echo "== $$e $$*"; \
		args=(-f $(CHART)/values-$$e.yaml --set image.tag=000000000000 "$$@"); \
		helm lint --strict $(CHART) "$${args[@]}"; \
		helm template helper2 $(CHART) -n helper2-$$e "$${args[@]}" \
			| kubeconform -strict -summary -schema-location default -schema-location '$(CRD_SCHEMAS)'; \
		redis=$$(helm template helper2 $(CHART) -n helper2-$$e "$${args[@]}" --show-only templates/redis.yaml); \
		grep -qF -- '--save "" --appendonly yes --appendfsync everysec' <<<"$$redis" \
			|| { echo "Redis must run with AOF and no RDB snapshots (--save \"\" --appendonly yes --appendfsync everysec)"; exit 1; }; \
		grep -q '^  volumeClaimTemplates:' <<<"$$redis" || { echo "Redis must keep /data on a PersistentVolumeClaim"; exit 1; }; \
	done; \
	echo "== daily limits and the guide cache TTL are rendered only where set: prod the credit cap, staging all six"; \
	limits='name: H2_(LIVE_WEB_GUEST_SEARCH|LIVE_WEB_GUEST_GUIDE|GUIDE_CLIENT_DAILY_QUESTIONS|LIVE_WEB_DAILY_CREDITS|GUIDE_DAILY_MODEL_CALLS|GUIDE_CACHE_TTL),'; \
	for e in prod staging; do \
		n=$$(helm template helper2 $(CHART) -f $(CHART)/values-$$e.yaml --set image.tag=000000000000 \
			--show-only templates/app.yaml | grep -cE "$$limits" || true); \
		want=1; [ $$e = staging ] && want=6; \
		[ "$$n" -eq "$$want" ] || { echo "$$e renders $$n daily limits and cache TTL, want $$want"; exit 1; }; \
	done; \
	echo "== prod signs in with GitHub and reuses similar guide answers at 0.96"; \
	prod=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --show-only templates/app.yaml); \
	grep -qE 'oauth-github' <<< "$$prod" || { echo "prod: no oauth-github profile"; exit 1; }; \
	grep -qE 'name: H2_GUIDE_SIMILAR_THRESHOLD, value: "0.96"' <<< "$$prod" || { echo "prod: no similar threshold 0.96"; exit 1; }; \
	echo "== shutdown (ADR 0027): preStop sleep 10 s, drain 30 s, grace 45 s, all from app.shutdown"; \
	grep -qE '^      terminationGracePeriodSeconds: 45$$' <<< "$$prod" || { echo "prod: terminationGracePeriodSeconds is not 45"; exit 1; }; \
	grep -qE 'name: H2_SHUTDOWN_TIMEOUT, value: "30s"' <<< "$$prod" || { echo "prod: H2_SHUTDOWN_TIMEOUT is not 30s"; exit 1; }; \
	grep -A2 '^            preStop:$$' <<< "$$prod" | grep -qE 'sleep: \{ seconds: 10 \}' || { echo "prod: no preStop sleep of 10 s"; exit 1; }; \
	grace=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		--set app.shutdown.preStopSeconds=3 --set app.shutdown.drainSeconds=20 --show-only templates/app.yaml); \
	grep -qE '^      terminationGracePeriodSeconds: 28$$' <<< "$$grace" || { echo "grace period must be preStopSeconds + drainSeconds + 5"; exit 1; }; \
	echo "== every image but the apps' is pinned by digest (ADR 0046); postgres once postgres.image.digest is set"; \
	imgs=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		$${offsite[*]} --set postgres.image.digest=sha256:$$(printf '0%.0s' $$(seq 64)) | grep -E '^ *image: ' | grep -vE '/apps/helper2(-backoffice|-web)?:' | sort -u); \
	test -n "$$imgs" || { echo "no images rendered"; exit 1; }; \
	if grep -v '@sha256:[0-9a-f]\{64\}' <<< "$$imgs"; then echo "these images are not pinned by digest"; exit 1; fi; \
	echo "== redis.auth.existingSecret: redis.auth.revision restarts Redis and the app; without it, no revision annotation"; \
	r=(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		--show-only templates/app.yaml --show-only templates/backoffice.yaml --show-only templates/redis.yaml); \
	ext=$$("$${r[@]}" --set redis.auth.existingSecret=redis-auth --set redis.auth.revision=7); \
	[ "$$(grep -c 'checksum/redis-auth-revision: "7"' <<< "$$ext")" -eq 3 ] \
		|| { echo "existingSecret: both apps and redis must carry checksum/redis-auth-revision: \"7\""; exit 1; }; \
	if grep -q 'checksum/auth:' <<< "$$ext"; then echo "existingSecret: no checksum/auth expected"; exit 1; fi; \
	if "$${r[@]}" | grep -q 'checksum/redis-auth-revision'; then echo "chart-managed password: no revision annotation expected"; exit 1; fi; \
	if "$${r[@]}" --set redis.auth.existingSecret=redis-auth --set redis.auth.revision=v2 >/dev/null 2>&1; then \
		echo "redis.auth.revision=v2 must fail the render"; exit 1; fi; \
	echo "== edge (ADR 0035, 0069): Service helper2-admin selects the back office; ingress-nginx reaches the three workloads on 8080 only; the web pods take nothing else"; \
	for e in prod staging; do \
		r=(helm template helper2 $(CHART) -f $(CHART)/values-$$e.yaml --set image.tag=000000000000); \
		app=$$("$${r[@]}" --show-only templates/app.yaml); bo=$$("$${r[@]}" --show-only templates/backoffice.yaml); \
		np=$$("$${r[@]}" --show-only templates/networkpolicy.yaml); \
		grep -A8 '^  name: helper2-admin$$' <<< "$$bo" | grep -q 'selector: { app: helper2-backoffice }' \
			|| { echo "$$e: Service helper2-admin does not select the back office"; exit 1; }; \
		if grep -qE 'H2_ADMIN_PORT|name: admin }|H2_SCOUT_' <<< "$$app"; then echo "$$e: the public app opens an admin port or runs the Scout"; exit 1; fi; \
		grep -q 'name: H2_SCOUT_ENABLED' <<< "$$bo" || { echo "$$e: the back office has no Scout settings"; exit 1; }; \
		[ "$$(grep -A5 'kubernetes.io/metadata.name: ingress-nginx' <<< "$$np" | grep -c 'ports: \[{ port: 8080, protocol: TCP }\]')" -eq 3 ] \
			|| { echo "$$e: ingress-nginx must reach the web pods and both apps on 8080 only"; exit 1; }; \
		web=$$(awk '/^  name: ingress-web$$/ {p=1} p && /^---/ {exit} p' <<< "$$np"); \
		grep -q 'podSelector: { matchLabels: { app: helper2-web } }' <<< "$$web" && [ "$$(grep -c 'port:' <<< "$$web")" -eq 1 ] \
			|| { echo "$$e: NetworkPolicy ingress-web must let in 8080 only"; exit 1; }; \
	done; \
	echo "== web (ADR 0069): the public pod copies its shell from the web image the web pods run, into a read-only /shell"; \
	r=(helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --set web.image.repository=reg/helper2-web --set web.image.tag=111111111111); \
	web=$$(helm template "$${r[@]}" --show-only templates/web.yaml | grep -E '^ *image: ' | sed 's/^ *//' | sort -u); \
	init=$$(helm template "$${r[@]}" --show-only templates/app.yaml | awk '/initContainers:/ {p=1} /^      containers:/ {p=0} p' | grep -E '^ *image: ' | sed 's/^ *//' | sort -u); \
	[ "$$web" = "image: reg/helper2-web:000000000000" ] && [ "$$web" = "$$init" ] \
		|| { echo "the web pods ($$web) and the init container ($$init) must both run the web image tagged image.tag"; exit 1; }; \
	app=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --show-only templates/app.yaml); \
	grep -qF '{ name: shell, mountPath: /shell, readOnly: true }' <<< "$$app" || { echo "the app must mount /shell read-only"; exit 1; }; \
	echo "== PrometheusRule holds deploy/monitoring/helper2-rules.yaml"; \
	rule=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		--set monitoring.prometheusRule.enabled=true --show-only templates/prometheusrule.yaml); \
	diff <(grep -oE 'alert: [A-Za-z0-9]+' <<<"$$rule") <(grep -oE 'alert: [A-Za-z0-9]+' $(ROOT)/deploy/monitoring/helper2-rules.yaml) \
		|| { echo "the PrometheusRule must hold the alerts of deploy/monitoring/helper2-rules.yaml"; exit 1; }; \
	echo "== backup (ADR 0027): a failed pg_dump fails the Job, the dump is verified before it replaces the old one, old dumps go last"; \
	bk=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --show-only templates/backup.yaml); \
	grep -q 'set -o pipefail' <<< "$$bk" || { echo "backup: no pipefail"; exit 1; }; \
	grep -q 'startingDeadlineSeconds: 3600' <<< "$$bk" || { echo "backup: no startingDeadlineSeconds"; exit 1; }; \
	awk '/gzip -t "\$$f.tmp"/ {t=NR} /mv "\$$f.tmp" "\$$f"/ {m=NR} /find \/backup .* -delete/ {d=NR} END { exit !(t && m && d && t < m && m < d) }' <<< "$$bk" \
		|| { echo "backup: gzip -t must come before mv, and find -delete after both"; exit 1; }

check-env:
	@test -f $(ENV)/app.env || { echo "$(ENV)/app.env missing: copy app.env.example and fill it"; exit 1; }

# The revision a rollback goes to: the newest one before the current that Helm marked deployed (still
# deployed while the current one is pending or failed, superseded once a later upgrade succeeded).
# `helm rollback` without a revision takes the one just before the current, which after an interrupted
# rollback is the failed upgrade itself (10 deployed, 11 failed, 12 pending-rollback: 11).
LAST_GOOD_REVISION = helm -n $(NS) history helper2 -o json | jq -r \
	'(max_by(.revision).revision) as $$now \
	 | [.[] | select(.revision != $$now and (.status == "deployed" or .status == "superseded")) | .revision] \
	 | max // empty'

# A release left in pending-install/upgrade/rollback (an interrupted helm run) refuses every new
# upgrade with "another operation is in progress". Recover it by rolling back to the last deployed
# revision before starting a new upgrade. No release yet (first install) is fine.
release-ready:
	@status=$$(helm -n $(NS) status helper2 2>/dev/null | awk '/^STATUS:/ {print $$2}'); \
	case "$$status" in \
	  pending-*) echo "release helper2 in $(NS) is $$status: an earlier helm run was interrupted."; \
	             rev=$$($(LAST_GOOD_REVISION)); \
	             if [ -z "$$rev" ]; then \
	               echo "No earlier deployed revision to roll back to."; \
	               echo "Check the pods and: helm -n $(NS) history helper2"; \
	               exit 1; \
	             fi; \
	             echo "Trying automatic recovery: rollback to revision $$rev, the last deployed one."; \
	             if ! helm -n $(NS) rollback helper2 $$rev --wait --timeout 12m >/dev/null; then \
	               echo "Automatic rollback failed."; \
	               echo "Check the pods, then run: make ENV=$(ENV) rollback"; \
	               exit 1; \
	             fi; \
	             status=$$(helm -n $(NS) status helper2 2>/dev/null | awk '/^STATUS:/ {print $$2}'); \
	             case "$$status" in \
	               pending-*) echo "release helper2 in $(NS) is still $$status after rollback."; \
	                          echo "Check the pods, then run: make ENV=$(ENV) rollback"; \
	                          exit 1 ;; \
	             esac; \
	             echo "release helper2 in $(NS) recovered: $$status." ;; \
	esac

# Revision 1 exists in both envs (adopted from kustomize on 2026-10-26), so a failed upgrade rolls
# back to the previous revision. Never `helm uninstall`: it would delete the Postgres StatefulSet
# (its data PVC stays, it is not owned by the release).
# --force-conflicts: Helm is the only owner. Fields last written by kubectl (kustomize era, or a manual
# `kubectl edit`) would otherwise block server-side apply, e.g. the image on the first new tag.
# 12m: the app's replicas are replaced one after another, each only once the previous one is ready.
# --wait-for-jobs: the release succeeds only once the migrate Job has (ADR 0070); a failed Job rolls it back.
# --history-max 3: enough to roll back; older revisions are deleted.
deploy: check-env release-ready env-secret ## env-secret, then helm upgrade ENV to TAG (default: current commit), wait, roll back on failure
	helm upgrade --install $(HELM_ARGS) --server-side=true --force-conflicts --wait --wait-for-jobs --timeout 12m --rollback-on-failure --history-max 3

# Server-side apply under its own field manager: a key removed from app.env is removed from the Secret too.
# The pods read it at start only; deploy passes its hash to Helm, so a change restarts them.
# H2_REDIS_PASSWORD is required only when the rendered chart reads it from this Secret: not with
# redis.auth.existingSecret, and not with redis.auth.enabled=false.
env-secret: check-env ## write ENV's app.env into the Secrets helper2-credentials and helper2-app-env (checked first by check-app-env.sh)
	@set -euo pipefail; \
	rendered=$$(helm template $(HELM_ARGS)); \
	redis_flag=--no-redis-password; \
	if grep -qF 'secretKeyRef: { name: $(CREDS_SECRET), key: H2_REDIS_PASSWORD }' <<< "$$rendered"; then redis_flag=; fi; \
	./check-app-env.sh $(ENV)/app.env $$redis_flag; \
	kubectl -n $(NS) create secret generic $(CREDS_SECRET) --dry-run=client -o yaml \
		--from-env-file=<(grep -E '^($(CREDENTIALS))=' $(ENV)/app.env) \
		| kubectl -n $(NS) apply --server-side --field-manager=helper2-env-secret -f - >/dev/null; \
	kubectl -n $(NS) create secret generic $(ENV_SECRET) --dry-run=client -o yaml \
		--from-env-file=<(grep -vE '^($(NOT_IN_APP_ENV))=' $(ENV)/app.env) \
		| kubectl -n $(NS) apply --server-side --field-manager=helper2-env-secret -f - >/dev/null; \
	echo "wrote $(NS)/$(CREDS_SECRET) and $(NS)/$(ENV_SECRET) from $(ENV)/app.env (revision $(ENV_REV))"

# kubectl diff exits 1 when there are changes (the usual case before a deploy) and >1 on an error
# (auth, API, invalid manifest): only 1 is tolerated. The chart is rendered first, so a render error
# cannot pass as a diff either.
diff: check-env ## what deploy would change in ENV (not the env Secret: env-secret writes it)
	@set -uo pipefail; \
	out=$$(mktemp); trap 'rm -f "$$out"' EXIT; \
	helm template $(HELM_ARGS) > "$$out" || exit; \
	kubectl diff -n $(NS) -f "$$out"; rc=$$?; \
	if [ $$rc -gt 1 ]; then echo "kubectl diff failed (exit $$rc)"; exit $$rc; fi

rollback: ## back to the last deployed helm revision of ENV (not a failed or interrupted one)
	@set -euo pipefail; \
	rev=$$($(LAST_GOOD_REVISION)); \
	test -n "$$rev" || { echo "no earlier deployed revision of helper2 in $(NS) to roll back to"; helm -n $(NS) history helper2 --max 5; exit 1; }; \
	echo "rolling back helper2 in $(NS) to revision $$rev"; \
	helm -n $(NS) rollback helper2 $$rev --wait --timeout 12m
	helm -n $(NS) history helper2 --max 5

status: ## ENV's pods (READY = the readiness probe) and the public site
	kubectl -n $(NS) get pods -o wide
	@# Actuator health is on the management port (8081), which the Services and the Ingresses never route.
	curl -s -o /dev/null -w "https://$(HOST)/ -> %{http_code}\n" https://$(HOST)/

# --- CD per component, signing and verification (ADR 0071) ---------------------------------------------------------

.PHONY: ci-images sign sign-existing verify-images verified cosign-pub chart-check-digests

# PUSHED, an absolute path: `make image` and `make postgres-image` append repository@digest of every image they push,
# taken from the push itself (podman --digestfile) and checked against Harbor right after. `make sign` signs those
# digests and nothing else, so an image someone else put in Harbor never gets our signature.
PUSHED ?=
PUSH_DIGEST = $(if $(PUSHED),--digestfile "$$work/pushed.digest")
record_push = $(if $(PUSHED),pushed=$$(cat "$$work/pushed.digest"); have=$$(harbor_api $(1) $(2) -f | jq -er .digest); [[ $$have == "$$pushed" ]] || { echo "$(1):$(2) is $$have in Harbor and not $$pushed as pushed"; exit 1; }; echo "$(1)@$$pushed" >> "$(PUSHED)";)

# CD per component: every push gives all three images the tag TAG, so the three always share one tag (the public pod
# copies its shell from the web image of image.tag). A component in BUILD (from .github/scripts/changes.sh) is built
# with `make image`. Any other gets TAG on the image of FROM, the commit CD ran before: the same digest, no build, and
# so the same signature. Carried only when that digest verifies with cosign.pub; built when FROM is empty, its image is
# not in Harbor (its run failed), or it is not signed by our key (the first run after signing came in). A TAG already
# in Harbor (a rerun) must verify: an unsigned one fails the run, it is never signed here.
BUILD ?= web public backoffice
FROM  ?=
COMPONENTS := web=apps/helper2-web=deploy/web.Dockerfile public=apps/helper2=deploy/Dockerfile \
		backoffice=apps/helper2-backoffice=deploy/backoffice.Dockerfile
ci-images: ## CD per component: build the components in BUILD, give the others TAG on the signed image of FROM
	@set -euo pipefail; \
	[[ -z "$(FROM)" || "$(FROM)" =~ ^[0-9a-f]{12}$$ ]] || { echo "FROM must be a 12-char commit, got '$(FROM)'"; exit 1; }; \
	[[ "$(PUSHED)" == /* ]] || { echo "PUSHED must be an absolute path: the digests this run pushes, for make sign"; exit 1; }; \
	for name in $(BUILD); do [[ " web public backoffice " == *" $$name "* ]] || { echo "BUILD: unknown component $$name"; exit 1; }; done; \
	$(HARBOR); $(COSIGN); \
	build=""; \
	for entry in $(COMPONENTS); do \
		name=$${entry%%=*}; image_entry=$${entry#*=}; repo=$${image_entry%%=*}; image=$(REGISTRY)/helper2/$$repo; \
		code=$$(harbor_api $$repo $(TAG) -o "$$work/tag.json" -w "%{http_code}"); case $$code in \
			200) digest=$$(jq -er .digest "$$work/tag.json"); \
				verify $$image@$$digest || { cat "$$work/verify.log"; \
					echo "$$image:$(TAG) ($$digest) is in Harbor but not signed by cosign.pub. CD signs only what it pushes itself."; \
					echo "Check where it came from; if CD built it, sign it from the workstation: make sign-existing TAG=$(TAG)"; exit 1; }; \
				echo "$$image:$(TAG) is in Harbor already, signed ($$digest)"; continue;; \
			404) ;; \
			*) echo "Harbor answered HTTP $$code for $$image:$(TAG)"; exit 1;; \
		esac; \
		if [[ " $(BUILD) " == *" $$name "* ]]; then echo "$$name changed: build"; build="$$build $$image_entry"; continue; fi; \
		if [ -z "$(FROM)" ]; then echo "$$name: no earlier commit to carry from: build"; build="$$build $$image_entry"; continue; fi; \
		code=$$(harbor_api $$repo $(FROM) -o "$$work/from.json" -w "%{http_code}"); case $$code in \
			200) digest=$$(jq -er .digest "$$work/from.json"); \
				if verify $$image@$$digest; then \
					jq -n --arg name "$(TAG)" '{name: $$name}' \
						| harbor_api $$repo $(FROM)/tags -f -X POST -H 'Content-Type: application/json' --data-binary @- -o /dev/null; \
					echo "$$name unchanged: tagged $$image:$(FROM) as $(TAG) ($$digest, signed)"; \
				else \
					echo "$$name: $$image:$(FROM) ($$digest) has no valid signature by cosign.pub: build"; build="$$build $$image_entry"; \
				fi;; \
			404) echo "$$name: $$image:$(FROM) is not in Harbor: build"; build="$$build $$image_entry";; \
			*) echo "Harbor answered HTTP $$code for $$image:$(FROM)"; exit 1;; \
		esac; \
	done; \
	if [ -n "$$build" ]; then $(MAKE) --no-print-directory image TAG=$(TAG) ENGINE=$(ENGINE) IMAGES="$$build" PUSHED="$(PUSHED)"; fi

# Signatures: cosign with the key in OpenBao Transit (COSIGN_KEY, mount TRANSIT_MOUNT). The key never leaves OpenBao;
# cosign.pub is its public half, committed. No transparency log: cosign-signing-config.json names no service, and
# verify skips the log. The trust is the key.
COSIGN_KEY     ?= hashivault://helper2-images
TRANSIT_MOUNT  ?= helper2/transit
COSIGN_PUB     := $(abspath cosign.pub)
SIGNING_CONFIG := $(abspath cosign-signing-config.json)
# After $(HARBOR): cosign logs in to Harbor with the same robot, into a config in $$work (gone with the trap);
# `digest_of <repository> <tag>` reads a tag's digest from Harbor; `verify <image@digest>` checks it against
# cosign.pub (exit 10: no signature at all).
define COSIGN
grep -q 'BEGIN PUBLIC KEY' $(COSIGN_PUB) || { echo "$(COSIGN_PUB) holds no public key yet: make cosign-pub"; exit 1; }; \
export DOCKER_CONFIG="$$work/docker"; \
printf '%s' "$$pass" | cosign login $(REGISTRY) --username "$$user" --password-stdin >/dev/null 2>"$$work/login.log" \
	|| { cat "$$work/login.log"; echo "cosign login to $(REGISTRY) failed"; exit 1; }; \
digest_of() { harbor_api "$$1" "$$2" -f | jq -er .digest; }; \
verify() { cosign verify --key $(COSIGN_PUB) --insecure-ignore-tlog=true "$$1" </dev/null >/dev/null 2>"$$work/verify.log"; }
endef
# Before cosign talks to Transit: the token of bao-login (CD) or of an operator login (workstation, OIDC when BAO_TOKEN
# is not set), handed to cosign as VAULT_ADDR and VAULT_TOKEN. Every other VAULT_ variable is dropped first, so a
# setting of the machine (or a token helper, ~/.vault-token) never wins.
define TRANSIT
if [ -z "$${BAO_TOKEN:-}" ] && [ -z "$${GITHUB_ACTIONS:-}" ]; then \
	BAO_TOKEN=$$(bao login -method=oidc -path=oidc -token-only role=helper2-operator); \
fi; \
[ -n "$${BAO_ADDR:-}" ] && [ -n "$${BAO_TOKEN:-}" ] || { echo "BAO_ADDR and BAO_TOKEN must be set to sign"; exit 1; }; \
export BAO_TOKEN; \
for v in $$(env | sed -n 's/^\(VAULT_[A-Za-z0-9_]*\)=.*/\1/p'); do unset "$$v"; done; \
export VAULT_ADDR="$$BAO_ADDR" VAULT_TOKEN="$$BAO_TOKEN" TRANSIT_SECRET_ENGINE_PATH=$(TRANSIT_MOUNT); \
if [ -n "$${BAO_CACERT:-}" ]; then export VAULT_CACERT="$$BAO_CACERT"; fi
endef
cosign_sign = cosign sign --yes --signing-config $(SIGNING_CONFIG) --key $(COSIGN_KEY) $(1) </dev/null >/dev/null

# CD: signs exactly the digests in PUSHED, the images this run built and pushed (and the Postgres image when its tag was
# new). Then every image of TAG and the Postgres image must verify: a carried image is signed already; an image pushed
# before signing (the current Postgres image, once) fails here and points at sign-existing.
SIGNED_REFS = apps/helper2-web:$(TAG) apps/helper2:$(TAG) apps/helper2-backoffice:$(TAG) apps/postgres:$(PG_TAG)
sign: ## CD: sign exactly the digests in PUSHED (pushed by this run), then check every image of TAG and the Postgres image
	@set -euo pipefail; \
	test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }; \
	[[ "$(PUSHED)" == /* ]] || { echo "PUSHED must be the absolute path that ci-images and postgres-image wrote"; exit 1; }; \
	$(HARBOR); $(COSIGN); $(TRANSIT); \
	touch "$(PUSHED)"; \
	while IFS= read -r line <&3; do \
		[[ $$line =~ ^apps/(helper2|helper2-web|helper2-backoffice|postgres)@sha256:[0-9a-f]{64}$$ ]] \
			|| { echo "PUSHED holds '$$line', not repository@digest of one of our images"; exit 1; }; \
		image=$(REGISTRY)/helper2/$$line; \
		rc=0; verify $$image || rc=$$?; case $$rc in \
			0) echo "$$line is signed already";; \
			10) $(call cosign_sign,$$image); \
				verify $$image || { cat "$$work/verify.log"; echo "$$line: signed, but the signature does not verify with cosign.pub"; exit 1; }; \
				echo "signed $$line (pushed by this run)";; \
			*) cat "$$work/verify.log"; echo "$$line: cosign verify failed (exit $$rc), not a missing signature: not signed"; exit 1;; \
		esac; \
	done 3< "$(PUSHED)"; \
	$(MAKE) --no-print-directory verify-images TAG=$(TAG) DIGESTS=/dev/null

# Workstation only, never from a workflow (chart-check fails if one names it): signs the images of TAG and the Postgres
# image of PG_TAG that have no signature at all, after showing their digests and a confirmation (or CONFIRM=yes).
# For the images pushed before signing: the current Postgres image once, and an old tag before a rollback to it. Sign
# only what CD built: check the digests against that commit's CD run first. Needs sign rights on helper2-images (helper2-operator, policy helper2-write).
CONFIRM ?=
sign-existing: ## workstation only: sign the unsigned images of TAG and of PG_TAG, after a confirmation (or CONFIRM=yes)
	@set -euo pipefail; \
	[ -z "$${GITHUB_ACTIONS:-}" ] && [ -z "$${CI:-}" ] || { echo "sign-existing is for the operator's workstation, never for CI"; exit 1; }; \
	[[ "$(TAG)" =~ ^[0-9a-f]{12}$$ || "$(TAG)" =~ ^[0-9]+\.[0-9]+\.[0-9]+$$ ]] || { echo "TAG must be a 12-char commit or X.Y.Z, got '$(TAG)'"; exit 1; }; \
	test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }; \
	$(TRANSIT); $(HARBOR); $(COSIGN); \
	todo=""; \
	for ref in $(SIGNED_REFS); do \
		repo=$${ref%%:*}; tag=$${ref#*:}; \
		digest=$$(digest_of $$repo $$tag) || { echo "$(REGISTRY)/helper2/$$ref is not in Harbor"; exit 1; }; \
		image=$(REGISTRY)/helper2/$$repo@$$digest; \
		rc=0; verify $$image || rc=$$?; case $$rc in \
			0) echo "$$ref ($$digest): signed already";; \
			10) echo "$$ref ($$digest): no signature"; todo="$$todo $$image";; \
			*) cat "$$work/verify.log"; echo "$$ref: cosign verify failed (exit $$rc), not a missing signature: not signed"; exit 1;; \
		esac; \
	done; \
	[ -n "$$todo" ] || { echo "nothing to sign"; exit 0; }; \
	echo "To sign with $(COSIGN_KEY):"; printf '  %s\n' $$todo; \
	if [ "$(CONFIRM)" != yes ]; then \
		[ -t 0 ] || { echo "no terminal to confirm on: check the digests, then run again with CONFIRM=yes"; exit 1; }; \
		read -r -p "Sign these? Only images CD built (check the digests in the commit's CD run). [y/N] " answer; \
		[[ $$answer == [yY] ]] || { echo "nothing signed"; exit 1; }; \
	fi; \
	for image in $$todo; do \
		$(call cosign_sign,$$image); \
		verify $$image || { cat "$$work/verify.log"; echo "$$image: signed, but the signature does not verify with cosign.pub"; exit 1; }; \
		echo "signed $$image"; \
	done

# The digest of each image of TAG and of the Postgres image in Harbor, and its signature checked against cosign.pub.
# One failure fails. Writes APP_DIGEST, BO_DIGEST, WEB_DIGEST and PG_DIGEST to DIGESTS. deploy.yml runs it with its own
# commit's Makefile before it checks out the target commit, and diff, env-secret and deploy run it again (`verified`).
DIGESTS ?= /dev/stdout
verify-images: ## digest of each image of TAG and of the Postgres image, signature checked against cosign.pub, into DIGESTS
	@set -euo pipefail; \
	test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }; \
	$(HARBOR); $(COSIGN); \
	out=""; \
	for entry in WEB_DIGEST=apps/helper2-web:$(TAG) APP_DIGEST=apps/helper2:$(TAG) BO_DIGEST=apps/helper2-backoffice:$(TAG) \
		PG_DIGEST=apps/postgres:$(PG_TAG); do \
		key=$${entry%%=*}; ref=$${entry#*=}; repo=$${ref%%:*}; tag=$${ref#*:}; \
		digest=$$(digest_of $$repo $$tag) || { echo "$(REGISTRY)/helper2/$$ref is not in Harbor" >&2; exit 1; }; \
		verify $(REGISTRY)/helper2/$$repo@$$digest || { cat "$$work/verify.log" >&2; \
			echo "::error::$$ref ($$digest) has no valid signature by cosign.pub. An image from before signing: make sign-existing TAG=$(TAG) on the workstation (deploy/k8s/CI.md)" >&2; exit 1; }; \
		echo "$$ref ($$digest): signature verified" >&2; \
		out="$$out$$key=$$digest"$$'\n'; \
	done; \
	printf '%s' "$$out" >> $(DIGESTS)

# diff, env-secret and deploy check the signatures themselves: `verified` runs verify-images into VERIFIED, bound to TAG,
# and HELM_ARGS takes the four digests only from that file. A file of another TAG, or none, stops the target. The
# lines are separate so that `make -n` runs nothing but the verify-images dry run.
VERIFIED ?= $(ENV)/digests.env
verified:
	@rm -f "$(VERIFIED)" "$(VERIFIED).new"; mkdir -p "$(dir $(VERIFIED))"
	@$(MAKE) --no-print-directory verify-images TAG=$(TAG) DIGESTS="$(abspath $(VERIFIED)).new"
	@echo "TAG=$(TAG)" >> "$(VERIFIED).new"; mv -f "$(VERIFIED).new" "$(VERIFIED)"
diff env-secret: verified
verified_digest = $(shell sed -n 's/^$(1)=//p' $(VERIFIED) 2>/dev/null)
need_digest = $(if $(and $(filter TAG=$(TAG),$(shell cat $(VERIFIED) 2>/dev/null)),$(filter sha256:%,$(call verified_digest,$(1)))),--set $(2)=$(call verified_digest,$(1)),$(error $(1): no signature check for TAG $(TAG) in $(VERIFIED). Run make verified (ADR 0071)))
HELM_ARGS += $(call need_digest,APP_DIGEST,image.digest) $(call need_digest,BO_DIGEST,backoffice.image.digest) \
	$(call need_digest,WEB_DIGEST,web.image.digest) $(call need_digest,PG_DIGEST,postgres.image.digest)

# The public half of the Transit key, once (and after a key rotation): commit the new cosign.pub.
cosign-pub: ## write cosign.pub from the OpenBao Transit key (needs read on its keys/ path)
	@set -euo pipefail; $(TRANSIT); \
	cosign public-key --key $(COSIGN_KEY) > $(COSIGN_PUB).new; \
	grep -q 'BEGIN PUBLIC KEY' $(COSIGN_PUB).new; mv $(COSIGN_PUB).new $(COSIGN_PUB); \
	echo "wrote $(COSIGN_PUB) from $(COSIGN_KEY) (mount $(TRANSIT_MOUNT)): commit it"

# Part of chart-check: the deploy renders every app image as repository:tag@digest (the digests verify-images checked),
# the web pods and the public pod's init container the same web image, a bad digest fails the render, diff takes its
# digests only from a signature check of its own TAG, and no workflow calls sign-existing.
chart-check: chart-check-digests
chart-check-digests:
	@set -euo pipefail; \
	echo "== digests (ADR 0071): with the four digests set, every image is repository:tag@digest; the web pods and the init container run the same one"; \
	d() { printf 'sha256:%064d' "$$1"; }; \
	r=(helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --set image.digest=$$(d 1) \
		--set web.image.digest=$$(d 2) --set backoffice.image.digest=$$(d 3) --set postgres.image.digest=$$(d 4)); \
	imgs=$$(helm template "$${r[@]}" | grep -E '^ *image: ' | sed 's/^ *image: //' | sort -u); \
	for want in "$(IMAGE):000000000000@$$(d 1)" "$(WEB_IMAGE):000000000000@$$(d 2)" "$(BO_IMAGE):000000000000@$$(d 3)" "$(PG_REPO):$(PG_TAG)@$$(d 4)"; do \
		grep -qxF "$$want" <<< "$$imgs" || { echo "not rendered: $$want"; echo "$$imgs"; exit 1; }; \
	done; \
	if grep -v '@sha256:[0-9a-f]\{64\}$$' <<< "$$imgs"; then echo "these images are not pinned by digest"; exit 1; fi; \
	web=$$(helm template "$${r[@]}" --show-only templates/web.yaml | grep -E '^ *image: ' | sed 's/^ *//' | sort -u); \
	init=$$(helm template "$${r[@]}" --show-only templates/app.yaml | awk '/initContainers:/ {p=1} /^      containers:/ {p=0} p' | grep -E '^ *image: ' | sed 's/^ *//' | sort -u); \
	[ "$$web" = "image: $(WEB_IMAGE):000000000000@$$(d 2)" ] && [ "$$web" = "$$init" ] \
		|| { echo "the web pods ($$web) and the init container ($$init) must both run the web image tag@digest"; exit 1; }; \
	for v in image.digest web.image.digest backoffice.image.digest postgres.image.digest; do \
		if helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --set $$v=sha256:abc >/dev/null 2>&1; then \
			echo "$$v=sha256:abc must fail the render"; exit 1; fi; \
	done; \
	echo "== diff, env-secret and deploy take the digests only from their own signature check (verified), bound to TAG"; \
	v=$$(mktemp); trap 'rm -f "$$v"' EXIT; \
	dry() { $(MAKE) --no-print-directory -s -n diff ENV=staging TAG=000000000000 VERIFIED="$$v" APP_DIGEST=$$(d 9) 2>&1 || true; }; \
	grep -q 'APP_DIGEST: no signature check for TAG 000000000000' <<< "$$(dry)" || { echo "make diff ran without a signature check: $$(dry)"; exit 1; }; \
	printf 'WEB_DIGEST=%s\nAPP_DIGEST=%s\nBO_DIGEST=%s\nPG_DIGEST=%s\nTAG=111111111111\n' $$(d 2) $$(d 1) $$(d 3) $$(d 4) > "$$v"; \
	grep -q 'no signature check for TAG 000000000000' <<< "$$(dry)" || { echo "make diff took the digests checked for another TAG"; exit 1; }; \
	sed -i.bak 's/^TAG=.*/TAG=000000000000/' "$$v"; rm -f "$$v.bak"; \
	grep -qF -- "--set image.digest=$$(d 1) --set backoffice.image.digest=$$(d 3)" <<< "$$(dry)" \
		|| { echo "make diff does not pass the checked digests to Helm (or took APP_DIGEST from the environment): $$(dry)"; exit 1; }; \
	grep -q 'verify-images' <<< "$$(dry)" || { echo "make diff does not run verify-images first"; exit 1; }; \
	echo "== sign-existing (workstation only) is called by no workflow"; \
	if grep -rn 'sign-existing' $(ROOT)/.github; then echo "a workflow names make sign-existing: it is for the operator's workstation only"; exit 1; fi
