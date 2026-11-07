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
#   make ENV=staging diff        what the next deploy would change
#   make ENV=staging env-secret  write ENV's app.env into the Secret helper2-app-env (never through Helm)
#   make ENV=staging deploy      env-secret, then helm upgrade --install (chart ../helm/helper2) with TAG, wait,
#                                roll back on failure
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
	for v in "create deployments" "create secrets" "patch secrets/helper2-app-env"; do \
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
		$(ENGINE) push $$image:$(TAG); \
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
			$(ENGINE) push $(PG_REPO):$(PG_TAG); \
			echo "pushed $(PG_REPO):$(PG_TAG)";; \
		*) echo "Harbor answered HTTP $$code for $(PG_REPO):$(PG_TAG)"; exit 1;; \
	esac; \
	echo "$(PG_REPO):$(PG_TAG) digest: $$(harbor_api apps/postgres $(PG_TAG) -f | jq -r .digest)"

# The env Secret (ADR 0007): app.env goes to the cluster with kubectl, never through Helm, so no value is kept
# in the release history. Helm gets only hashes, which restart the app (any change) and Redis (a new password).
ENV_SECRET := helper2-app-env
ENV_REV     = $(shell sha256sum < $(ENV)/app.env 2>/dev/null | cut -c1-16)
REDIS_REV   = $(shell grep -E '^H2_REDIS_PASSWORD=' $(ENV)/app.env 2>/dev/null | sha256sum | cut -c1-16)
HELM_ARGS = helper2 $(CHART) -n $(NS) -f $(CHART)/values-$(ENV).yaml \
	--set image.repository=$(IMAGE) --set image.tag=$(TAG) --set backoffice.image.repository=$(BO_IMAGE) \
	--set web.image.repository=$(WEB_IMAGE) \
	--set envSecret.name=$(ENV_SECRET) --set envSecret.revision=$(ENV_REV) --set envSecret.redisPasswordRevision=$(REDIS_REV)

# CI runs this on every pull request; no cluster needed. Each environment's values (and prod with the
# off-site backup and the PrometheusRule on) with a fixture app.env, linted and checked against the Kubernetes
# schemas, and Redis rendered with AOF (no RDB snapshots) on its own PersistentVolumeClaim (ADR 0005). The
# PrometheusRule is checked against its CRD schema from the CRDs-catalog, pinned to a commit, and must hold the
# alerts of deploy/monitoring/helper2-rules.yaml (read through the chart's files/ symlink).
CRD_SCHEMAS := https://raw.githubusercontent.com/datreeio/CRDs-catalog/3eb90d47aba8d61919d4419dd38301536e32a94b/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json
chart-check: ## helm lint + helm template | kubeconform, for staging and prod; Redis AOF + PVC; preStop, drain and grace period; images pinned by digest; redis.auth.revision restarts; daily limits only where set; admin Service on the back office, ingress-nginx on 8080; the public pod's shell from the web image; PrometheusRule = rule file; the backup verifies its dump; the chart renders no Secret; app.env checks
	@set -euo pipefail; \
	env=$$(mktemp); bad=$$(mktemp); trap 'rm -f "$$env" "$$bad"' EXIT; \
	printf 'H2_DATABASE_PASSWORD=ci\nH2_REDIS_PASSWORD=00000000000000000000000000000000\n' > "$$env"; \
	echo "== app.env: a key outside ^H2_[A-Z0-9_]+$$, no database password or a short Redis password fails"; \
	./check-app-env.sh "$$env"; \
	for b in 'NEBIUS_API_KEY=stale' 'H2_REDIS_PASSWORD=0000000000000000' 'H2_DATABASE_PASSWORD=ci' 'H2_X=trailing ' ' H2_X=indented' 'H2_X'; do \
		{ cat "$$env"; echo "$$b"; } > "$$bad"; \
		if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed with $$b"; exit 1; fi; \
	done; \
	grep -v DATABASE "$$env" > "$$bad"; \
	if ./check-app-env.sh "$$bad" 2>/dev/null; then echo "check-app-env.sh passed without H2_DATABASE_PASSWORD"; exit 1; fi; \
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
# --history-max 3: enough to roll back; older revisions are deleted.
deploy: check-env release-ready env-secret ## env-secret, then helm upgrade ENV to TAG (default: current commit), wait, roll back on failure
	helm upgrade --install $(HELM_ARGS) --server-side=true --force-conflicts --wait --timeout 12m --rollback-on-failure --history-max 3

# Server-side apply under its own field manager: a key removed from app.env is removed from the Secret too.
# The pods read it at start only; deploy passes its hash to Helm, so a change restarts them.
# H2_REDIS_PASSWORD is required only when the rendered chart reads it from this Secret: not with
# redis.auth.existingSecret, and not with redis.auth.enabled=false.
env-secret: check-env ## write ENV's app.env into the Secret helper2-app-env (checked first by check-app-env.sh)
	@set -euo pipefail; \
	rendered=$$(helm template $(HELM_ARGS)); \
	redis_flag=--no-redis-password; \
	if grep -qF 'secretKeyRef: { name: $(ENV_SECRET), key: H2_REDIS_PASSWORD }' <<< "$$rendered"; then redis_flag=; fi; \
	./check-app-env.sh $(ENV)/app.env $$redis_flag; \
	kubectl -n $(NS) create secret generic $(ENV_SECRET) --from-env-file=$(ENV)/app.env --dry-run=client -o yaml \
		| kubectl -n $(NS) apply --server-side --field-manager=helper2-env-secret -f - >/dev/null; \
	echo "wrote $(NS)/$(ENV_SECRET) from $(ENV)/app.env (revision $(ENV_REV))"

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
