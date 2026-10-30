import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.26.0"
}

group = "ai.helper2"
version = "1.0.2"

java {
    toolchain {
        // Java 25 (latest LTS). Gradle provisions the JDK if missing (foojay resolver in settings.gradle.kts).
        languageVersion = JavaLanguageVersion.of((findProperty("javaToolchain") as String?)?.toInt() ?: 25)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.2"
extra["springModulithVersion"] = "2.1.2"
extra["httpclient5.version"] = "5.6.5"
// Keep the Kotlin stdlib/reflect managed by the BOM in line with the Kotlin plugin.
extra["kotlin.version"] = "2.4.21"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Sign-in sessions in Redis, so a signed-in admin is recognised by every replica.
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
    // One Scout run and one embedding batch at a time across replicas: a lock row in Postgres (table shedlock).
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Annotations referenced by generated OpenAPI code.
    implementation("io.swagger.core.v3:swagger-annotations:2.2.55")
    // Language of queries and catalog text, for the matching Postgres stemmer (LanguageDetector).
    implementation("com.github.pemistahl:lingua:1.2.2")
    // Parses the YAML conference files of huggingface/ai-deadlines (version from the Spring Boot BOM).
    implementation("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// Contract-first: controller interfaces + DTOs are generated from ../api/openapi.yaml.
val openApiSpec = layout.projectDirectory.file("../api/openapi.yaml")
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("spring")
    inputSpec.set(openApiSpec.asFile.path)
    outputDir.set(generatedApiDir.get().asFile.path)
    apiPackage.set("ai.helper2.api.generated")
    modelPackage.set("ai.helper2.api.generated.model")
    modelNameSuffix.set("Dto")
    cleanupOutput.set(true)
    configOptions.set(mapOf(
        "useSpringBoot4" to "true",
        "useJackson3" to "true",
        "interfaceOnly" to "true",
        "useTags" to "true",
        "skipDefaultInterface" to "true",
        "openApiNullable" to "false",
        "useBeanValidation" to "true",
        "performBeanValidation" to "false",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "dateLibrary" to "java8",
        "sourceFolder" to "src/main/java",
        "useOptional" to "false",
        "containerDefaultToNull" to "false",
        // Explicit (generator 7.24+ warns when unset): null handling stays with the global JSON mapper.
        "generateJsonIncludeAnnotations" to "false",
        "generateJsonSetterNullsAnnotations" to "false",
    ))
}

sourceSets {
    main {
        java.srcDir(generatedApiDir.map { it.dir("src/main/java") })
    }
}

tasks.named("compileJava") { dependsOn("openApiGenerate") }
tasks.named("compileKotlin") { dependsOn("openApiGenerate") }

// Testcontainers on Podman (macOS): if DOCKER_HOST is unset and a Podman machine is running, point
// Testcontainers at its socket and disable Ryuk (unsupported on rootless Podman). Docker users are unaffected.
val podmanSocket = providers.exec {
    commandLine("sh", "-c", "command -v podman >/dev/null && podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null || true")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

tasks.withType<Test>().configureEach {
    systemProperty("openapi.spec", openApiSpec.asFile.absolutePath)
    // Hermetic tests: never pick up a developer's .env (keys, sources, ports).
    // spring.config.import="" alone did not stop it: application.yaml's own import still loaded ../.env.
    systemProperty("helper2.dotenv-import", "optional:file:build/no-dotenv[.properties]")
    // ...nor a real embedding key from the shell: vector search stays off unless a test turns it on.
    systemProperty("helper2.embedding.api-key", "")
    doFirst {
        val socket = podmanSocket.get()
        if (System.getenv("DOCKER_HOST").isNullOrBlank() && socket.isNotEmpty() && file(socket).exists()) {
            environment("DOCKER_HOST", "unix://$socket")
            environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("live", "eval", "eval-record")
    }
}

// Search relevance and cutoff evaluation on recorded snapshots (ADR 0036): ./gradlew evalTest
// Reads backend/eval-data (git-ignored) and docs/eval; skips with a message when either is missing.
tasks.register<Test>("evalTest") {
    description = "Runs @Tag(\"eval\") tests: offline replay and metrics over recorded search snapshots."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("eval") }
    outputs.upToDateWhen { false }
}

// Records search snapshots against the live sources through the real adapters (ADR 0036): ./gradlew evalRecord
// Uses the repo-root .env and the embedding key, unlike the hermetic test tasks.
tasks.register<Test>("evalRecord") {
    description = "Runs @Tag(\"eval-record\"): one live search per query, saved to backend/eval-data."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("eval-record") }
    systemProperty("helper2.dotenv-import", "optional:file:../.env[.properties]")
    systemProperty("helper2.embedding.api-key", System.getenv("H2_NVIDIA_API_KEY") ?: "")
    outputs.upToDateWhen { false }
}

// Opt-in smoke tests that hit real external APIs: ./gradlew liveTest
tasks.register<Test>("liveTest") {
    description = "Runs @Tag(\"live\") tests against real external sources."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
}


