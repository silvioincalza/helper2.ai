{{- define "searchavo.labels" -}}
app.kubernetes.io/part-of: searchavo
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "searchavo.envSecret" -}}
{{ required "envSecret.name is required: the Secret with the SA_ keys of app.env (make env-secret)" .Values.envSecret.name }}
{{- end }}

{{- define "searchavo.credentialsSecret" -}}
{{ required "credentialsSecret.name is required: the Secret with the database and Redis passwords (make env-secret)" .Values.credentialsSecret.name }}
{{- end }}

{{- define "searchavo.atDigest" -}}
{{- with . }}{{ if not (regexMatch "^sha256:[0-9a-f]{64}$" .) }}{{ fail "an image digest must be sha256:<64 hex>" }}{{ end }}@{{ . }}{{ end }}
{{- end }}

{{- define "searchavo.backofficeTag" -}}
{{ default .Values.image.tag .Values.backoffice.image.tag }}
{{- end }}

{{- define "searchavo.backofficeImage" -}}
{{ required "backoffice.image.repository is required (your registry, e.g. ghcr.io/<owner>/searchavo-backoffice)" .Values.backoffice.image.repository }}:{{ required "backoffice.image.tag or image.tag is required" (include "searchavo.backofficeTag" .) }}{{ include "searchavo.atDigest" .Values.backoffice.image.digest }}
{{- end }}

{{- define "searchavo.webImage" -}}
{{ required "web.image.repository is required (your registry, e.g. ghcr.io/<owner>/searchavo-web)" .Values.web.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{ include "searchavo.atDigest" .Values.web.image.digest }}
{{- end }}

{{- define "searchavo.image" -}}
{{ required "image.repository is required (your registry, e.g. ghcr.io/<owner>/searchavo)" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{ include "searchavo.atDigest" .Values.image.digest }}
{{- end }}

{{- define "searchavo.redisPasswordFrom" -}}
{{- $root := index . 0 -}}{{- $key := index . 1 -}}
{{- with $root.Values.redis.auth -}}
{{- if .existingSecret -}}
secretKeyRef: { name: {{ .existingSecret }}, key: {{ ternary (required "redis.auth.existingSecretKey" .existingSecretKey) $key (eq $key "SA_REDIS_PASSWORD") }} }
{{- else -}}
secretKeyRef: { name: {{ include "searchavo.credentialsSecret" $root }}, key: {{ $key }} }
{{- end -}}
{{- end -}}
{{- end }}

{{- define "searchavo.redisUserPasswordKey" -}}
SA_REDIS_{{ trimPrefix "h2_" . | upper }}_PASSWORD
{{- end }}

{{- define "searchavo.postgresImage" -}}
{{- with .Values.postgres.image -}}
{{ required "postgres.image.repository is required (Postgres + pgvector built from deploy/postgres)" .repository }}:{{ required "postgres.image.tag" .tag }}{{ include "searchavo.atDigest" .digest }}
{{- end }}
{{- end }}

{{- define "searchavo.embedderImage" -}}
{{- with .Values.embedder.image -}}
{{ required "embedder.image.repository is required (the embedding model image built from deploy/embedder)" .repository }}:{{ required "embedder.image.tag" .tag }}{{ include "searchavo.atDigest" .digest }}
{{- end }}
{{- end }}

{{- define "searchavo.embedderUrl" -}}
{{- with .Values.embedder -}}
{{- if and .enabled .url }}{{ fail "embedder.enabled runs the embedder in this release and embedder.url calls one of another release: set one" }}{{ end -}}
{{- if .url -}}
{{- if not (regexMatch "^http://searchavo-embedder\\.[a-z0-9-]+\\.svc:8080/v1$" .url) }}{{ fail "embedder.url must be http://searchavo-embedder.<namespace>.svc:8080/v1" }}{{ end -}}
{{- if ne .url (printf "http://searchavo-embedder.%s.svc:8080/v1" (required "embedder.namespace is required with embedder.url" .namespace)) }}{{ fail "embedder.url must name embedder.namespace" }}{{ end -}}
{{ .url }}
{{- else if .enabled -}}
http://searchavo-embedder:8080/v1
{{- end -}}
{{- end -}}
{{- end }}

{{- define "searchavo.embedderEnv" -}}
{{- with include "searchavo.embedderUrl" . }}
- { name: SA_NVIDIA_EMBEDDING_BASE_URL, value: {{ . | quote }} }
- { name: SA_NVIDIA_EMBEDDING_MODEL, value: {{ required "embedder.model" $.Values.embedder.model | quote }} }
- { name: SA_NVIDIA_API_KEY, value: in-cluster }
- { name: SA_EMBEDDING_EVERY, value: {{ required "embedder.indexEvery" $.Values.embedder.indexEvery | quote }} }
{{- end }}
{{- end }}

{{- define "searchavo.embedderEgress" -}}
{{- with .Values.embedder }}
{{- if .enabled }}
- to: [{ podSelector: { matchLabels: { app: searchavo-embedder } } }]
  ports: [{ port: 8080, protocol: TCP }]
{{- else if .url }}
- to:
    - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: {{ .namespace }} } }
      podSelector: { matchLabels: { app: searchavo-embedder } }
  ports: [{ port: 8080, protocol: TCP }]
{{- end }}
{{- end }}
{{- end }}

{{- define "searchavo.redisAuthRevision" -}}
{{- $r := toString .Values.redis.auth.revision -}}
{{- if not (regexMatch "^[0-9]+$" $r) }}{{ fail "redis.auth.revision must be a non-negative integer" }}{{ end -}}
{{ $r | quote }}
{{- end }}

{{- define "searchavo.canaryPrefix" -}}
{{ ternary "canary-" "" .Values.canary.only }}
{{- end }}

{{- define "searchavo.appName" -}}
{{ ternary "searchavo-canary" "searchavo" .Values.canary.only }}
{{- end }}

{{- define "searchavo.webName" -}}
{{ ternary "searchavo-web-canary" "searchavo-web" .Values.canary.only }}
{{- end }}

{{- define "searchavo.migrateName" -}}
{{ ternary "searchavo-canary-migrate" "searchavo-migrate" .Values.canary.only }}
{{- end }}

{{- define "searchavo.offsiteEnv" -}}
{{- $offsite := .Values.backup.offsite -}}
- { name: RCLONE_CONFIG_OFFSITE_TYPE, value: s3 }
- { name: RCLONE_CONFIG_OFFSITE_PROVIDER, value: Cloudflare }
- { name: RCLONE_CONFIG_OFFSITE_NO_CHECK_BUCKET, value: "true" }
- name: RCLONE_CONFIG_OFFSITE_ENDPOINT
  valueFrom: { secretKeyRef: { name: {{ $offsite.secret }}, key: endpoint } }
- name: RCLONE_CONFIG_OFFSITE_ACCESS_KEY_ID
  valueFrom: { secretKeyRef: { name: {{ $offsite.secret }}, key: access_key_id } }
- name: RCLONE_CONFIG_OFFSITE_SECRET_ACCESS_KEY
  valueFrom: { secretKeyRef: { name: {{ $offsite.secret }}, key: secret_access_key } }
- name: OFFSITE_BUCKET
  valueFrom: { secretKeyRef: { name: {{ $offsite.secret }}, key: bucket } }
- name: CRYPT_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ $offsite.secret }}, key: crypt_password } }
- { name: RCLONE_CONFIG_SEALED_TYPE, value: crypt }
- { name: RCLONE_CONFIG_SEALED_REMOTE, value: "offsite:$(OFFSITE_BUCKET)/{{ $offsite.prefix }}" }
- { name: RCLONE_CONFIG_SEALED_FILENAME_ENCRYPTION, value: "off" }
- { name: RCLONE_CONFIG_SEALED_DIRECTORY_NAME_ENCRYPTION, value: "false" }
{{- end }}
