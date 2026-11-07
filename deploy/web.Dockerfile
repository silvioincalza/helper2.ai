# syntax=docker/dockerfile:1.7
# The web image (ADR 0069): the built public web app, static files only, served by nginx. It serves /assets/* and the
# root files of frontend/public (theme.js, favicon.svg); anything else is a 404, never index.html. The built shell
# (index.html) sits outside the served folder, at /usr/share/helper2/shell/index.html: an init container of the
# public pod copies it from the web image of the same tag, and the public app writes every page into it (ADR 0050).
# The admin web app is the back office image's (backoffice.Dockerfile).
# The build stage runs on the build machine's own architecture (fast on Apple silicon); only the runtime stage is the
# target platform, e.g. --platform linux/amd64 for the cluster.
# Base images are pinned by the digest of their multi-arch index (ADR 0046): a moved tag cannot change a build.
# Dependabot updates tag and digest together.
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:c6ad9e9c1d80f6eb607c26b4b0a132ff7a2344fad1c78ea3baa2a7d4c8de6d94 AS web
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
# The public app only: the same steps as `npm run build` without the admin app.
RUN cd frontend && npm run gen:api && npx tsc --noEmit && npx vite build
# nginx.conf names every file it serves. A new file in frontend/public, or a source map, stops the build here: add
# it to nginx.conf and to the Ingress routes (deploy/k8s/README.md) first.
RUN cd frontend/dist && found=$(ls -A | sort | tr '\n' ' ') \
    && [ "$found" = "assets favicon.svg index.html theme.js " ] \
    || { echo "frontend/dist holds '$found': update deploy/web/nginx.conf and the Ingress routes"; exit 1; } \
    && ! find . -name '*.map' | grep -q . || { echo "source maps in frontend/dist"; exit 1; }

FROM docker.io/nginxinc/nginx-unprivileged:1.31.7-alpine-slim@sha256:a8cb0f33d51fa107c96fd2bd3a71aaf564ec131e9d2bdf8700944f0ed8b170d7
USER root
# Alpine's fixed zlib (CVE-2026-4738, Critical) before the base image ships it: one package, so the rest of the
# image stays what the pinned digest gives. Drop this line once the base image's own zlib is 1.4 or later.
RUN apk upgrade --no-cache zlib
# Only our config: no default site, no sample pages, no entrypoint scripts (they write under /etc/nginx, which is
# read-only in the cluster).
RUN rm -rf /usr/share/nginx/html /etc/nginx/conf.d /docker-entrypoint.sh /docker-entrypoint.d
COPY deploy/web/nginx.conf /etc/nginx/nginx.conf
COPY deploy/web/headers.conf /etc/nginx/helper2-headers.conf
COPY --from=web /src/frontend/dist/assets /srv/web/assets
COPY --from=web /src/frontend/dist/theme.js /src/frontend/dist/favicon.svg /srv/web/
COPY --from=web /src/frontend/dist/index.html /usr/share/helper2/shell/index.html
USER 101
RUN nginx -t
# 8080: the files; 8081: /healthz for the probes, never routed by the Service.
EXPOSE 8080 8081
STOPSIGNAL SIGQUIT
ENTRYPOINT ["nginx", "-g", "daemon off;"]
