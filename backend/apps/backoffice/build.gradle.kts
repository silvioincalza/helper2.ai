import ai.helper2.buildlogic.BundleContract

// The back office app (ADR 0032): the admin API and the admin web app, the Scout, its collectors
// and
// enrichment, the home-example check and the catalog's jobs. Served only on the in-network admin
// host
// (ADR 0024).
plugins { id("helper2.spring-app") }

// The boot jar is helper2-backoffice-<version>.jar.
base.archivesName = "helper2-backoffice"

tasks.named<BundleContract>("bundleContract") {
  entries.from(rootProject.layout.projectDirectory.file("../api/admin.yaml"))
}

dependencies {
  implementation(project(":libs:kernel"))
  implementation(project(":libs:platform"))
  implementation(project(":libs:monetisation"))
  implementation(project(":libs:identity"))
  implementation(project(":libs:search"))
  implementation(project(":libs:catalog"))
  implementation(project(":libs:examples"))
  implementation(project(":libs:sources"))
  implementation(project(":libs:schema"))

  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  // Flyway validates only: the migrate step of the public image migrates (ADR 0004).
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  // Sign-in sessions in Redis (own namespace), so a signed-in admin is recognised by every replica.
  implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
  implementation("org.springframework.modulith:spring-modulith-starter-core")
  // OpenAI-compatible client, used for NVIDIA Nemotron on Nebius Token Factory.
  implementation("org.springframework.ai:spring-ai-starter-model-openai")
  implementation("org.apache.httpcomponents.client5:httpclient5")
  implementation("io.micrometer:micrometer-registry-prometheus")
  // Micrometer Tracing over OpenTelemetry; spans go by OTLP to the cluster Alloy, then Tempo.
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
  implementation("org.flywaydb:flyway-database-postgresql")
  // One Scout run and one embedding batch at a time across replicas: a lock row in Postgres (table
  // shedlock).
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("tools.jackson.module:jackson-module-kotlin")
  // Language of queries and catalog text, for the matching Postgres stemmer (LanguageDetector).
  implementation("com.github.pemistahl:lingua:1.2.2")
  // Parses the YAML conference files of huggingface/ai-deadlines (version from the Spring Boot
  // BOM).
  implementation("org.yaml:snakeyaml")
  // Circuit breakers, bulkheads and retries as named policies (ADR 0006), built from the registries
  // in code.
  // The 2.4.0 BOM shipped without the Spring Boot 4 module at first: its version is pinned here.
  implementation(
    "io.github.resilience4j:resilience4j-spring-boot4:${property("resilience4jVersion")}"
  )
  implementation("io.github.resilience4j:resilience4j-circuitbreaker")
  implementation("io.github.resilience4j:resilience4j-bulkhead")
  implementation("io.github.resilience4j:resilience4j-retry")
  implementation("io.github.resilience4j:resilience4j-micrometer")
  runtimeOnly("org.postgresql:postgresql")
  developmentOnly("org.springframework.boot:spring-boot-docker-compose")

  testImplementation(testFixtures(project(":libs:platform")))
  testImplementation(testFixtures(project(":libs:search")))
  testImplementation(testFixtures(project(":libs:sources")))
}
