# syntax=docker/dockerfile:1.7
# JVM image: builds the SPA, embeds it as static resources, runs on a JRE 25.
# Build stages run on the build machine's own architecture (fast on Apple silicon); only the
# runtime stage is the target platform, e.g. --platform linux/amd64 for the cluster.
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine AS web
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build

FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk AS app
WORKDIR /src
COPY api ./api
COPY backend ./backend
COPY --from=web /src/frontend/dist ./backend/src/main/resources/static
RUN cd backend && ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /app.jar

FROM docker.io/library/eclipse-temurin:25-jre
RUN useradd --system --uid 10001 helper2
USER 10001
COPY --from=app /app.jar /app/app.jar
EXPOSE 8080
# Heap = 60% of the container limit. The rest is for what lives outside the heap (metaspace, thread
# stacks, code cache, direct buffers): with 75% of a 1Gi limit the pod risked an OOM kill. An
# OutOfMemoryError exits the JVM, so Kubernetes restarts a clean pod instead of keeping a broken one.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
