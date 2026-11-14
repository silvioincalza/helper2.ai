import ai.helper2.buildlogic.SHARED_API_PACKAGE
import ai.helper2.buildlogic.springContract
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
  id("helper2.kotlin-library")
  id("org.openapi.generator")
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
  api(project(":libs:search"))

  testImplementation(testFixtures(project(":libs:search")))
}
