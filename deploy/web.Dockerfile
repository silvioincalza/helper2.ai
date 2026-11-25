# syntax=docker/dockerfile:1.7
ARG BUILD=source
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS web-source
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run gen:api && npx tsc --noEmit && npx vite build

FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS web-prebuilt
COPY prebuilt/web /src/frontend/dist

FROM web-${BUILD} AS web
RUN cd /src/frontend/dist && found=$(ls -A | sort | tr '\n' ' ') \
    && [ "$found" = "assets favicon.ico favicon.svg index.html theme.js " ] \
    || { echo "frontend/dist holds '$found': update deploy/web/nginx.conf"; exit 1; } \
    && ! find . -name '*.map' | grep -q . || { echo "source maps in frontend/dist"; exit 1; }

FROM docker.io/nginxinc/nginx-unprivileged:1.31.7-alpine-slim@sha256:a8cb0f33d51fa107c96fd2bd3a71aaf564ec131e9d2bdf8700944f0ed8b170d7
USER root
RUN apk upgrade --no-cache zlib
RUN rm -rf /usr/share/nginx/html /etc/nginx/conf.d /docker-entrypoint.sh /docker-entrypoint.d
COPY deploy/web/nginx.conf /etc/nginx/nginx.conf
COPY deploy/web/headers.conf /etc/nginx/searchavo-headers.conf
COPY --from=web /src/frontend/dist/assets /srv/web/assets
COPY --from=web /src/frontend/dist/theme.js /src/frontend/dist/favicon.svg /src/frontend/dist/favicon.ico /srv/web/
COPY --from=web /src/frontend/dist/index.html /usr/share/searchavo/shell/index.html
USER 101
RUN nginx -t
EXPOSE 8080 8081
STOPSIGNAL SIGQUIT
ENTRYPOINT ["nginx", "-g", "daemon off;"]
