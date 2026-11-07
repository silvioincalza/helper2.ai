import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// A backend project: Java 25 + Kotlin, the Spring Boot, Modulith, Spring AI and Resilience4j BOMs,
// the shared test setup and the opt-in test tasks. Libraries apply it alone; apps through
// helper2.spring-app.
plugins {
  `java-library`
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.spring")
  id("io.spring.dependency-management")
  id("helper2.format")
}

group = rootProject.group

version = rootProject.version

// helper2-<project>.jar: a plain project name (kernel, search) could clash with a library jar in
// the boot jar's BOOT-INF/lib.
base.archivesName = "helper2-${project.name}"

java {
  toolchain {
    // Java 25 (latest LTS). Gradle provisions the JDK if missing (foojay resolver in
    // settings.gradle.kts).
    languageVersion =
      JavaLanguageVersion.of((findProperty("javaToolchain") as String?)?.toInt() ?: 25)
  }
}

extra["springAiVersion"] = "2.0.2"

extra["springModulithVersion"] = "2.1.2"

extra["httpclient5.version"] = "5.6.5"

extra["resilience4jVersion"] = "2.4.0"

// Keep the Kotlin stdlib/reflect managed by the BOM in line with the Kotlin plugin.
extra["kotlin.version"] = "2.4.21"

dependencyManagement {
  imports {
    // The Spring Boot plugin imports this BOM itself in an app; a library imports it here.
    mavenBom(SpringBootPlugin.BOM_COORDINATES)
    mavenBom(
      "org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}"
    )
    mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    mavenBom("io.github.resilience4j:resilience4j-bom:${property("resilience4jVersion")}")
  }
}

dependencies {
  // @ApplicationModule on each module's package-info: Spring Modulith finds the modules in every
  // project.
  implementation("org.springframework.modulith:spring-modulith-api")
  // Metadata for the @ConfigurationProperties records (ADR 0007): IDE completion and a known key
  // list for application.yaml. The records are Java, so javac runs it; Kotlin needs no kapt.
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
  testImplementation("org.springframework.boot:spring-boot-starter-security-test")
  testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.springframework.modulith:spring-modulith-starter-test")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.testcontainers:testcontainers-postgresql")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
  testImplementation("com.networknt:json-schema-validator:3.0.8")
  // TOML: parse-back test of the Codex setup snippet. YAML: ContractValidator reads the OpenAPI
  // contract.
  testImplementation("tools.jackson.dataformat:jackson-dataformat-toml")
  testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
  // Counts SQL statements per test (StatementCounting): N+1 regressions fail a test, not a page
  // view. MIT.
  testImplementation("net.ttddyy:datasource-proxy:1.11.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// What the Spring Boot plugin sets in an app, so a library compiles the same way: parameter names
// for constructor binding and row mappers, UTF-8 sources.
tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  if (!options.compilerArgs.contains("-parameters")) {
    options.compilerArgs.add("-parameters")
  }
}

configure<KotlinJvmProjectExtension> {
  compilerOptions {
    javaParameters = true
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

// ObservedHttp's User-Agent reads the version from the manifest of the jar its class is in.
tasks.named<Jar>("jar") {
  manifest {
    attributes(
      "Implementation-Title" to base.archivesName.get(),
      "Implementation-Version" to project.version,
    )
  }
}

// Testcontainers on Podman (macOS): if DOCKER_HOST is unset and a Podman machine is running, point
// Testcontainers at its socket and disable Ryuk (unsupported on rootless Podman). Docker users are
// unaffected.
val podmanSocket =
  providers
    .exec {
      commandLine(
        "sh",
        "-c",
        "command -v podman >/dev/null && podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null || true",
      )
      isIgnoreExitValue = true
    }
    .standardOutput
    .asText
    .map { it.trim() }

tasks.withType<Test>().configureEach {
  // Every test runs in backend/, as when the backend was one project: paths like ../frontend,
  // ../.env, docs/eval and build/perf resolve as before.
  workingDir = rootProject.projectDir
  // Gradle gives a test JVM 512 MB by default. The integration tests keep a few Spring contexts in
  // the test context cache, and CI ran out of memory twice (5 Nov 2026: #169, then main after the
  // config PRs). 2 GB leaves room for Postgres and Redis on a 7 GB GitHub runner.
  maxHeapSize = "2g"
  // Hermetic tests: never pick up a developer's .env (keys, sources, ports).
  // spring.config.import="" alone did not stop it: application.yaml's own import still loaded
  // ../.env.
  systemProperty("helper2.dotenv-import", "optional:file:build/no-dotenv[.properties]")
  // ...nor a real embedding key from the shell: vector search stays off unless a test turns it on.
  systemProperty("helper2.embedding.api-key", "")
  // A slow CI runner can take more than production's 250 ms for one Redis round trip to the
  // container. Set for every test, not per class, so the tests keep sharing one Spring context.
  systemProperty("spring.data.redis.timeout", "5s")
  // The category backfill (ADR 0013) would run in the background of every new test context and
  // race the test's own writes; the tests that need it call it.
  systemProperty("helper2.catalog.categories.backfill-at-startup", "false")
  doFirst {
    val socket = podmanSocket.get()
    if (
      System.getenv("DOCKER_HOST").isNullOrBlank() && socket.isNotEmpty() && file(socket).exists()
    ) {
      environment("DOCKER_HOST", "unix://$socket")
      environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    }
  }
}

tasks.named<Test>("test") {
  useJUnitPlatform { excludeTags("live", "eval", "eval-record", "perf") }
}

// The opt-in tasks below exist in every project, so ./gradlew <task> in backend/ runs the tagged
// tests wherever they live. A project with none of them passes.
fun Test.taggedFromTestSources(tag: String) {
  group = "verification"
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform { includeTags(tag) }
  failOnNoDiscoveredTests = false
}

// Search relevance and cutoff evaluation on recorded snapshots (ADR 0036): ./gradlew evalTest
// Reads backend/eval-data (git-ignored) and docs/eval; skips with a message when either is missing.
tasks.register<Test>("evalTest") {
  description =
    "Runs @Tag(\"eval\") tests: offline replay and metrics over recorded search snapshots."
  taggedFromTestSources("eval")
  outputs.upToDateWhen { false }
}

// Records search snapshots against the live sources through the real adapters (ADR 0036): ./gradlew
// evalRecord
// Uses the repo-root .env and the embedding key, unlike the hermetic test tasks.
tasks.register<Test>("evalRecord") {
  description = "Runs @Tag(\"eval-record\"): one live search per query, saved to backend/eval-data."
  taggedFromTestSources("eval-record")
  systemProperty("helper2.dotenv-import", "optional:file:../.env[.properties]")
  systemProperty("helper2.embedding.api-key", System.getenv("H2_NVIDIA_API_KEY") ?: "")
  outputs.upToDateWhen { false }
}

// Latency tests on a seeded catalog (Postgres in a container), before/after numbers for a PR:
// ./gradlew perfTest
// Prints p50/p95/p99 and writes them to build/perf/. Not part of test: they take minutes and the
// numbers depend on the machine.
tasks.register<Test>("perfTest") {
  description = "Runs @Tag(\"perf\") tests: latency percentiles on a seeded catalog."
  taggedFromTestSources("perf")
  testLogging { showStandardStreams = true }
  outputs.upToDateWhen { false }
}

// Opt-in smoke tests that hit real external APIs: ./gradlew liveTest
tasks.register<Test>("liveTest") {
  description = "Runs @Tag(\"live\") tests against real external sources."
  taggedFromTestSources("live")
}
