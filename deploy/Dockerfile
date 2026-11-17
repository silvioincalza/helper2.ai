# syntax=docker/dockerfile:1.7
ARG BUILD=source
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d AS app-source
WORKDIR /src
COPY api ./api
COPY backend ./backend
RUN cd backend && ./gradlew --no-daemon :apps:public:bootJar -x test && cp apps/public/build/libs/*.jar /app.jar

FROM scratch AS app-prebuilt
COPY prebuilt/public.jar /app.jar

FROM app-${BUILD} AS app

FROM docker.io/library/eclipse-temurin:25-jre@sha256:a640968ae55027b322cedd5f8228b01a84baff0b9e9360e47601c6a111ba1699
RUN useradd --system --uid 10001 helper2
USER 10001
COPY --from=app /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
