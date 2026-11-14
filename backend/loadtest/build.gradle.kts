plugins {
  java
  id("io.gatling.gradle") version "3.16.0"
  id("helper2.format")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

tasks.named<Copy>("processGatlingResources") {
  from(rootProject.file("../docs/eval")) { include("search-queries.json", "guide-queries.json") }
}

tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
  systemProperties = providers.systemPropertiesPrefixedBy("h2.load.").get()
  outputs.upToDateWhen { false }
}

tasks.named("test") { dependsOn("gatlingClasses") }
