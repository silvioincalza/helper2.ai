{{/* Labels on every object's metadata. Selectors stay `app: <name>` only: they are immutable. */}}
{{- define "helper2.labels" -}}
app.kubernetes.io/part-of: helper2
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{/* The Secret with the app's H2_ settings, created outside the chart (make env-secret). */}}
{{- define "helper2.envSecret" -}}
{{ required "envSecret.name is required: the Secret with the H2_ keys of app.env (make env-secret)" .Values.envSecret.name }}
{{- end }}

{{/* The Secret with the credentials (ADR 0070): the database owner's and each app's passwords, Redis' default
     user's and each app user's. Written by make env-secret from the same app.env; no app loads it whole, each
     workload names only its own keys. */}}
{{- define "helper2.credentialsSecret" -}}
{{ required "credentialsSecret.name is required: the Secret with the database and Redis passwords (make env-secret)" .Values.credentialsSecret.name }}
{{- end }}

{{/* "@sha256:<hex>" when an image's digest is set, else nothing: the deploy (make verify-images, ADR 0071) pins each
     image to the digest it checked the signature of, as repository:tag@digest; without one, by tag (local renders). */}}
{{- define "helper2.atDigest" -}}
{{- with . }}{{ if not (regexMatch "^sha256:[0-9a-f]{64}$" .) }}{{ fail "an image digest must be sha256:<64 hex>" }}{{ end }}@{{ . }}{{ end }}
{{- end }}

{{/* The back office's tag: its own, or the public app's, so both deploy the same version by default. */}}
{{- define "helper2.backofficeTag" -}}
{{ default .Values.image.tag .Values.backoffice.image.tag }}
{{- end }}

{{- define "helper2.backofficeImage" -}}
{{ required "backoffice.image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2-backoffice)" .Values.backoffice.image.repository }}:{{ required "backoffice.image.tag or image.tag is required" (include "helper2.backofficeTag" .) }}{{ include "helper2.atDigest" .Values.backoffice.image.digest }}
{{- end }}

{{/* The web image (ADR 0069): the web Deployment and the public pod's init container both name it through this helper,
     so the shell the public app writes into always comes from the same version as the files the web pods serve. Its
     tag: its own, or the public app's. */}}
{{- define "helper2.webImage" -}}
{{ required "web.image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2-web)" .Values.web.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{ include "helper2.atDigest" .Values.web.image.digest }}
{{- end }}

{{- define "helper2.image" -}}
{{ required "image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2)" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{ include "helper2.atDigest" .Values.image.digest }}
{{- end }}

{{/* A Redis password as an env var source, by its key (H2_REDIS_PASSWORD for the default user,
     H2_REDIS_PUBLIC_PASSWORD, H2_REDIS_BACKOFFICE_PASSWORD for the apps' ACL users): from redis.auth.existingSecret,
     or from the credentials Secret. Call with (list $ "<key>"). With existingSecret, the default user's key is
     redis.auth.existingSecretKey. */}}
{{- define "helper2.redisPasswordFrom" -}}
{{- $root := index . 0 -}}{{- $key := index . 1 -}}
{{- with $root.Values.redis.auth -}}
{{- if .existingSecret -}}
secretKeyRef: { name: {{ .existingSecret }}, key: {{ ternary (required "redis.auth.existingSecretKey" .existingSecretKey) $key (eq $key "H2_REDIS_PASSWORD") }} }
{{- else -}}
secretKeyRef: { name: {{ include "helper2.credentialsSecret" $root }}, key: {{ $key }} }
{{- end -}}
{{- end -}}
{{- end }}

{{/* The key of an app's Redis ACL user password: h2_public -> H2_REDIS_PUBLIC_PASSWORD. */}}
{{- define "helper2.redisUserPasswordKey" -}}
H2_REDIS_{{ trimPrefix "h2_" . | upper }}_PASSWORD
{{- end }}

{{/* repository:tag, or repository:tag@digest when postgres.image.digest pins it. */}}
{{- define "helper2.postgresImage" -}}
{{- with .Values.postgres.image -}}
{{ required "postgres.image.repository is required (Postgres + pgvector built from deploy/postgres)" .repository }}:{{ required "postgres.image.tag" .tag }}{{ include "helper2.atDigest" .digest }}
{{- end }}
{{- end }}

{{/* redis.auth.revision as a plain number. Anything else fails: `int` would turn "v2" into 0, and a rotation would
     then restart nothing. */}}
{{- define "helper2.redisAuthRevision" -}}
{{- $r := toString .Values.redis.auth.revision -}}
{{- if not (regexMatch "^[0-9]+$" $r) }}{{ fail "redis.auth.revision must be a non-negative integer" }}{{ end -}}
{{ $r | quote }}
{{- end }}
