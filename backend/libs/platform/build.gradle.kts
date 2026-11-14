plugins {
  id("helper2.kotlin-library")
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:kernel"))
  api("org.springframework.boot:spring-boot-starter-webmvc")
  api("org.springframework.boot:spring-boot-starter-validation")
  api("org.springframework.boot:spring-boot-starter-actuator")
  api("org.springframework.boot:spring-boot-starter-jdbc")
  api("org.springframework.boot:spring-boot-starter-data-redis")
  api("org.springframework.ai:spring-ai-starter-model-openai")
  api("org.apache.httpcomponents.client5:httpclient5")
  api("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.11.0")
  api("io.github.resilience4j:resilience4j-circuitbreaker")
  api("io.github.resilience4j:resilience4j-bulkhead")
  api("io.github.resilience4j:resilience4j-retry")
  implementation("io.github.resilience4j:resilience4j-micrometer")

  testFixturesApi("org.springframework.boot:spring-boot-starter-webmvc-test")
  testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
  testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
  testFixturesApi("org.testcontainers:testcontainers-postgresql")
  testFixturesApi("net.ttddyy:datasource-proxy:1.11.1")
  testFixturesApi(project(":libs:schema"))
}

tasks.named<Test>("test") {
  inputs
    .files(
      rootProject.files(
        "apps/public/src/main/resources/application.yaml",
        "apps/backoffice/src/main/resources/application.yaml",
      )
    )
    .withPropertyName("appSettings")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(rootProject.files("../deploy/helm/helper2/files/redis-users.acl"))
    .withPropertyName("redisUsers")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
