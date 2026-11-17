# syntax=docker/dockerfile:1.7
ARG BUILD=source
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS web
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build

FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS app-source
WORKDIR /src
COPY api ./api
COPY backend ./backend
COPY --from=web /src/frontend/dist-admin ./backend/apps/backoffice/src/main/resources/static/admin
RUN cd backend && ./gradlew --no-daemon :apps:backoffice:bootJar -x test && cp apps/backoffice/build/libs/*.jar /app.jar

FROM scratch AS app-prebuilt
COPY prebuilt/backoffice.jar /app.jar

FROM app-${BUILD} AS app

FROM docker.io/library/eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
RUN useradd --system --uid 10001 helper2
USER 10001
COPY --from=app /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
