# syntax=docker/dockerfile:1.7
ARG BUILD=source
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216 AS web
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build

FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS app-source
WORKDIR /src
COPY api ./api
COPY backend ./backend
COPY --from=web /src/frontend/dist-admin ./backend/apps/backoffice/src/main/resources/static/admin
RUN cd backend && ./gradlew --no-daemon :apps:backoffice:bootJar -x test && cp apps/backoffice/build/libs/*.jar /app.jar

FROM scratch AS app-prebuilt
COPY prebuilt/backoffice.jar /app.jar

FROM app-${BUILD} AS app

FROM docker.io/library/eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
RUN useradd --system --uid 10001 helper2
USER 10001
COPY --from=app /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
