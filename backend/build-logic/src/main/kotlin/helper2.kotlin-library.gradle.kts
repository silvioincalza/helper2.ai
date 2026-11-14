import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
  `java-library`
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.spring")
  id("io.spring.dependency-management")
  id("helper2.format")
}

group = rootProject.group

version = rootProject.version

base.archivesName = "helper2-${project.name}"

java {
  toolchain {
    languageVersion =
      JavaLanguageVersion.of((findProperty("javaToolchain") as String?)?.toInt() ?: 25)
  }
}

extra["springAiVersion"] = "2.0.2"

extra["springModulithVersion"] = "2.1.2"

extra["httpclient5.version"] = "5.6.5"

extra["resilience4jVersion"] = "2.4.0"

extra["kotlin.version"] = "2.4.21"

dependencyManagement {
  imports {
    mavenBom(SpringBootPlugin.BOM_COORDINATES)
    mavenBom(
      "org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}"
    )
    mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    mavenBom("io.github.resilience4j:resilience4j-bom:${property("resilience4jVersion")}")
  }
}

dependencies {
  implementation("org.springframework.modulith:spring-modulith-api")
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
  testImplementation("tools.jackson.dataformat:jackson-dataformat-toml")
  testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
  testImplementation("net.ttddyy:datasource-proxy:1.11.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

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

tasks.named<Jar>("jar") {
  manifest {
    attributes(
      "Implementation-Title" to base.archivesName.get(),
      "Implementation-Version" to project.version,
    )
  }
}

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
  workingDir = rootProject.projectDir
  maxHeapSize = "2g"
  systemProperty("helper2.dotenv-import", "optional:file:build/no-dotenv[.properties]")
  systemProperty("helper2.embedding.api-key", "")
  systemProperty("spring.data.redis.timeout", "5s")
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

fun Test.taggedFromTestSources(tag: String) {
  group = "verification"
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform { includeTags(tag) }
  failOnNoDiscoveredTests = false
}

tasks.register<Test>("evalTest") {
  description =
    "Runs @Tag(\"eval\") tests: offline replay and metrics over recorded search snapshots."
  taggedFromTestSources("eval")
  outputs.upToDateWhen { false }
}

tasks.register<Test>("evalRecord") {
  description = "Runs @Tag(\"eval-record\"): one live search per query, saved to backend/eval-data."
  taggedFromTestSources("eval-record")
  systemProperty("helper2.dotenv-import", "optional:file:../.env[.properties]")
  systemProperties.remove("helper2.embedding.api-key")
  outputs.upToDateWhen { false }
}

tasks.register<Test>("perfTest") {
  description = "Runs @Tag(\"perf\") tests: latency percentiles on a seeded catalog."
  taggedFromTestSources("perf")
  testLogging { showStandardStreams = true }
  outputs.upToDateWhen { false }
}

tasks.register<Test>("liveTest") {
  description = "Runs @Tag(\"live\") tests against real external sources."
  taggedFromTestSources("live")
}
