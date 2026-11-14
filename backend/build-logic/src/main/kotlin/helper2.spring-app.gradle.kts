import ai.helper2.buildlogic.BundleContract
import ai.helper2.buildlogic.sharedModelClasses
import ai.helper2.buildlogic.springContract
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
  id("helper2.kotlin-library")
  id("org.springframework.boot")
  id("org.openapi.generator")
}

tasks.named<JavaExec>("bootRun") { workingDir = rootProject.projectDir }

val apiDir = rootProject.layout.projectDirectory.dir("../api")
val sharedContract = apiDir.file("common.yaml")
val contract = layout.buildDirectory.file("openapi/contract.yaml")
val generationContract = layout.buildDirectory.file("openapi/generation.yaml")
val bundleContract =
  tasks.register<BundleContract>("bundleContract") {
    referenced.from(apiDir.asFileTree.matching { include("*.yaml") })
    shared.set(sharedContract)
    bundle.set(contract)
    generation.set(generationContract)
  }
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
  dependsOn(bundleContract)
  springContract(
    generationContract.get().asFile,
    generatedApiDir.get().asFile,
    "ai.helper2.api.generated",
  )
  val shared by lazy { sharedModelClasses(sharedContract.asFile, temporaryDir) }
  schemaMappings.set(
    providers.provider { shared.mapValues { (_, type) -> type.substringAfterLast('.') } }
  )
  importMappings.set(
    providers.provider { shared.values.associateBy { it.substringAfterLast('.') } }
  )
}

sourceSets { main { java.srcDir(generatedApiDir.map { it.dir("src/main/java") }) } }

tasks.named("compileJava") { dependsOn("openApiGenerate") }

tasks.named("compileKotlin") { dependsOn("openApiGenerate") }

tasks.named<Jar>("jar") { enabled = false }

tasks.withType<Test>().configureEach {
  dependsOn(bundleContract)
  systemProperty("openapi.spec", contract.get().asFile.absolutePath)
  inputs
    .file(contract)
    .withPropertyName("openApiContract")
    .withPathSensitivity(PathSensitivity.NONE)
}
