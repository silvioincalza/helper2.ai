import ai.helper2.buildlogic.SHARED_API_PACKAGE
import ai.helper2.buildlogic.springContract
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
  id("helper2.kotlin-library")
  id("org.openapi.generator")
  `java-test-fixtures`
}

val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
  springContract(
    rootProject.layout.projectDirectory.file("../api/common.yaml").asFile,
    generatedApiDir.get().asFile,
    SHARED_API_PACKAGE,
  )
  doLast { delete(generatedApiDir.get().dir("src/main/java/org")) }
}

sourceSets { main { java.srcDir(generatedApiDir.map { it.dir("src/main/java") }) } }

tasks.named("compileJava") { dependsOn("openApiGenerate") }

tasks.named("compileKotlin") { dependsOn("openApiGenerate") }

dependencies {
  api(project(":libs:platform"))
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-security-oauth2-client")
  api("org.springframework.boot:spring-boot-starter-session-data-redis")
  implementation("com.github.pemistahl:lingua:1.2.2")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("tools.jackson.module:jackson-module-kotlin")

  testImplementation(testFixtures(project(":libs:platform")))
  testImplementation("io.micrometer:micrometer-registry-prometheus")
  testFixturesImplementation(testFixtures(project(":libs:platform")))
  testFixturesImplementation("org.jetbrains.kotlin:kotlin-reflect")
}
