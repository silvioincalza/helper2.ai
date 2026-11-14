SHELL    := /bin/bash
ROOT     := $(abspath ../..)
REGISTRY := harbor-i.infra.incalza.me
IMAGE    := $(REGISTRY)/helper2/apps/helper2
BO_IMAGE := $(REGISTRY)/helper2/apps/helper2-backoffice
WEB_IMAGE := $(REGISTRY)/helper2/apps/helper2-web
CHART    := ../helm/helper2
PG_REPO  := $(REGISTRY)/helper2/apps/postgres
PG_TAG   := $(shell awk '/^postgres:/ {p=1; next} p && /^[^ \#]/ {exit} p && $$1 == "tag:" {print $$2; exit}' $(CHART)/values.yaml)
TAG      ?= $(shell git -C $(ROOT) rev-parse --short=12 HEAD)
ENGINE   ?= podman
ENV      ?= staging
NS       := helper2-$(ENV)
HOST     := $(if $(filter prod,$(ENV)),helper2.ai,helper2.incalza.me)

ifeq ($(filter $(ENV),staging prod),)
$(error ENV must be staging or prod)
endif

.PHONY: help credentials backup-credentials image retag sbom backoffice-sbom web-sbom postgres-sbom postgres-image chart-check check-env release-ready deploy diff rollback status canary canary-smoke uncanary

help: ## this list
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

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

backup-credentials: ## write ENV's off-site backup Secret (pg-backup-offsite) from OpenBao
	@set -euo pipefail; \
	r2=$$(bao kv get -mount=helper2/kv -format=json backup/r2); \
	fields=$$(jq -er '.data.data as $$d | ("access_key_id", "secret_access_key", "endpoint", "bucket", "crypt_password") | "\(.)=\($$d[.] // error("helper2/kv backup/r2 has no \(.)"))"' <<<"$$r2"); \
	kubectl -n $(NS) create secret generic pg-backup-offsite --dry-run=client -o yaml \
		--from-env-file=<(printf '%s\n' "$$fields") \
		| kubectl apply -f - >/dev/null; \
	echo "wrote $(NS)/pg-backup-offsite from helper2/kv backup/r2"

HARBOR_KV ?= ci/harbor
define HARBOR
work=$$(mktemp -d); trap 'rm -rf "$$work"' EXIT; \
creds=$$(bao kv get -mount=helper2/kv -format=json $(HARBOR_KV)); \
user=$$(jq -r .data.data.harbor_user <<<"$$creds"); pass=$$(jq -r .data.data.harbor_password <<<"$$creds"); \
printf 'user = "%s:%s"\n' "$$user" "$$pass" > "$$work/curl.cfg"; \
harbor_api() { curl -sS -K "$$work/curl.cfg" "https://$(REGISTRY)/api/v2.0/projects/helper2/repositories/$${1//\//%252F}/artifacts/$$2" "$${@:3}"; }; \
harbor_login() { printf '%s' "$$pass" | $(ENGINE) login $(REGISTRY) --username "$$user" --password-stdin >/dev/null; }
endef

IMAGES := apps/helper2-web=deploy/web.Dockerfile apps/helper2=deploy/Dockerfile apps/helper2-backoffice=deploy/backoffice.Dockerfile
PREBUILT ?=
image: ## build the linux/amd64 images of TAG (web, public app, back office) and push them to Harbor, unless they are there; PREBUILT=<dir> packs the jars and web bundle CI built
	@set -euo pipefail; $(HARBOR); \
	if [ -n "$(PREBUILT)" ]; then for f in public.jar backoffice.jar web/index.html; do \
		[ -f "$(PREBUILT)/$$f" ] || { echo "PREBUILT=$(PREBUILT) has no $$f"; exit 1; }; done; fi; \
	for entry in $(IMAGES); do \
		repo=$${entry%%=*}; dockerfile=$${entry#*=}; image=$(REGISTRY)/helper2/$$repo; \
		code=$$(harbor_api $$repo $(TAG) -o /dev/null -w "%{http_code}"); case $$code in \
			200) echo "$$image:$(TAG) is in Harbor already, not rebuilt"; continue;; \
			404) ;; \
			*) echo "Harbor answered HTTP $$code for $$image:$(TAG)"; exit 1;; \
		esac; \
		if [ ! -d "$$work/src" ]; then \
			mkdir "$$work/src"; git -C $(ROOT) archive $(TAG) | tar -x -C "$$work/src"; harbor_login; \
			if [ -n "$(PREBUILT)" ]; then cp -R "$(PREBUILT)" "$$work/src/prebuilt"; fi; \
		fi; \
		$(ENGINE) build --platform linux/amd64 --build-arg BUILD=$(if $(PREBUILT),prebuilt,source) \
			-f "$$work/src/$$dockerfile" -t $$image:$(TAG) "$$work/src"; \
		$(ENGINE) push $(PUSH_DIGEST) $$image:$(TAG); $(call record_push,$$repo,$(TAG)) \
		echo "pushed $$image:$(TAG)"; \
	done

VERSION ?=
retag: ## add the release tag VERSION (YYYY.N.P) to the three pushed images TAG in Harbor: same digests, no rebuild
	@set -euo pipefail; \
	[[ "$(VERSION)" =~ ^[0-9]+\.[0-9]+\.[0-9]+$$ ]] || { echo "VERSION must be YYYY.N.P, got '$(VERSION)'"; exit 1; }; \
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

SBOM ?= sbom.cdx.json
sbom: ## CycloneDX SBOM of the image TAG, read from Harbor (needs syft), into SBOM (default sbom.cdx.json)
	@set -euo pipefail; $(HARBOR); \
	SYFT_REGISTRY_AUTH_AUTHORITY=$(REGISTRY) SYFT_REGISTRY_AUTH_USERNAME="$$user" SYFT_REGISTRY_AUTH_PASSWORD="$$pass" \
		syft registry:$(IMAGE):$(TAG) -o cyclonedx-json=$(SBOM); \
	echo "SBOM of $(IMAGE):$(TAG) in $(SBOM)"

backoffice-sbom: ## CycloneDX SBOM of the back office image TAG, read from Harbor, into SBOM
	@$(MAKE) --no-print-directory sbom IMAGE=$(BO_IMAGE)

web-sbom: ## CycloneDX SBOM of the web image TAG, read from Harbor, into SBOM
	@$(MAKE) --no-print-directory sbom IMAGE=$(WEB_IMAGE)

postgres-sbom: ## CycloneDX SBOM of the Postgres image postgres.image.tag, read from Harbor, into SBOM
	@test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }
	@$(MAKE) --no-print-directory sbom IMAGE=$(PG_REPO) TAG=$(PG_TAG)

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

ENV_SECRET   := helper2-app-env
CREDS_SECRET := helper2-credentials
CREDENTIALS  := H2_DATABASE_PASSWORD|H2_DATABASE_PUBLIC_PASSWORD|H2_DATABASE_BACKOFFICE_PASSWORD|H2_REDIS_PASSWORD|H2_REDIS_PUBLIC_PASSWORD|H2_REDIS_BACKOFFICE_PASSWORD
NOT_IN_APP_ENV := H2_DATABASE_PUBLIC_PASSWORD|H2_DATABASE_BACKOFFICE_PASSWORD|H2_REDIS_PUBLIC_PASSWORD|H2_REDIS_BACKOFFICE_PASSWORD
ENV_REV     = $(shell sha256sum < $(ENV)/app.env 2>/dev/null | cut -c1-16)
REDIS_REV   = $(shell grep -E '^H2_REDIS_(PUBLIC_|BACKOFFICE_)?PASSWORD=' $(ENV)/app.env 2>/dev/null | sha256sum | cut -c1-16)
HELM_ARGS = helper2 $(CHART) -n $(NS) -f $(CHART)/values-$(ENV).yaml \
	--set image.repository=$(IMAGE) --set image.tag=$(TAG) --set backoffice.image.repository=$(BO_IMAGE) \
	--set web.image.repository=$(WEB_IMAGE) \
	--set envSecret.name=$(ENV_SECRET) --set credentialsSecret.name=$(CREDS_SECRET) \
	--set envSecret.revision=$(ENV_REV) --set envSecret.redisPasswordRevision=$(REDIS_REV)

CRD_SCHEMAS := https://raw.githubusercontent.com/datreeio/CRDs-catalog/3eb90d47aba8d61919d4419dd38301536e32a94b/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json
chart-check: ## helm lint + helm template | kubeconform, for staging and prod; Redis AOF + PVC; preStop, drain and grace period; images pinned by digest; redis.auth.revision restarts; daily limits only where set; admin Service on the back office, ingress-nginx on 8080; the public pod's shell from the web image; PrometheusRule = rule file; the backup verifies its dump; the chart renders no Secret; app.env checks; make status fails on an unhealthy release
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
	echo "== ADR 0004: no app holds the owner's or Redis' default password; only Postgres, pg-backup and the migrate Job get the owner's"; \
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
	echo "== imagePullSecrets go on the ServiceAccount helper2, which every pod runs as; none by default"; \
	r=(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --set backup.enabled=true); \
	pods=$$("$${r[@]}" | grep -cE '^kind: (Deployment|StatefulSet|Job|CronJob)$$'); \
	[ "$$pods" -gt 0 ] && [ "$$("$${r[@]}" | grep -cE '^ +serviceAccountName: helper2$$')" -eq "$$pods" ] \
		|| { echo "every workload ($$pods) must run as the ServiceAccount helper2"; exit 1; }; \
	if "$${r[@]}" | grep -q 'imagePullSecrets'; then echo "the chart sets imagePullSecrets by default"; exit 1; fi; \
	sa=$$("$${r[@]}" --set 'imagePullSecrets[0].name=registry-pull' --show-only templates/serviceaccount.yaml); \
	grep -A1 '^imagePullSecrets:$$' <<< "$$sa" | grep -qxF '  - name: registry-pull' \
		|| { echo "the ServiceAccount helper2 does not carry imagePullSecrets"; exit 1; }; \
	"$${r[@]}" --set 'imagePullSecrets[0].name=registry-pull' | kubeconform -strict -schema-location default -schema-location '$(CRD_SCHEMAS)'; \
	echo "== envSecret.revision restarts the app, envSecret.redisPasswordRevision restarts Redis"; \
	rev=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		--set envSecret.revision=abc --set envSecret.redisPasswordRevision=def --show-only templates/app.yaml --show-only templates/backoffice.yaml --show-only templates/redis.yaml); \
	[ "$$(grep -c 'checksum/env-revision: "abc"' <<< "$$rev")" -eq 2 ] || { echo "app and back office: no checksum/env-revision"; exit 1; }; \
	grep -q 'checksum/auth: "def"' <<< "$$rev" || { echo "redis: no checksum/auth"; exit 1; }; \
	echo "== canary (ADR 0045): only its own objects, one pod each, reached only with its header and a token"; \
	tok=0123456789abcdef0123456789abcdef; \
	for e in staging prod; do \
		c=(helm template helper2-canary $(CHART) -n helper2-$$e -f $(CHART)/values-$$e.yaml --set image.tag=000000000000 --set canary.only=true); \
		out=$$("$${c[@]}" --set canary.token=$$tok); \
		kubeconform -strict -summary -schema-location default -schema-location '$(CRD_SCHEMAS)' <<< "$$out"; \
		names=$$(awk '/^kind:/ {k=$$2} /^  name:/ {print k "/" $$2}' <<< "$$out" | sort | xargs); \
		[ "$$names" = "Deployment/helper2-canary Deployment/helper2-web-canary Ingress/helper2-canary Job/helper2-canary-migrate-1 NetworkPolicy/canary-egress-app NetworkPolicy/canary-egress-migrate NetworkPolicy/canary-ingress-app NetworkPolicy/canary-ingress-postgres NetworkPolicy/canary-ingress-postgres-migrate NetworkPolicy/canary-ingress-redis NetworkPolicy/canary-ingress-web Service/helper2-canary Service/helper2-web-canary" ] \
			|| { echo "$$e canary renders: $$names"; exit 1; }; \
		[ "$$(grep -c '^  replicas: 1$$' <<< "$$out")" -eq 2 ] || { echo "$$e canary: the app and web Deployments must run one pod each"; exit 1; }; \
		grep -qF "nginx.ingress.kubernetes.io/canary-by-header-value: \"$$tok\"" <<< "$$out" || { echo "$$e canary: the Ingress must route on the header and its token"; exit 1; }; \
		if grep -q 'canary-weight' <<< "$$out"; then echo "$$e canary: no user traffic goes to a canary"; exit 1; fi; \
		if "$${c[@]}" >/dev/null 2>&1; then echo "$$e canary renders without a token"; exit 1; fi; \
		if "$${c[@]}" --set canary.token=short >/dev/null 2>&1; then echo "$$e canary renders with a short token"; exit 1; fi; \
	done; \
	offsite=(--set backup.offsite.enabled=true); \
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
	echo "== shutdown (ADR 0043): preStop sleep 10 s, drain 30 s, grace 45 s, all from app.shutdown"; \
	grep -qE '^      terminationGracePeriodSeconds: 45$$' <<< "$$prod" || { echo "prod: terminationGracePeriodSeconds is not 45"; exit 1; }; \
	grep -qE 'name: H2_SHUTDOWN_TIMEOUT, value: "30s"' <<< "$$prod" || { echo "prod: H2_SHUTDOWN_TIMEOUT is not 30s"; exit 1; }; \
	grep -A2 '^            preStop:$$' <<< "$$prod" | grep -qE 'sleep: \{ seconds: 10 \}' || { echo "prod: no preStop sleep of 10 s"; exit 1; }; \
	grace=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 \
		--set app.shutdown.preStopSeconds=3 --set app.shutdown.drainSeconds=20 --show-only templates/app.yaml); \
	grep -qE '^      terminationGracePeriodSeconds: 28$$' <<< "$$grace" || { echo "grace period must be preStopSeconds + drainSeconds + 5"; exit 1; }; \
	echo "== every image but the apps' is pinned by digest (ADR 0044); postgres once postgres.image.digest is set"; \
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
	echo "== edge (ADR 0036, 0043): Service helper2-admin selects the back office; ingress-nginx reaches the three workloads on 8080 only; the web pods take nothing else"; \
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
	echo "== web (ADR 0043): the public pod copies its shell from the web image the web pods run, into a read-only /shell"; \
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
	echo "== backup (ADR 0043): a failed pg_dump fails the Job, the dump is verified before it replaces the old one, old dumps go last"; \
	bk=$$(helm template helper2 $(CHART) -f $(CHART)/values-prod.yaml --set image.tag=000000000000 --show-only templates/backup.yaml); \
	grep -q 'set -o pipefail' <<< "$$bk" || { echo "backup: no pipefail"; exit 1; }; \
	grep -q 'startingDeadlineSeconds: 3600' <<< "$$bk" || { echo "backup: no startingDeadlineSeconds"; exit 1; }; \
	awk '/gzip -t "\$$f.tmp"/ {t=NR} /mv "\$$f.tmp" "\$$f"/ {m=NR} /find \/backup .* -delete/ {d=NR} END { exit !(t && m && d && t < m && m < d) }' <<< "$$bk" \
		|| { echo "backup: gzip -t must come before mv, and find -delete after both"; exit 1; }

check-env:
	@test -f $(ENV)/app.env || { echo "$(ENV)/app.env missing: copy app.env.example and fill it"; exit 1; }

LAST_GOOD_REVISION = helm -n $(NS) history helper2 -o json | jq -r \
	'(max_by(.revision).revision) as $$now \
	 | [.[] | select(.revision != $$now and (.status == "deployed" or .status == "superseded")) | .revision] \
	 | max // empty'

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
	             if ! helm -n $(NS) rollback helper2 $$rev --wait --wait-for-jobs --timeout 12m >/dev/null; then \
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

SMOKE_PATHS ?= / /api/v1/sources
SMOKE_URLS = $(addprefix https://$(HOST),$(SMOKE_PATHS))
ROLLBACK_UNHEALTHY_GIVEN := $(if $(findstring command line,$(origin ROLLBACK_UNHEALTHY)),$(ROLLBACK_UNHEALTHY),true)
ifeq ($(filter $(ROLLBACK_UNHEALTHY_GIVEN),true false),)
$(error ROLLBACK_UNHEALTHY must be true or false, got '$(ROLLBACK_UNHEALTHY_GIVEN)')
endif
ROLLBACK_TARGET = $(if $(filter false,$(ROLLBACK_UNHEALTHY_GIVEN)),off,$${before:-none})
deploy: check-env release-ready env-secret ## env-secret, then helm upgrade ENV to TAG (default: current commit), wait, roll back on failure; then the status check, and back to the revision deployed before when it fails (not with ROLLBACK_UNHEALTHY=false, on the command line of one deploy)
	set -euo pipefail; \
	before=$$(./check-release.sh deployed-revision $(NS)); \
	helm upgrade --install $(HELM_ARGS) --server-side=true --force-conflicts --wait --wait-for-jobs --timeout 12m --rollback-on-failure --history-max 3; \
	./check-release.sh --or-roll-back-to "$(ROLLBACK_TARGET)" $(NS) $(SMOKE_URLS)

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

WITHOUT_MIGRATE_JOB := awk 'function flush() { if (!job) printf "%s", doc; doc = ""; job = 0 } /^---$$/ { flush() } /^kind: Job$$/ { job = 1 } { doc = doc $$0 "\n" } END { flush() }'
diff: check-env ## what deploy would change in ENV (not the env Secret: env-secret writes it; not the migrate Job: each revision makes its own)
	@set -uo pipefail; \
	out=$$(mktemp); trap 'rm -f "$$out"' EXIT; \
	helm template $(HELM_ARGS) | $(WITHOUT_MIGRATE_JOB) > "$$out" || exit; \
	kubectl diff -n $(NS) -f "$$out"; rc=$$?; \
	if [ $$rc -gt 1 ]; then echo "kubectl diff failed (exit $$rc)"; exit $$rc; fi

rollback: ## back to the last deployed helm revision of ENV (not a failed or interrupted one)
	@set -euo pipefail; \
	rev=$$($(LAST_GOOD_REVISION)); \
	test -n "$$rev" || { echo "no earlier deployed revision of helper2 in $(NS) to roll back to"; helm -n $(NS) history helper2 --max 5; exit 1; }; \
	echo "rolling back helper2 in $(NS) to revision $$rev"; \
	helm -n $(NS) rollback helper2 $$rev --wait --wait-for-jobs --timeout 12m
	helm -n $(NS) history helper2 --max 5

CANARY_TOKEN ?=
CANARY_VERSION ?= -
CANARY_ARGS = helper2-canary $(wordlist 2,$(words $(HELM_ARGS)),$(HELM_ARGS)) --set canary.only=true --set canary.token=$(CANARY_TOKEN)
canary: check-env env-secret ## install the canary of TAG in ENV beside the release: its migrate Job, one public app and one web pod, reached only with the header X-Helper2-Canary: CANARY_TOKEN
	@[[ "$(CANARY_TOKEN)" =~ ^[0-9a-f]{32,}$$ ]] || { echo "CANARY_TOKEN must be at least 32 hex characters (openssl rand -hex 16)"; exit 1; }
	@helm upgrade --install $(CANARY_ARGS) --server-side=true --force-conflicts --wait --wait-for-jobs --timeout 12m --history-max 1

canary-smoke: ## smoke test of the canary through the public host with its header (it must serve CANARY_VERSION), then of the release that runs, on the migrated schema
	@[[ "$(CANARY_TOKEN)" =~ ^[0-9a-f]{32,}$$ ]] || { echo "CANARY_TOKEN must be at least 32 hex characters"; exit 1; }
	@./check-release.sh smoke https://$(HOST) "$(CANARY_VERSION)" -H "X-Helper2-Canary: $(CANARY_TOKEN)"
	./check-release.sh smoke https://$(HOST) -

uncanary: ## remove the canary of ENV, when there is one
	helm -n $(NS) uninstall helper2-canary --ignore-not-found --wait --timeout 5m

status: ## ENV's pods, then fails unless the release is deployed, every Deployment and StatefulSet is rolled out, this revision's migrate Job completed and the public site answers 200 on SMOKE_PATHS
	kubectl -n $(NS) get pods -o wide
	./check-release.sh $(NS) $(SMOKE_URLS)

.PHONY: ci-images sign sign-pushed sign-existing verify-images verified cosign-pub chart-check-digests chart-check-status

PUSHED ?=
PUSH_DIGEST = $(if $(PUSHED),--digestfile "$$work/pushed.digest")
record_push = $(if $(PUSHED),pushed=$$(cat "$$work/pushed.digest"); have=$$(harbor_api $(1) $(2) -f | jq -er .digest); [[ $$have == "$$pushed" ]] || { echo "$(1):$(2) is $$have in Harbor and not $$pushed as pushed"; exit 1; }; echo "$(1)@$$pushed" >> "$(PUSHED)";)

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
	if [ -n "$$build" ]; then $(MAKE) --no-print-directory image TAG=$(TAG) ENGINE=$(ENGINE) IMAGES="$$build" PUSHED="$(PUSHED)" PREBUILT="$(PREBUILT)"; fi

COSIGN_KEY     ?= hashivault://helper2-images
TRANSIT_MOUNT  ?= helper2/transit
COSIGN_PUB     := $(abspath cosign.pub)
SIGNING_CONFIG := $(abspath cosign-signing-config.json)
define COSIGN
grep -q 'BEGIN PUBLIC KEY' $(COSIGN_PUB) || { echo "$(COSIGN_PUB) holds no public key yet: make cosign-pub"; exit 1; }; \
export DOCKER_CONFIG="$$work/docker"; \
printf '%s' "$$pass" | cosign login $(REGISTRY) --username "$$user" --password-stdin >/dev/null 2>"$$work/login.log" \
	|| { cat "$$work/login.log"; echo "cosign login to $(REGISTRY) failed"; exit 1; }; \
digest_of() { harbor_api "$$1" "$$2" -f | jq -er .digest; }; \
verify() { cosign verify --key $(COSIGN_PUB) --insecure-ignore-tlog=true "$$1" </dev/null >/dev/null 2>"$$work/verify.log"; }
endef
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

SIGNED_REFS = apps/helper2-web:$(TAG) apps/helper2:$(TAG) apps/helper2-backoffice:$(TAG) apps/postgres:$(PG_TAG)
sign: sign-pushed ## CD: sign exactly the digests in PUSHED (pushed by this run), then check every image of TAG and the Postgres image
	@test -n "$(PG_TAG)" || { echo "postgres.image.tag not found in $(CHART)/values.yaml"; exit 1; }; \
	$(MAKE) --no-print-directory verify-images TAG=$(TAG) DIGESTS=/dev/null

sign-pushed: ## CD: sign exactly the digests in PUSHED (pushed by this run), nothing else
	@set -euo pipefail; \
	[[ "$(PUSHED)" == /* ]] || { echo "PUSHED must be the absolute path that ci-images and postgres-image wrote"; exit 1; }; \
	[ -s "$(PUSHED)" ] || { echo "nothing pushed by this run, nothing to sign"; exit 0; }; \
	$(HARBOR); $(COSIGN); $(TRANSIT); \
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
	done 3< "$(PUSHED)"

CONFIRM ?=
sign-existing: ## workstation only: sign the unsigned images of TAG and of PG_TAG, after a confirmation (or CONFIRM=yes)
	@set -euo pipefail; \
	[ -z "$${GITHUB_ACTIONS:-}" ] && [ -z "$${CI:-}" ] || { echo "sign-existing is for the operator's workstation, never for CI"; exit 1; }; \
	[[ "$(TAG)" =~ ^[0-9a-f]{12}$$ || "$(TAG)" =~ ^[0-9]+\.[0-9]+\.[0-9]+$$ ]] || { echo "TAG must be a 12-char commit or YYYY.N.P, got '$(TAG)'"; exit 1; }; \
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

VERIFIED ?= $(ENV)/digests.env
verified:
	@rm -f "$(VERIFIED)" "$(VERIFIED).new"; mkdir -p "$(dir $(VERIFIED))"
	@$(MAKE) --no-print-directory verify-images TAG=$(TAG) DIGESTS="$(abspath $(VERIFIED)).new"
	@echo "TAG=$(TAG)" >> "$(VERIFIED).new"; mv -f "$(VERIFIED).new" "$(VERIFIED)"
diff env-secret: verified
verified_digest = $(shell sed -n 's/^$(1)=//p' $(VERIFIED) 2>/dev/null)
need_digest = $(if $(and $(filter TAG=$(TAG),$(shell cat $(VERIFIED) 2>/dev/null)),$(filter sha256:%,$(call verified_digest,$(1)))),--set $(2)=$(call verified_digest,$(1)),$(error $(1): no signature check for TAG $(TAG) in $(VERIFIED). Run make verified (ADR 0044)))
HELM_ARGS += $(call need_digest,APP_DIGEST,image.digest) $(call need_digest,BO_DIGEST,backoffice.image.digest) \
	$(call need_digest,WEB_DIGEST,web.image.digest) $(call need_digest,PG_DIGEST,postgres.image.digest)

cosign-pub: ## write cosign.pub from the OpenBao Transit key (needs read on its keys/ path)
	@set -euo pipefail; $(TRANSIT); \
	cosign public-key --key $(COSIGN_KEY) > $(COSIGN_PUB).new; \
	grep -q 'BEGIN PUBLIC KEY' $(COSIGN_PUB).new; mv $(COSIGN_PUB).new $(COSIGN_PUB); \
	echo "wrote $(COSIGN_PUB) from $(COSIGN_KEY) (mount $(TRANSIT_MOUNT)): commit it"

chart-check: chart-check-digests chart-check-status
chart-check-status:
	@set -euo pipefail; \
	echo "== deploy and status fail the job when the release is not healthy (check-release.sh), on the page and on one API read; deploy goes back to the revision deployed before the upgrade"; \
	./check-release.sh --self-test; \
	dry=$$($(MAKE) --no-print-directory -s -n status ENV=prod); \
	grep -qxF './check-release.sh helper2-prod https://helper2.ai/ https://helper2.ai/api/v1/sources' <<< "$$dry" \
		|| { echo "make status does not run check-release.sh on the public page and API: $$dry"; exit 1; }; \
	grep -qE '^ +run: make status$$' $(ROOT)/.github/workflows/deploy.yml || { echo "deploy.yml does not run make status after the deploy"; exit 1; }; \
	recipe=$$(awk '/^deploy:/ {p=1; next} p && !/^\t/ {exit} p' Makefile); \
	awk '/check-release.sh deployed-revision \$$\(NS\)/ {b=NR} /helm upgrade --install/ {u=NR} /check-release.sh --or-roll-back-to "\$$\(ROLLBACK_TARGET\)" \$$\(NS\) \$$\(SMOKE_URLS\)/ {c=NR} END { exit !(b && u && c && b < u && u < c) }' <<< "$$recipe" \
		|| { echo "make deploy must record the deployed revision, then upgrade, then check or roll back to that revision"; exit 1; }; \
	err=$$(mktemp); trap 'rm -f "$$err"' EXIT; \
	target() { env $${2:-} $(MAKE) --no-print-directory -s -f Makefile -f <(printf 'print-target:\n\t@printf "%%s" %s\n' "'"'$$(ROLLBACK_TARGET)'"'") print-target ENV=prod $${1:-} 2>"$$err" || true; }; \
	[ "$$(target)" = '$${before:-none}' ] || { echo "make deploy must roll back by default: $$(target)"; exit 1; }; \
	[ "$$(target "" ROLLBACK_UNHEALTHY=false)" = '$${before:-none}' ] || { echo "ROLLBACK_UNHEALTHY from the environment must not switch the rollback off"; exit 1; }; \
	[ "$$(target ROLLBACK_UNHEALTHY=false)" = off ] || { echo "ROLLBACK_UNHEALTHY=false on the command line must switch the rollback off: $$(target ROLLBACK_UNHEALTHY=false)"; exit 1; }; \
	target ROLLBACK_UNHEALTHY=no >/dev/null; grep -q "ROLLBACK_UNHEALTHY must be true or false" "$$err" || { echo "ROLLBACK_UNHEALTHY=no must be refused: $$(cat "$$err")"; exit 1; }; \
	if grep -rn 'ROLLBACK_UNHEALTHY' $(ROOT)/deploy/helm app.env.example; then echo "ROLLBACK_UNHEALTHY is for one deploy on the make command line: no chart value or app.env key may set it"; exit 1; fi; \
	echo "== one path switches the rollback off in a workflow: cd.yml's manual input keep-unhealthy (default false) -> deploy.yml -> make deploy ROLLBACK_UNHEALTHY=false"; \
	wf=$(ROOT)/.github/workflows; \
	[ "$$(grep -rn 'ROLLBACK_UNHEALTHY' $(ROOT)/.github | sed 's/:[0-9]*: */: /')" = "$$wf/deploy.yml: make deploy ROLLBACK_UNHEALTHY=false" ] \
		|| { echo "only deploy.yml may pass ROLLBACK_UNHEALTHY, in one line: make deploy ROLLBACK_UNHEALTHY=false"; grep -rn 'ROLLBACK_UNHEALTHY' $(ROOT)/.github; exit 1; }; \
	[ "$$(grep -rl 'keep-unhealthy' $(ROOT)/.github | sort | tr '\n' ' ')" = "$$wf/cd.yml $$wf/deploy.yml " ] \
		|| { echo "only cd.yml and deploy.yml may name keep-unhealthy:"; grep -rl 'keep-unhealthy' $(ROOT)/.github; exit 1; }; \
	[ "$$(grep -hE '^ +keep-unhealthy: [^ ]' $$wf/cd.yml $$wf/deploy.yml | sed 's/^ *//')" = 'keep-unhealthy: $${{ inputs.keep-unhealthy }}' ] \
		|| { echo "keep-unhealthy is passed once, by cd.yml's manual job, from its own input: no workflow sets it to a fixed value"; exit 1; }; \
	for f in cd deploy; do \
		[ "$$(grep -A3 '^      keep-unhealthy:$$' $$wf/$$f.yml | grep -cE '^        (type: boolean|default: false)$$')" -eq 2 ] \
			|| { echo "$$f.yml: the input keep-unhealthy must be a boolean with default false"; exit 1; }; \
	done; \
	grep -q '"::warning::keep-unhealthy: the automatic rollback is OFF' $$wf/deploy.yml && grep -q 'Automatic rollback OFF.*GITHUB_STEP_SUMMARY' $$wf/deploy.yml \
		|| { echo "deploy.yml must say in the log and in the run summary that the rollback is off"; exit 1; }
chart-check-digests:
	@set -euo pipefail; \
	echo "== digests (ADR 0044): with the four digests set, every image is repository:tag@digest; the web pods and the init container run the same one"; \
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
	echo "== make diff leaves out the migrate Job: a finished Job cannot be changed, each upgrade makes a new one"; \
	all=$$(helm template "$${r[@]}"); kept=$$($(WITHOUT_MIGRATE_JOB) <<< "$$all"); \
	[ "$$(grep -c '^kind: Job$$' <<< "$$all")" -eq 1 ] || { echo "the chart renders more or less than one Job"; exit 1; }; \
	if grep -q '^kind: Job$$' <<< "$$kept"; then echo "make diff still previews the migrate Job"; exit 1; fi; \
	[ "$$(grep -c '^kind: ' <<< "$$kept")" -eq "$$(( $$(grep -c '^kind: ' <<< "$$all") - 1 ))" ] || { echo "make diff drops more than the migrate Job"; exit 1; }; \
	grep -qF 'helm template $$(HELM_ARGS) | $$(WITHOUT_MIGRATE_JOB)' $(firstword $(MAKEFILE_LIST)) || { echo "make diff does not filter the migrate Job"; exit 1; }; \
	echo "== sign-existing (workstation only) is called by no workflow"; \
	if grep -rn 'sign-existing' $(ROOT)/.github; then echo "a workflow names make sign-existing: it is for the operator's workstation only"; exit 1; fi
