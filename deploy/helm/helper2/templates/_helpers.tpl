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

{{/* The back office's tag: its own, or the public app's, so both deploy the same version by default. */}}
{{- define "helper2.backofficeTag" -}}
{{ default .Values.image.tag .Values.backoffice.image.tag }}
{{- end }}

{{- define "helper2.backofficeImage" -}}
{{ required "backoffice.image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2-backoffice)" .Values.backoffice.image.repository }}:{{ required "backoffice.image.tag or image.tag is required" (include "helper2.backofficeTag" .) }}
{{- end }}

{{- define "helper2.image" -}}
{{ required "image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2)" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}
{{- end }}

{{/* The Redis password as an env var source: redis.auth.existingSecret, or H2_REDIS_PASSWORD of the env Secret. */}}
{{- define "helper2.redisPasswordFrom" -}}
{{- with .Values.redis.auth -}}
{{- if .existingSecret -}}
secretKeyRef: { name: {{ .existingSecret }}, key: {{ required "redis.auth.existingSecretKey" .existingSecretKey }} }
{{- else -}}
secretKeyRef: { name: {{ include "helper2.envSecret" $ }}, key: H2_REDIS_PASSWORD }
{{- end -}}
{{- end -}}
{{- end }}

{{/* repository:tag, or repository:tag@digest when postgres.image.digest pins it. */}}
{{- define "helper2.postgresImage" -}}
{{- with .Values.postgres.image -}}
{{ required "postgres.image.repository is required (Postgres + pgvector built from deploy/postgres)" .repository }}:{{ required "postgres.image.tag" .tag }}
{{- with .digest }}{{ if not (regexMatch "^sha256:[0-9a-f]{64}$" .) }}{{ fail "postgres.image.digest must be sha256:<64 hex>" }}{{ end }}@{{ . }}{{ end }}
{{- end }}
{{- end }}

{{/* redis.auth.revision as a plain number. Anything else fails: `int` would turn "v2" into 0, and a rotation would
     then restart nothing. */}}
{{- define "helper2.redisAuthRevision" -}}
{{- $r := toString .Values.redis.auth.revision -}}
{{- if not (regexMatch "^[0-9]+$" $r) }}{{ fail "redis.auth.revision must be a non-negative integer" }}{{ end -}}
{{ $r | quote }}
{{- end }}
