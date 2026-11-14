import ai.helper2.buildlogic.BundleContract

plugins { id("helper2.spring-app") }

base.archivesName = "helper2-public"

tasks.named<BundleContract>("bundleContract") {
  entries.from(rootProject.layout.projectDirectory.file("../api/public.yaml"))
}

dependencies {
  implementation(project(":libs:kernel"))
  implementation(project(":libs:platform"))
  implementation(project(":libs:identity"))
  implementation(project(":libs:search"))
  implementation(project(":libs:catalog"))
  implementation(project(":libs:examples"))
  implementation(project(":libs:sources"))
  implementation(project(":libs:schema"))
  implementation(project(":libs:web-contract"))

  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("io.projectreactor:reactor-core")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
  implementation("org.springframework.modulith:spring-modulith-starter-core")
  implementation("org.springframework.ai:spring-ai-starter-model-openai")
  implementation("org.apache.httpcomponents.client5:httpclient5")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("tools.jackson.module:jackson-module-kotlin")
  implementation("com.github.pemistahl:lingua:1.2.2")
  implementation(
    "io.github.resilience4j:resilience4j-spring-boot4:${property("resilience4jVersion")}"
  )
  implementation("io.github.resilience4j:resilience4j-circuitbreaker")
  implementation("io.github.resilience4j:resilience4j-bulkhead")
  implementation("io.github.resilience4j:resilience4j-retry")
  implementation("io.github.resilience4j:resilience4j-micrometer")
  implementation("com.github.ben-manes.caffeine:caffeine:3.3.1")
  runtimeOnly("org.postgresql:postgresql")
  developmentOnly("org.springframework.boot:spring-boot-docker-compose")

  testImplementation(testFixtures(project(":libs:platform")))
  testImplementation(testFixtures(project(":libs:search")))
  testImplementation(testFixtures(project(":libs:sources")))
}

tasks.named<Test>("test") {
  inputs
    .files(rootProject.files("../deploy/helm/helper2/files/redis-users.acl"))
    .withPropertyName("redisUsers")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(
      rootProject.files(
        "../frontend/index.html",
        "../frontend/src/routes.tsx",
        "../frontend/src/i18n/locales/en.json",
      )
    )
    .withPropertyName("frontendPages")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(rootProject.files("../docs/eval/search-baseline.json"))
    .withPropertyName("rankingBaseline")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(
      rootProject.fileTree(".") {
        include("libs/*/src/main/java/**", "libs/*/src/main/kotlin/**")
        include("apps/*/src/main/java/**", "apps/*/src/main/kotlin/**")
      }
    )
    .withPropertyName("backendSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
