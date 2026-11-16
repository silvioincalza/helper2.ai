{{- define "helper2.labels" -}}
app.kubernetes.io/part-of: helper2
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "helper2.envSecret" -}}
{{ required "envSecret.name is required: the Secret with the H2_ keys of app.env (make env-secret)" .Values.envSecret.name }}
{{- end }}

{{- define "helper2.credentialsSecret" -}}
{{ required "credentialsSecret.name is required: the Secret with the database and Redis passwords (make env-secret)" .Values.credentialsSecret.name }}
{{- end }}

{{- define "helper2.atDigest" -}}
{{- with . }}{{ if not (regexMatch "^sha256:[0-9a-f]{64}$" .) }}{{ fail "an image digest must be sha256:<64 hex>" }}{{ end }}@{{ . }}{{ end }}
{{- end }}

{{- define "helper2.backofficeTag" -}}
{{ default .Values.image.tag .Values.backoffice.image.tag }}
{{- end }}

{{- define "helper2.backofficeImage" -}}
{{ required "backoffice.image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2-backoffice)" .Values.backoffice.image.repository }}:{{ required "backoffice.image.tag or image.tag is required" (include "helper2.backofficeTag" .) }}{{ include "helper2.atDigest" .Values.backoffice.image.digest }}
{{- end }}

{{- define "helper2.webImage" -}}
{{ required "web.image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2-web)" .Values.web.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{ include "helper2.atDigest" .Values.web.image.digest }}
{{- end }}

{{- define "helper2.image" -}}
{{ required "image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2)" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{ include "helper2.atDigest" .Values.image.digest }}
{{- end }}

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

{{- define "helper2.redisUserPasswordKey" -}}
H2_REDIS_{{ trimPrefix "h2_" . | upper }}_PASSWORD
{{- end }}

{{- define "helper2.postgresImage" -}}
{{- with .Values.postgres.image -}}
{{ required "postgres.image.repository is required (Postgres + pgvector built from deploy/postgres)" .repository }}:{{ required "postgres.image.tag" .tag }}{{ include "helper2.atDigest" .digest }}
{{- end }}
{{- end }}

{{- define "helper2.redisAuthRevision" -}}
{{- $r := toString .Values.redis.auth.revision -}}
{{- if not (regexMatch "^[0-9]+$" $r) }}{{ fail "redis.auth.revision must be a non-negative integer" }}{{ end -}}
{{ $r | quote }}
{{- end }}

{{- define "helper2.canaryPrefix" -}}
{{ ternary "canary-" "" .Values.canary.only }}
{{- end }}

{{- define "helper2.appName" -}}
{{ ternary "helper2-canary" "helper2" .Values.canary.only }}
{{- end }}

{{- define "helper2.webName" -}}
{{ ternary "helper2-web-canary" "helper2-web" .Values.canary.only }}
{{- end }}

{{- define "helper2.migrateName" -}}
{{ ternary "helper2-canary-migrate" "helper2-migrate" .Values.canary.only }}
{{- end }}

{{- define "helper2.offsiteEnv" -}}
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
