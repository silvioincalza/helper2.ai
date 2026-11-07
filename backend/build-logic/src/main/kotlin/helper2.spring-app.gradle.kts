import ai.helper2.build.BundleContract
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

// A runnable Spring Boot app: the backend conventions, the boot jar, and the controller interfaces
// and DTOs generated from the OpenAPI contract.
plugins {
  id("helper2.kotlin-library")
  id("org.springframework.boot")
  id("org.openapi.generator")
}

// Run from backend/, as when the backend was one project: application.yaml imports ../.env and the
// dev profile starts ../compose.yaml, both relative to the working directory.
tasks.named<JavaExec>("bootRun") { workingDir = rootProject.projectDir }

// Contract-first (ADR 0003): controller interfaces + DTOs are generated from the app's
// contract. While one app serves both, that is ../api/public.yaml and ../api/admin.yaml joined into
// one file, with the types of ../api/common.yaml.
val apiDir = rootProject.layout.projectDirectory.dir("../api")
val contract = layout.buildDirectory.file("openapi/contract.yaml")
val bundleContract =
  tasks.register<BundleContract>("bundleContract") {
    entries.from(apiDir.file("public.yaml"), apiDir.file("admin.yaml"))
    referenced.from(apiDir.asFileTree.matching { include("*.yaml") })
    bundle.set(contract)
  }
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
  dependsOn(bundleContract)
  generatorName.set("spring")
  inputSpec.set(contract.get().asFile.path)
  outputDir.set(generatedApiDir.get().asFile.path)
  apiPackage.set("ai.helper2.api.generated")
  modelPackage.set("ai.helper2.api.generated.model")
  modelNameSuffix.set("Dto")
  cleanupOutput.set(true)
  configOptions.set(
    mapOf(
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
      // Explicit (generator 7.24+ warns when unset): null handling stays with the global JSON
      // mapper.
      "generateJsonIncludeAnnotations" to "false",
      "generateJsonSetterNullsAnnotations" to "false",
    )
  )
}

sourceSets { main { java.srcDir(generatedApiDir.map { it.dir("src/main/java") }) } }

tasks.named("compileJava") { dependsOn("openApiGenerate") }

tasks.named("compileKotlin") { dependsOn("openApiGenerate") }

// The tests that check responses against the contract read the joined file.
tasks.withType<Test>().configureEach {
  dependsOn(bundleContract)
  systemProperty("openapi.spec", contract.get().asFile.absolutePath)
}
