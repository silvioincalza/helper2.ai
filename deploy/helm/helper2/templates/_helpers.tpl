{{/* Labels on every object's metadata. Selectors stay `app: <name>` only: they are immutable. */}}
{{- define "helper2.labels" -}}
app.kubernetes.io/part-of: helper2
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{/*
envFile (KEY=value lines, # comments) as a dict, like kustomize's secretGenerator `envs`:
the value is everything after the first "=", kept as written.
*/}}
{{- define "helper2.env" -}}
{{- $out := dict -}}
{{- range $line := splitList "\n" .Values.envFile -}}
{{- $l := trim $line -}}
{{- if and $l (not (hasPrefix "#" $l)) (contains "=" $l) -}}
{{- $kv := regexSplit "=" $l 2 -}}
{{- $_ := set $out (trim (index $kv 0)) (index $kv 1) -}}
{{- end -}}
{{- end -}}
{{- if not (hasKey $out "H2_DATABASE_PASSWORD") -}}
{{- fail "envFile must contain H2_DATABASE_PASSWORD (pass --set-file envFile=<env>/app.env)" -}}
{{- end -}}
{{- toJson $out -}}
{{- end }}

{{- define "helper2.image" -}}
{{ required "image.repository is required (your registry, e.g. ghcr.io/<owner>/helper2)" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}
{{- end }}

{{/*
The Redis password as an env var source: existingSecret, or H2_REDIS_PASSWORD of helper2-env (then it
must be in envFile, letters and digits only).
*/}}
{{- define "helper2.redisPasswordFrom" -}}
{{- with .Values.redis.auth -}}
{{- if .existingSecret -}}
secretKeyRef: { name: {{ .existingSecret }}, key: {{ required "redis.auth.existingSecretKey" .existingSecretKey }} }
{{- else -}}
{{- $pw := get (include "helper2.env" $ | fromJson) "H2_REDIS_PASSWORD" -}}
{{- if not (regexMatch "^[A-Za-z0-9]{16,}$" (toString $pw)) -}}
{{- fail "redis.auth: envFile needs H2_REDIS_PASSWORD, 16+ letters and digits (openssl rand -hex 32), or set redis.auth.existingSecret" -}}
{{- end -}}
secretKeyRef: { name: helper2-env, key: H2_REDIS_PASSWORD }
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
