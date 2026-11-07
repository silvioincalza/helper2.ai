# syntax=docker/dockerfile:1.7
# JVM image: builds the SPA, embeds it as static resources, runs on a JRE 25.
# Build stages run on the build machine's own architecture (fast on Apple silicon); only the
# runtime stage is the target platform, e.g. --platform linux/amd64 for the cluster.
# Base images are pinned by the digest of their multi-arch index (ADR 0046): a moved tag cannot change a build.
# Dependabot updates tag and digest together.
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:c6ad9e9c1d80f6eb607c26b4b0a132ff7a2344fad1c78ea3baa2a7d4c8de6d94 AS web
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build

FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS app
WORKDIR /src
COPY api ./api
COPY backend ./backend
COPY --from=web /src/frontend/dist ./backend/apps/helper2/src/main/resources/static
RUN cd backend && ./gradlew --no-daemon :apps:helper2:bootJar -x test && cp apps/helper2/build/libs/*.jar /app.jar

FROM docker.io/library/eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
RUN useradd --system --uid 10001 helper2
USER 10001
COPY --from=app /app.jar /app/app.jar
EXPOSE 8080
# Heap = 60% of the container limit. The rest is for what lives outside the heap (metaspace, thread
# stacks, code cache, direct buffers): with 75% of a 1Gi limit the pod risked an OOM kill. An
# OutOfMemoryError exits the JVM, so Kubernetes restarts a clean pod instead of keeping a broken one.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
