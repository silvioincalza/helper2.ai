# syntax=docker/dockerfile:1.7
ARG BUILD=source
FROM --platform=$BUILDPLATFORM docker.io/library/node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS web
WORKDIR /src
COPY api ./api
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build

FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS app-source
WORKDIR /src
COPY api ./api
COPY backend ./backend
COPY --from=web /src/frontend/dist-admin ./backend/apps/backoffice/src/main/resources/static/admin
RUN cd backend && ./gradlew --no-daemon :apps:backoffice:bootJar -x test && cp apps/backoffice/build/libs/*.jar /app.jar

FROM scratch AS app-prebuilt
COPY prebuilt/backoffice.jar /app.jar

FROM app-${BUILD} AS app

FROM docker.io/library/eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
RUN useradd --system --uid 10001 searchavo
USER 10001
COPY --from=app /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
