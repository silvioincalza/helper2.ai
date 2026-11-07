import ai.helper2.buildlogic.BundleContract
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
// contract: its entry file (the app sets bundleContract.entries: ../api/public.yaml or
// ../api/admin.yaml) joined with the parts of ../api/common.yaml it names, so the shared types keep
// their names in both apps.
val apiDir = rootProject.layout.projectDirectory.dir("../api")
val contract = layout.buildDirectory.file("openapi/contract.yaml")
val bundleContract =
  tasks.register<BundleContract>("bundleContract") {
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

// The web adapters both apps serve the same way (search, sources, categories; ../common-web): one
// source folder compiled into each app against that app's own generated contract types, which have
// the same names in both (ADR 0069).
val commonWebDir = rootProject.layout.projectDirectory.dir("apps/common-web/src/main/java")

sourceSets {
  main {
    java.srcDir(generatedApiDir.map { it.dir("src/main/java") })
    java.srcDir(commonWebDir)
  }
}

tasks.named("compileJava") { dependsOn("openApiGenerate") }

tasks.named("compileKotlin") { dependsOn("openApiGenerate") }

// Boot jar only: the plain jar would be one more file in build/libs for the image to tell apart.
tasks.named<Jar>("jar") { enabled = false }

// The tests that check responses against the contract read the joined file.
tasks.withType<Test>().configureEach {
  dependsOn(bundleContract)
  systemProperty("openapi.spec", contract.get().asFile.absolutePath)
}
