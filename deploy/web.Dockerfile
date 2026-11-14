# syntax=docker/dockerfile:1.7
ARG BUILD=source
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216 AS web-source
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run gen:api && npx tsc --noEmit && npx vite build

FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216 AS web-prebuilt
COPY prebuilt/web /src/frontend/dist

FROM web-${BUILD} AS web
RUN cd /src/frontend/dist && found=$(ls -A | sort | tr '\n' ' ') \
    && [ "$found" = "assets favicon.svg index.html theme.js " ] \
    || { echo "frontend/dist holds '$found': update deploy/web/nginx.conf and the Ingress routes"; exit 1; } \
    && ! find . -name '*.map' | grep -q . || { echo "source maps in frontend/dist"; exit 1; }

FROM docker.io/nginxinc/nginx-unprivileged:1.31.7-alpine-slim@sha256:a8cb0f33d51fa107c96fd2bd3a71aaf564ec131e9d2bdf8700944f0ed8b170d7
USER root
RUN apk upgrade --no-cache zlib
RUN rm -rf /usr/share/nginx/html /etc/nginx/conf.d /docker-entrypoint.sh /docker-entrypoint.d
COPY deploy/web/nginx.conf /etc/nginx/nginx.conf
COPY deploy/web/headers.conf /etc/nginx/helper2-headers.conf
COPY --from=web /src/frontend/dist/assets /srv/web/assets
COPY --from=web /src/frontend/dist/theme.js /src/frontend/dist/favicon.svg /srv/web/
COPY --from=web /src/frontend/dist/index.html /usr/share/helper2/shell/index.html
USER 101
RUN nginx -t
EXPOSE 8080 8081
STOPSIGNAL SIGQUIT
ENTRYPOINT ["nginx", "-g", "daemon off;"]
