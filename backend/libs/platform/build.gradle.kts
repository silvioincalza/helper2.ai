// Cross-cutting infrastructure (package ai.helper2.platform): HTTP, LLM client, resilience,
// quotas, cache, settings, observability. Its test fixtures are the shared test helpers.
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
  // OpenAI-compatible client, used for NVIDIA Nemotron on Nebius Token Factory.
  api("org.springframework.ai:spring-ai-starter-model-openai")
  api("org.apache.httpcomponents.client5:httpclient5")
  api("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
  api("io.github.resilience4j:resilience4j-circuitbreaker")
  api("io.github.resilience4j:resilience4j-bulkhead")
  api("io.github.resilience4j:resilience4j-retry")
  implementation("io.github.resilience4j:resilience4j-micrometer")

  // ai.helper2.testing and TestcontainersConfiguration, for the tests of every project.
  testFixturesApi("org.springframework.boot:spring-boot-starter-webmvc-test")
  testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
  testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
  testFixturesApi("org.testcontainers:testcontainers-postgresql")
  testFixturesApi("net.ttddyy:datasource-proxy:1.11.1")
  // The shared containers migrate once as the owner and open the apps' logins (ADR 0070).
  testFixturesApi(project(":libs:schema"))
}
